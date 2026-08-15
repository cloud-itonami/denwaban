# 実接続 — 電話が鳴るまでに残っていること

2026-08-15 実測。**この文書は「あと何が要るか」を正直に書くためのもの**で、
できていないことを進行中と書かない。

## いま在るもの / 無いもの（測った値）

| | 状態 | 測り方 |
|---|---|---|
| 会話 → 席 → 確定した 予約 | ✅ 動く | `clojure -M:test`（79 tests / 218 assertions） |
| 着信 webhook の署名検証 | ✅ `denwaban.carrier` | Twilio 公開ベクタで既知回答テスト |
| 転送で来た通話の扱い | ✅ `denwaban.arrival` | 転送時は発信者番号を事実にしない |
| **日本語 TTS（喋る）** | ✅ **fleet 全ノードに既に在る** | `say -v Kyoko`。下記参照 |
| **STT（聞く）** | ❌ **無い** | `com-whisper` は clean-room のスタブ。`infer.murakumo.cloud` は completion のみ |
| メディア WebSocket ブリッジ | ❌ 未実装 | — |
| 電話番号 / キャリア契約 | ❌ 無い | オーナーの手が要る |

### TTS は既に在る（2026-08-15 実測）

macOS の `say` が**電話の形式で直接出せる**。fleet ノード `judah`（macOS 26.2）で確認:

```bash
say -v Kyoko -o out.wav --data-format=ulaw@8000 --channels=1 "本日はご予約ありがとうございます。"
# → 8kHz mono μ-law。Twilio / Telnyx のメディアストリームがそのまま受ける形式
```

- 日本語音声 **9 種**（Kyoko / Eddy / Flo / Grandma / Grandpa ほか）
- 合成レイテンシ **約 1.14 秒**（1 文、このマシンで 3 回測って 1.13–1.17）。
  **これは客が黙って待つ時間**なので、実運用では文単位で先に流し始める必要がある。
  いまは測った値だけを記録し、ストリーミングは未実装とする
- G4（murakumo-only）に**適合する** —— 外部 SaaS を通らない。ElevenLabs は不要

### STT が無い（これが本当のブロッカー）

`kotoba-lang/com-whisper` は名前に反して **Twilio や ElevenLabs と同じ
clean-room API 実装**（`src/whisper/main.cljc` 1 本、Datomic 裏付け）で、
音声を認識しない。`infer.murakumo.cloud` が配っているのは
`Qwen-AgentWorld-35B-A3B` で capability は `completion` だけ。

**耳が無い。** ここを埋めるまで、電話は「こちらが喋る」ことしかできない。

推奨は **whisper.cpp を fleet に載せる**（M4 + Metal、日本語実用、ストリーミング
対応、外部通信なしで G4 適合）。macOS の Speech framework も on-device で
条件は満たすが、CLI が無いので小さなネイティブ実行体を書くことになる。

## オーナーの手が要ること（私はできない）

安全床①②（認証情報を自分でフォーム入力しない・資金を動かさない）に触るため、
ここは代行しない。

1. **キャリアの account 作成**（Twilio 推奨。理由は下記）
2. **支払い方法の登録**
3. **本人確認（Regulatory Bundle）** — 日本番号は必須、審査 **2 営業日**
4. **番号の購入** — 日本は 050（`$4.75/月`）。0AB-J（03-…）は料金表に
   `$20.00/月` と載っているが、実務記事は「Twilio は日本の 0AB-J を扱っていない」
   と書いていて**食い違っている**。買う前に確認すること

### なぜ最初は Twilio か

最安の可能性があるのは Telnyx（番号 `$1〜$5/月`、着信 `$0.002/分`、SIP 直結なので
Twilio の Media Streams `$0.004/分` が要らない）。ただし**日本 inbound の実レートが
公開ページから取れない**（価格表 PDF が要る）。

ここで効くのは、`koe.ports/ITelephony` が provider-neutral で
`denwaban.transport` に admission がある——**乗り換えコストが構造的に低い**という
事実である。だから最初の一手は「一番安い」ではなく「**一番早く実測できる**」を採る。
`$4.75` の番号 1 本と数百円の通話料で、その判断材料が買える。

## 番号が取れたあと（私が続きをやる）

1. **メディアブリッジ**を書く（μ-law 8k ⇄ STT/TTS、`run-turn` に接続）。
   **fleet ノード上で動かす**——音声を認識する機械と合成する機械の外へ出さないため。
   公開面は既存の murakumo ingress（`api.murakumo.cloud` は稼働中）に相乗りする
2. **whisper.cpp** をノードに入れて STT サービスにする
3. Twilio の webhook を `POST /voice` に向け、`denwaban.carrier/admit` を通す
4. `denwaban.transport/plan` に provider descriptor を渡す:

```clojure
{:telephony-providers [{:id :twilio :kind :telephony :health :ready :admission :approved
                        :capabilities #{:inbound-pstn :bidirectional-media :call-events
                                        :webhook-auth :hangup}
                        :media-format :ulaw-8000 :priority 0}]
 :access-paths [{:id :terrestrial :kind :ip-access :health :ready :admission :approved
                 :capabilities #{:ip-egress}}]}
```

5. **G7 を開ける**（Council Lv6+ + named operator + 明示フラグ）。
   `denwaban.session/g7-open?` は 3 つとも**値で**検査する（truthy では開かない）

## 署名検証について（`denwaban.carrier`）

- Twilio は `X-Twilio-Signature` = base64(HMAC-SHA1(authToken, URL + **名前順に**
  連結したパラメータ))。**ソートが安全性そのもの**で、到着順に連結する検証器は
  並べ替えられたリクエストを通す
- テストは **Twilio 公開のベクタ**を使う。自分で生成した値を自分で検証しても、
  実装が自己整合であることしか示さない——それは誤った実装も満たす。
  実際、最初に書いたベクタは記憶違い（ホスト名と署名の両方）で**落ちた**。
  実装の方が正しかった
- 検証できないものは `false` を返す（`nil` は呼び出し側で「問題なし」に読める）。
  **未実装の provider は拒否**する。ClojureScript 側は空の verifier 表を持つ
  ——HMAC の無い runtime で「なるべく頑張る」検証器は、走ったかどうかで
  同じ値を返す検査の典型
- 未検証のリクエストには `<Hangup/>` だけを返す。喋らない——相手が誰か
  分かっていないので、言葉にした内容はすべて未知の相手に渡す情報になる
- 応答は `<Connect><Stream>`（`<Start>` ではない）。`Start` は音声の複製を
  こちらに流しつつ通話を続ける = **盗聴装置**。受付は参加者であって傍受者ではない

# denwaban (電話番) — voice receptionist actor

着信応対 + 音声対話 + 自動予約を1つの actor に束ねる voice receptionist。
`toritsugi`（行政・LINE 窓口）の **電話/音声版の姉妹**。

予約は自分で持たず **`yotei` に委譲**する（no-double-book + member 署名確定は yotei が保証）。
音声 I/O は **`kotoba-lang/com-whisper`（STT）+ `kotoba-lang/com-elevenlabs`（TTS）**、
電話面は **`kotoba-lang/com-twilio`**、Web 着信は **WebRTC**（ADR-2606271800）。
再利用カーネルは **`kotoba-lang/koe`**（共通ライブラリ）にあり、本 actor はその公益インスタンス。

> 参照名は 2026-07-30 に実在する repo へ揃えた（ADR-2607300300 step 3）。旧 `*-compat` は
> `kotoba-lang/com-*` へ、`koe-clj` は `kotoba-lang/koe` へ、booking の委譲先は
> `cloud-itonami/yotei`（同名の `kotoba-lang/yotei` とは別 repo）。GitHub の redirect が
> 生きているため、この drift は壊れずに見えないまま残っていた。

設計確定: **ADR-2606271930**。詳細は `CLAUDE.md`。

```
clojure -M:test    # pipeline 合成 + G2 booking 委譲 + G7 gate + social_post 膜の拒否経路 + HTTP 面（29 tests / 91 assertions）
clojure -M:serve   # consent surface（loopback :1343）
```

**Status: R0 scaffold** — `run-session` は raise（G7 outward-gate）。実発着信なし・
fixtures のみ。`plan-session` は pure でオフライン検証可能。**2026-07-30 に HTTP 面
（`denwaban.http`）が付いた**ので「socket なし」ではなくなったが、**G7 の天井は変わって
いない** — socket があることと電話に出られることは別。

## HTTP 面 — listener は1つ、`pending` は存在しない

consent surface が同意済みの提案を渡してくる面。**`cloud-itonami-esim` /
`cloud-itonami-card-issuing` は listener を2つ持つが、この actor は1つしか持たない。**

あちらは提案が `pending` で終わり、operator の決定が実際の行為（プロファイル
provisioning、カード発行）に変わるので、consent surface が自分の提案を自分で承認
できないように2つ目の listener が要る。**denwaban には承認するものが無い** —
`run-session` は G7 で raise し、R0 では telephony transport も STT/TTS actor も
booking client も束ねられていない。ここでの決定はどれも電話に出られない。承認が
それが認可するはずの行為に変わらない decide endpoint は、**gate に見えて何も
留めない gate** なので、operator 面は置かない。

```
consent (:1343)  POST /commit                 -> held（G7）+ 拒否した plan
                 GET  /proposals/<reference>   -> 常に unknown
                 GET  /healthz                 -> can-answer-calls: false
```

**なぜ `pending` ではなく「拒否」なのか。** app 側の authority spine は
`:authority-refused` と `:authority-pending` を区別し、その差が人間に見えるものを
決める（pending =「待て、誰かが判断している」／refused =「これは起こらない」）。
ここで pending を返すと、**誰も下せない判断を人間が待つ**ことになる。この面が
無かった間、app が受け取っていたのは `:endpoint-not-configured`（=「訊けなかった」）
だけで、実際の答えは「no」だった。訊かれて正直に答えることがこの面の目的。

**gate はここでも検査する。** app の voice adapter も不正な発信者番号・allowlist 外・
同意なき録音保持を拒否するが、この面は自分が所有する gate（G1: 録音は明示同意が
無ければ transient）を**もう一度**検査する。呼び出し側が検証済みだと信じる actor は
自分の gate を持たない — 次の呼び出し側は別の app かもしれず、同じ app のバグかも
しれない。

G7 が開いたとき（Council Lv6+ と named operator）に追加されるのが operator 面で、
その時に初めて `pending` が到達可能になる。

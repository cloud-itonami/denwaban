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
clojure -M:test   # pipeline 合成 + G2 booking 委譲 + G7 gate の contract test
```

**Status: R0 scaffold** — `run-session` は raise（G7 outward-gate）。socket なし・実発着信なし・
fixtures のみ。`plan-session` は pure でオフライン検証可能。

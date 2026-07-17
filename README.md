# denwaban (電話番) — voice receptionist actor

着信応対 + 音声対話 + 自動予約を1つの actor に束ねる voice receptionist。
`toritsugi`（行政・LINE 窓口）の **電話/音声版の姉妹**。

予約は自分で持たず **`yotei` に委譲**する（no-double-book + member 署名確定は yotei が保証）。
音声 I/O は **whisper-compat（STT）+ elevenlabs-compat（TTS）**、電話面は
**twilio-compat**、Web 着信は **kotoba-net WebRTC**（ADR-2606271800）。再利用カーネルは
**com-junkawasaki/koe-clj**（共通ライブラリ）にあり、本 actor はその etzhayyim 公益インスタンス。

設計確定: **ADR-2606271930**。詳細は `CLAUDE.md`。

```
bb test     # pipeline 合成 + G2 booking 委譲 + G7 gate の contract test
```

**Status: R0 scaffold** — `run-session` は raise（G7 outward-gate）。socket なし・実発着信なし・
fixtures のみ。`plan-session` は pure でオフライン検証可能。

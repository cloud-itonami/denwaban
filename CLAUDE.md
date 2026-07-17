# denwaban (電話番) — CLAUDE actor guide

**Voice receptionist.** Tier-B · `did:web:denwaban.etzhayyim.com` ·
ADR-2606271930 · **R0 scaffold (no cells run, no live call)**.

## What this actor IS

The **voice/telephone counterpart of `toritsugi`** (which is the text/LINE window for
government procedures). denwaban stands at the **電話の窓口**: it answers an inbound call,
**converses by voice**, and **takes a booking** — but it does **not own the booking**.
It delegates to `yotei` (the Calendly-inverse scheduling commons), so the no-double-book
invariant and member-signed confirmation are guaranteed by yotei, not re-implemented here.

```
着信 ─► ingress (twilio-compat / SIP / WebRTC soft-phone)   ← G7-gated
        ▼
      listen (whisper-compat STT, streaming partials for barge-in)
        ▼
      converse (KotobaLLM dialog; intent + slot extraction)   ← G4 Murakumo-only
        ├─ book ─► yotei.BookSlot / SetAvailability (MCP)      ← G2 member-signed
        ▼
      speak (elevenlabs-compat TTS) ─► voice back over ingress
```

## Composition (no duplicate implementation)

denwaban is **mostly glue**. The reusable session kernel (telephony/STT/TTS/booking
ports + dialog loop) lives in **`com-junkawasaki/koe-clj`** (shared library), not here.
The pieces it binds already exist:

| piece | actor | org | role |
|---|---|---|---|
| telephony | twilio-compat (alt: vonage/bandwidth) | etzhayyim | inbound/outbound voice, SIP |
| STT (聞取) | **whisper-compat** | etzhayyim | speech → text (new, ADR-2606271930) |
| TTS (発話) | elevenlabs-compat | etzhayyim | text → speech |
| 予約 | **yotei** | etzhayyim | booking (delegation target, single source of truth) |
| Web 着信 | kotoba-net WebRTC | com-junkawasaki | soft-phone Live transport (ADR-2606271800) |
| kernel | **koe-clj** | com-junkawasaki | reusable ports + dialog loop (shared library) |

## Org placement (per the three-way rule)

- **com-junkawasaki** = shared/common library → `koe-clj` (the reusable voice kernel).
- **etzhayyim** = OSS + public-benefit actor → `denwaban` (this) + `whisper-compat`.
- **gftdcojp** = business/private deployment → **only if needed** (not created at R0).

## Gates (immutable R0→R3)

G1 consent-first / no-secret-recording · G2 member-signed-booking (yotei) ·
G3 no-booker-harvest · G4 Murakumo-only (KotobaLLM) · G5 no-server-key (kotoba-turn
short-lived TURN cred) · G6 no-robocall / no caller-ID spoofing · G7 outward-gated
(R0 = offline + intent only; live = Council Lv6+ + operator) · G8 sourcing-honesty.

## Non-goals

N1 not a seat-priced IVR/contact-center SaaS · N2 no untargeted outbound / autodialer ·
N3 no always-on recording / voiceprint dataset / emotion-analytics monetization ·
N4 no booking logic re-implemented here (yotei is the source of truth) ·
N5 no detection-evasion / caller-ID spoofing use.

## Build / test

```
bb test            # repository-native contract tests under babashka
```

R0 = design (ADR-2606271930) + manifest + DID + session pipeline stub. `solve` raises;
no socket, no live call, fixtures only. Live telephony is G7-gated.

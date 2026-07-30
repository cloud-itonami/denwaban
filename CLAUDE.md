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
着信 ─► ingress (com-twilio / SIP / WebRTC soft-phone)   ← G7-gated
        ▼
      listen (com-whisper STT, streaming partials for barge-in)
        ▼
      converse (KotobaLLM dialog; intent + slot extraction)   ← G4 Murakumo-only
        ├─ book ─► yotei.BookSlot / SetAvailability (MCP)      ← G2 member-signed
        ▼
      speak (com-elevenlabs TTS) ─► voice back over ingress
```

## Composition (no duplicate implementation)

denwaban is **mostly glue**. The reusable session kernel (telephony/STT/TTS/booking
ports + dialog loop) lives in **`kotoba-lang/koe`** (shared library), not here.
The pieces it binds already exist:

| piece | repository | role |
|---|---|---|
| telephony | `kotoba-lang/com-twilio` (alt: vonage/bandwidth) | inbound/outbound voice, SIP |
| STT (聞取) | **`kotoba-lang/com-whisper`** | speech → text (ADR-2606271930) |
| TTS (発話) | `kotoba-lang/com-elevenlabs` | text → speech |
| 予約 | **`cloud-itonami/yotei`** | booking (delegation target, single source of truth) |
| Web 着信 | WebRTC transport — provenance unsettled, see below | soft-phone Live transport (ADR-2606271800) |
| kernel | **`kotoba-lang/koe`** | reusable ports + dialog loop (shared library) |

## Reference realignment (2026-07-30, ADR-2607300300 step 3)

Every name above used to point somewhere that has since moved. GitHub still
redirects the old URLs, which is why nothing broke and nobody noticed:

| was | is | how it was checked |
|---|---|---|
| `com-junkawasaki/koe-clj` | `kotoba-lang/koe` | pinned SHA present in the new repo |
| `etzhayyim/com-etzhayyim-social-publication` | `kotoba-lang/social-publication` | pinned SHA present; namespace still `etzhayyim.social.publication`, so the `:require` did NOT change |
| `etzhayyim/com-etzhayyim-yotei` | `cloud-itonami/yotei` | **not** `kotoba-lang/yotei`, which exists separately and lacks this SHA |
| `kotoba-lang/net` | `kotoba-lang/io-libp2p` | pinned SHA present there |
| `*-compat` actor names | `kotoba-lang/com-*` | repos verified in the west manifest |

**Open question, not resolved by guessing:** a repository now named `io-libp2p`
supplying the WebRTC `ITelephony` transport of ADR-2606271800 is surprising, and a
separate `kotoba-lang/webrtc` exists which does **not** contain the pinned SHA.
`manifest.edn` records this as `:review :binding-provenance-unsettled`. Whoever takes
the WebRTC ingress past R0 must settle which repository owns that binding. Meanwhile
the pipeline labels the stage by its **transport** (`"webrtc"`) rather than naming a
repository, so the data asserts nothing it cannot support.

## Org placement (per the three-way rule)

- **kotoba-lang** = every library and clean-room compat actor → `koe`, `com-twilio`,
  `com-whisper`, `com-elevenlabs`, `social-publication` (library placement rule,
  ADR-2606302300; the `-clj` suffix is retired, ADR-2607102200 addendum 14).
- **cloud-itonami** = the deployable actors → `denwaban` (this), `yotei`, `toritsugi`.
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
clojure -M:test    # repository-native contract tests (6 tests / 16 assertions)
```

`bb.edn` was removed in the same pass: babashka is retired as a script host
(ADR-2607173000), and keeping a second copy of the dependency coordinates was itself
the reason the URLs above could drift out of sync unnoticed.

R0 = design (ADR-2606271930) + manifest + DID + session pipeline stub. `solve` raises;
no socket, no live call, fixtures only. Live telephony is G7-gated.

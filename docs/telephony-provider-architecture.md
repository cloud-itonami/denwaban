# Telephony provider and access-path architecture

Verified against provider documentation on 2026-08-14. Availability, inventory,
price and regulatory admission are external facts and must be probed again before
activation; they are deliberately not constants in code.

## Decision

Denwaban does not bind its `ITelephony` port to Twilio. It admits a configured
telephone provider only when a current health observation and all required
capabilities are present. Independently, it chooses an IP access path.

```
Japanese number / PSTN / SIP provider
                  |
       bidirectional voice media
                  |
              denwaban
       STT -> dialog -> TTS -> booking
                  |
        IP access to provider edge
     fiber | cellular eSIM | Starlink
```

The upper and lower choices are not substitutes. Starlink and an IoT eSIM can
carry packets but do not, by themselves, supply an inbound Japanese number,
call-control events, authenticated webhooks or bidirectional call media.

Provider failover is **new-call-only**. An established PSTN call is not claimed
to migrate invisibly to another carrier. Existing calls receive a bounded
apology/transfer/hangup policy; new calls may be routed by carrier forwarding,
SIP trunk policy or a separately held standby number.

## Current comparison

| Option | Role here | AI audio path | Japan fit | Assessment |
|---|---|---|---|---|
| Twilio Programmable Voice | CPaaS/PSTN | Bidirectional Media Streams over WebSocket; signed stream requests | Japanese local/national/toll-free numbers require a regulatory bundle | Strong default and ecosystem; keep as one adapter, not the architecture |
| Telnyx | Carrier/CPaaS/SIP | Bidirectional RTP/media over WebSocket | Publishes Japan number and local-calling coverage | Best second CPaaS candidate; useful diversity from Twilio and direct SIP orientation |
| Vonage Voice API | CPaaS | Full-duplex WebSocket with signed JWT or custom authorization and event fallback | Number inventory and exact Japanese type must be verified at order time | Strong adapter candidate; a `com-vonage` mirror already exists in this workspace |
| Amazon Connect Customer | Managed contact center | Live customer audio via Kinesis; also has managed agentic voice | Tokyo supports 050, 03, 06, 0120 and 0800 subject to business documents | Strong managed/human-fallback plane and 99.99% service commitment; heavier and less direct for the Murakumo-local dialog loop |
| Starlink Priority | IP access/backhaul | Carries WSS/SIP/WebRTC packets | Japan service; Priority advertises 99.9% network-availability SLA | Good geographically diverse backup access, never a PSTN provider |
| au Starlink Direct | handset access | Voice applications over available data | KDDI service in Japan | Useful caller-side reach in dead zones, not a programmable denwaban DID |
| SORACOM eSIM | cellular IP access | Data path to the provider edge | Japan profiles exist; published eSIM matrix has no voice capability | Good terrestrial backup access; do not classify it as telephone ingress |

Official references:

- Twilio Media Streams: https://www.twilio.com/docs/voice/media-streams
- Twilio Japan regulatory bundle: https://www.twilio.com/docs/phone-numbers/regulatory/getting-started
- Telnyx media streaming: https://developers.telnyx.com/docs/voice/programmable-voice/media-streaming
- Telnyx Japan numbers: https://telnyx.com/phone-numbers/japan
- Vonage WebSockets: https://developer.vonage.com/en/voice/voice-api/concepts/websockets
- Amazon Connect Japan numbers: https://docs.aws.amazon.com/connect/latest/adminguide/connect-tokyo-region.html
- Amazon Connect SLA: https://aws.amazon.com/connect/sla/
- Starlink Business: https://starlink.com/jp/business
- Starlink Direct to Cell: https://www.starlink.com/business/direct-to-cell
- SORACOM eSIM profiles: https://developers.soracom.io/en/docs/air/esim-profiles/

## Admission contract

A telephony adapter must demonstrate `:inbound-pstn`, `:bidirectional-media`,
`:call-events`, `:webhook-auth` and `:hangup`. An access path must demonstrate
`:ip-egress`. Both need `:admission :approved` and a live `:health :ready`
observation. Missing facts hold the call plan rather than choosing optimistically.

No provider credential, phone-number purchase, recording retention or live-call
activation is implied by this design. G1-G8 and cloud-itonami-app's Passkey-bound
voice authority remain in force.

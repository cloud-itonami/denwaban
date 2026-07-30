(ns denwaban.session
  "denwaban session pipeline (R0 stub).

  Binds the voice-receptionist pipeline: ingress → listen → converse → speak → book.
  The reusable kernel + port protocols live in `kotoba-lang/koe`; this cell is the
  public-benefit instance that injects the concrete actors (`kotoba-lang/com-twilio`
  / `com-whisper` / `com-elevenlabs`, and `cloud-itonami/yotei`) into those ports.

  R0: no socket, no live call, no model. `plan-session` is a PURE description of the
  pipeline (testable offline); `run-session` raises (G7 outward-gate). ADR-2606271930."
  (:require [koe.ports :as ports]
            [koe.session :as koe-session]))

(def kernel-contract
  {:session 'koe.session/converse
   :booking-invariant 'koe.session/booking-delegated?
   :ports {:ingress 'koe.ports/ITelephony
           :listen 'koe.ports/ISTT
           :converse 'koe.ports/IDialog
           :speak 'koe.ports/ITTS
           :book 'koe.ports/IBooking}})

(def gates #{"G1" "G2" "G3" "G4" "G5" "G6" "G7" "G8"})

;; Pipeline as data: each stage names the actor that fulfils its port + its gate.
;; kotoba-lang/koe defines the port protocols (ITelephony/ISTT/IDialog/ITTS/IBooking);
;; denwaban only chooses the bindings below.
;;
;; Actor names were realigned 2026-07-30 (ADR-2607300300 step 3) to the repositories
;; that actually exist: the `*-compat` clean-room actors moved to kotoba-lang under a
;; `com-` prefix, so `twilio-compat` is `kotoba-lang/com-twilio` and so on.
(def pipeline
  [{:stage :ingress  :port :ITelephony :actor "com-twilio"     :gate "G7"}
   {:stage :listen   :port :ISTT       :actor "com-whisper"    :gate "G1"}
   {:stage :converse :port :IDialog    :actor "kotoba-llm"     :gate "G4"}
   {:stage :speak    :port :ITTS       :actor "com-elevenlabs" :gate "G1"}
   {:stage :book     :port :IBooking   :actor "yotei"          :gate "G2"}])

(defn plan-session
  "Pure: return the ordered pipeline for a session intent. No I/O. Used by the
  contract test to assert the composition (and that booking is delegated to yotei,
  never confirmed locally — G2)."
  [{:keys [reach] :or {reach :pstn}}]
  {:reach reach
   :kernel kernel-contract
   :stages (cond-> pipeline
             ;; a WebRTC soft-phone swaps the ingress transport (ADR-2606271800),
             ;; not the rest of the pipeline.
             ;;
             ;; The label is the TRANSPORT, deliberately not a repository name. It
             ;; used to read "kotoba-net/webrtc", but `kotoba-lang/net` has since
             ;; become `kotoba-lang/io-libp2p` -- and a repository named io-libp2p
             ;; supplying an ITelephony WebRTC transport is surprising enough that
             ;; the binding needs review rather than a silent rewrite (there is also
             ;; a separate `kotoba-lang/webrtc`, which does NOT contain the SHA this
             ;; actor's manifest pins). See manifest.edn :webrtc for the open
             ;; question; naming the transport keeps this data honest meanwhile.
             (= reach :webrtc)
             (assoc-in [0 :actor] "webrtc"))
   :booking-owner "yotei"          ; never "denwaban" — single source of truth (G2)
   :recording :transient})         ; G1: no retention without explicit consent

(defn delegates-booking?
  "Invariant: denwaban must delegate booking to yotei and hold no booking state (G2)."
  [plan]
  (and (= "yotei" (:booking-owner plan))
       (= 'koe.session/booking-delegated?
          (get-in plan [:kernel :booking-invariant]))))

(defn run-session
  "R0 gate: live telephony/audio is offline + intent only (G7)."
  [& _]
  (throw (ex-info "denwaban R0: live call is G7-gated (Council Lv6+ + operator); plan only"
                  {:status :r0 :gate "G7"})))

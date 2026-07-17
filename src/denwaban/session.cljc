(ns denwaban.session
  "denwaban session pipeline (R0 stub).

  Binds the voice-receptionist pipeline: ingress → listen → converse → speak → book.
  The reusable kernel + port protocols live in `com-junkawasaki/koe-clj`; this cell is
  the etzhayyim public-benefit instance that injects the concrete actors
  (twilio-compat / whisper-compat / elevenlabs-compat / yotei) into those ports.

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
;; koe-clj defines the port protocols (ITelephony/ISTT/IDialog/ITTS/IBooking);
;; denwaban only chooses the bindings below.
(def pipeline
  [{:stage :ingress  :port :ITelephony :actor "twilio-compat"     :gate "G7"}
   {:stage :listen   :port :ISTT       :actor "whisper-compat"    :gate "G1"}
   {:stage :converse :port :IDialog    :actor "kotoba-llm"        :gate "G4"}
   {:stage :speak    :port :ITTS       :actor "elevenlabs-compat" :gate "G1"}
   {:stage :book     :port :IBooking   :actor "yotei"             :gate "G2"}])

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
             (= reach :webrtc)
             (assoc-in [0 :actor] "kotoba-net/webrtc"))
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

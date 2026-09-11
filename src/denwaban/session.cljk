(ns denwaban.session
  "denwaban session pipeline.

  Binds the voice-receptionist pipeline: ingress → listen → converse → speak → book.
  The reusable kernel + port protocols live in `kotoba-lang/koe`; this cell is the
  public-benefit instance that injects an admitted telephony provider plus the
  concrete STT/TTS/booking actors (`com-whisper` / `com-elevenlabs`, and
  `cloud-itonami/yotei`) into those ports.

  ## Where the gate is, after 2026-08-15 (ADR-2608150900)

  Until now `run-session` raised, full stop, and that was honest: there was
  nothing behind it. A 予約 could not have been taken even by a human holding
  the receiver, because no code turned a conversation into one.

  There is now. `run-call` drives a real conversation to a real confirmed 予約
  in `cloud-itonami/yotei`, through `denwaban.uketsuke` and `denwaban.yoyaku`.
  What it does **not** do is open a telephone channel: it is handed one. That
  distinction is where G7 now sits, and it sits there because opening a channel
  is the act that reaches the public — the rest is arithmetic that a test, an
  operator's soft-phone and a live carrier all run identically.

  So `run-session` (which opens) still refuses without Council Lv6+, a named
  operator and an explicit flag, and `run-call` (which is given a channel) does
  not consult G7 at all. Anyone who can call `run-call` on a live line has
  already passed through the gate that mattered.

  ADR-2606271930, ADR-2608150900."
  (:require [denwaban.transport :as transport]
            [denwaban.uketsuke :as uketsuke]
            [koe.ports :as ports]
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
  [{:stage :ingress  :port :ITelephony :actor "telephony-provider" :gate "G7"}
   {:stage :listen   :port :ISTT       :actor "com-whisper"    :gate "G1"}
   {:stage :converse :port :IDialog    :actor "kotoba-llm"     :gate "G4"}
   {:stage :speak    :port :ITTS       :actor "com-elevenlabs" :gate "G1"}
   {:stage :book     :port :IBooking   :actor "yotei"          :gate "G2"}])

(defn plan-session
  "Pure: return the ordered pipeline for a session intent. No I/O. Used by the
  contract test to assert the composition (and that booking is delegated to yotei,
  never confirmed locally — G2)."
  [{:keys [reach transport-plan] :or {reach :pstn}}]
  {:reach reach
   :transport transport-plan
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
             (assoc-in [0 :actor] "webrtc")

             (and (= reach :pstn) transport-plan)
             (assoc-in [0 :actor] (transport/ingress-actor transport-plan)))
   :booking-owner "yotei"          ; never "denwaban" — single source of truth (G2)
   :recording :transient})         ; G1: no retention without explicit consent

(defn delegates-booking?
  "Invariant: denwaban must delegate booking to yotei and hold no booking state (G2)."
  [plan]
  (and (= "yotei" (:booking-owner plan))
       (= 'koe.session/booking-delegated?
          (get-in plan [:kernel :booking-invariant]))))

;; ── G7: opening a channel ────────────────────────────────────────────────────

(defn g7-open?
  "Whether this deployment may open a telephone channel to the public.

  All three are required and each is checked for the value it must have, not
  for truthiness: a configuration that half-answers must read as closed. An
  absent `:g7` is closed, which is the only safe reading of 'nobody said'."
  [{:keys [council-level operator live-enabled?]}]
  (boolean (and (integer? council-level) (>= council-level 6)
                (string? operator) (seq operator)
                (true? live-enabled?))))

(defn run-session
  "Open a live channel and run a call on it.

  Refuses unless G7 is open AND an admitted telephony provider was selected by
  `denwaban.transport/plan`. Both, because they refuse different things: G7 is
  the decision that this service may speak to the public at all, and the
  transport plan is whether there is a carrier to speak through."
  [{:keys [g7 transport-plan] :as opts}]
  (cond
    (not (g7-open? g7))
    (throw (ex-info "live call is G7-gated (Council Lv6+ + named operator + explicit flag)"
                    {:gate "G7" :status :refused :g7 g7}))

    (not= :ready (:status transport-plan))
    (throw (ex-info "no admitted telephony provider; nothing to open a channel on"
                    {:gate "G7" :status :refused
                     :reasons (:reasons transport-plan)}))

    :else
    (assoc (plan-session opts) :channel :live)))

;; ── running a call on a channel somebody already opened ──────────────────────

(defn turn-invariant-ok?
  "`koe.session/booking-delegated?`, plus the one case its kernel does not
  model: yotei answering *refused*.

  A refusal is not a booking, so it is checked as the kernel's `nil` branch
  rather than as an unsigned one. The predicate itself is koe's — restating it
  here is how the invariant and the thing it guards drift apart."
  [turn]
  (koe-session/booking-delegated?
   (cond-> turn (get-in turn [:booking "refused"]) (assoc :booking nil))))

(defn- confirmation-reply [booking]
  (str "承りました。" (get booking "yoyakuId") " でお席をご用意してお待ちしております。"))

(defn run-turn
  "One turn: utterance in, reply out, and a 予約 if this was the turn that
  completed it.

  The booking half is `koe.session/converse`'s — propose then member-signed
  confirm, never a confirmation manufactured here. What this adds is folding
  yotei's answer back into the conversation: a refusal becomes the next
  question or a callback, never silence."
  [{:keys [dialog tts booking] :as ports*} state utterance {:keys [voice signature] :as opts}]
  (let [turn (koe-session/converse ports* state utterance opts)]
    (when-not (turn-invariant-ok? turn)
      (throw (ex-info "G2: a booking came back that did not go through yotei's confirm"
                      {:gate "G2" :turn (dissoc turn :audio)})))
    (let [b (:booking turn)]
      (cond
        (and (= :book (:action turn)) (nil? b))
        turn                                        ; held, not yet confirmed

        (and (= :book (:action turn)) (get b "refused"))
        (let [{:keys [state reply action]}
              (uketsuke/after-refusal (:state turn)
                                      (map keyword (or (get b "reasons") ["not-proposed"])))]
          (assoc turn :state state :reply reply :action action
                 :audio (when (and tts reply) (ports/synth tts reply {:voice voice}))
                 :booking nil))

        (= :book (:action turn))
        (let [reply (confirmation-reply b)]
          (assoc turn
                 :state (assoc (:state turn) :denwaban/outcome :confirmed)
                 :reply reply
                 :audio (when tts (ports/synth tts reply {:voice voice}))))

        :else turn))))

(defn run-call
  "Drive a whole call over a channel that is already open.

  `utterances` are what `koe.ports/ISTT` produced. Returns the final state, the
  transcript of replies, and the 予約 if one was confirmed. Stops early once the
  call has an outcome — a receptionist that keeps asking questions after
  escalating is collecting details nobody will use (G3)."
  [ports* state utterances opts]
  (reduce
   (fn [{:keys [state] :as acc} utterance]
     (if (:denwaban/outcome state)
       (reduced acc)
       (let [turn (run-turn ports* state utterance opts)]
         (-> acc
             (assoc :state (:state turn))
             (update :replies conj (:reply turn))
             (cond-> (and (= :book (:action turn)) (:booking turn))
               (assoc :booking (:booking turn)))))))
   {:state state :replies [] :booking nil}
   utterances))

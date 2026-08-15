(ns denwaban.yoyaku
  "The 予約 half of the receptionist: `koe.ports/IBooking` backed by yotei.

  denwaban owns no 予約 (G2). What it owns is the translation between a
  conversation and yotei's wire shape, and the discipline that the answer comes
  back from yotei's own functions rather than from a copy of them living here.
  Every decision below is `yotei.seat`'s or `yotei.delegation`'s; this namespace
  chooses no table, permits no time and confirms nothing.

  ## Verification is asked for, never assumed

  `:verify-owner` and `:verify-delegate` are injected and must answer `true` for
  a 予約 to be confirmed. They are expected to answer `false` or `nil` when they
  cannot check — and `yotei.delegation/admit` treats both the same as a refusal,
  which is the point: an unreachable verifier must not read as a verified
  signature. denwaban holds no key of its own beyond the delegate key the owner
  named in the envelope, and even that one signs rather than decides.

  ## The contact never travels in the clear

  `:seal-contact` produces yotei's encrypted envelope ref. It is required, not
  defaulted: a missing sealer would otherwise mean a caller's name and telephone
  number going into the 予約 log as text, which is the exact harvesting G2 and
  yotei's G2 both exist to prevent. Absent, this refuses to propose at all."
  (:require [koe.ports :as ports]
            [yotei.delegation :as delegation]
            [yotei.seat :as seat]
            [yotei.yoyaku :as yoyaku]))

(defn- refusal
  "Refusals are shaped like yotei's, not like denwaban's.

  `koe.session/converse` proposes and then confirms in one step, so a refused
  proposal arrives at `confirm` whether or not anyone wanted it to. One shape
  means `confirm` recognises it and passes the reasons through, instead of
  handing a refusal to `yotei.delegation` as though it were a 予約."
  [reasons]
  {"state" "refused" "refused" true "reasons" (mapv name reasons)})

(defn propose
  "Hold a table. Returns yotei's `proposed` 予約, or a refusal carrying the
  reasons in `yotei.delegation/admit`'s vocabulary so the dialog has one
  refusal language to translate rather than two."
  [{:keys [authorization confirmed-fn seal-contact]} slot]
  (let [{:keys [party-size start-epoch-min name contact consent-ref consent-kind]} slot
        confirmed (confirmed-fn)
        flr (delegation/authorized-floor authorization)
        assignment (seat/assign flr confirmed start-epoch-min
                                (:yotei/seating-min authorization) party-size)]
    (cond
      (nil? seal-contact)
      (refusal [:no-contact-seal])

      (:yotei/refused assignment)
      (refusal [(case (:yotei/refused assignment)
                  :no-table-large-enough :party-exceeds-authorization
                  :all-tables-taken :all-tables-taken
                  :bad-party-size :party-size-unknown
                  :table-not-in-authorization)])

      :else
      (let [tbl (:yotei/table assignment)
            req {"yoyakuId" (:yoyaku-id slot)
                 "calendarDid" (:yotei/calendar-did tbl)
                 ;; A telephone caller has no DID. Empty is the honest value:
                 ;; minting one would put an identifier in the log that resolves
                 ;; to nobody and reads like a member.
                 "requesterDid" ""
                 "responderDid" (:yotei/restaurant-did authorization)
                 "startEpochMin" start-epoch-min
                 "durationMin" (:yotei/seating-min authorization)
                 "consentRef" consent-ref
                 "contactRef" (seal-contact {:name name :contact contact})}
            proposed (yoyaku/propose-yoyaku req confirmed)]
        (if (= "refused" (get proposed "state"))
          (refusal [(if (seq consent-ref) :slot-taken :missing-consent)])
          (assoc proposed
                 "consentKind" consent-kind
                 "partySize" party-size))))))

(defn confirm
  "Confirm through `yotei.delegation`, which re-derives the whole 予約 against
  the owner-signed envelope. denwaban supplies the signature and the two
  verification answers, and nothing else."
  [{:keys [authorization confirmed-fn now-fn verify-owner verify-delegate]} proposal signature]
  ;; `signature` may be a function. koe's kernel takes it as an opt decided
  ;; before `propose` runs, which is right for a member signing a slot they
  ;; chose and impossible for a delegate: the table -- and so the calendar DID
  ;; being signed over -- is not known until the proposal exists. Accepting a
  ;; thunk keeps that inside denwaban rather than widening koe's port for one
  ;; caller's problem.
  (if (get proposal "refused")
    proposal                                   ; nothing was held; say so unchanged
    (let [sig (if (fn? signature) (signature proposal) signature)]
      (if-not (map? sig)
        (assoc proposal "refused" true "reasons" ["delegate-signature-unverified"])
        (let [out (delegation/confirm authorization proposal sig
                                      {:yotei/now-epoch-min (now-fn)
                                       :yotei/confirmed (confirmed-fn)
                                       :yotei/party-size (get proposal "partySize")
                                       :yotei/owner-signature-verified? (verify-owner authorization)
                                       :yotei/delegate-signature-verified? (verify-delegate proposal sig)})]
          (cond-> out
            ;; koe's `booking-delegated?` guard reads `:signed-by` to tell a
            ;; confirmed 予約 from a hand-built one. yotei records the same fact
            ;; as "confirmedSig"; this names it in the key koe's invariant looks
            ;; at rather than teaching koe about yotei's wire shape.
            (= "confirmed" (get out "state"))
            (assoc :signed-by (get sig "ref"))))))))

(defn open-times
  "The instants this party could be offered. `yotei.seat` decides; the horizon
  is the envelope's own, so the receptionist cannot offer further ahead than the
  owner signed for."
  [{:keys [authorization confirmed-fn now-fn]} party-size]
  (let [now (now-fn)]
    (seat/open-times (delegation/authorized-floor authorization)
                     now
                     (+ now (* 1440 (:yotei/horizon-days authorization)))
                     (confirmed-fn) now party-size)))

(defrecord YoteiBooking [ctx]
  ports/IBooking
  (propose [_ slot] (propose ctx slot))
  (confirm [_ proposal signature] (confirm ctx proposal signature)))

(defn booking
  "The `koe.ports/IBooking` a session injects."
  [ctx]
  (->YoteiBooking ctx))

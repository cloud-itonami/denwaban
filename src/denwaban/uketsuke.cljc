(ns denwaban.uketsuke
  "受付 — the conversation that turns a telephone call into a 予約, and the
  refusals that keep it from turning into a promise nobody can keep.

  ## The model does not decide anything

  A caller says 「明日の7時に4人で」 in any order, in any words, with the date
  half-implied. Extracting facts from that is what a language model is for.
  *Deciding* whether those facts add up to a 予約 is not: it is arithmetic over
  seats, hours and an owner-signed envelope, and a governor has to be able to
  recompute it and get the same answer.

  So this namespace is split at exactly that line. `absorb` takes facts that
  have already been extracted — by KotobaLLM in production (G4), by a fixture in
  tests — and everything after it is pure. The model never sees the question
  'may this be booked'; it only answers 'what did they say'.

  ## What it will not do

  **Guess a fact it did not hear.** A missing party size is asked for, not
  assumed to be two. A 予約 for the wrong number of people is worse than one
  more question.

  **Persuade.** Alternatives are offered when a time is unavailable, because a
  caller who wanted 19:00 wants to know what is possible. What is never said is
  how full the room is: 'only one table left' is the scarcity pressure G6
  forbids, and it is also a nightly attendance report on somebody's business.

  **Keep talking when it cannot book.** If the envelope is expired, or a
  signature cannot be verified, the call ends with a human callback and the
  caller is told so. A receptionist that keeps taking details it cannot act on
  is collecting personal data for nothing (G3)."
  (:require [clojure.string :as str]
            [denwaban.consent :as consent]
            [koe.ports :as ports]))

;; ── state ────────────────────────────────────────────────────────────────────

(def fact-order
  "Asked in this order. Party size first because it decides which times exist:
  asking for a time before knowing the size means offering times that may not
  survive the next answer."
  [:party-size :start-epoch-min :name :contact :consent?])

(defn initial-state
  "`caller-contact` is the inbound caller ID when the network supplied one. It
  is a fact already in hand, not a shortcut: a withheld number is simply asked
  for like anything else."
  [{:keys [call-ref caller-contact now-epoch-min]}]
  {:denwaban/call-ref call-ref
   :denwaban/now-epoch-min now-epoch-min
   :denwaban/facts (cond-> {} (seq caller-contact) (assoc :contact caller-contact))
   :denwaban/offered []
   :denwaban/turns 0
   :denwaban/outcome nil})

(def ^:private fact-keys (set fact-order))

(defn absorb
  "Fold extracted facts into the state.

  `:offer-index` resolves against what was last offered, so 「2番目で」 works
  without the model having to know what the times were. An index that does not
  match an offer is dropped rather than guessed — off-by-one on a restaurant
  booking is a party arriving on the wrong evening."
  [state extracted]
  (let [offered (:denwaban/offered state)
        idx (:offer-index extracted)
        from-offer (when (and (integer? idx) (< -1 idx (count offered)))
                     {:start-epoch-min (nth offered idx)})
        new-facts (->> (merge (select-keys extracted fact-keys) from-offer)
                       (remove (comp nil? val))
                       (into {}))]
    (-> state
        (update :denwaban/facts merge new-facts)
        (update :denwaban/turns inc))))

(defn missing
  "The first fact still needed, in `fact-order`, or nil."
  [state]
  (let [facts (:denwaban/facts state)]
    (first (remove #(some? (get facts %)) fact-order))))

(defn complete? [state] (nil? (missing state)))

(defn consented? [state] (true? (get-in state [:denwaban/facts :consent?])))

;; ── what to say ──────────────────────────────────────────────────────────────

(defn- hhmm [tz-offset-min epoch-min]
  (let [local (+ epoch-min tz-offset-min)
        m (mod local 1440)]
    (str (quot m 60) "時" (when (pos? (mod m 60)) (str (mod m 60) "分")))))

(defn offer-line
  "Times as a sentence. Instants only — never a count of what is behind them."
  [tz-offset-min instants]
  (str/join "、" (map #(hhmm tz-offset-min %) instants)))

(def asks
  "The consent question is deliberately absent: `denwaban.consent` owns that
  sentence, because the sentence asked and the sentence attested to have to be
  the same one. `ask-for` takes it as an argument rather than copying it here."
  {:party-size      "何名さまでしょうか。"
   :start-epoch-min "ご希望の日時をお願いします。"
   :name            "お名前をお願いします。"
   :contact         "お電話番号をお願いします。"})

(defn ask-for
  "The question for a missing fact."
  [fact {:keys [consent-statement tz-offset-min offers]}]
  (case fact
    :consent? consent-statement
    :start-epoch-min (if (seq offers)
                       (str "ご希望のお時間ですが、" (offer-line tz-offset-min offers)
                            " が承れます。いかがでしょうか。")
                       (get asks :start-epoch-min))
    (get asks fact)))

;; ── how a refusal becomes a sentence ─────────────────────────────────────────

(def refusal-responses
  "Every refusal `yotei.delegation/admit` can return, and what the caller hears.

  `:escalate?` marks the ones where the call must end with a human, not with
  another question. The three signature/expiry reasons are all escalations on
  purpose: they mean this machine cannot confirm anything right now, and the one
  thing it must not do is keep collecting details as though it could."
  {:slot-taken                          {:retry :start-epoch-min
                                         :reply "申し訳ありません、そのお時間は満席です。"}
   :all-tables-taken                    {:retry :start-epoch-min
                                         :reply "申し訳ありません、そのお時間は満席です。"}
   :outside-published-hours             {:retry :start-epoch-min
                                         :reply "そのお時間は営業時間外です。"}
   :in-the-past                         {:retry :start-epoch-min
                                         :reply "そのお時間はもう過ぎております。"}
   :beyond-horizon                      {:retry :start-epoch-min
                                         :reply "そこまで先のご予約はお電話では承っておりません。"}
   :party-exceeds-authorization         {:escalate? true
                                         :reply "その人数はお電話では承れないので、店の者から折り返しご連絡します。"}
   :table-cannot-seat-party             {:retry :start-epoch-min
                                         :reply "その人数で空いているお席が、そのお時間にはございません。"}
   :table-not-in-authorization          {:escalate? true
                                         :reply "こちらでお席をご用意できませんでした。店の者から折り返しご連絡します。"}
   :duration-not-authorized-seating-time {:escalate? true
                                          :reply "ご利用時間の条件が合いませんでした。店の者から折り返しご連絡します。"}
   :missing-consent                     {:retry :consent?
                                         :reply "恐れ入ります、もう一度確認させてください。"}
   ;; Not one of admit's reasons: denwaban's own, for when it has no way to seal
   ;; the caller's details. Escalating rather than proceeding in the clear is the
   ;; whole of G3 — a receptionist that cannot protect a telephone number does
   ;; not get to take one.
   :no-contact-seal                     {:escalate? true
                                         :reply "ただいまお電話でのご確定ができません。店の者から折り返しご連絡します。"}
   :party-size-unknown                  {:retry :party-size
                                         :reply "恐れ入ります、人数をもう一度お願いします。"}
   :not-proposed                        {:escalate? true
                                         :reply "手続きが進められませんでした。店の者から折り返しご連絡します。"}
   :authorization-expired               {:escalate? true
                                         :reply "ただいまお電話でのご確定ができません。店の者から折り返しご連絡します。"}
   :owner-signature-unverified          {:escalate? true
                                         :reply "ただいまお電話でのご確定ができません。店の者から折り返しご連絡します。"}
   :delegate-signature-unverified       {:escalate? true
                                         :reply "ただいまお電話でのご確定ができません。店の者から折り返しご連絡します。"}})

(def ^:private unknown-refusal
  {:escalate? true
   :reply "お電話ではお受けできませんでした。店の者から折り返しご連絡します。"})

(defn respond-to-refusal
  "Turn a set of admit reasons into one thing to say.

  An escalating reason wins over a retryable one: if the envelope has expired
  *and* the table is taken, offering another time would be a lie, because no
  time can be confirmed. Among retryable reasons the first in `fact-order`
  wins, so the caller is asked for one thing at a time."
  [reasons]
  (let [rs (map #(get refusal-responses % unknown-refusal) reasons)]
    (or (first (filter :escalate? rs))
        (first rs)
        unknown-refusal)))

;; ── the turn ─────────────────────────────────────────────────────────────────

(defn next-step
  "Pure: given the state, what happens next.

  `{:kind :ask :fact k}` | `{:kind :book}` | `{:kind :escalate :reason k}`"
  [state]
  (cond
    (= :escalated (:denwaban/outcome state)) {:kind :escalate :reason (:denwaban/reason state)}
    (= :confirmed (:denwaban/outcome state)) {:kind :done}
    (complete? state) (if (consented? state)
                        {:kind :book}
                        {:kind :ask :fact :consent?})
    :else {:kind :ask :fact (missing state)}))

(defn booking-slot
  "The request `denwaban.yoyaku/propose` will hold a table with.

  The consent reference is minted **here**, at the moment the conversation is
  complete, from `denwaban.consent/attest` — not carried along from earlier and
  not defaulted. If consent was not actually granted, `attest` refuses and there
  is no slot, so a 予約 cannot be proposed for a caller who never said yes."
  [ctx state]
  (let [{:keys [party-size start-epoch-min name contact]} (:denwaban/facts state)
        attested (consent/attest {:call-ref (:denwaban/call-ref state)
                                  :granted? (consented? state)
                                  :spoken-at-epoch-min (:denwaban/now-epoch-min state)})]
    (if (:denwaban/refused attested)
      attested
      {:yoyaku-id ((:mint-id ctx))
       :party-size party-size
       :start-epoch-min start-epoch-min
       :name name
       :contact contact
       :consent-ref (:denwaban/consent-ref attested)
       :consent-kind (:denwaban/consent-kind attested)})))

(defn step
  "One turn of the receptionist. `ctx` supplies the injected pieces:

    :extract           text + state -> extracted facts  (KotobaLLM in production)
    :open-times        party-size -> [instant ...]      (yotei.seat, via the client)
    :mint-id           () -> a fresh 予約 id
    :consent-statement the sentence denwaban.consent attests to (defaults to it)
    :tz-offset-min     the shop's clock, for reading times back

  Returns koe's `{:reply :action :state}`. `:action` is `:book` only when every
  fact is in hand and consent was given; the actual 予約 is `koe.session`'s to
  delegate, never this namespace's to make."
  [ctx state utterance]
  (let [extracted ((:extract ctx) utterance state)
        state' (absorb state extracted)
        {:keys [kind fact reason]} (next-step state')]
    (case kind
      :book (let [slot (booking-slot ctx state')]
              (if (:denwaban/refused slot)
                ;; attest refused: consent is not actually in hand. Ask again
                ;; rather than proposing a 予約 nobody agreed to.
                {:state (update state' :denwaban/facts dissoc :consent?)
                 :action nil
                 :reply (:consent-statement ctx consent/statement)}
                {:state (assoc state' :slot slot) :action :book :reply nil}))
      :escalate {:state (assoc state' :denwaban/outcome :escalated)
                 :action :escalate
                 :reply (:reply (respond-to-refusal [reason]))}
      :done {:state state' :action nil :reply nil}
      :ask (let [offers (when (= fact :start-epoch-min)
                          (when-let [n (get-in state' [:denwaban/facts :party-size])]
                            (vec (take 3 ((:open-times ctx) n)))))
                 state'' (cond-> state' (seq offers) (assoc :denwaban/offered offers))]
             {:state state''
              :action nil
              :reply (ask-for fact {:consent-statement (:consent-statement ctx consent/statement)
                                    :tz-offset-min (:tz-offset-min ctx)
                                    :offers offers})}))))

(defn after-refusal
  "Fold a refused 予約 back into the conversation.

  A retryable refusal *clears the fact it was about* — otherwise the state is
  still complete, `next-step` says `:book` again, and the call loops on a 予約
  that will be refused every time."
  [state reasons]
  (let [{:keys [retry escalate? reply]} (respond-to-refusal reasons)]
    (if escalate?
      {:state (assoc state :denwaban/outcome :escalated :denwaban/reason (first reasons))
       :action :escalate
       :reply reply}
      {:state (update state :denwaban/facts dissoc retry)
       :action nil
       :reply reply})))

(defrecord RestaurantDialog [ctx]
  ports/IDialog
  (step [_ state utterance] (step ctx state utterance)))

(defn dialog
  "The `koe.ports/IDialog` a session injects."
  [ctx]
  (->RestaurantDialog ctx))

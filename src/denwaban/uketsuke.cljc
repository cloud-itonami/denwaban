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
            [koe.arrival :as arrival]
            [denwaban.consent :as consent]
            [koe.ports :as ports]))

;; ── state ────────────────────────────────────────────────────────────────────

(def fact-order
  "Asked in this order. Party size first because it decides which times exist:
  asking for a time before knowing the size means offering times that may not
  survive the next answer."
  [:party-size :start-epoch-min :name :contact :consent?])

(defn initial-state
  "Start a call.

  The caller's number is taken from `koe.arrival`, never from the presented
  caller ID directly. On a forwarded call -- the first way this is deployed, and
  the cheapest -- the presented number may be the shop's own line rather than the
  caller's, and a wrong contact is worse than a missing one: it is never asked
  about, and the 予約 looks well formed while holding a table for somebody nobody
  can reach. When it is not usable, `:contact` is simply absent and the ordinary
  slot-filling asks for it.

  `:locale` decides which language is spoken AND which consent sentence is
  attested to; it is carried on the state so both come from one place."
  [{:keys [call-ref now-epoch-min locale] :as call}]
  (let [{:koe/keys [contact reason]} (arrival/caller-contact call)]
    {:denwaban/call-ref call-ref
     :denwaban/now-epoch-min now-epoch-min
     :denwaban/locale (or locale consent/default-locale)
     :denwaban/arrival (arrival/describe call)
     :koe/contact-unavailable reason
     :denwaban/facts (cond-> {} contact (assoc :contact contact))
     :denwaban/offered []
     :denwaban/turns 0
     :denwaban/outcome nil}))

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
  the same one. `ask-for` takes it from there rather than copying it here."
  {:ja {:party-size      "何名さまでしょうか。"
        :start-epoch-min "ご希望の日時をお願いします。"
        :name            "お名前をお願いします。"
        :contact         "お電話番号をお願いします。"}
   :en {:party-size      "How many people will there be?"
        :start-epoch-min "What day and time would you like?"
        :name            "May I have your name?"
        :contact         "And a phone number?"}})

(defn ask-for
  "The question for a missing fact, in the caller's language."
  [locale fact {:keys [tz-offset-min offers]}]
  (case fact
    :consent? (consent/statement locale)
    :start-epoch-min (if (seq offers)
                       (str (get-in {:ja "ご希望のお時間ですが、" :en "I can offer "} [locale])
                            (offer-line tz-offset-min offers)
                            (get-in {:ja " が承れます。いかがでしょうか。"
                                     :en ". Would any of those suit?"} [locale]))
                       (get-in asks [locale :start-epoch-min]))
    (get-in asks [locale fact])))

;; ── how a refusal becomes a sentence ─────────────────────────────────────────

(def refusal-policy
  "What each refusal MEANS, independent of language.

  Policy and wording are separate maps on purpose. A single map per locale would
  make `:escalate?` something each translation restates, and the first
  mistranslation would be a language in which an expired envelope quietly kept
  taking bookings. There is one policy; the languages only phrase it.

  `:escalate?` marks the refusals where the call must end with a human rather
  than another question. The signature, expiry and timezone reasons are all
  escalations: they mean this machine cannot confirm anything right now, and the
  one thing it must not do is keep collecting details as though it could."
  {:slot-taken                           {:retry :start-epoch-min}
   :all-tables-taken                     {:retry :start-epoch-min}
   :outside-published-hours              {:retry :start-epoch-min}
   :in-the-past                          {:retry :start-epoch-min}
   :beyond-horizon                       {:retry :start-epoch-min}
   :table-cannot-seat-party              {:retry :start-epoch-min}
   :missing-consent                      {:retry :consent?}
   :party-size-unknown                   {:retry :party-size}
   :party-exceeds-authorization          {:escalate? true}
   :table-not-in-authorization           {:escalate? true}
   :duration-not-authorized-seating-time {:escalate? true}
   :not-proposed                         {:escalate? true}
   :authorization-expired                {:escalate? true}
   :owner-signature-unverified           {:escalate? true}
   :delegate-signature-unverified        {:escalate? true}
   ;; The zone could not be resolved, so nothing can be judged against the
   ;; owner's hours. Offering another time would be guessing.
   :timezone-unresolved                  {:escalate? true}
   ;; denwaban's own, not admit's: no way to seal the caller's details. A
   ;; receptionist that cannot protect a telephone number does not take one.
   :no-contact-seal                      {:escalate? true}})

(def ^:private callback
  {:ja "ただいまお電話でのご確定ができません。店の者から折り返しご連絡します。"
   :en "I can't confirm that on the phone right now. Someone from the restaurant will call you back."})

(def refusal-text
  "Wording only. A reason with no wording in this locale falls back to the
  callback sentence, which is safe in the direction that matters: it never
  invents an availability claim."
  {:ja {:slot-taken "申し訳ありません、そのお時間は満席です。"
        :all-tables-taken "申し訳ありません、そのお時間は満席です。"
        :outside-published-hours "そのお時間は営業時間外です。"
        :in-the-past "そのお時間はもう過ぎております。"
        :beyond-horizon "そこまで先のご予約はお電話では承っておりません。"
        :table-cannot-seat-party "その人数で空いているお席が、そのお時間にはございません。"
        :missing-consent "恐れ入ります、もう一度確認させてください。"
        :party-size-unknown "恐れ入ります、人数をもう一度お願いします。"
        :party-exceeds-authorization "その人数はお電話では承れないので、店の者から折り返しご連絡します。"}
   :en {:slot-taken "I'm sorry, we're fully booked at that time."
        :all-tables-taken "I'm sorry, we're fully booked at that time."
        :outside-published-hours "We're closed at that time."
        :in-the-past "I'm afraid that time has already passed."
        :beyond-horizon "We don't take bookings that far ahead by phone."
        :table-cannot-seat-party "We don't have a table for that many at that time."
        :missing-consent "Sorry, may I check that with you once more?"
        :party-size-unknown "Sorry, how many people was that?"
        :party-exceeds-authorization "I can't take a party that size by phone -- someone from the restaurant will call you back."}})

(defn respond-to-refusal
  "Turn a set of admit reasons into one thing to say.

  An escalating reason wins over a retryable one: if the envelope has expired
  *and* the table is taken, offering another time would be a lie, because no time
  can be confirmed. Among retryable reasons the first given wins, so the caller
  is asked for one thing at a time."
  [locale reasons]
  (let [policy (fn [r] (assoc (get refusal-policy r {:escalate? true}) :reason r))
        rs (map policy reasons)
        chosen (or (first (filter :escalate? rs)) (first rs) {:escalate? true :reason :unknown})
        text (or (get-in refusal-text [locale (:reason chosen)])
                 (get callback locale)
                 (get callback consent/default-locale))]
    (assoc chosen :reply text)))

;; ── the turn ─────────────────────────────────────────────────────────────────

(defn next-step
  "Pure: given the state, what happens next.

  `{:kind :ask :fact k}` | `{:kind :book}` | `{:kind :escalate :reason k}`"
  [state]
  (cond
    (= :escalated (:denwaban/outcome state)) {:kind :escalate :reason (:koe/reason state)}
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
                                  :spoken-at-epoch-min (:denwaban/now-epoch-min state)
                                  :locale (:denwaban/locale state)})]
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
    :tz-offset-min     the shop's clock, for reading offered times back

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
                 :reply (consent/statement (:denwaban/locale state'))}
                {:state (assoc state' :slot slot) :action :book :reply nil}))
      :escalate {:state (assoc state' :denwaban/outcome :escalated)
                 :action :escalate
                 :reply (:reply (respond-to-refusal (:denwaban/locale state') [reason]))}
      :done {:state state' :action nil :reply nil}
      :ask (let [offers (when (= fact :start-epoch-min)
                          (when-let [n (get-in state' [:denwaban/facts :party-size])]
                            (vec (take 3 ((:open-times ctx) n)))))
                 state'' (cond-> state' (seq offers) (assoc :denwaban/offered offers))]
             {:state state''
              :action nil
              :reply (ask-for (:denwaban/locale state') fact
                              {:tz-offset-min (:tz-offset-min ctx)
                               :offers offers})}))))

(defn after-refusal
  "Fold a refused 予約 back into the conversation.

  A retryable refusal *clears the fact it was about* — otherwise the state is
  still complete, `next-step` says `:book` again, and the call loops on a 予約
  that will be refused every time."
  [state reasons]
  (let [{:keys [retry escalate? reply]} (respond-to-refusal (:denwaban/locale state) reasons)]
    (if escalate?
      {:state (assoc state :denwaban/outcome :escalated :koe/reason (first reasons))
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

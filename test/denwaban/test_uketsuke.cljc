(ns denwaban.test-uketsuke
  "A telephone call that ends in a 予約 that exists.

  This is the suite that was missing while `run-session` raised: not that the
  pipeline is composed correctly, but that a person can say five sentences and a
  table is held for them in `cloud-itonami/yotei`. The ports here are fixtures —
  no carrier, no model, no WebCrypto — but every decision on the path is the
  production one: `yotei.seat` chooses the table, `yotei.delegation` enforces the
  envelope, `yotei.yoyaku` refuses the overlap, `koe.session` delegates.

  The refusal tests matter at least as much. A receptionist that books when it
  should and also books when it should not is worse than one that never books."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denwaban.consent :as consent]
            [denwaban.session :as session]
            [denwaban.uketsuke :as uketsuke]
            [denwaban.yoyaku :as booking]
            [koe.ports :as ports]
            [yotei.delegation :as delegation]
            [yotei.seat :as seat]
            [yotei.time :as t]))

(def REST "did:web:app.itonami.cloud:calendar:torikai")
(def CALLER "+819012345678")

(def ^:private t19 (+ (* (t/days-from-civil 2026 8 20) 1440) (- (* 19 60) 540)))
(def ^:private now (- t19 (* 2 1440)))

(defn- auth* [& kvs]
  (delegation/authorization
   (merge {:yotei/delegate-did "did:web:denwaban.etzhayyim.com#uketsuke-2026-08"
           :yotei/restaurant-did REST
           :yotei/not-after-epoch-min (+ now (* 60 1440))
           :yotei/max-party-size 6
           :yotei/seating-min 90
           :yotei/notice-min 60
           :yotei/horizon-days 30
           :yotei/tz-offset-min 540
           :yotei/tables [[(seat/table-did REST "t2") 2]
                          [(seat/table-did REST "t4") 4]
                          [(seat/table-did REST "t6") 6]]
           :yotei/windows [{:yotei/day :thursday :yotei/from "17:30" :yotei/to "22:00"}]}
          (apply hash-map kvs))))

;; ── fixture ports ────────────────────────────────────────────────────────────

(def ^:private script
  "What the language model would have extracted. The model's job ends here; every
  decision after this map is arithmetic."
  {"予約をお願いします"          {}
   "4人です"                    {:party-size 4}
   "9人です"                    {:party-size 9}
   "2人です"                    {:party-size 2}
   "20日の19時でお願いします"     {:start-epoch-min t19}
   "川崎です"                    {:name "川崎"}
   "はい"                       {:consent? true}
   "いいえ"                     {:consent? false}})

(defn- extract [utterance _state] (get script utterance {}))

(defrecord FixtureTTS []
  ports/ITTS
  (synth [_ text _opts] {:audio/text text}))

(defn- ports*
  [{:keys [authorization confirmed sealed?] :or {confirmed [] sealed? true}}]
  (let [store (atom (vec confirmed))
        ids (atom 0)
        bctx {:authorization authorization
              :confirmed-fn (fn [] @store)
              :now-fn (constantly now)
              :verify-owner (constantly true)
              :verify-delegate (constantly true)
              :seal-contact (when sealed?
                              (fn [{:keys [name contact]}]
                                (str "sealed:" (hash [name contact]))))}]
    {:store store
     :ports {:dialog (uketsuke/dialog
                      {:extract extract
                       :tz-offset-min 540
                       :mint-id (fn [] (str "y-" (swap! ids inc)))
                       :open-times (fn [n] (booking/open-times bctx n))})
             :tts (->FixtureTTS)
             :booking (booking/booking bctx)}}))

(defn- signer [proposal]
  {"origin" "delegate" "ref" (str "sig:" (get proposal "yoyakuId"))})

(defn- call
  "Run a whole call and hand back the outcome."
  [opts utterances]
  (let [{:keys [ports store]} (ports* opts)]
    (assoc (session/run-call ports
                             (uketsuke/initial-state {:call-ref "call-1"
                                                      :caller-contact CALLER
                                                      :now-epoch-min now})
                             utterances
                             {:signature signer})
           :store store)))

(def ^:private full-call
  ["予約をお願いします" "4人です" "20日の19時でお願いします" "川崎です" "はい"])

;; ── the call that works ──────────────────────────────────────────────────────

(deftest test-a-telephone-call-ends-in-a-confirmed-yoyaku
  (let [{:keys [booking replies state]} (call {:authorization (auth*)} full-call)]
    (testing "yotei confirmed it"
      (is (= "confirmed" (get booking "state")))
      (is (= "delegate" (get booking "confirmedVia"))))
    (testing "and it says which sentence the owner signed to allow it"
      (is (= (:yotei/statement (auth*)) (get booking "authorizedBy"))))
    (testing "on a table that fits four"
      (is (= (seat/table-did REST "t4") (get booking "calendarDid"))))
    (testing "at the time that was asked for, for the seating time the owner set"
      (is (= t19 (get booking "startEpochMin")))
      (is (= 90 (get booking "durationMin"))))
    (testing "the caller was asked for exactly what was missing, in order"
      (is (= ["何名さまでしょうか。" "お名前をお願いします。" consent/statement]
             (->> replies (remove nil?) butlast (remove #(str/includes? % "承れます"))))))
    (is (= :confirmed (:denwaban/outcome state)))))

(deftest test-the-callers-details-are-not-in-the-yoyaku
  (testing "G2/G3: a name and a telephone number go in sealed or not at all"
    (let [{:keys [booking]} (call {:authorization (auth*)} full-call)
          text (pr-str booking)]
      (is (not (str/includes? text CALLER)))
      (is (not (str/includes? text "川崎")))
      (is (str/starts-with? (get booking "contactRef") "sealed:")))))

(deftest test-the-consent-is-stamped-as-attested-not-signed
  (testing "nobody downstream can mistake a telephone yes for a DID-signed consent"
    (let [{:keys [booking]} (call {:authorization (auth*)} full-call)]
      (is (= consent/kind (get booking "consentKind")))
      (is (str/starts-with? (get booking "consentRef") consent/kind)))))

(deftest test-times-are-offered-only-once-the-party-size-is-known
  (let [{:keys [replies]} (call {:authorization (auth*)} ["予約をお願いします" "4人です"])]
    (is (= "何名さまでしょうか。" (first (remove nil? replies))))
    (is (str/includes? (last (remove nil? replies)) "承れます"))))

;; ── the refusals ─────────────────────────────────────────────────────────────

(deftest test-a-taken-table-is-not-booked-twice
  (let [taken (mapv (fn [id] {"status" "confirmed" "calendarDid" (seat/table-did REST id)
                              "startEpochMin" t19 "durationMin" 90})
                    ["t4" "t6"])
        {:keys [booking replies]} (call {:authorization (auth*) :confirmed taken} full-call)]
    (is (nil? booking))
    (is (some #(and % (str/includes? % "満席")) replies))))

(deftest test-a-party-larger-than-the-envelope-escalates
  (let [{:keys [booking replies state]}
        (call {:authorization (auth*)}
              ["予約をお願いします" "9人です" "20日の19時でお願いします" "川崎です" "はい"])]
    (is (nil? booking))
    (is (= :escalated (:denwaban/outcome state)))
    (is (some #(and % (str/includes? % "折り返し")) replies))))

(deftest test-an-expired-envelope-escalates-and-stops-asking
  (testing "a receptionist that cannot confirm must not keep taking details"
    (let [{:keys [booking replies state]}
          (call {:authorization (auth* :yotei/not-after-epoch-min (- now 1))} full-call)]
      (is (nil? booking))
      (is (= :escalated (:denwaban/outcome state)))
      (is (some #(and % (str/includes? % "折り返し")) replies)))))

(deftest test-without-a-way-to-seal-the-contact-nothing-is-proposed
  (let [{:keys [booking state]} (call {:authorization (auth*) :sealed? false} full-call)]
    (is (nil? booking))
    (is (= :escalated (:denwaban/outcome state)))))

(deftest test-a-caller-who-says-no-is-never-booked
  (let [{:keys [booking]}
        (call {:authorization (auth*)}
              ["予約をお願いします" "4人です" "20日の19時でお願いします" "川崎です" "いいえ"])]
    (is (nil? booking))))

(deftest test-consent-is-re-asked-not-assumed
  (let [{:keys [replies]}
        (call {:authorization (auth*)}
              ["予約をお願いします" "4人です" "20日の19時でお願いします" "川崎です" "いいえ"])]
    (is (= consent/statement (last (remove nil? replies))))))

;; ── consent, one test per layer ──────────────────────────────────────────────
;;
;; Consent is checked twice: `next-step` refuses to reach `:book`, and
;; `booking-slot` refuses to mint a consent reference. That is deliberate, and it
;; is also why the call-level test above proves neither of them -- disabling
;; either layer alone leaves the other one catching it, so the whole suite still
;; passed with each gate removed in turn (measured, not assumed). Defence in
;; depth needs a test per layer or it is only ever evidence about the layer that
;; happened to fire.

(defn- state-with [facts]
  (uketsuke/absorb (uketsuke/initial-state {:call-ref "call-1" :caller-contact CALLER
                                            :now-epoch-min now})
                   facts))

(deftest test-layer-one-next-step-will-not-book-without-consent
  (let [complete {:party-size 2 :start-epoch-min t19 :name "川崎"}]
    (is (= {:kind :book} (uketsuke/next-step (state-with (assoc complete :consent? true)))))
    (is (= {:kind :ask :fact :consent?}
           (uketsuke/next-step (state-with (assoc complete :consent? false)))))))

(deftest test-layer-two-booking-slot-will-not-mint-a-consent-that-was-not-given
  (let [ctx {:mint-id (constantly "y-1")}
        complete {:party-size 2 :start-epoch-min t19 :name "川崎"}]
    (is (= :consent-not-granted
           (:denwaban/refused (uketsuke/booking-slot ctx (state-with (assoc complete :consent? false))))))
    (is (some? (:consent-ref (uketsuke/booking-slot ctx (state-with (assoc complete :consent? true))))))))

(deftest test-attest-refuses-what-it-cannot-attest-to
  (is (= :consent-not-granted (:denwaban/refused (consent/attest {:call-ref "c" :spoken-at-epoch-min now}))))
  (is (= :consent-not-granted (:denwaban/refused (consent/attest {:call-ref "c" :granted? "yes"
                                                                  :spoken-at-epoch-min now}))))
  (is (= :no-call-reference (:denwaban/refused (consent/attest {:granted? true :spoken-at-epoch-min now}))))
  (is (= :no-time-of-consent (:denwaban/refused (consent/attest {:call-ref "c" :granted? true})))))

;; ── the invariant koe exists to guard ────────────────────────────────────────

(deftest test-a-booking-that-skipped-confirm-is-rejected
  (testing "G2: denwaban raises rather than reporting a 予約 nobody confirmed"
    (is (not (session/turn-invariant-ok?
              {:action :book :booking {"state" "confirmed"}})))
    (testing "and a refusal is not treated as an unsigned booking"
      (is (session/turn-invariant-ok?
           {:action :book :booking {"refused" true "reasons" ["slot-taken"]}})))))

;; ── G7 still gates the thing it always gated ─────────────────────────────────

(deftest test-opening-a-live-channel-is-still-refused
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
               (session/run-session {}))))

(deftest test-g7-needs-all-three
  (is (not (session/g7-open? {})))
  (is (not (session/g7-open? {:council-level 6 :operator "jun"})))
  (is (not (session/g7-open? {:council-level 5 :operator "jun" :live-enabled? true})))
  (is (not (session/g7-open? {:council-level 6 :operator "" :live-enabled? true})))
  (is (not (session/g7-open? {:council-level 6 :operator "jun" :live-enabled? "yes"})))
  (is (session/g7-open? {:council-level 6 :operator "jun" :live-enabled? true})))

(deftest test-an-open-g7-without-a-carrier-still-refuses
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
               (session/run-session {:g7 {:council-level 6 :operator "jun" :live-enabled? true}
                                     :transport-plan {:status :held :reasons [:no-admitted-telephony-provider]}}))))

(deftest test-with-g7-open-and-a-carrier-a-channel-opens
  (let [plan (session/run-session
              {:g7 {:council-level 6 :operator "jun" :live-enabled? true}
               :transport-plan {:status :ready :telephony-provider :telnyx
                                :access-path :terrestrial}})]
    (is (= :live (:channel plan)))
    (is (= "telephony/telnyx" (-> plan :stages first :actor)))))

(ns denwaban.test-kikitori
  "聞き取り, tested against what the speech engine actually produced.

  The strings in `whisper-transcripts` are not invented examples. They are the
  exact output of `whisper-cli` with `ggml-large-v3-turbo` on `levi`, over
  Japanese reservation utterances that had been degraded to 8 kHz μ-law and back
  — the path a real call takes. Testing extraction against hand-written text
  would test a language nobody's telephone produces: the engine writes 「4人です」
  where the sentence said 「四人です」, and it is the engine's spelling that has to
  parse.

  The other half of this suite is refusals. Every function returns nil rather
  than a guess, because an absent field costs one more question and a wrong one
  costs a party of six at a table for two."
  (:require [clojure.test :refer [deftest is testing]]
            [denwaban.kikitori :as k]))

;; 2026-08-20 is a Thursday. now = Tuesday 2026-08-18 12:00 JST.
(def ^:private tz 540)
;; Verified against the calendar rather than counted by hand: the first version
;; of this constant was 20684 and made three weekday tests fail by exactly one
;; day. The code was right; the constant was not.
(def ^:private day-2026-08-18 20683)                 ; Tuesday
(def ^:private now (- (+ (* day-2026-08-18 1440) (* 12 60)) tz))
(def ^:private ctx {:now-epoch-min now :tz-offset-min tz})

(defn- local-hhmm [epoch-min]
  (let [m (mod (+ epoch-min tz) 1440)]
    [(quot m 60) (mod m 60)]))

(defn- local-day [epoch-min]
  (long (Math/floor (/ (double (+ epoch-min tz)) 1440.0))))

;; ── against the engine's own output ──────────────────────────────────────────

(def whisper-transcripts
  "Measured on levi, 2026-08-15, ggml-large-v3-turbo, telephony-degraded audio."
  [["予約をお願いします。"                    {}]
   ["4人です。"                              {:party-size 4}]
   ["川崎と申します。"                        {:name "川崎"}]
   ["電話番号は090-1234-5678です。"           {:contact "+819012345678"}]
   ["はい、それでお願いします。"               {:consent? true}]])

(deftest test-the-engines-own-transcripts-parse
  (doseq [[text expected] whisper-transcripts]
    (is (= expected (k/extract text ctx)) (str "transcript: " text))))

(deftest test-the-opening-line-is-not-consent
  (testing "「予約をお願いします」 opens the call; reading it as agreement would attest"
    (testing "that the caller handed over their details before being asked"
      (is (nil? (k/consent "予約をお願いします。")))
      (is (not (contains? (k/extract "予約をお願いします。" ctx) :consent?))))))

(deftest test-a-day-and-a-time-together-resolve
  (let [got (k/extract "今週の木曜日の7時でお願いします。" ctx)
        t (:start-epoch-min got)]
    (is (some? t))
    (is (= [19 0] (local-hhmm t)) "7時 in a restaurant is 19:00, not 07:00")
    (is (= 2 (- (local-day t) day-2026-08-18)) "Thursday is two days after Tuesday")))

(deftest test-the-six-oclock-half-case
  (let [got (k/extract "6名で土曜日の6時半は空いてますか?" ctx)]
    (is (= 6 (:party-size got)))
    (is (= [18 30] (local-hhmm (:start-epoch-min got))))
    (is (= 4 (- (local-day (:start-epoch-min got)) day-2026-08-18)) "Saturday")))

(deftest test-tonight-plus-a-time
  (let [got (k/extract "二人で、今夜8時お願いできますか。" ctx)]
    (is (= 2 (:party-size got)))
    (is (= [20 0] (local-hhmm (:start-epoch-min got))))
    (is (= 0 (- (local-day (:start-epoch-min got)) day-2026-08-18)) "tonight is today")))

;; ── numbers ──────────────────────────────────────────────────────────────────

(deftest test-japanese-numerals
  (is (= 4 (k/kanji->int "四")))
  (is (= 10 (k/kanji->int "十")))
  (is (= 12 (k/kanji->int "十二")))
  (is (= 20 (k/kanji->int "二十")))
  (is (= 23 (k/kanji->int "二十三")))
  (is (= 7 (k/kanji->int "7")))
  (testing "and anything else is nil rather than approximated"
    (is (nil? (k/kanji->int "百二十三")))
    (is (nil? (k/kanji->int "よん")))
    (is (nil? (k/kanji->int "")))))

(deftest test-party-size-needs-a-counter-word
  (is (= 4 (k/party-size "4人です")))
  (is (= 6 (k/party-size "六名で")))
  (is (= 2 (k/party-size "2名様")))
  (is (= 1 (k/party-size "ひとりです")))
  (is (= 2 (k/party-size "ふたりで")))
  (testing "a bare number is never a party size"
    (is (nil? (k/party-size "4番テーブルでした")))
    (is (nil? (k/party-size "4月に伺います")))
    (is (nil? (k/party-size "4")))))

;; ── time ─────────────────────────────────────────────────────────────────────

(deftest test-time-of-day
  (is (= (+ (* 19 60) 0) (k/time-of-day "7時")))
  (is (= (+ (* 19 60) 30) (k/time-of-day "7時半")))
  (is (= (+ (* 19 60) 15) (k/time-of-day "7時15分")))
  (is (= (* 19 60) (k/time-of-day "19時")))
  (is (= (* 19 60) (k/time-of-day "午後7時")))
  (is (= (* 7 60) (k/time-of-day "午前7時")))
  (is (= (* 11 60) (k/time-of-day "11時")) "11時 is not shifted; only 1–10 are")
  (testing "and no time at all is nil"
    (is (nil? (k/time-of-day "空いてますか")))))

(deftest test-a-time-with-no-day-does-not-become-today
  (testing "at 23:00 that silently books tomorrow, or a slot already gone"
    (is (nil? (k/start-epoch-min "7時でお願いします" ctx)))))

(deftest test-a-day-with-no-time-does-not-become-a-booking
  (is (nil? (k/start-epoch-min "木曜日でお願いします" ctx))))

(deftest test-a-named-weekday-means-the-next-one
  (testing "said on a Tuesday, 火曜日 means a week today — a caller meaning today says 今日"
    (let [t (k/start-epoch-min "火曜日の7時" ctx)]
      (is (= 7 (- (local-day t) day-2026-08-18))))))

;; ── contact and name ─────────────────────────────────────────────────────────

(deftest test-phone-numbers-are-anchored
  (is (= "+819012345678" (k/phone-number "090-1234-5678です")))
  (is (= "+819012345678" (k/phone-number "０９０１２３４５６７８")))
  (is (= "+81312345678" (k/phone-number "03-1234-5678")))
  (testing "and a number that is not a telephone number is not read as one"
    (is (nil? (k/phone-number "2026年8月20日")))
    (is (nil? (k/phone-number "6名です")))
    (is (nil? (k/phone-number "12345")))))

(deftest test-names-must-be-marked-by-the-caller
  (is (= "川崎" (k/caller-name "川崎です")))
  (is (= "川崎" (k/caller-name "川崎と申します")))
  (testing "and a sentence ending in です is not a name"
    (is (nil? (k/caller-name "4人です")))
    (is (nil? (k/caller-name "はいです")))
    (is (nil? (k/caller-name "7時です")))))

;; ── consent ──────────────────────────────────────────────────────────────────

(deftest test-consent-is-three-valued
  (is (true? (k/consent "はい")))
  (is (true? (k/consent "はい、大丈夫です")))
  (is (false? (k/consent "いいえ")))
  (is (false? (k/consent "結構です")))
  (testing "and an utterance that is not an answer is not recorded as one"
    (is (nil? (k/consent "6名でお願いします")))
    (is (nil? (k/consent "何時からですか")))))

;; ── the seam ─────────────────────────────────────────────────────────────────

(deftest test-the-extractor-matches-uketsukes-injection-shape
  (let [f (k/extractor {:tz-offset-min tz})
        state {:denwaban/now-epoch-min now}]
    (is (= {:party-size 4} (f "4人です" state)))))

(deftest test-the-model-is-consulted-only-when-nothing-was-read
  (let [calls (atom 0)
        f (k/extractor {:tz-offset-min tz
                        :fallback (fn [_ _] (swap! calls inc) {:party-size 9})})
        state {:denwaban/now-epoch-min now}]
    (testing "a readable utterance costs no model call — this is the whole point"
      (is (= {:party-size 4} (f "4人です" state)))
      (is (zero? @calls)))
    (testing "an unreadable one falls back"
      (is (= {:party-size 9} (f "えーっと、そうですねぇ" state)))
      (is (= 1 @calls)))))

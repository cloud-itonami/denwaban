(ns denwaban.kikitori
  "聞き取り — pulling the regular facts out of a Japanese utterance without a
  model.

  ## Why this exists

  `denwaban.uketsuke` takes extraction as an injected function and says the model
  does that part. Measured 2026-08-15, the fleet's dialog model answers at
  **11.9 tokens/second** and is a reasoning model that spent all 80 tokens of a
  budget on thinking and returned an empty `content` — 9 to 10.6 seconds for one
  extraction that produced nothing. A telephone turn already costs ~1.45 s of STT
  and ~1.14 s of TTS. Ten more seconds is not a slow receptionist; it is a call
  the other person hangs up.

  But most of what a reservation needs is not a language problem. 「四人です」
  「6時半」「はい」「090-1234-5678」 are regular, and a model is a strange thing to
  ask. This handles the regular cases in microseconds and leaves the rest.

  ## Partial is fine. Wrong is not.

  Every function here returns nil rather than a guess. A field this cannot read
  is simply absent, and `uketsuke` then asks for it — one more question, which is
  cheap. A field it reads *wrongly* is a party of six seated at a two-top, or a
  table held on the wrong evening, and nobody finds out until they arrive.

  So the patterns are narrow and anchored. 「4人」 is a party size; a bare 「4」 is
  not, because a bare number in 「4番テーブル」 or 「4月」 is something else. When
  the utterance is not one of the shapes below, this returns `{}` and the caller
  falls back to the model — slowly, but only for the calls that need it.

  ## What it deliberately does not read

  **Dates beyond the near ones.** 今日/明日/明後日/今夜 and a weekday are
  resolvable from `now`. 「再来週の金曜」「月末」「連休中日」 are not attempted:
  each is a chance to be a week wrong in a way that is confirmed and signed.

  **Names, except where the caller marked one.** 「川崎です」 and 「川崎と申します」
  are explicit. Anything else risks recording a fragment of the sentence as
  somebody's name."
  (:require [clojure.string :as str]))

;; ── numbers ──────────────────────────────────────────────────────────────────

(def ^:private kanji-digits
  {\〇 0 \零 0 \一 1 \二 2 \三 3 \四 4 \五 5 \六 6 \七 7 \八 8 \九 9})

(defn kanji->int
  "Japanese numerals up to 99, or nil.

  Handles the 十 forms a caller actually says — 十(10) 十二(12) 二十(20)
  二十三(23) — and refuses anything else rather than approximating."
  [s]
  (let [s (str s)]
    (cond
      (str/blank? s) nil
      (re-matches #"\d{1,2}" s) (#?(:clj Integer/parseInt :cljs js/parseInt) s)
      (re-matches #"[〇零一二三四五六七八九]" s) (get kanji-digits (first s))
      (= "十" s) 10
      (re-matches #"十[一二三四五六七八九]" s) (+ 10 (get kanji-digits (second s)))
      (re-matches #"[二三四五六七八九]十" s) (* 10 (get kanji-digits (first s)))
      (re-matches #"[二三四五六七八九]十[一二三四五六七八九]" s)
      (+ (* 10 (get kanji-digits (first s))) (get kanji-digits (nth s 2)))
      :else nil)))

(def ^:private num-pattern "[0-9]{1,2}|[〇零一二三四五六七八九十]{1,4}")

;; ── party size ───────────────────────────────────────────────────────────────

(def ^:private party-words
  "Counter words that mean people. 名 and 人 are unambiguous here; 組 is not
  (a 組 is a party, not a headcount) and is left out."
  #{"人" "名" "名様" "人前"})

(defn party-size
  "How many people, or nil.

  Anchored on a counter word, so a bare number is never a party size —
  「4番テーブル」 and 「4月」 both contain a 4 that means something else."
  [text]
  (let [t (str text)]
    (or (when-let [m (re-find (re-pattern (str "(" num-pattern ")\\s*(名様|名|人前|人)")) t)]
          (when (contains? party-words (nth m 2))
            (kanji->int (nth m 1))))
        ;; The two irregular ones a caller says constantly.
        (cond (re-find #"ひとり|一人" t) 1
              (re-find #"ふたり|二人" t) 2
              :else nil))))

;; ── time of day ──────────────────────────────────────────────────────────────

(defn time-of-day
  "Minutes from local midnight, or nil.

  `午後7時` / `19時` / `7時半` / `7時15分`. A 12-hour reading with no marker is
  resolved toward **service hours**: a restaurant saying 「7時」 means 19:00, and
  reading it as 07:00 would offer a morning nobody asked for. That rule is stated
  rather than inferred, and it is why `evening-bias?` is a parameter."
  [text & [{:keys [evening-bias?] :or {evening-bias? true}}]]
  (let [t (str text)
        m (re-find (re-pattern (str "(午前|午後|夜|朝)?\\s*(" num-pattern ")\\s*時\\s*(半|(" num-pattern ")\\s*分)?")) t)]
    (when m
      (let [marker (nth m 1)
            h (kanji->int (nth m 2))
            half? (= "半" (nth m 3))
            mins (or (when half? 30)
                     (some-> (nth m 4) kanji->int)
                     0)]
        (when (and h (<= 0 h 23) (<= 0 mins 59))
          (let [h (cond
                    (and (#{"午後" "夜"} marker) (< h 12)) (+ h 12)
                    (#{"午前" "朝"} marker) h
                    ;; No marker: 7時 in a restaurant is 19:00.
                    (and evening-bias? (<= 1 h 10)) (+ h 12)
                    :else h)]
            (+ (* 60 h) mins)))))))

;; ── the day ──────────────────────────────────────────────────────────────────

(def ^:private weekday-names
  {"日" 0 "月" 1 "火" 2 "水" 3 "木" 4 "金" 5 "土" 6})

(defn- local-epoch-day [now-epoch-min tz-offset-min]
  (long (Math/floor (/ (double (+ now-epoch-min tz-offset-min)) 1440.0))))

(defn day-offset
  "How many local days ahead the caller meant, or nil.

  `now-weekday` is 0=Sunday. A named weekday means the NEXT one — 「木曜日」 said
  on a Thursday means a week today, not today, because a caller who meant today
  says 今日 or 今晩."
  [text now-weekday]
  (let [t (str text)]
    (cond
      (re-find #"今日|今夜|今晩|本日" t) 0
      (re-find #"明日|あした|あす" t) 1
      (re-find #"明後日|あさって" t) 2
      :else
      (when-let [m (re-find #"([日月火水木金土])曜" t)]
        (let [target (get weekday-names (nth m 1))
              d (mod (- target now-weekday) 7)]
          (if (zero? d) 7 d))))))

(defn start-epoch-min
  "The instant the caller asked for, or nil.

  Requires BOTH a day and a time. A time with no day is not resolved to 'today'
  — at 23:00 that silently books tomorrow, or a slot that has already passed."
  [text {:keys [now-epoch-min tz-offset-min]}]
  (when (and (integer? now-epoch-min) (integer? tz-offset-min))
    (let [today (local-epoch-day now-epoch-min tz-offset-min)
          ;; 1970-01-01 was a Thursday (4).
          now-weekday (mod (+ 4 today) 7)
          d (day-offset text now-weekday)
          tod (time-of-day text)]
      (when (and d tod)
        (- (+ (* (+ today d) 1440) tod) tz-offset-min)))))

;; ── the rest ─────────────────────────────────────────────────────────────────

(defn phone-number
  "A Japanese telephone number in E.164, or nil. Anchored on a leading 0 and a
  plausible length, so a date or a price is not read as a number to call."
  [text]
  (let [digits (-> (str text)
                   (str/replace #"[^0-9０-９]" "")
                   (str/replace #"[０-９]" #(str (char (- (int (first %)) 65248)))))]
    (when (and (re-matches #"0\d{9,10}" digits))
      (str "+81" (subs digits 1)))))

(defn caller-name
  "A name the caller explicitly marked, or nil."
  [text]
  (when-let [m (re-find #"([一-龥ぁ-んァ-ヶА-Яa-zA-Z]{1,8})(?:と申します|です|になります)" (str text))]
    (let [n (nth m 1)]
      ;; Guard against the shapes that end in です but are not names.
      (when-not (re-find #"人|名|時|分|日|はい|そう|大丈夫|結構" n) n))))

(defn consent
  "true / false / nil. nil is the important one: an utterance that is not an
  answer must not be recorded as one, because this value becomes an attestation
  that a person agreed to a specific sentence.

  **Bare 「お願いします」 is not agreement.** It is how the call OPENS —
  「予約をお願いします」 — and reading it as consent would attest that the caller
  agreed to hand over their name and telephone number before anyone asked them.
  Consent needs an explicit affirmative; a caller who answers the consent
  question with only 「お願いします」 is asked once more, which is the cheap
  direction to be wrong in."
  [text]
  (let [t (str text)]
    (cond
      (re-find #"いいえ|やめ|結構です|嫌|だめ|ダメ|やっぱり" t) false
      ;; 「そうです」 is deliberately absent. It matched 「そうですねぇ」 — a filler
      ;; while the caller thinks — and recorded it as agreement (caught by this
      ;; namespace's own test). Agreement that becomes an attestation cannot be
      ;; read out of a hesitation.
      (re-find #"はい|ええ|うん|大丈夫です|かまいません|構いません|それでお願い" t) true
      :else nil)))

(defn offer-index
  "Which of the offered times, or nil. 「1番目」「二つ目」「最初の」."
  [text]
  (let [t (str text)]
    (cond
      (re-find #"最初|一番目|1番目|一つ目|1つ目" t) 0
      (re-find #"二番目|2番目|二つ目|2つ目" t) 1
      (re-find #"三番目|3番目|三つ目|3つ目" t) 2
      :else nil)))

;; ── the seam uketsuke already has ────────────────────────────────────────────

(defn extract
  "One utterance → the facts it clearly contains. Absent is absent."
  [text {:keys [now-epoch-min tz-offset-min]}]
  (->> {:party-size (party-size text)
        :start-epoch-min (start-epoch-min text {:now-epoch-min now-epoch-min
                                                :tz-offset-min tz-offset-min})
        :name (caller-name text)
        :contact (phone-number text)
        :consent? (consent text)
        :offer-index (offer-index text)}
       (remove (comp nil? val))
       (into {})))

(defn extractor
  "The `:extract` function `denwaban.uketsuke` injects.

  `fallback` is called only with what this could not read — the model, slowly,
  for the calls that need it. Without one, an unreadable utterance simply yields
  nothing and the receptionist asks again."
  [{:keys [tz-offset-min fallback]}]
  (fn [utterance state]
    (let [found (extract utterance {:now-epoch-min (:denwaban/now-epoch-min state)
                                    :tz-offset-min tz-offset-min})]
      (if (and fallback (empty? found))
        (merge found (or (fallback utterance state) {}))
        found))))

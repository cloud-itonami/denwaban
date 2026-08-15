(ns denwaban.serifu
  "台詞 — the lines this receptionist can say, and which of them it never has to
  wait to say.

  ## The measurement this exists for

  Synthesis costs 0.65–1.14 s depending on sentence length (measured 2026-08-15,
  macOS `say -v Kyoko` at `ulaw@8000`). Reading an already-rendered file costs
  **0.02 s**, most of which is process startup. Thirty times faster, and the
  difference is silence on the telephone while a person waits.

  The receptionist's questions are a **closed set** — `denwaban.uketsuke/asks`,
  the refusal wordings, and the consent sentence are all constants. There is no
  reason to synthesize 「何名さまでしょうか。」 on the call when it was the same
  sentence yesterday.

  ## Derived, never listed

  `phrases` is computed from the maps the dialog actually speaks from. A second
  hand-maintained list of things to pre-render is how a question gets added to
  the dialog, missed by the renderer, and silently costs a second forever — or
  worse, how a stale rendering keeps being played after the wording changed.

  ## Unknown lines are synthesized, never dropped

  `plan` returns `:synthesize` for anything not in the set. The dynamic lines —
  a confirmation naming a particular 予約 — are one turn at the end of a call
  rather than every turn, and paying 0.7 s once is fine. What must never happen
  is a line the receptionist cannot say."
  (:require [clojure.string :as str]
            [denwaban.consent :as consent]
            [denwaban.uketsuke :as uketsuke]))

(def locales
  "Which languages have a complete set. Derived from the consent sentences,
  because a locale without one cannot take a booking at all (G12)."
  (set (keys consent/statements)))

(defn phrase-id
  "A stable file name for a line. Content-addressed on the exact text, so a
  reworded question renders to a different file and cannot play the old wording."
  [locale text]
  (let [h (bit-and (hash (str/replace (str text) #"\s+" "")) 0x7fffffff)]
    (str (name locale) "-"
         #?(:clj (Integer/toHexString h)
            :cljs (.toString h 16)))))

(defn phrases
  "Every line the receptionist can say in this locale, as `{id text}`.

  Derived from `uketsuke/asks`, `uketsuke/refusal-text`, the callback sentence
  reachable through `respond-to-refusal`, and `consent/statements`."
  [locale]
  (let [texts (concat (vals (get uketsuke/asks locale))
                      (vals (get uketsuke/refusal-text locale))
                      ;; The fallback wording every unlisted refusal uses.
                      [(:reply (uketsuke/respond-to-refusal locale [::not-a-real-reason]))]
                      [(consent/statement locale)])]
    (into {} (for [t texts :when (seq t)] [(phrase-id locale t) t]))))

(def default-voices
  "A voice per locale. Not one voice for everything: the first version rendered
  the English lines with a Japanese voice, which is not an accent — it is a
  synthesizer reading English letters by Japanese rules."
  {:ja "Kyoko" :en "Samantha"})

(defn render-command
  "How one line is rendered ahead of time. Returned rather than executed — the
  host runs it at build time, and a test can assert the format without a
  synthesizer.

  `ulaw@8000` mono is the carrier's own format, so nothing is converted on the
  call."
  [{:keys [voice locale out text]}]
  ["say" "-v" (or voice (get default-voices locale) "Kyoko") "-o" out
   "--data-format=ulaw@8000" "--channels=1" (str text)])

(defn plan
  "What to do with a line the dialog wants spoken.

  `{:serifu/pre-rendered <id>}` when it was rendered ahead of time,
  `{:serifu/synthesize <text>}` otherwise. Never nil, and never silence."
  [locale text]
  (let [id (phrase-id locale text)]
    (if (contains? (phrases locale) id)
      {:serifu/pre-rendered id :serifu/text text}
      {:serifu/synthesize text})))

(defn manifest
  "Everything a build step needs: for each locale, the lines and where each one
  is written. Used by `denwaban.render-serifu` (`clojure -M:serifu`)."
  [{:keys [dir voice] :or {dir "resources/serifu"}}]
  (for [locale (sort-by name locales)
        [id text] (sort (phrases locale))]
    {:serifu/locale locale
     :serifu/id id
     :serifu/text text
     :serifu/path (str dir "/" id ".wav")
     :serifu/command (render-command {:voice voice :locale locale
                                      :out (str dir "/" id ".wav") :text text})}))

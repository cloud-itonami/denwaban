(ns denwaban.test-serifu
  "台詞 — what the receptionist never has to wait to say.

  Measured 2026-08-15: synthesis 0.65–1.14 s, reading a rendered file 0.02 s.
  The property under test is that the pre-rendered set is DERIVED from the maps
  the dialog speaks from, so a reworded question cannot keep playing yesterday's
  recording and a new question cannot be silently missed."
  (:require [clojure.test :refer [deftest is testing]]
            [denwaban.consent :as consent]
            [denwaban.serifu :as serifu]
            [denwaban.uketsuke :as uketsuke]))

(deftest test-every-question-the-dialog-asks-is-pre-rendered
  (doseq [locale [:ja :en]
          [fact _] (get uketsuke/asks locale)]
    (let [text (uketsuke/ask-for locale fact {})]
      (is (:serifu/pre-rendered (serifu/plan locale text))
          (str locale " " fact ": " text)))))

(deftest test-the-consent-sentence-is-pre-rendered
  (doseq [locale [:ja :en]]
    (is (:serifu/pre-rendered (serifu/plan locale (consent/statement locale))))))

(deftest test-every-refusal-wording-is-pre-rendered
  (doseq [locale [:ja :en]
          reason (keys uketsuke/refusal-policy)]
    (let [reply (:reply (uketsuke/respond-to-refusal locale [reason]))]
      (is (:serifu/pre-rendered (serifu/plan locale reply))
          (str locale " " reason ": " reply)))))

(deftest test-rewording-a-line-changes-its-file
  (testing "content-addressed, so a changed question cannot play the old recording"
    (is (not= (serifu/phrase-id :ja "何名さまでしょうか。")
              (serifu/phrase-id :ja "何名様でしょうか。")))))

(deftest test-the-same-line-is-stable-across-calls
  (is (= (serifu/phrase-id :ja "何名さまでしょうか。")
         (serifu/phrase-id :ja "何名さまでしょうか。")))
  (testing "and whitespace is not part of the identity"
    (is (= (serifu/phrase-id :ja "何名さまでしょうか。")
           (serifu/phrase-id :ja "何名さまでしょうか。 ")))))

(deftest test-a-locale-does-not-borrow-another-locales-recording
  (is (not= (serifu/phrase-id :ja "x") (serifu/phrase-id :en "x"))))

(deftest test-an-unknown-line-is-synthesized-never-dropped
  (let [p (serifu/plan :ja "承りました。y-1 でお席をご用意してお待ちしております。")]
    (is (= "承りました。y-1 でお席をご用意してお待ちしております。" (:serifu/synthesize p)))
    (is (nil? (:serifu/pre-rendered p)))))

(deftest test-each-locale-gets-a-voice-that-speaks-it
  (testing "the first version rendered the English lines with a Japanese voice"
    (let [by-locale (group-by :serifu/locale (serifu/manifest {}))
          voice-of (fn [entry] (nth (:serifu/command entry) 2))]
      (is (every? #(= "Kyoko" (voice-of %)) (get by-locale :ja)))
      (is (every? #(= "Samantha" (voice-of %)) (get by-locale :en)))))
  (testing "and an explicit voice still overrides"
    (is (some #{"Otoya"} (:serifu/command (first (serifu/manifest {:voice "Otoya"})))))))

(deftest test-the-render-command-emits-carrier-format
  (let [argv (serifu/render-command {:out "/tmp/a.wav" :text "テスト"})]
    (is (some #{"--data-format=ulaw@8000"} argv) "no conversion on the call")
    (is (some #{"--channels=1"} argv))
    (is (= "テスト" (last argv)))))

(deftest test-the-manifest-covers-both-locales-and-names-a-file-each
  (let [m (serifu/manifest {})]
    (is (seq m))
    (is (= #{:ja :en} (set (map :serifu/locale m))))
    (is (every? #(re-find #"\.wav$" (:serifu/path %)) m))
    (testing "and every id appears exactly once"
      (is (= (count m) (count (set (map :serifu/id m))))))))

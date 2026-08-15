(ns denwaban.consent
  "The consent a telephone caller can actually give, and the honesty about what
  it is not.

  yotei's G8 wants a member DID-signed consent before a 予約 holds a slot. A
  person telephoning a restaurant has no DID, no passkey and no wallet. The
  choice is between refusing every telephone 予約 and admitting a weaker thing —
  and admitting a weaker thing is only safe if it can never afterwards be read
  as the stronger one.

  So a telephone consent is **attested**, never signed: denwaban records that
  the question was asked and answered, stamps `consentKind`, and that stamp
  travels with the 予約 for the rest of its life. Nobody downstream has to know
  how this file works to know they are not looking at a DID-signed consent.

  What is deliberately absent: the recording. G1 says a call is transient by
  default, and an attestation that carries the audio would turn every 予約 into
  a retained voice record of a member of the public. The attestation carries
  that consent was given, when, and to what text — not the voice that gave it."
  (:require [clojure.string :as str]))

(def kind "telephone-attested")

(def statement
  "The sentence that must actually be said out loud before contact details are
  taken. It is a constant so that the thing attested to and the thing asked are
  the same thing; a caller-specific paraphrase would make the attestation refer
  to a sentence nobody kept."
  "ご予約のため、お名前とお電話番号をお預かりします。よろしいでしょうか。")

(defn attest
  "Mint the consent reference for one call, or refuse.

  Refuses unless consent was **granted** — literal `true`, so a nil 'we never
  asked' cannot arrive here as a yes. Refuses without a call reference, because
  an attestation nobody can trace back to a call is an assertion, not evidence."
  [{:keys [call-ref granted? spoken-at-epoch-min]}]
  (cond
    (not (true? granted?))
    {:denwaban/refused :consent-not-granted}

    (str/blank? (str call-ref))
    {:denwaban/refused :no-call-reference}

    (not (integer? spoken-at-epoch-min))
    {:denwaban/refused :no-time-of-consent}

    :else
    {:denwaban/consent-ref (str kind ":" call-ref ":" spoken-at-epoch-min)
     :denwaban/consent-kind kind
     :denwaban/statement statement}))

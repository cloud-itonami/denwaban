(ns denwaban.test-state-machine
  "The social_post membrane's REFUSAL paths.

  `test_social` covers the happy path -- a record with two citations drafts a dry-run
  post -- and that was all this state machine had. The four invariants that decide
  whether the actor publishes at all had no test, and they are the ones that matter:
  each of them is the only thing standing between a governed observation and the
  actor putting something on the mesh under its own key.

  Every expectation here was measured from the function before it was written down,
  not assumed from reading it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denwaban.cells.social-post.state-machine :as sm]))

(defn- drive [state] (get (sm/transition-to-drafted state) "cell_state"))

(def ^:private two-sources ["session-log" "booking-record"])

(defn- refused? [cs] (= sm/phase-refused (get cs "phase")))
(defn- drafted? [cs] (= sm/phase-drafted (get cs "phase")))

;; ---------------------------------------------------------------------------
;; source-provenance
;; ---------------------------------------------------------------------------

(deftest an-empty-record-refuses-rather-than-drafting-an-uncited-post
  (testing "absent citations are not permission -- a record with no sources at all
            must not become a post"
    (let [cs (drive {})]
      (is (refused? cs))
      (is (str/starts-with? (get cs "refusal") "source-provenance"))
      (is (= {} (get cs "payload")) "nothing was drafted"))))

(deftest one-citation-is-not-enough-and-two-is-the-boundary
  (testing "the invariant is >= 2, so 1 refuses and 2 drafts -- the boundary is
            asserted on both sides, because an off-by-one here publishes a
            single-sourced claim"
    (is (refused? (drive {"subject" "s" "sources" ["only-one"]})))
    (is (drafted? (drive {"subject" "s" "sources" two-sources})))))

;; ---------------------------------------------------------------------------
;; no-server-key
;; ---------------------------------------------------------------------------

(deftest a-server-held-key-refuses-even-with-good-citations
  (testing "the whole point of this membrane is that the actor self-signs in its own
            mesh runtime; a server-held key means someone else can post as it"
    (let [cs (drive {"subject" "s" "sources" two-sources "server_held_key" true})]
      (is (refused? cs))
      (is (str/starts-with? (get cs "refusal") "no-server-key")))))

(deftest server-held-key-is-coerced-to-a-boolean-not-tested-for-truthiness
  (testing "a wire value of \"false\" (a string) is truthy in Clojure, and reading it
            as a permission would be the wrong direction to fail"
    (is (refused? (drive {"subject" "s" "sources" two-sources
                          "server_held_key" "false"}))
        "a non-empty string counts as a server-held key, so it refuses")
    (is (drafted? (drive {"subject" "s" "sources" two-sources
                          "server_held_key" false})))
    (is (drafted? (drive {"subject" "s" "sources" two-sources
                          "server_held_key" nil}))
        "an explicit nil is absence, and absence of a server key is the safe case")))

;; ---------------------------------------------------------------------------
;; R0-gate
;; ---------------------------------------------------------------------------

(deftest a-request-to-publish-live-is-refused-not-downgraded
  (testing "silently answering `dry-run` to a `published` request would let a caller
            believe the post went out"
    (let [cs (drive {"subject" "s" "sources" two-sources
                     "requested_status" "published"})]
      (is (refused? cs))
      (is (str/starts-with? (get cs "refusal") "R0-gate"))
      (is (= {} (get cs "payload"))))))

(deftest a-keyword-shaped-status-is-normalised-rather-than-refused
  (testing "a caller sending :dry-run (EDN keyword print form) means dry-run; the
            leading colons are stripped, so this drafts"
    (is (drafted? (drive {"subject" "s" "sources" two-sources
                          "requested_status" ":dry-run"})))
    (is (drafted? (drive {"subject" "s" "sources" two-sources
                          "requested_status" "::dry-run"})))
    (testing "and normalisation does not turn a live request into a dry run"
      (is (refused? (drive {"subject" "s" "sources" two-sources
                            "requested_status" ":published"}))))))

;; ---------------------------------------------------------------------------
;; order, and what a drafted post actually contains
;; ---------------------------------------------------------------------------

(deftest the-first-failed-invariant-is-the-one-reported
  (testing "a record failing all three reports source-provenance, because that is the
            order the cond checks them -- pinned so a reordering that changes which
            reason a human sees is visible in a diff"
    (let [cs (drive {"sources" [] "server_held_key" true
                     "requested_status" "published"})]
      (is (refused? cs))
      (is (str/starts-with? (get cs "refusal") "source-provenance")))))

(deftest a-drafted-post-is-a-non-adjudicating-mirror
  (let [cs (drive {"subject" "着信応対の観測" "sources" two-sources})
        payload (get cs "payload")]
    (is (drafted? cs))
    (is (= "" (get cs "refusal")) "a drafted record carries no refusal text")
    (testing "the body OPENS with the disclaimer, so the non-adjudicating notice
              cannot be lost by truncation"
      (is (str/starts-with? (get payload ":post/body") sm/disclaimer)))
    (is (str/includes? (get payload ":post/body") "着信応対の観測"))
    (is (true? (get payload ":post/is-mirror")))
    (is (true? (get payload ":post/non-adjudicating-notice")))
    (is (false? (get payload ":post/server-held-key")))
    (is (= ":dry-run" (get payload ":post/status"))
        "the status in the payload is the dry-run one regardless of what was asked")
    (testing "and the citations survive verbatim -- a mirror that renamed its sources
              would not be checkable"
      (is (= two-sources (get payload ":post/sources"))))))

(deftest a-nested-cell-state-supplies-the-record-when-top-level-keys-are-absent
  (testing "the cell contract passes state under \"cell_state\", so a record that
            arrives only in that shape must still be read"
    (let [cs (drive {"cell_state" {"subject" "nested" "sources" two-sources}})]
      (is (drafted? cs))
      (is (= "nested" (get cs "subject"))))))

(deftest a-top-level-key-wins-over-the-nested-one
  (let [cs (drive {"subject" "outer"
                   "cell_state" {"subject" "inner" "sources" two-sources}
                   "sources" two-sources})]
    (is (= "outer" (get cs "subject")))))

(deftest the-three-phases-are-distinct
  (testing "init / drafted / refused must not collide, or a refused record could read
            as a drafted one"
    (is (= 3 (count (set [sm/phase-init sm/phase-drafted sm/phase-refused]))))
    (is (= sm/phase-init (get sm/state-defaults "phase"))
        "a record starts at init, not at drafted")))

(deftest the-defaults-are-the-safe-ones
  (testing "every default is the conservative value: no sources, no server key, and
            dry-run -- so a record that fills in nothing cannot publish"
    (is (= [] (get sm/state-defaults "sources")))
    (is (false? (get sm/state-defaults "server_held_key")))
    (is (= "dry-run" (get sm/state-defaults "requested_status")))))

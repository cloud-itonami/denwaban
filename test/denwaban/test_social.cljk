(ns denwaban.test-social
  (:require [clojure.test :refer [deftest is]]
            [denwaban.cells.social-post.state-machine :as state-machine]
            [denwaban.social :as social]))

(deftest shared-publication-adapter
  (let [post (social/draft-observation-post "call" "observed" ["session" "booking"])
        state (state-machine/transition-to-drafted
               {"subject" "call" "sources" ["session" "booking"]})]
    (is (= ":dry-run" (get post ":post/status")))
    (is (false? (get post ":post/server-held-key")))
    (is (= state-machine/phase-drafted (get-in state ["cell_state" "phase"])))))

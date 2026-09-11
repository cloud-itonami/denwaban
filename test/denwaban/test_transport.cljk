(ns denwaban.test-transport
  (:require [clojure.test :refer [deftest is testing]]
            [denwaban.transport :as transport]))

(def full-voice
  transport/required-telephony-capabilities)

(deftest telephone-and-access-layers-are-not-interchangeable
  (testing "Starlink/eSIM can carry media but cannot become a PSTN provider"
    (let [result (transport/plan
                  {:telephony-providers
                   [{:id :starlink :kind :ip-access :health :ready
                     :admission :approved :priority 0
                     :capabilities #{:ip-egress}}]
                   :access-paths
                   [{:id :cellular-esim :kind :ip-access :health :ready
                     :admission :approved :priority 0
                     :capabilities #{:ip-egress}}]})]
      (is (= :held (:status result)))
      (is (= [:no-admitted-telephony-provider] (:reasons result))))))

(deftest independently-selects-telephone-and-access
  (let [result (transport/plan
                {:telephony-providers
                 [{:id :primary :kind :telephony :health :degraded
                   :admission :approved :priority 0 :capabilities full-voice}
                  {:id :secondary :kind :telephony :health :ready
                   :admission :approved :priority 1 :capabilities full-voice
                   :media-format :pcm16-16khz}]
                 :access-paths
                 [{:id :terrestrial :kind :ip-access :health :down
                   :admission :approved :priority 0
                   :capabilities #{:ip-egress}}
                  {:id :starlink :kind :ip-access :health :ready
                   :admission :approved :priority 2
                   :capabilities #{:ip-egress}}]})]
    (is (= :ready (:status result)))
    (is (= :secondary (:telephony-provider result)))
    (is (= :starlink (:access-path result)))
    (is (= :new-call-only (:failover result)))))

(deftest refuses-unapproved-or-incomplete-provider
  (doseq [candidate [{:id :unapproved :kind :telephony :health :ready
                      :admission :candidate :capabilities full-voice}
                     {:id :one-way :kind :telephony :health :ready
                      :admission :approved
                      :capabilities (disj full-voice :bidirectional-media)}]]
    (is (= :held
           (:status (transport/plan
                     {:telephony-providers [candidate]
                      :access-paths
                      [{:id :ethernet :kind :ip-access :health :ready
                        :admission :approved
                        :capabilities #{:ip-egress}}]}))))))

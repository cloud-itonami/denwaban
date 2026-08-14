(ns denwaban.test-session
  (:require [clojure.test :refer [deftest is testing]]
            [denwaban.session :as s]))

(deftest pipeline-composes-existing-actors
  (testing "the five stages bind ports without hard-coding a PSTN vendor"
    (let [plan (s/plan-session {})
          by-stage (into {} (map (juxt :stage :actor) (:stages plan)))]
      (is (= "telephony-provider" (:ingress by-stage)))
      (is (= "com-whisper"    (:listen   by-stage)))
      (is (= "com-elevenlabs" (:speak    by-stage)))
      (is (= "yotei"             (:book     by-stage)))
      (is (= 'koe.session/converse (get-in plan [:kernel :session])))
      (is (= 'koe.ports/IBooking (get-in plan [:kernel :ports :book]))))))

(deftest admitted-provider-is-visible-without-becoming-the-kernel
  (let [plan (s/plan-session
              {:transport-plan {:status :ready
                                :telephony-provider :telnyx
                                :access-path :starlink}})]
    (is (= "telephony/telnyx" (-> plan :stages first :actor)))
    (is (= :starlink (get-in plan [:transport :access-path])))))

(deftest booking-is-delegated-to-yotei
  (testing "G2: denwaban never owns the booking — yotei is the source of truth"
    (let [plan (s/plan-session {})]
      (is (s/delegates-booking? plan))
      (is (= "yotei" (:booking-owner plan)))
      (is (not= "denwaban" (:booking-owner plan))))))

(deftest webrtc-only-swaps-ingress
  (testing "a WebRTC soft-phone swaps only the ingress transport (ADR-2606271800)"
    (let [plan (s/plan-session {:reach :webrtc})
          ingress (first (:stages plan))]
      (is (= "webrtc" (:actor ingress)))
      (is (= :webrtc (:reach plan))))))

(deftest recording-transient-by-default
  (testing "G1: no recording retention without explicit consent"
    (is (= :transient (:recording (s/plan-session {}))))))

(deftest run-session-is-r0-gated
  (testing "G7: live call raises at R0"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (s/run-session {})))))

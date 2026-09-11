(ns denwaban.test-http
  "Wire behaviour over real sockets.

  The property this file exists for: a refusal is not an outage. The app's authority
  spine tells `:authority-refused` apart from `:authority-pending`, and this actor
  must answer the first -- with the gate that refuses -- rather than the second, and
  rather than nothing at all."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [denwaban.http :as http])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 5)) (.build)))

(defn- send-it [builder]
  (let [res (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (try (json/read-str (.body res) :key-fn keyword) (catch Exception _ nil))}))

(defn- req [port path]
  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
      (.timeout (Duration/ofSeconds 10))))

(def consent "test-voice-consent-token")

(defn- post
  "POST with the consent token attached -- the surface requires it."
  [port path body]
  (send-it (.POST (-> (req port path)
                      (.header "Content-Type" "application/json")
                      (.header "X-VOICE-CONSENT-TOKEN" consent))
                  (HttpRequest$BodyPublishers/ofString (json/write-str body)))))

(defn- post-raw
  "POST with whatever header pair is given (or none), for the refusal paths."
  [port path body & [header value]]
  (let [b (-> (req port path) (.header "Content-Type" "application/json"))
        b (cond-> b value (.header header value))]
    (send-it (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))))

(defn- get! [port path]
  (send-it (.GET (.header (req port path) "X-VOICE-CONSENT-TOKEN" consent))))

(defn- get-open! [port path] (send-it (.GET (req port path))))

(defn- with-surface [f]
  (with-redefs [http/consent-token (constantly consent)]
   (let [running (http/start! {:port 0})
        port (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer
                                    (:consent running)))]
    (try (f port) (finally (http/stop! running))))))

(defn- proposal [op value]
  {:proposal {:id "p-1" :authority "voice" :op op :value value
              :digest "digest-1" :status "approved"
              :passkey-credential-id "cred-1"}})

(defn- rule [body] (some-> (get-in body [:refusal :rule]) keyword name))

;; ---------------------------------------------------------------------------

(deftest healthz-says-it-cannot-answer-calls
  (testing "a deployment should learn the ceiling from /healthz, before it sends
            anything -- not by inferring it from a refusal"
    (with-surface
      (fn [port]
        (let [{:keys [status body]} (get! port "/healthz")]
          (is (= 200 status))
          (is (= "ok" (:status body)))
          (is (= "cloud-itonami-denwaban" (:actor body)))
          (is (= "R0" (:maturity body)))
          (is (false? (:can-answer-calls body)))
          (is (= "G7" (:outward-gate body)))
          (is (= "yotei" (:booking-owner body))))))))

(deftest an-outward-op-is-refused-at-g7-not-left-pending
  (testing "pending would leave a human waiting on a decision nobody can make"
    (with-surface
      (fn [port]
        (let [{:keys [status body]} (post port "/commit"
                                          (proposal "call/answer-authority"
                                                    {:caller-number "+819012345678"}))]
          (is (= 200 status))
          (is (= "held" (:status body)))
          (is (not= "pending" (:status body)))
          (is (= "g7-outward-gate" (rule body)))
          (is (= "G7" (get-in body [:refusal :gate]))))))))

(deftest the-refusal-carries-the-plan-it-refused-to-run
  (testing "a refusal that shows what would have run is reviewable, and it comes
            from the same pure plan-session the contract test asserts"
    (with-surface
      (fn [port]
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "call/answer-authority"
                                             {:caller-number "+819012345678"}))
              stages (get-in body [:refusal :plan :stages])]
          (is (= 5 (count stages)))
          (is (= [":ingress" ":listen" ":converse" ":speak" ":book"]
                 (mapv :stage stages)))
          (is (= "yotei" (:actor (last stages))) "booking is delegated (G2)")
          (is (= "yotei" (get-in body [:refusal :booking-owner]))))))))

(deftest a-webrtc-reach-swaps-only-the-ingress-transport
  (with-surface
    (fn [port]
      (let [{:keys [body]} (post port "/commit"
                                 (proposal "call/answer-authority"
                                           {:caller-number "+819012345678"
                                            :reach "webrtc"}))
            stages (get-in body [:refusal :plan :stages])]
        (is (= "webrtc" (:actor (first stages))))
        (is (= "com-whisper" (:actor (second stages)))
            "the rest of the pipeline is unchanged (ADR-2606271800)")))))

(deftest g1-is-enforced-here-and-not-assumed-of-the-caller
  (testing "the app checks this too, but an actor that trusts its caller to have
            validated the request has no gate of its own"
    (with-surface
      (fn [port]
        (let [{:keys [body]} (post port "/commit"
                                   (proposal "call/answer-authority"
                                             {:caller-number "+819012345678"
                                              :retain-recording? true}))]
          (is (= "g1-recording-consent-missing" (rule body)))
          (is (= "G1" (get-in body [:refusal :gate]))))
        (testing "and with consent it reaches the G7 gate instead"
          (let [{:keys [body]} (post port "/commit"
                                     (proposal "call/answer-authority"
                                               {:caller-number "+819012345678"
                                                :retain-recording? true
                                                :caller-consented-to-recording? true}))]
            (is (= "g7-outward-gate" (rule body)))))))))

(deftest a-booking-delegate-without-a-slot-is-refused-before-the-gate
  (with-surface
    (fn [port]
      (let [{:keys [body]} (post port "/commit"
                                 (proposal "call/booking-delegate"
                                           {:caller-number "+819012345678"}))]
        (is (= "slot-missing" (rule body))))
      (let [{:keys [body]} (post port "/commit"
                                 (proposal "call/booking-delegate"
                                           {:caller-number "+819012345678"
                                            :slot "2026-08-01T10:00"}))]
        (is (= "g7-outward-gate" (rule body)))
        (is (= "yotei" (get-in body [:refusal :booking-owner])))))))

(deftest an-unknown-op-is-refused-not-coerced
  (with-surface
    (fn [port]
      (doseq [op ["call/dial-out" "" "answer-authority"]]
        (let [{:keys [body]} (post port "/commit" (proposal op {}))]
          (is (= "op-unsupported" (rule body)) (pr-str op)))))))

(deftest there-is-no-operator-surface-and-it-says-so
  (testing "a decide endpoint whose approval cannot produce the act it authorises
            would be a gate that holds nothing"
    (with-surface
      (fn [port]
        (doseq [path ["/proposals/p-1/decide" "/decide"]]
          (let [{:keys [status body]} (post port path {:status "approved" :by "op"})]
            (is (= 404 status) path)
            (is (= "no-operator-surface" (rule body)))))))))

(deftest a-reference-is-unknown-rather-than-pending
  (with-surface
    (fn [port]
      (let [{:keys [body]} (get! port "/proposals/p-1")]
        (is (= "unknown" (:status body)))
        (is (not= "pending" (:status body))
            "polling must not look like it will eventually resolve")))))

(deftest a-malformed-request-is-refused-with-a-reason
  (with-surface
    (fn [port]
      (let [{:keys [status body]} (post port "/commit" {:nope true})]
        (is (= 400 status))
        (is (= "malformed-request" (rule body)))))))

(deftest every-answer-is-held-or-unknown-and-never-pending
  (testing "the whole surface, enumerated: nothing here can end pending"
    (with-surface
      (fn [port]
        (doseq [[op value] [["call/answer-authority" {:caller-number "+819012345678"}]
                            ["call/booking-delegate" {:caller-number "+819012345678"
                                                      :slot "2026-08-01T10:00"}]
                            ["call/dial-out" {}]
                            ["call/answer-authority" {:retain-recording? true}]]]
          (let [{:keys [body]} (post port "/commit" (proposal op value))]
            (is (= "held" (:status body)) (str op " -> " (:status body)))))
        (is (= "unknown" (:status (:body (get! port "/proposals/anything")))))))))

;; ---------------------------------------------------------------------------
;; the consent surface's own token
;; ---------------------------------------------------------------------------

(deftest a-proposal-without-the-consent-token-is-refused
  (testing "the G7 refusal is not the only thing this surface produces -- it answers
            with the pipeline it declined to run, and an unauthenticated caller should
            not be able to probe that or to fill a log with proposals nobody made"
    (with-surface
      (fn [port]
        (let [{:keys [status body]} (post-raw port "/commit"
                                              (proposal "call/answer-authority" {}))]
          (is (= 401 status))
          (is (= "consent-token-mismatch" (rule body))))))))

(deftest a-wrong-consent-token-is-refused
  (with-surface
    (fn [port]
      (let [{:keys [status body]} (post-raw port "/commit"
                                            (proposal "call/answer-authority" {})
                                            "X-VOICE-CONSENT-TOKEN" "wrong")]
        (is (= 401 status))
        (is (= "consent-token-mismatch" (rule body)))))))

(deftest an-unset-consent-token-refuses-rather-than-waving-through
  (with-redefs [http/consent-token (constantly nil)]
    (let [running (http/start! {:port 0})
          port (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer
                                      (:consent running)))]
      (try
        (let [{:keys [status body]} (post-raw port "/commit"
                                              (proposal "call/answer-authority" {})
                                              "X-VOICE-CONSENT-TOKEN" "anything")]
          (is (= 503 status))
          (is (= "consent-surface-unconfigured" (rule body))))
        (finally (http/stop! running))))))

(deftest healthz-stays-open-because-it-is-how-you-learn-the-ceiling
  (testing "/healthz says can-answer-calls false; a deployment should be able to learn
            that before it has a token to ask with"
    (with-surface
      (fn [port]
        (let [{:keys [status body]} (get-open! port "/healthz")]
          (is (= 200 status))
          (is (false? (:can-answer-calls body))))))))

(deftest a-reference-read-needs-the-token
  (with-surface
    (fn [port]
      (is (= 401 (:status (get-open! port "/proposals/p-1"))))
      (is (= 200 (:status (get! port "/proposals/p-1")))))))

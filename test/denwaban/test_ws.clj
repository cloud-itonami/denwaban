(ns denwaban.test-ws
  "The socket, actually opened.

  Everything else in this actor is a decision and is tested as one. This is the
  file that opens ports, and the only way to know it is wired is to open them:
  the server runs on a real port, a real HTTP request arrives, and the answer is
  read back. Without this the routing, the signature plumbing and the response
  shape are three things nobody has ever run."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [denwaban.ws :as ws]
            [koe.carrier :as carrier]
            [koe.media :as media]
            [org.httpkit.client :as http]))

(def ^:private port 18179)
(def ^:private base (str "http://127.0.0.1:" port))
(def ^:private secret "test-token")
(def ^:private webhook-url (str base "/voice"))

(defn- ctx []
  {:provider :twilio
   :webhook-url webhook-url
   :stream-url "wss://denwaban.example/media"
   :carrier-secret (constantly secret)
   :calls (atom {})
   :dialog-state (constantly {})
   :turn-fn (fn [s _] {:state s :reply nil})})

(defn- body-str [b] (if (string? b) b (slurp b)))

(defn- with-server [f]
  (let [server (ws/start! (ctx) port)]
    (try (f) (finally (ws/stop! server)))))

(defn- signed-params []
  (let [params {"CallSid" "CA-1" "From" "+819012345678" "To" "+815012345678"}]
    [params (#'carrier/hmac-sha1-base64 secret (carrier/twilio-signing-string webhook-url params))]))

;; ── the webhook ──────────────────────────────────────────────────────────────

(deftest test-a-signed-call-is-answered-with-a-stream
  (with-server
    (fn []
      (let [[params sig] (signed-params)
            {:keys [status body]} @(http/post webhook-url
                                              {:form-params params
                                               :headers {"X-Twilio-Signature" sig}
                                               :timeout 5000})]
        (is (= 200 status))
        (is (str/includes? (body-str body) "<Connect>"))
        (is (str/includes? (body-str body) "wss://denwaban.example/media"))))))

(deftest test-an-unsigned-call-is-hung-up-on
  (with-server
    (fn []
      (let [[params _] (signed-params)
            {:keys [status body]} @(http/post webhook-url
                                              {:form-params params
                                               :headers {"X-Twilio-Signature" "wrong"}
                                               :timeout 5000})]
        (is (= 403 status))
        (is (str/includes? (body-str body) "<Hangup/>"))
        (testing "and says nothing else to a party it cannot identify"
          (is (not (str/includes? (body-str body) "<Say"))))))))

(deftest test-a-call-with-no-signature-at-all-is-refused
  (with-server
    (fn []
      (let [[params _] (signed-params)
            {:keys [status]} @(http/post webhook-url {:form-params params :timeout 5000})]
        (is (= 403 status))))))

(deftest test-form-params-are-parsed-here-not-by-middleware
  (testing "http-kit is a server, not a Ring stack; :form-params is nobody's job"
    (is (= {"CallSid" "CA-1" "From" "+81901234"}
           (ws/form-params {:body "CallSid=CA-1&From=%2B81901234"})))
    (is (= {} (ws/form-params {:body nil})))
    (is (= {} (ws/form-params {})))))

(deftest test-healthz-answers
  (with-server
    (fn []
      (let [{:keys [status body]} @(http/get (str base "/healthz") {:timeout 5000})]
        (is (= 200 status))
        (is (str/includes? (body-str body) "can-answer-calls"))))))

(deftest test-an-unknown-path-is-404
  (with-server
    (fn []
      (is (= 404 (:status @(http/get (str base "/nope") {:timeout 5000})))))))

;; ── the audio the engine is handed ───────────────────────────────────────────

(deftest test-an-utterance-is-written-as-a-ulaw-wav
  (let [frames [(vec (repeat 160 0xFF)) (vec (repeat 160 0x00))]
        path (str (System/getProperty "java.io.tmpdir") "/denwaban-test-" (System/nanoTime) ".wav")]
    (try
      (ws/write-utterance! frames path)
      (let [all (with-open [in (clojure.java.io/input-stream (clojure.java.io/file path))]
                  (.readAllBytes in))]
        (is (= (+ 46 320) (alength all)) "header plus both frames")
        (testing "and it is the header koe.media wrote, format 7 = μ-law"
          (is (= 7 (bit-and (long (aget all 20)) 0xFF)))))
      (finally (clojure.java.io/delete-file (clojure.java.io/file path) true)))))

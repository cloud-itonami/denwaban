(ns denwaban.http
  "The governed HTTP surface a consent surface hands voice proposals to.

  ONE LISTENER, DELIBERATELY -- and this is the substantive difference from
  cloud-itonami-esim and cloud-itonami-card-issuing, which each run two.

  Those actors run a consent listener and a separate operator listener, because a
  proposal there can end `pending` and an operator's decision turns it into a real
  act: a provisioned profile, an issued card. The second listener exists so the
  consent surface cannot approve its own proposals.

  denwaban has nothing to approve. `denwaban.session/run-session` raises at the G7
  outward gate: at R0 there is no bound telephony transport, no STT/TTS actor and no
  booking client, so no decision made here can answer a call. An operator listener
  would be a decide endpoint whose approval cannot produce the act it appears to
  authorise -- a gate that looks like a gate and holds nothing. So there is no
  operator port, and there is no `pending`: every outward op is refused with the gate
  that refuses it.

    consent (default :1343)  POST /commit                  -> held (G7), with the plan
                             GET  /proposals/<reference>    -> unknown, always
                             GET  /healthz

  WHY REFUSED AND NOT PENDING. The app's authority spine distinguishes
  `:authority-refused` from `:authority-pending`, and the difference decides what a
  human sees: pending means \"wait, someone is deciding\", refused means \"this will
  not happen\". Answering pending here would leave a caller waiting on a decision no
  one can make -- and before this surface existed the app got neither, only
  `:endpoint-not-configured`, which reads as \"we could not ask\" when the truth is
  \"the answer is no\". Being asked and answering honestly is the whole point.

  THE GATES ARE RE-CHECKED HERE. cloud-itonami-app's voice adapter already refuses a
  malformed caller number, a caller outside its allowlist, and recording retention
  without consent. This surface checks the ones it owns again anyway, because an
  actor that trusts its caller to have validated the request has no gate of its own:
  the next caller may be a different app, or the same app with a bug. G1 (recording
  is transient unless the caller consented) is denwaban's own invariant, so denwaban
  enforces it.

  When G7 opens (Council Lv6+ plus a named operator), the operator listener is what
  gets added, and `pending` becomes reachable. Until then the shape of this file is
  the honest one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [denwaban.session :as session])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private max-body-bytes 65536)

(def actor-id "cloud-itonami-denwaban")

(def ops
  "The ops this actor recognises, mirroring cloud.itonami.app.authority.voice/ops.
  An allowlist: anything else is refused rather than coerced into the nearest
  plausible op."
  #{:call/answer-authority :call/booking-delegate})

;; ---------------------------------------------------------------------------
;; wire helpers
;; ---------------------------------------------------------------------------

(defn- read-body [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)
              out (ByteArrayOutputStream.)]
    (let [buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toString out StandardCharsets/UTF_8)
            (> (+ total n) max-body-bytes)
            (throw (ex-info "request body too large" {:type :http/body-too-large}))
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- send! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/write-str payload) StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" "application/json; charset=utf-8")
      (.set "Cache-Control" "no-store"))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))
    (.close exchange)))

(defn- kw
  "Read a keyword the wire sent as a string, keeping its namespace. nil for anything
  unusable, so an unrecognised op meets the allowlist as nil and is refused there."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (seq v)) (keyword v)
    :else nil))

;; ---------------------------------------------------------------------------
;; the answer
;; ---------------------------------------------------------------------------

(defn- held [rule detail extra]
  (merge {:status "held" :refusal (merge {:rule rule :detail detail} extra)}))

(defn outcome
  "What became of one proposal. Total over the ops: every branch returns a `held`,
  because no branch can reach a telephone.

  The plan is included on the G7 refusal deliberately -- a refusal that also shows
  what would have run is reviewable, and it is the same pure `plan-session` the
  contract test asserts, not a second description that could drift from it."
  [{:keys [op value]}]
  (let [op (kw op)
        value (or value {})
        reach (or (kw (:reach value)) :pstn)]
    (cond
      (not (contains? ops op))
      (held :op-unsupported
            (str "未対応の op です: " (pr-str op))
            {:supported (mapv str (sort ops))})

      ;; G1 is denwaban's own invariant, so denwaban enforces it rather than
      ;; assuming the caller did. Refusing beats quietly downgrading the request to
      ;; transient recording: answering a different request than the one asked for
      ;; is how a consent stops meaning anything.
      (and (:retain-recording? value) (not (:caller-consented-to-recording? value)))
      (held :g1-recording-consent-missing
            "録音の保持には発信者の明示同意が必要です（G1）"
            {:gate "G1"})

      (and (= :call/booking-delegate op) (nil? (:slot value)))
      (held :slot-missing "予約枠 (slot) が必要です" {})

      :else
      (let [plan (session/plan-session {:reach reach})]
        (held :g7-outward-gate
              (str "denwaban R0: 実発着信は G7 gate（Council Lv6+ と operator）の"
                   "内側です。plan のみで、この提案は実行されません")
              {:gate "G7"
               :stage "ingress"
               ;; :booking-owner is carried up because it is the G2 invariant a
               ;; reader of this refusal most needs: even when G7 opens, the booking
               ;; is yotei's to confirm and never denwaban's.
               :booking-owner (:booking-owner plan)
               :recording (str (:recording plan))
               :plan {:reach (str (:reach plan))
                      :stages (mapv (fn [{:keys [stage port actor gate]}]
                                      {:stage (str stage) :port (str port)
                                       :actor actor :gate gate})
                                    (:stages plan))}})))))

(defn proposal-status
  "Always unknown, and says why: this actor never creates a pending proposal,
  because there is no decision here that could resolve one. A caller that polls is
  not waiting for something that will arrive."
  [reference]
  {:status "unknown"
   :reference reference
   :detail (str actor-id " は pending な提案を作りません（G7 gate のため決着させる"
                "operator が存在しない）。/commit は即座に held を返します")})

(defn health []
  (let [plan (session/plan-session {})]
    {:status "ok"
     :actor actor-id
     :maturity "R0"
     :outward-gate "G7"
     ;; Stated as a field rather than left to be inferred from a refusal: a
     ;; deployment reading /healthz should learn this before it sends anything.
     :can-answer-calls false
     :booking-owner (:booking-owner plan)
     :stages (mapv (comp str :stage) (:stages plan))}))

;; ---------------------------------------------------------------------------
;; listener
;; ---------------------------------------------------------------------------

(defn handler []
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod ^HttpExchange exchange)
              path (.getPath (.getRequestURI ^HttpExchange exchange))]
          (cond
            (and (= "GET" method) (= "/healthz" path))
            (send! exchange 200 (health))

            (and (= "GET" method) (re-matches #"/proposals/[^/]+" path))
            (send! exchange 200
                   (proposal-status (subs path (count "/proposals/"))))

            (and (= "POST" method) (= "/commit" path))
            (let [body (json/read-str (read-body exchange) :key-fn keyword)
                  proposal (:proposal body)]
              (if-not (map? proposal)
                (send! exchange 400
                       (held :malformed-request "proposal がありません" {}))
                (send! exchange 200 (outcome proposal))))

            ;; Named explicitly rather than falling into the generic 404: a caller
            ;; looking for decide should learn that no operator surface exists and
            ;; why, not merely that a path is missing.
            (str/includes? path "/decide")
            (send! exchange 404
                   (held :no-operator-surface
                         (str "この actor に operator 面はありません。G7 gate の内側では"
                              "承認が実行に変わらないため、decide を置くこと自体が"
                              "gate の見せかけになります")
                         {:gate "G7"}))

            :else
            (send! exchange 404 (held :not-found path {}))))
        (catch Exception e
          (send! exchange 500 (held :actor-error (str (.getMessage e)) {})))))))

(defn start!
  "Start the consent surface. Returns {:consent <HttpServer>}.

  Loopback only: this surface has no transport security of its own."
  ([] (start! {}))
  ([{:keys [port] :or {port 1343}}]
   (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
     (.createContext server "/" (handler))
     (.setExecutor server nil)
     (.start server)
     {:consent server})))

(defn stop! [{:keys [consent]}]
  (when consent (.stop ^HttpServer consent 0)))

(defn -main [& args]
  (let [port (if-let [p (first args)] (parse-long p) 1343)
        running (start! {:port port})]
    (println "cloud-itonami-denwaban (R0)")
    (println (str "  consent http://127.0.0.1:" port))
    (println "    POST /commit                 -- a consented voice proposal")
    (println "    GET  /proposals/<reference>  -- always unknown; see docstring")
    (println "    GET  /healthz")
    (println)
    (println "There is no operator surface, and no proposal ends pending: every")
    (println "outward op is refused at the G7 gate. An approval here could not")
    (println "answer a call, so offering a decide endpoint would be theatre.")
    (.join (Thread/currentThread))
    running))

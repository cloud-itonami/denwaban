(ns denwaban.ws
  "The socket. Everything a live call needs that is not a decision.

  `koe.bridge` decides what happens; this opens the ports, moves the bytes and
  performs the two slow things the bridge hands back — transcribe this utterance,
  say this line. It is deliberately the least interesting file in the actor, and
  it is short because everything worth arguing about was moved out of it.

  ## Two surfaces

    POST /voice   the carrier's inbound-call webhook. Verified by
                  `koe.carrier/admit`; an unverified request gets `<Hangup/>`
                  and nothing else. The answer connects the call to:
    WS   /media   the bidirectional media stream, one bridge per connection.

  ## What it does between bridge calls

  Transcription: writes the utterance as a μ-law WAV (`koe.media/wav-header`)
  and posts it to a **local** speech engine — `whisper-server` on the same
  machine, so a stranger's voice never leaves the host that transcribes it (G4).

  Speech: `denwaban.serifu` first. The receptionist's questions are already
  rendered, so the common path is a file read rather than a synthesizer. Only a
  line outside that set is synthesized, and it is synthesized to the carrier's
  own format so nothing is converted.

  ## Failure is loud

  A transcription that failed is not passed on as an utterance — `koe.bridge`
  refuses a blank transcript, and `whisper.engine` refuses to call a crash
  silence. What this file adds is that the reason reaches the log instead of
  being swallowed by a `catch` that returns nil."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [denwaban.serifu :as serifu]
            [denwaban.session :as session]
            [denwaban.uketsuke :as uketsuke]
            [koe.bridge :as bridge]
            [koe.carrier :as carrier]
            [koe.media :as media]
            [org.httpkit.client :as http]
            [org.httpkit.server :as hk])
  (:import [java.util Base64]))

(def ^:private b64-decoder (Base64/getDecoder))
(def ^:private b64-encoder (Base64/getEncoder))

(defn- decode-audio [^String payload]
  (vec (map #(bit-and (long %) 0xFF) (.decode b64-decoder payload))))

(defn- encode-audio [bytes]
  (.encodeToString b64-encoder (byte-array (map unchecked-byte bytes))))

;; ── the two slow things ──────────────────────────────────────────────────────

(defn write-utterance!
  "Write collected μ-law frames as a WAV the speech engine can read.

  The header is `koe.media`'s, verified against a real decoder rather than
  against itself: a rebuilt file of exactly this shape reads as
  `1 ch, 8000 Hz, ulaw` and transcribes correctly (2026-08-15)."
  [frames path]
  (let [payload (byte-array (map unchecked-byte (apply concat frames)))
        header (byte-array (map unchecked-byte (media/wav-header (alength payload))))]
    (with-open [o (io/output-stream (io/file path))]
      (.write o header)
      (.write o payload))
    path))

(defn transcribe!
  "Post one utterance to the local speech engine. Returns the text, or nil.

  nil rather than an empty string: `koe.bridge` treats a blank transcript as
  nothing said and keeps listening, and an empty string that actually means 'the
  engine is down' would look to it exactly like a caller who stayed quiet. The
  reason is logged here because this is the only place that has it."
  [{:keys [engine-url]} frames]
  (let [path (str (System/getProperty "java.io.tmpdir") "/denwaban-" (System/nanoTime) ".wav")]
    (try
      (write-utterance! frames path)
      (let [{:keys [status body error]}
            @(http/post engine-url
                        {:multipart [{:name "file" :content (io/file path)
                                      :filename "utterance.wav"}
                                     {:name "language" :content "ja"}
                                     {:name "response_format" :content "text"}]
                         :timeout 15000})]
        (cond
          error (do (println "denwaban.ws: speech engine unreachable:" (str error)) nil)
          (not= 200 status) (do (println "denwaban.ws: speech engine returned" status) nil)
          :else (let [t (str/trim (str body))]
                  (when (seq t) t))))
      (catch Exception e
        (println "denwaban.ws: transcription failed:" (.getMessage e))
        nil)
      (finally (io/delete-file (io/file path) true)))))

(defn say!
  "Audio for a line: a pre-rendered file when the line is one the receptionist
  always says, a synthesizer otherwise. Returns μ-law byte frames, or nil."
  [{:keys [serifu-dir voice locale] :or {locale :ja}} text]
  (let [plan (serifu/plan locale text)
        path (if-let [id (:serifu/pre-rendered plan)]
               (str serifu-dir "/" id ".wav")
               (let [tmp (str (System/getProperty "java.io.tmpdir")
                              "/denwaban-say-" (System/nanoTime) ".wav")
                     {:keys [exit err]} (apply shell/sh (serifu/render-command
                                                         {:voice voice :locale locale
                                                          :out tmp :text text}))]
                 (when-not (zero? exit)
                   (println "denwaban.ws: synthesis failed:" err))
                 (when (zero? exit) tmp)))]
    (when (and path (.exists (io/file path)))
      (let [all (with-open [in (io/input-stream (io/file path))] (.readAllBytes in))
            ;; `say` pads its header, so find the data chunk rather than assuming
            ;; a size -- assuming 44 or 58 puts header bytes into the audio.
            start (loop [i 0]
                    (cond (>= i (- (alength all) 4)) 0
                          (and (= (aget all i) (byte 100)) (= (aget all (inc i)) (byte 97))
                               (= (aget all (+ i 2)) (byte 116)) (= (aget all (+ i 3)) (byte 97)))
                          (+ i 8)
                          :else (recur (inc i))))]
        (->> (range start (alength all) media/samples-per-frame)
             (map (fn [i] (let [end (min (+ i media/samples-per-frame) (alength all))]
                            (mapv #(bit-and (long (aget all %)) 0xFF) (range i end)))))
             (remove empty?)
             vec)))))

;; ── the loop ─────────────────────────────────────────────────────────────────

(defn- send! [ch messages]
  (doseq [m messages] (hk/send! ch (json/write-str m))))

(defn- speak! [ctx ch bridge text mark]
  (if-let [frames (say! ctx text)]
    (do (send! ch (bridge/audio-frames bridge (map encode-audio frames) mark))
        true)
    (do (println "denwaban.ws: nothing to say for" (pr-str text)) false)))

(defn media-handler
  "One WebSocket connection = one call."
  [ctx]
  (fn [request]
    (hk/as-channel
     request
     {:on-open
      (fn [ch]
        (let [b (bridge/start {:dialog-state ((:dialog-state ctx))
                               :greeting (:greeting ctx)})]
          (hk/on-close ch (fn [_] nil))
          (swap! (:calls ctx) assoc ch (atom b))))
      :on-receive
      (fn [ch msg]
        (when-let [state (get @(:calls ctx) ch)]
          (let [decoded (json/read-str msg)
                r (bridge/on-frame @state decoded decode-audio)]
            (reset! state (:koe/bridge r))
            (send! ch (:koe/out r))
            ;; A greeting is said once, as soon as the stream exists.
            (when-let [g (bridge/greeting-due @state)]
              (when (speak! ctx ch @state g "greeting")
                (swap! state bridge/greeting-sent)))
            (when-let [frames (:koe/transcribe r)]
              (let [text (transcribe! ctx frames)
                    tr (bridge/on-transcript @state text (:turn-fn ctx))]
                (reset! state (:koe/bridge tr))
                (when-let [line (:koe/say tr)]
                  (speak! ctx ch @state line (str "turn-" (:koe/turns @state))))
                (when (bridge/ended? @state)
                  (hk/close ch)))))))
      :on-close (fn [ch _] (swap! (:calls ctx) dissoc ch))})))

(defn form-params
  "Parse an `application/x-www-form-urlencoded` body.

  Written here rather than assumed: http-kit is a server, not a Ring stack, and
  `:form-params` is middleware nobody installed. The first version of this
  handler read that key, got nil, verified a signature over ZERO parameters and
  refused every legitimate call — which looked exactly like a wrong secret. The
  socket test caught it; nothing else could have."
  [request]
  (let [body (:body request)
        raw (cond (nil? body) "" (string? body) body :else (slurp body))]
    (into {}
          (for [pair (str/split raw #"&") :when (seq pair)]
            (let [[k v] (str/split pair #"=" 2)]
              [(java.net.URLDecoder/decode (str k) "UTF-8")
               (java.net.URLDecoder/decode (str (or v "")) "UTF-8")])))))

(defn voice-webhook
  "The carrier's inbound-call webhook."
  [ctx]
  (fn [request]
    (let [params (form-params request)
          admission (carrier/admit {:provider (:provider ctx)
                                    :url (:webhook-url ctx)
                                    :params params
                                    :signature (get-in request [:headers "x-twilio-signature"])
                                    :secret ((:carrier-secret ctx))})]
      (if-not (:admitted admission)
        (do (println "denwaban.ws: refused inbound call:" (:reason admission))
            {:status 403 :headers {"content-type" "text/xml"} :body carrier/refusal-document})
        {:status 200 :headers {"content-type" "text/xml"}
         :body (carrier/answer-document {:stream-url (:stream-url ctx)})}))))

(defn handler [ctx]
  (fn [request]
    (case (:uri request)
      "/media" ((media-handler ctx) request)
      "/voice" ((voice-webhook ctx) request)
      "/healthz" {:status 200 :headers {"content-type" "application/json"}
                  :body (json/write-str {:can-answer-calls true})}
      {:status 404 :body "not found"})))

(defn default-ctx
  "The wiring. `turn-fn` is denwaban's own — `koe.bridge` never learns what a
  refusal means."
  [{:keys [ports authorization] :as opts}]
  (merge {:provider :twilio
          :engine-url "http://127.0.0.1:8178/inference"
          :serifu-dir "resources/serifu"
          :locale :ja
          :calls (atom {})
          :dialog-state (fn [] (uketsuke/initial-state
                                {:call-ref (str (System/nanoTime))
                                 :now-epoch-min (quot (System/currentTimeMillis) 60000)}))
          :turn-fn (fn [dialog-state utterance]
                     (let [turn (session/run-turn ports dialog-state utterance
                                                  {:signature (:signer opts)})]
                       {:state (:state turn) :reply (:reply turn)
                        :ended? (= :confirmed (:denwaban/outcome (:state turn)))}))
          :carrier-secret (fn [] (System/getenv "DENWABAN_CARRIER_SECRET"))}
         (dissoc opts :ports :authorization)))

(defn start!
  "Open the sockets. Returns the server; pass it to `stop!`.

  G7 is NOT checked here: this serves a channel the carrier opens, and the
  decision that this service may answer the public is
  `denwaban.session/run-session`'s. Wire that in front of it."
  [ctx port]
  (hk/run-server (handler ctx) {:port port :legacy-return-value? false}))

(defn stop!
  "Close the sockets and wait for in-flight calls to finish."
  [server]
  (hk/server-stop! server {:timeout 100}))

(defn -main [& [port]]
  (let [p (if port (Integer/parseInt port) 1344)]
    (start! (default-ctx {}) p)
    (println "denwaban.ws listening on" p "— POST /voice, WS /media")
    @(promise)))

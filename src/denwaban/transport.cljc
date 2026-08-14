(ns denwaban.transport
  "Provider-neutral admission for the telephone and IP-access layers.

  A PSTN/voice provider owns a number, call control and bidirectional media. An
  access provider only carries IP packets. Starlink, an eSIM, Wi-Fi and Ethernet
  therefore cannot accidentally be selected as the thing that answers a phone
  call. Selection is pure and fail-closed; live health probes and credentials are
  injected by the host and never guessed here.")

(def required-telephony-capabilities
  #{:inbound-pstn :bidirectional-media :call-events :webhook-auth :hangup})

(def required-access-capabilities
  #{:ip-egress})

(defn- eligible?
  [kind required candidate]
  (and (= kind (:kind candidate))
       (= :ready (:health candidate))
       (= :approved (:admission candidate))
       (every? (set (:capabilities candidate)) required)))

(defn- choose
  [kind required candidates]
  (->> candidates
       (filter #(eligible? kind required %))
       (sort-by (juxt #(long (or (:priority %) 9007199254740991))
                      (comp str :id)))
       first))

(defn plan
  "Choose an admitted telephony provider and an independent IP access path.

  `:priority` is lower-is-preferred. Health must be observed by the caller; an
  absent or stale observation must be represented as something other than
  `:ready`. Provider failover applies to a new call only: an established PSTN
  call is never claimed to migrate invisibly between carriers."
  [{:keys [telephony-providers access-paths]}]
  (let [telephony (choose :telephony required-telephony-capabilities
                          telephony-providers)
        access (choose :ip-access required-access-capabilities access-paths)
        reasons (cond-> []
                  (nil? telephony) (conj :no-admitted-telephony-provider)
                  (nil? access) (conj :no-admitted-ip-access))]
    (if (seq reasons)
      {:status :held
       :reasons reasons
       :failover :new-call-only}
      {:status :ready
       :telephony-provider (:id telephony)
       :access-path (:id access)
       :media-format (:media-format telephony)
       :failover :new-call-only})))

(defn ingress-actor
  "The provider binding shown in a session plan; never returns an access path."
  [transport-plan]
  (if (= :ready (:status transport-plan))
    (str "telephony/" (name (:telephony-provider transport-plan)))
    "telephony-provider"))

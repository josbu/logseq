(ns frontend.db.rtc.debug-ui
  "Debug UI for rtc module"
  (:require [fipp.edn :as fipp]
            [frontend.common.missionary :as c.m]
            [frontend.db :as db]
            [frontend.handler.db-based.rtc-flows :as rtc-flows]
            [frontend.handler.user :as user]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.shui.ui :as shui]
            [missionary.core :as m]
            [promesa.core :as p]
            [rum.core :as rum]))

(defonce debug-state (:rtc/state @state/state))

(defn- stop
  []
  (p/do!
   (state/<invoke-db-worker :thread-api/rtc-stop)
   (reset! debug-state nil)))

(rum/defcs ^:large-vars/cleanup-todo rtc-debug-ui < rum/reactive
  (rum/local nil ::logs)
  (rum/local nil ::sub-log-canceler)
  (rum/local nil ::keys-state)
  {:will-mount (fn [state]
                 (let [canceler
                       (c.m/run-task ::sub-logs
                         (m/reduce
                          (fn [logs log]
                            (let [logs* (if log
                                          (take 10 (conj logs log))
                                          logs)]
                              (reset! (get state ::logs) logs*)
                              logs*))
                          nil rtc-flows/rtc-log-flow))]
                   (reset! (get state ::sub-log-canceler) canceler)
                   state))
   :will-unmount (fn [state]
                   (when-let [canceler (some-> (get state ::sub-log-canceler) deref)]
                     (canceler))
                   state)}
  [state]
  (let [debug-state* (rum/react debug-state)
        rtc-logs @(get state ::logs)
        rtc-state (:rtc-state debug-state*)
        rtc-lock (:rtc-lock debug-state*)]
    [:div
     {:on-click (fn [^js e]
                  (when-let [^js btn (.closest (.-target e) ".ui__button")]
                    (.setAttribute btn "disabled" "true")
                    (js/setTimeout #(.removeAttribute btn "disabled") 2000)))}
     [:div.flex.gap-2.flex-wrap.items-center.pb-3
      (shui/button
       {:size :sm
        :on-click (fn [_]
                    (p/let [new-state (state/<invoke-db-worker :thread-api/rtc-get-debug-state)]
                      (swap! debug-state (fn [old] (merge old new-state)))))}
       (shui/tabler-icon "refresh") "state")

      (shui/button
       {:size :sm
        :on-click
        (fn [_]
          (let [token (state/get-auth-id-token)]
            (p/let [graph-list (state/<invoke-db-worker :thread-api/rtc-get-graphs token)]
              (swap! debug-state assoc
                     :remote-graphs
                     (map
                      #(into {}
                             (filter second
                                     (select-keys % [:graph-uuid
                                                     :graph-schema-version
                                                     :graph-name
                                                     :graph-status
                                                     :graph<->user-user-type
                                                     :graph<->user-grant-by-user])))
                      graph-list)))))}
       (shui/tabler-icon "download") "graph-list")
      (shui/button
       {:size :sm
        :on-click #(c.m/run-task :upload-test-avatar
                     (user/new-task--upload-user-avatar "TEST_AVATAR"))}
       (shui/tabler-icon "upload") "upload-test-avatar")]

     [:div.pb-4
      [:pre.select-text
       (-> {:user-uuid (user/user-uuid)
            :graph (:graph-uuid debug-state*)
            :rtc-state rtc-state
            :rtc-logs rtc-logs
            :local-tx (:local-tx debug-state*)
            :pending-block-update-count (:unpushed-block-update-count debug-state*)
            :remote-graphs (:remote-graphs debug-state*)
            :online-users (:online-users debug-state*)
            :auto-push? (:auto-push? debug-state*)
            :remote-profile? (:remote-profile? debug-state*)
            :current-page (state/get-current-page)
            :blocks-count (when-let [page (state/get-current-page)]
                            (count (:block/_page (db/get-page page))))
            :schema-version {:app (db-schema/schema-version->string db-schema/version)
                             :local-graph (:local-graph-schema-version debug-state*)
                             :remote-graph (str (:remote-graph-schema-version debug-state*))}}
           (fipp/pprint {:width 20})
           with-out-str)]]

     (if (nil? rtc-lock)
       (shui/button
        {:variant :outline
         :class "text-green-rx-09 border-green-rx-10 hover:text-green-rx-10"
         :on-click (fn [] (state/<invoke-db-worker :thread-api/rtc-start false))}
        (shui/tabler-icon "player-play") "start")

       [:div.my-2.flex
        [:div.mr-2 (ui/button (str "Toggle auto push updates("
                                   (if (:auto-push? debug-state*)
                                     "ON" "OFF")
                                   ")")
                              {:on-click
                               (fn []
                                 (state/<invoke-db-worker :thread-api/rtc-toggle-auto-push))})]
        [:div.mr-2 (ui/button (str "Toggle remote profile("
                                   (if (:remote-profile? debug-state*)
                                     "ON" "OFF")
                                   ")")
                              {:on-click
                               (fn []
                                 (state/<invoke-db-worker :thread-api/rtc-toggle-remote-profile))})]
        [:div (shui/button
               {:variant :outline
                :class "text-red-rx-09 border-red-rx-08 hover:text-red-rx-10"
                :size :sm
                :on-click (fn [] (stop))}
               (shui/tabler-icon "player-stop") "stop")]])

     (when (some? debug-state*)
       [:hr]
       [:div.flex.flex-row.items-center.gap-2
        (ui/button "grant graph access to"
                   {:icon "award"
                    :on-click (fn []
                                (let [token (state/get-auth-id-token)
                                      user-uuid (some-> (:grant-access-to-user debug-state*) parse-uuid)
                                      user-email (when-not user-uuid (:grant-access-to-user debug-state*))]
                                  (when-let [graph-uuid (:graph-uuid debug-state*)]
                                    (state/<invoke-db-worker :thread-api/rtc-grant-graph-access
                                                             token graph-uuid
                                                             (some-> user-uuid vector)
                                                             (some-> user-email vector)))))})

        [:b "➡️"]
        [:input.form-input.my-2.py-1
         {:on-change (fn [e] (swap! debug-state assoc :grant-access-to-user (util/evalue e)))
          :on-focus (fn [e] (let [v (.-value (.-target e))]
                              (when (= v "input email or user-uuid here")
                                (set! (.-value (.-target e)) ""))))
          :placeholder "input email or user-uuid here"}]])

     [:hr.my-2]

     [:div.flex.flex-row.items-center.gap-2
      (ui/button (str "download graph to")
                 {:icon "download"
                  :class "mr-2"
                  :on-click (fn []
                              (when-let [graph-name (:download-graph-to-repo debug-state*)]
                                (when-let [{:keys [graph-uuid graph-schema-version]}
                                           (:graph-uuid-to-download debug-state*)]
                                  (prn :download-graph graph-uuid graph-schema-version :to graph-name)
                                  (p/let [token (state/get-auth-id-token)
                                          download-info-uuid (state/<invoke-db-worker
                                                              :thread-api/rtc-request-download-graph
                                                              token graph-uuid graph-schema-version)
                                          {:keys [_download-info-uuid
                                                  download-info-s3-url
                                                  _download-info-tx-instant
                                                  _download-info-t
                                                  _download-info-created-at]
                                           :as result}
                                          (state/<invoke-db-worker :thread-api/rtc-wait-download-graph-info-ready
                                                                   token download-info-uuid graph-uuid graph-schema-version 60000)]
                                    (when (not= result :timeout)
                                      (assert (some? download-info-s3-url) result)
                                      (state/<invoke-db-worker :thread-api/rtc-download-graph-from-s3
                                                               graph-uuid graph-name download-info-s3-url))))))})

      [:b "➡"]
      [:div.flex.flex-row.items-center.gap-2
       (shui/select
        {:on-value-change (fn [[graph-uuid graph-schema-version]]
                            (when (and (parse-uuid graph-uuid) graph-schema-version)
                              (swap! debug-state assoc
                                     :graph-uuid-to-download
                                     {:graph-uuid graph-uuid
                                      :graph-schema-version graph-schema-version})))}
        (shui/select-trigger
         {:class "!px-2 !py-0 !h-8 border-gray-04"}
         (shui/select-value
          {:placeholder "Select a graph-uuid"}))
        (shui/select-content
         (shui/select-group
          (for [{:keys [graph-uuid graph-schema-version graph-status]} (sort-by :graph-uuid (:remote-graphs debug-state*))]
            (shui/select-item {:value [graph-uuid graph-schema-version] :disabled (some? graph-status)} graph-uuid)))))

       [:b "＋"]
       [:input.form-input.my-2.py-1
        {:on-change (fn [e] (swap! debug-state assoc :download-graph-to-repo (util/evalue e)))
         :on-focus (fn [e] (let [v (.-value (.-target e))]
                             (when (= v "repo name here")
                               (set! (.-value (.-target e)) ""))))
         :placeholder "repo name here"}]]]

     [:div.flex.my-2.items-center.gap-2
      (ui/button (str "upload current repo")
                 {:icon "upload"
                  :on-click (fn []
                              (let [repo (state/get-current-repo)
                                    token (state/get-auth-id-token)
                                    remote-graph-name (:upload-as-graph-name debug-state*)]
                                (state/<invoke-db-worker :thread-api/rtc-async-upload-graph
                                                         repo token remote-graph-name)))})
      [:b "➡️"]
      [:input.form-input.my-2.py-1.w-32
       {:on-change (fn [e] (swap! debug-state assoc :upload-as-graph-name (util/evalue e)))
        :on-focus (fn [e] (let [v (.-value (.-target e))]
                            (when (= v "remote graph name here")
                              (set! (.-value (.-target e)) ""))))
        :placeholder "remote graph name here"}]]

     [:div.pb-2.flex.flex-row.items-center.gap-2
      (ui/button (str "delete graph")
                 {:icon "trash"
                  :on-click (fn []
                              (when-let [{:keys [graph-uuid graph-schema-version]} (:graph-uuid-to-delete debug-state*)]
                                (let [token (state/get-auth-id-token)]
                                  (prn ::delete-graph graph-uuid graph-schema-version)
                                  (state/<invoke-db-worker :thread-api/rtc-delete-graph
                                                           token graph-uuid graph-schema-version))))})

      (shui/select
       {:on-value-change (fn [[graph-uuid graph-schema-version]]
                           (when (and (parse-uuid graph-uuid) graph-schema-version)
                             (swap! debug-state assoc
                                    :graph-uuid-to-delete
                                    {:graph-uuid graph-uuid
                                     :graph-schema-version graph-schema-version})))}
       (shui/select-trigger
        {:class "!px-2 !py-0 !h-8"}
        (shui/select-value
         {:placeholder "Select a graph-uuid"}))
       (shui/select-content
        (shui/select-group
         (for [{:keys [graph-uuid graph-schema-version graph-status]} (:remote-graphs debug-state*)]
           (shui/select-item {:value [graph-uuid graph-schema-version] :disabled (some? graph-status)} graph-uuid)))))]

     [:div.pb-2.flex.flex-row.items-center.gap-2
      (ui/button "Run server-migrations"
                 {:on-click (fn []
                              (let [repo (state/get-current-repo)]
                                (when-let [server-schema-version (:server-schema-version debug-state*)]
                                  (state/<invoke-db-worker :thread-api/rtc-add-migration-client-ops
                                                           repo server-schema-version))))})
      [:input.form-input.my-2.py-1.w-32
       {:on-change (fn [e] (swap! debug-state assoc :server-schema-version (util/evalue e)))
        :on-focus (fn [e] (let [v (.-value (.-target e))]
                            (when (= v "server migration start version here(e.g. \"64.2\")")
                              (set! (.-value (.-target e)) ""))))
        :placeholder "server migration start version here(e.g. \"64.2\")"}]]

     [:hr.my-2]

     (let [*keys-state (get state ::keys-state)
           keys-state @*keys-state]
       [:div
        [:div.pb-2.flex.flex-row.items-center.gap-2
         (shui/button
          {:size :sm
           :on-click (fn [_]
                       (p/let [graph-keys (state/<invoke-db-worker :thread-api/rtc-get-graph-keys (state/get-current-repo))
                               devices (some->> (state/get-auth-id-token)
                                                (state/<invoke-db-worker :thread-api/list-devices))]
                         (swap! (get state ::keys-state) #(merge % graph-keys {:devices devices}))))}
          (shui/tabler-icon "refresh") "keys-state")]
        [:div.pb-4
         [:pre.select-text
          (-> {:devices (:devices keys-state)
               :graph-aes-key-jwk (:aes-key-jwk keys-state)}
              (fipp/pprint {:width 20})
              with-out-str)]]
        (shui/button
         {:size :sm
          :on-click (fn [_]
                      (when-let [device-uuid (not-empty (:remove-device-device-uuid keys-state))]
                        (when-let [token (state/get-auth-id-token)]
                          (state/<invoke-db-worker :thread-api/remove-device token device-uuid))))}
         "Remove device:")
        [:input.form-input.my-2.py-1.w-32
         {:on-change (fn [e] (swap! *keys-state assoc :remove-device-device-uuid (util/evalue e)))
          :on-focus (fn [e] (let [v (.-value (.-target e))]
                              (when (= v "device-uuid here")
                                (set! (.-value (.-target e)) ""))))
          :placeholder "device-uuid here"}]
        (shui/button
         {:size :sm
          :on-click (fn [_]
                      (when-let [device-uuid (not-empty (:remove-public-key-device-uuid keys-state))]
                        (when-let [key-name (not-empty (:remove-public-key-key-name keys-state))]
                          (when-let [token (state/get-auth-id-token)]
                            (state/<invoke-db-worker :thread-api/remove-device-public-key token device-uuid key-name)))))}
         "Remove public-key:")
        [:input.form-input.my-2.py-1.w-32
         {:on-change (fn [e] (swap! *keys-state assoc :remove-public-key-device-uuid (util/evalue e)))
          :on-focus (fn [e] (let [v (.-value (.-target e))]
                              (when (= v "device-uuid here")
                                (set! (.-value (.-target e)) ""))))
          :placeholder "device-uuid here"}]
        [:input.form-input.my-2.py-1.w-32
         {:on-change (fn [e] (swap! *keys-state assoc :remove-public-key-key-name (util/evalue e)))
          :on-focus (fn [e] (let [v (.-value (.-target e))]
                              (when (= v "key-name here")
                                (set! (.-value (.-target e)) ""))))
          :placeholder "key-name here"}]
        (shui/button
         {:size :sm
          :on-click (fn [_]
                      (when-let [token (state/get-auth-id-token)]
                        (when-let [device-uuid (not-empty (:sync-private-key-device-uuid keys-state))]
                          (state/<invoke-db-worker :thread-api/rtc-sync-current-graph-encrypted-aes-key
                                                   token [(parse-uuid device-uuid)]))))}
         "Sync CurrentGraph EncryptedAesKey")
        [:input.form-input.my-2.py-1.w-32
         {:on-change (fn [e] (swap! *keys-state assoc :sync-private-key-device-uuid (util/evalue e)))
          :on-focus (fn [e] (let [v (.-value (.-target e))]
                              (when (= v "device-uuid here")
                                (set! (.-value (.-target e)) ""))))
          :placeholder "device-uuid here"}]])]))

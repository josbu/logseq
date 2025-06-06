(ns frontend.worker.rtc.log-and-state
  "Fns to generate rtc related logs"
  (:require [frontend.common.missionary :as c.m]
            [frontend.worker.shared-service :as shared-service]
            [lambdaisland.glogi :as log]
            [logseq.common.defkeywords :refer [defkeywords]]
            [malli.core :as ma]
            [missionary.core :as m]))

(def ^:private *rtc-log (atom nil))

(def rtc-log-flow
  "used by rtc-e2e-test"
  (m/watch *rtc-log))

(def ^:private rtc-log-type-schema
  (vec
   (concat
    [:enum]
    (take-nth
     2
     (defkeywords
       :rtc.log/upload {:doc "rtc log type for upload-graph."}
       :rtc.log/download {:doc "rtc log type for upload-graph."}
       :rtc.log/cancelled {:doc "rtc has been cancelled"}
       :rtc.log/apply-remote-update {:doc "apply remote updates to local graph"}
       :rtc.log/push-local-update {:doc "push local updates to remote graph"}
       :rtc.log/higher-remote-schema-version-exists {:doc "remote-graph with larger schema-version exists"}
       :rtc.log/branch-graph {:doc "rtc log type for creating a new graph branch"}

       :rtc.asset.log/cancelled {:doc "rtc asset sync has been cancelled"}
       :rtc.asset.log/upload-assets {:doc "upload local assets to remote"}
       :rtc.asset.log/download-assets {:doc "download assets from remote"}
       :rtc.asset.log/remove-assets {:doc "remove remote assets"}
       :rtc.asset.log/initial-download-missing-assets {:doc "download assets if not exists in rtc-asset-sync initial phase"})))))

(def ^:private rtc-log-type-validator (ma/validator rtc-log-type-schema))

(defn rtc-log
  [type m]
  {:pre [(map? m) (rtc-log-type-validator type)]}
  (reset! *rtc-log (assoc m :type type :created-at (js/Date.)))
  nil)

;;; some other states

(def ^:private graph-uuid->t-schema
  [:map-of :uuid :int])

(def ^:private graph-uuid->t-validator (let [validator (ma/validator graph-uuid->t-schema)]
                                         (fn [v]
                                           (if (validator v)
                                             true
                                             (do (log/error :debug-graph-uuid->t-validator v)
                                                 false)))))

(def *graph-uuid->local-t (atom {} :validator graph-uuid->t-validator))
(def *graph-uuid->remote-t (atom {} :validator graph-uuid->t-validator))

(defn- ensure-uuid
  [v]
  (cond
    (uuid? v)   v
    (string? v) (uuid v)
    :else       (throw (ex-info "illegal value" {:data v}))))

(defn- create-local-t-flow
  [graph-uuid]
  (->> (m/watch *graph-uuid->local-t)
       (m/eduction (keep (fn [m] (get m (ensure-uuid graph-uuid)))))
       c.m/continue-flow))

(defn- create-remote-t-flow
  [graph-uuid]
  (->> (m/watch *graph-uuid->remote-t)
       (m/eduction (keep (fn [m] (get m (ensure-uuid graph-uuid)))))
       c.m/continue-flow))

(defn create-local&remote-t-flow
  "ensure local-t <= remote-t"
  [graph-uuid]
  (assert (some? graph-uuid))
  (->> (m/latest vector (create-local-t-flow graph-uuid) (create-remote-t-flow graph-uuid))
       (m/eduction (filter (fn [[local-t remote-t]] (>= remote-t local-t))))))

(defn update-local-t
  [graph-uuid local-t]
  (let [graph-uuid (ensure-uuid graph-uuid)
        current-remote-t (get @*graph-uuid->remote-t graph-uuid)
        current-local-t (get @*graph-uuid->local-t graph-uuid)]
    (when (and current-remote-t current-local-t)
      (assert (and (>= local-t current-local-t) (<= local-t current-remote-t))
              {:local-t local-t
               :current-local-t current-local-t
               :current-remote-t current-remote-t}))
    (swap! *graph-uuid->local-t assoc graph-uuid local-t)))

(defn update-remote-t
  [graph-uuid remote-t]
  (let [graph-uuid (ensure-uuid graph-uuid)
        current-remote-t (get @*graph-uuid->remote-t graph-uuid)
        current-local-t (get @*graph-uuid->local-t graph-uuid)]
    (when (and current-remote-t current-local-t)
      (assert (and (>= remote-t current-remote-t) (>= remote-t current-local-t))
              {:remote-t remote-t
               :current-local-t current-local-t
               :current-remote-t current-remote-t}))
    (swap! *graph-uuid->remote-t assoc graph-uuid remote-t)))

;;; subscribe-logs, push to frontend
;;; TODO: refactor by using c.m/run-background-task
(defn- subscribe-logs
  []
  (remove-watch *rtc-log :subscribe-logs)
  (add-watch *rtc-log :subscribe-logs
             (fn [_ _ _ n] (when n (shared-service/broadcast-to-clients! :rtc-log n)))))
(subscribe-logs)

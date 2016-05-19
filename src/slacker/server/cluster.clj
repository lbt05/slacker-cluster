(ns slacker.server.cluster
  (:require [slacker.zk :as zk])
  (:use [slacker common serialization])
  (:use [clojure.string :only [split]])
  (:require [slacker.server])
  (:require [slacker.utils :as utils])
  (:require [clojure.tools.logging :as logging])
  (:import java.net.Socket))

(declare ^{:dynamic true} *zk-conn*)

(defn- auto-detect-ip
  "detect IP by connecting to zookeeper"
  [zk-addr]
  (let [zk-address (split zk-addr #":")
        zk-ip (first zk-address)
        zk-port (Integer/parseInt (second zk-address))]
    (with-open [socket (Socket. ^String ^Integer zk-ip zk-port)]
      (.getHostAddress (.getLocalAddress socket)))))

(defn- create-node
  "get zk connector & node  :persistent?
   check whether exist already
   if not ,create & set node data with func metadata
   "
  [zk-conn node-name
   & {:keys [data persistent?]
      :or {persistent? false}}]
  ;; delete ephemeral node before create it
  (when-not persistent?
    (try
      (zk/delete zk-conn node-name)
      (catch Exception _)))
  (when-not (zk/exists zk-conn node-name)
    (zk/create-all zk-conn node-name
                   :persistent? persistent?
                   :data data)))

(defn select-leaders [zk-root cluster-name nss server-node]
  (doall
   (map #(let [blocker (promise)
               leader-path (utils/zk-path
                            zk-root
                            cluster-name
                            "namespaces"
                            %
                            "_leader")
               leader-mutex-path (utils/zk-path
                                  leader-path
                                  "mutex")]
           (create-node *zk-conn* leader-mutex-path
                        :persistent? true)
           (zk/start-leader-election *zk-conn*
                                     leader-mutex-path
                                     (fn [conn]
                                       (logging/infof "%s is becoming the leader of %s" server-node %)
                                       (zk/set-data conn
                                                    leader-path
                                                    (.getBytes ^String server-node "UTF-8"))
                                       ;; block forever
                                       @blocker)))
        nss)))

(defn publish-cluster
  "publish server information to zookeeper as cluster for client"
  [cluster port ns-names funcs-map server-data]
  (let [cluster-name (cluster :name)
        zk-root (cluster :zk-root "/slacker/cluster/")
        server-node (str (or (cluster :node)
                             (auto-detect-ip (first (split (:zk cluster) #","))))
                         ":" port)
        server-path (utils/zk-path zk-root cluster-name
                                   "servers" server-node)
        server-path-data (serialize :clj server-data :bytes)
        funcs (keys funcs-map)

        ns-path-fn (fn [p] (utils/zk-path zk-root cluster-name "namespaces" p server-node))
        ephemeral-servers-node-paths (conj (map #(vector (ns-path-fn %) nil) ns-names)
                                           [server-path server-path-data])]

    ;; persistent nodes
    (create-node *zk-conn* (utils/zk-path zk-root cluster-name "servers")
                 :persistent? true)

    (doseq [nn ns-names]
      (create-node *zk-conn* (utils/zk-path zk-root
                                            cluster-name
                                            "namespaces"
                                            nn)
                   :persistent? true))

    (doseq [fname funcs]
      (create-node *zk-conn*
                   (utils/zk-path zk-root cluster-name "functions" fname)
                   :persistent? true
                   :data (serialize
                          :clj
                          (select-keys
                           (meta (funcs-map fname))
                           [:name :doc :arglists])
                          :bytes)))

    (let [ephemeral-nodes (doall (map #(do
                                         (try (zk/delete *zk-conn* (first %)) (catch Exception _))
                                         (zk/create-persistent-ephemeral-node *zk-conn* (first %) (second %)))
                                      ephemeral-servers-node-paths))
          leader-selectors (select-leaders zk-root cluster-name ns-names server-node)]
      [server-path ephemeral-nodes leader-selectors])))

(defmacro with-zk
  "publish server information to specifized zookeeper for client"
  [zk-conn & body]
  `(binding [*zk-conn* ~zk-conn]
     ~@body))

(defrecord SlackerClusterServer [svr zk-conn zk-data])

(defn set-server-data!
  "Update server data for this server, clients will be notified"
  [slacker-server data]
  (let [serialized-data (serialize :clj data :bytes)
        data-path (first (.zk-data slacker-server))]
    (with-zk (.zk-conn slacker-server)
      (zk/set-data *zk-conn* data-path serialized-data))))

(defn extract-ns [fn-coll]
  (mapcat #(if (map? %) (keys %) [(ns-name %)]) fn-coll))

(defn start-slacker-server
  "Start a slacker server to expose all public functions under
  a namespace. This function is enhanced for cluster support. You can
  supply a zookeeper instance and a cluster name to the :cluster option
  to register this server as a node of the cluster."
  [fn-coll port & options]
  (let [svr (apply slacker.server/start-slacker-server
                   fn-coll port options)
        {:keys [cluster server-data]
         :as options} options
        fn-coll (if (vector? fn-coll) fn-coll [fn-coll])
        funcs (apply merge (map slacker.server/parse-funcs fn-coll))
        zk-conn (zk/connect (:zk cluster) options)
        zk-data (when-not (nil? cluster)
                  (with-zk zk-conn
                    (publish-cluster cluster port (extract-ns fn-coll)
                                     funcs server-data)))]
    (zk/register-error-handler zk-conn
                               (fn [msg e]
                                 (logging/warn e "Unhandled Error" msg)))

    (SlackerClusterServer. svr zk-conn zk-data)))

(defn unpublish-cluster! [server]
  (let [{zk-data :zk-data} server
        [_ zk-ephemeral-nodes leader-selectors] zk-data]
    (doseq [n zk-ephemeral-nodes]
      (zk/uncreate-persistent-ephemeral-node n))
    (doseq [n leader-selectors]
      (zk/stop-leader-election n))))

(defn stop-slacker-server [server]
  (unpublish-cluster! server)
  (let [{svr :svr zk-conn :zk-conn} server]
    (zk/close zk-conn)

    ;; TODO: wait some time to allow zk to notify all clients?
    (slacker.server/stop-slacker-server svr)))

(defn get-slacker-server-working-ip [zk-addrs]
  (auto-detect-ip (first (split zk-addrs #","))))

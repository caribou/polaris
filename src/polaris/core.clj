(ns polaris.core
  (:require [clojure.string :as string]
            [clout.core :as clout]))

(defrecord Route [key method path route action])
(defrecord RouteTree [order mapping])

(defn empty-routes
  []
  (RouteTree. [] {}))

(defn- sanitize-method
  [method]
  (let [method (if (or (nil? method)
                       (and (string? method)
                            (empty? method)))
                 :ALL
                 method)]
    (keyword (string/lower-case (name method)))))

;; borrowed from ring.util.codec --------------

(defn- double-escape [^String x]
  (.replace x "\\" "\\\\"))

(defn percent-encode
  "Percent-encode every character in the given string using either the specified
  encoding, or UTF-8 by default."
  [unencoded & [encoding]]
  (->> (.getBytes unencoded (or encoding "UTF-8"))
       (map (partial format "%%%02X"))
       (string/join)))

(defn url-encode
  "Returns the url-encoded version of the given string, using either a specified
  encoding or UTF-8 by default."
  [unencoded & [encoding]]
  (string/replace
    unencoded
    #"[^A-Za-z0-9_~.+-]+"
    #(double-escape (percent-encode % encoding))))

;; -------------------------------------

(defn- add-optional-slash-to-route
  [route]
  (update-in
   route
   [:re]
   (fn [re]
     (re-pattern (str re "/?")))))

(defn merge-route
  [routes key method path action]
  (let [method (sanitize-method method)
        compiled-route (clout/route-compile path)
        route (Route. key method path (add-optional-slash-to-route compiled-route) action)
        mapped (assoc-in routes [:mapping key] route)]
    (update-in mapped [:order] #(conj % route))))

(defn- resolve-action-map
  [[method action]]
  (cond
   (fn? action) [method action]
   (symbol? action) [method (resolve action)]
   (map? action) [method (:action action)]
   :else [method action]))

(defn- action-methods
  [methods]
  (cond
   (fn? methods) [[:ALL methods]]
   (symbol? methods) [[:ALL (resolve methods)]]
   (map? methods) (map resolve-action-map methods)
   :else [[:ALL methods]]))

(declare build-route-tree)

(defn build-route
  [root-path [path key action subroutes]]
  (let [sub-path (string/replace path #"^/" "")
        full-path (str root-path "/" sub-path)
        full-path (string/replace full-path #"/$" "")
        children (build-route-tree full-path subroutes)
        actions (action-methods action)
        routes (map
                (fn [[method action]]
                  [key method full-path action])
                actions)]
    (concat routes children)))

(defn build-route-tree
  [root-path route-tree]
  (mapcat (partial build-route root-path) route-tree))

(defn build-routes
  ([route-tree] (build-routes route-tree ""))
  ([route-tree root-path]
     (let [routes (empty-routes)
           built (build-route-tree root-path route-tree)]
       (reduce
        (fn [routes [key method path action]]
          (merge-route routes key method path action))
        routes built))))

(defn route-matches?
  [request route]
  (let [request-method (:request-method request)
        compiled-route (:route route)
        method (:method route)
        method-matches (or (= :all method)
                           (= method request-method)
                           (and (nil? request-method) (= method :get)))]
    (when method-matches
      (when-let [match-result (clout/route-matches compiled-route request)]
        [route match-result]))))

(defn find-first
  [p s]
  (first (remove nil? (map p s))))

(defn router
  "takes a request and performs the action associated with the matching route"
  ([routes] (router routes (fn [request] {:status 200 :body "No action defined at this route"})))
  ([routes default-action]
     (fn [request]
       (let [ordered-routes (:order routes)
             [route match] (find-first (partial route-matches? request) ordered-routes)]
         (if match
           (let [request (assoc request :route-params match)
                 request (update-in request [:params] #(merge % match))
                 action (:action route)]
             (if action
               (action request)
               (do
                 (println (format "No action for route %s %s: %s!" (:method route) (:path route) (:key route)))
                 (default-action request))))
           {:status 404})))))

(defn- get-path
  [routes key]
  (or
   (get-in routes [:mapping (keyword key) :path])
   (throw (new Exception (str "route for " key " not found")))))

(defn sort-route-params
  [routes key params]
  (let [path (get-path routes key)
        opt-keys (keys params)
        route-keys (map
                    read-string
                    (filter
                     #(= (first %) \:)
                     (string/split path #"/")))
        query-keys (remove (into #{} route-keys) opt-keys)]
    {:path path
     :route (select-keys params route-keys)
     :query (select-keys params query-keys)}))

(defn query-item
  [[k v]] 
  (str 
   (url-encode (name k))
   "="
   (url-encode v)))

(defn build-query-string
  [params query-keys]
  (let [query (string/join "&" (map query-item (select-keys params query-keys)))]
    (and (seq query) (str "?" query))))

(defn reverse-route
  ([routes key params] (reverse-route routes key params {}))
  ([routes key params opts]
     (let [{path :path
            route-matches :route
            query-matches :query} (sort-route-params routes key params)
            route-keys (keys route-matches)
            query-keys (keys query-matches)
            opt-keys (keys params)
            base (reduce
                  #(string/replace-first %1 (str (keyword %2)) (get params %2))
                  path opt-keys)
            query (if-not (:no-query opts) (build-query-string params query-keys))]
       (str base query))))

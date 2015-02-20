(ns polaris.core
  (:require
   [clojure.string :as string]
   [ring.util.codec :as codec]
   [clout.core :as clout]))

(defrecord Route [key method path route action])
(defrecord RouteTree [order mapping])

(defn empty-routes
  []
  (RouteTree. [] {}))

(defn default-action
  [request]
  {:status 500
   :body "No action defined at this route"})

(defn- resolve-action-symbol
  [action]
  (let [space (namespace action)]
    (when-not space
      (throw (IllegalArgumentException. (str "Must be namespace qualified: " action))))
    (-> space symbol require))
  (resolve action))

(defmulti resolve-action type)
(defmethod resolve-action clojure.lang.Symbol [s] (resolve-action-symbol s))
(defmethod resolve-action clojure.lang.AFn [f] f)
(defmethod resolve-action clojure.lang.Var [v] v)
(defmethod resolve-action :default
  [x]
  (throw
   (IllegalArgumentException.
    (str "Invalid action type " (-> x class str) " for " x))))

(defn empty-method?
  [method]
  (or
   (nil? method)
   (and
    (string? method)
    (empty? method))))

(defn- sanitize-method
  [method]
  (let [method (if (empty-method? method) :ALL method)]
    (-> method name string/lower-case keyword)))

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

(def method-types [:ALL :GET :PUT :POST :DELETE])

(defn- action-methods
  [action]
  (if (map? action)
    (select-keys action method-types)
    [[:ALL action]]))

(declare build-route-tree)

(defn compose-wrapper
  [float wrapper sink]
  (apply comp (filter identity [float wrapper sink])))

(defn build-route
  [root-path wrapper [path key action subroutes]]
  (let [sub-path (string/replace path #"^/" "")
        full-path (str root-path "/" sub-path)
        full-path (string/replace full-path #"/$" "")
        {:keys [float sink]} action
        wrapper (compose-wrapper float wrapper sink)
        children (build-route-tree full-path wrapper subroutes)
        actions (action-methods action)
        routes (map
                (fn [[method action]]
                  [key method full-path (wrapper action)])
                actions)]
    (concat routes children)))

(defn build-route-tree
  [root-path wrapper route-tree]
  (mapcat (partial build-route root-path wrapper) route-tree))

(defn build-routes
  ([route-tree] (build-routes route-tree ""))
  ([route-tree root-path]
     (let [routes (empty-routes)
           built (build-route-tree root-path identity route-tree)]
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
  ([routes] (router routes default-action))
  ([routes default]
     (fn [request]
       (let [ordered-routes (:order routes)
             [route match] (find-first (partial route-matches? request) ordered-routes)]
         (if match
           (let [request (assoc request :route-params match)
                 request (update-in request [:params] #(merge % match))
                 action (-> route :action resolve-action)]
             (if action
               (action request)
               (default request)))
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
   (codec/url-encode (name k))
   "="
   (codec/url-encode v)))

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

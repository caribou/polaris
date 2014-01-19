(ns polaris.core
  (:require [clojure.string :as string]
            [clout.core :as clout]
            [ring.util.codec
             :refer [url-encode]]))

(defn- sanitize-method
  [method]
  (-> (if (and (string? method)
               (empty? method))
        :ALL
        method)
      name
      string/lower-case
      keyword))

(defn- resolve-action-symbol
  [action]
  (doto (namespace action)
    (when-not (throw (IllegalArgumentException.
                      (str "Must be namespace qualified: " action))))
    (-> symbol require))
  (resolve action))

(defn- sanitize-action-subspec
  [subspec]
  (doto (cond
         (fn? subspec) subspec
         (symbol? subspec) (resolve-action-symbol subspec))
    (when-not (throw (IllegalArgumentException.
                      (str "Invalid action-subspec: " subspec))))))

(defn- sanitize-action-spec
  "Convert action-spec to a hash-map request-method->action."
  [action-spec]
  (if (map? action-spec)
    (reduce-kv #(assoc %1
                  (sanitize-method %2)
                  (sanitize-action-subspec %3)) {} action-spec)
    (recur {:ALL action-spec})))

(defn- add-optional-slash-to-route
  [route]
  (update-in route [:re]
             (fn [re]
               (re-pattern (str re "/?")))))

(defn- compile-path
  [path]
  (-> path
      clout/route-compile
      add-optional-slash-to-route))

(defn- compose-path
  [root-path sub-path]
  (let [sub-path (string/replace sub-path #"^/" "")]
    (-> (cond-> root-path
          (seq sub-path) (str "/" sub-path))
        (string/replace #"/$" ""))))

(defn- sanitize-routespec
  [spec]
  (let [[path obj1 obj2] spec
        idents (cond (keyword? obj1) #{obj1}
                     (set? obj1) obj1)]
    (merge {:path path}
           (if idents
             {:idents idents
              :action-spec obj2
              :sub-specs (drop 3 spec)}
             (if (sequential? obj1)
               {:sub-specs (rest spec)}
               {:action-spec obj1
                :sub-specs (drop 2 spec)})))))

(defn- to-routes
  ([user-specs]
     (to-routes "" user-specs))
  ([root-path user-specs]
     (let [make-sub-path (partial compose-path root-path)
           step (fn [user-spec]
                  (if-not (coll? (first user-spec))
                    (let [{:keys [idents path action-spec sub-specs] :as dbg}
                          (sanitize-routespec user-spec)
                          sub-path (compose-path root-path path)]
                      (-> {:compiled-path (compile-path sub-path)
                           :full-path sub-path
                           :idents idents}
                          (cond->
                            action-spec
                            (assoc :actions
                              (sanitize-action-spec action-spec)))
                          vector
                          (into (to-routes sub-path sub-specs))))
                    ;; unnest:
                    (to-routes root-path user-spec)))]
       (->> user-specs
            (mapv step)
            (reduce into [])))))

(defn- match-in-routes
  [request routes]
  (reduce (fn [_ route]
            (when-let [match (clout/route-matches (:compiled-path route)
                                                  request)]
              (reduced [route match]))) nil routes))

(defn- assoc-ident-lookup
  [acc route]
  (let [idents (:idents route)]
    (doseq [ident idents]
      (when-let [existing (get acc ident)]
        (throw
         (IllegalArgumentException.
          (str
           "Can not add route with ident: " ident \newline
           "Existing spec: " \tab existing
           "At path: " \tab (:full-path route))))))
    (merge acc
           (zipmap idents (repeat route)))))

(defn- make-lookup-tables
  [routes]
  (reduce (fn [acc route]
            (-> acc
                (update-in [:by-ident]
                           assoc-ident-lookup route)))
          {:routes routes} routes))

;; API
(defn build-routes
  "Create routes that can be used with router. 

  Specs will be matched in the order they are provided, regardless of
  their hierachy level.

  A spec* must be a vector of either**

  [path-spec ident action-spec & specs] or

  [path-spec action-spec] or

  [path-spec & specs]


  path-spec is a string specifying a sub directory in in its
  containing specs path-spec (by default \"/\"). It may contain
  parameterizable sub-paths such as
  \"/home/profiles/:username/\". These can be resolved under :params
  in the request when it is passed to an action.

  Idents can used for reverse-routing (reverse-route). They must be
  keywords and are optional. Multiple idents for one route may be
  specified in a set instead of a single keyword. Each ident used in
  the overall spec must be unique.

  action-spec is either:

  - a function that will be invoked for all requests that match the
    path

  - a namespace qualified symbol that will be resolved before this
    function returns. require will be invoked on its namespace

  - a hash-map mapping request-methods to functions or namespaces

  * multiple specs may be grouped in an extra vector, but must not"
  [& specs]
  (-> specs to-routes make-lookup-tables))

(defn reverse-route
  "Reconstruct url for route at ident based on parameters in opts
  (optional)."
  ([routes ident] (reverse-route ident {}))
  ([routes ident opts]
     (let [path (get-in routes [:by-ident ident :full-path])
           [route-str opts-left]
           (reduce (fn [[route-str opts] sub-dir]
                     (if (= (first sub-dir) \:)
                       (let [kw (keyword (subs sub-dir 1))]
                         [(str route-str "/" (get opts kw))
                          (dissoc opts kw)])
                       [(str route-str "/" sub-dir) opts]))
                   ["" opts]
                   (filter seq (string/split path #"/")))]
       (str route-str
            (some->> opts-left
                     (mapv (fn [[k v]] (str (url-encode (name k))
                                            "="
                                            (url-encode v))))
                     (string/join "&")
                     seq
                     (str "?"))))))

(defn router
  "Create a Polaris request handler. Routes must have been built using
  build-routes. 

  A map with resolved parameters is merged onto :params in requests
  passed to resolved actions, routes can be found under :routes."
  ([routes]
     (router
      routes
      (constantly {:status 405
                   :body "Method not allowed."})
      (constantly {:status 404
                   :body "Page not found."})))
  ([routes method-not-allowed page-not-found]
     (let [{:keys [routes]} routes]
       (fn [{method :request-method :as request}]
         (let [[route match]
               (match-in-routes request routes)
               {:keys [actions]} route
               action (or (get actions (or method
                                           (sanitize-method :GET)))
                          (get actions (sanitize-method :ALL)))]
           (if match
             (if action
               (-> request
                   (assoc :routes routes)
                   (update-in [:params] merge match)
                   action)
               (method-not-allowed request))
             (page-not-found request)))))))

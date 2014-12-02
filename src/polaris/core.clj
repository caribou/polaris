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

(defmulti add-handler type)

(defmethod add-handler clojure.lang.Symbol
  [s]
  (resolve-action-symbol s))

(defmethod add-handler clojure.lang.AFn
  [f]
  f)

(defmethod add-handler clojure.lang.Var
  [v]
  v)

(defmethod add-handler :default
  [x]
  (throw (IllegalArgumentException.
          (str "Invalid action-subspec: " x))))

(defn- sanitize-action-subspec
  [subspec]
  (doto (cond
         (symbol? subspec) (resolve-action-symbol subspec)
         (instance? subspec clojure.lang.AFn) subspec
         (fn? subspec) subspec
         (var? subspec) subspec)
    (when-not (throw (IllegalArgumentException.
                      (str "Invalid action-subspec: " subspec))))))

(defn- sanitize-action-spec
  "Convert action-spec to a hash-map request-method->action."
  [action-spec]
  (if (map? action-spec)
    (reduce-kv #(assoc %1
                  (sanitize-method %2)
                  (add-handler %3)) {} action-spec)
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
  "Multi-purpose path-builder:

  Split sub-paths into more sub-paths if they contain slashes, replace
  batched slashes with a single slash, concat all sub-paths with
  slashes between them.

  If no sub-paths could be composed, return the empty-string.

  Sub-paths must be strings, not nil."
  [& sub-paths]
  (let [path (->> sub-paths
                  (mapcat #(string/split % #"/"))
                  (keep not-empty)
                  (string/join "/"))]
    (if (empty? path)
      ""
      (str "/" path))))

(defn- sanitize-user-specs
  [specs]
  (cond
   (coll? (first specs)) (not-empty
                          (mapcat sanitize-user-specs specs))
   (seq specs)
   (let [[path obj1 obj2] specs
         idents (cond (keyword? obj1) #{obj1}
                      (set? obj1) obj1)]
     [(merge {:path (compose-path path)}
             (if idents
               {:idents idents
                :action-spec (sanitize-action-spec obj2)
                :sub-specs (->> specs
                                (drop 3)
                                sanitize-user-specs)}
               (if (sequential? obj1)
                 {:sub-specs (->> specs
                                  rest
                                  sanitize-user-specs)}
                 {:action-spec (sanitize-action-spec obj1)
                  :sub-specs (->> specs
                                  (drop 2)
                                  sanitize-user-specs)})))])))

(defn- merge-specs-by-path
  "If multiple routes are specified with the same path on one level,
  merge them ontop of each other."  
  [specs]
  (->> specs
       (map :path)
       distinct
       (map (group-by :path specs))
       (map (fn [specs]
              (-> (reduce #(-> %1
                               (update-in [:idents]
                                          (fnil into #{}) (:idents %2))
                               (update-in [:action-spec]
                                          merge (:action-spec %2))
                               (update-in [:sub-specs]
                                          concat (:sub-specs %2)))
                          specs)
                  (update-in [:sub-specs]
                             #(some-> % merge-specs-by-path)))))))

(defn- compile-specs
  "Generate full sub-paths and compile Clout routes."
  [root-path specs]
  (letfn [(step [{:keys [idents path action-spec sub-specs] :as spec}]
            (let [sub-path (compose-path root-path path)]
              (-> []
                  (cond-> action-spec
                          (conj (assoc spec
                                  :compiled-path (compile-path sub-path)
                                  :full-path sub-path)))
                  (into (compile-specs sub-path sub-specs)))))]
    (mapcat step specs)))

(defn- to-routes
  ([user-specs] (to-routes "/" user-specs))
  ([root-path user-specs]
     (->> user-specs
          (sanitize-user-specs)
          (merge-specs-by-path)
          (compile-specs root-path))))

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
           "Can't add route with ident: " ident \newline
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

  A spec must be a vector of either

  [path-spec ident action-spec & specs*] or

  [path-spec action-spec] or

  [path-spec & specs*]


  path-spec is a string specifying the parent directory (by default
  \"/\") of nested specs. It may contain parameterizable sub-paths
  such as \"/home/profiles/:username/\". These can be resolved
  under :params in the request when it is passed to an action.

  Idents can used for reverse-routing (reverse-route). They must be
  keywords and are optional. Multiple idents for one route may be
  specified in a set instead of a single keyword. The same idents may
  be used exclusively in specs that have identical paths and reside on
  one level.

  action-spec is either:

  - a function that will be invoked for all requests that match the
    path

  - a namespace qualified symbol that will be resolved before this
    function returns. require will be invoked on its namespace

  - a var that can be resolved to a function at request time

  - a hash-map mapping request-methods to one of those


  Specs will be matched in the order they are provided, regardless of
  their hierachy level, unless they are on the same level and have the
  same path in which case their action-specs and idents are merged.

  * multiple specs may be grouped in an extra vector"
  [& specs]
  (-> specs to-routes make-lookup-tables))

(defn- build-query-string
  [opts-map]
  (str (some->> opts-map
                (mapv (fn [[k v]] (str (url-encode (name k))
                                       "="
                                       (url-encode v))))
                (string/join "&")
                not-empty
                (str "?"))))

(defn reverse-route
  "Reconstruct url for route at ident based on parameters in params
  (optional). opts may specify the following settings in a hash-map

  :no-query When set to logical true, don't append a query-string like
  ?unmatched-param=its-val for params that could not be used to name
  sub-dirs in the route."
  ([routes ident] (reverse-route routes ident {}))
  ([routes ident params] (reverse-route routes ident params {}))
  ([routes ident params opts]
     (let [path (get-in routes [:by-ident (keyword ident) :full-path])
           [sub-dirs params-left]
           (->> (string/split path #"/")
                (filter seq)
                (reduce (fn [[sub-dirs params-left] sub-dir]
                          (if (= (first sub-dir) \:)
                            (let [kw (keyword (subs sub-dir 1))]
                              [(conj sub-dirs (or (str (get params-left kw))
                                                  sub-dir))
                               (dissoc params-left kw)])
                            [(conj sub-dirs sub-dir)
                             params-left]))
                        [[] params]))]
       (cond-> (apply compose-path sub-dirs)
         (not (:no-query opts))
         (str (build-query-string params-left))))))

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
               {:keys [action-spec]} route
               action (or (get action-spec (or method
                                           (sanitize-method :GET)))
                          (get action-spec (sanitize-method :ALL)))]
           (if match
             (if action
               (-> request
                   (assoc :routes routes)
                   (update-in [:params] merge match)
                   action)
               (method-not-allowed request))
             (page-not-found request)))))))

# polaris

### Requests guided by the constellations

Routing defined by data, not macros

Also, provides reverse routing for building urls from parameters

## Installation

#### Leiningen

    [polaris "0.0.1"]

## Usage

Polaris uses a data-driven routing defintion approach.  Routes are given as a
vector of route definitions.  Every route definition has the following form:

```clj
["/path/to/match" :identifying-key handler-fn [optional-child-routes]]
```

Variables in paths can be specified with a `:keyword`:

```clj
["/path/with/:variable" :identifying-key handler-fn [optional-child-routes]]
```

Once you have a vector of route definitions, you can build your routes with `polaris.core/build-routes`:

```clj
(defn base-handler
  [request]
  {:status 200 
   :body "This is the base"})
  
(defn sub-handler
  [request]
  {:status 200 
   :body (str "We received " (-> request :params :leaf))})

(def route-definitions
  [["/base-path" :base base-handler
    [["/sub-path/:leaf" :sub sub-handler]]]])

(def routes (polaris.core/build-routes route-definitions))
```

Once you have some routes, you can match requests!

```clj
(def handler (polaris.core/router routes))
(handler {:uri "/base-path"}) ;; ---> {:status 200 :body "This is the base"}
```

Child routes inherit their path from their parent, so for the above routes the
following request would work:

```clj
(handler {:uri "/base-path/sub-path/yellow"}) ;; ---> {:status 200 :body "We received yellow"}
```

Route matching works with or without following slashes:

```clj
(handler {:uri "/base-path/sub-path/chartreuse/"}) ;; ---> {:status 200 :body "We received chartreuse"}
```

Handlers can be scoped to different request methods:

```clj
(defn get-handler
  [request]
  {:status 200 
   :body "The method defaults to GET"})

(defn post-handler
  [request]
  {:status 200 
   :body "This is a POST"})

(defn delete-handler
  [request]
  {:status 200 
   :body "DELETED!!"})

(def route-definitions
  [["/method-sensitive" :base {:GET base-handler :POST post-handler :DELETE delete-handler}]])

(def routes (polaris.core/build-routes route-definitions))
(def handler (polaris.core/router routes))

(handler {:uri "/method-sensitive"}) ;; ---> {:status 200 :body "The method defaults to GET"}
(handler {:uri "/method-sensitive" :request-method :post}) ;; --> {:status 200 :body "This is a POST"}
(handler {:uri "/method-sensitive" :request-method :delete}) ;; --> {:status 200 :body "DELETED!!"}
```

### Reverse Routing

Polaris supports reverse routing, which means you can reconstruct a url based on
the route's identifying key and a map of values to substitute into any variable
path elements.

```clj
(def route-definitions
  [["/path/:with/:lots/:of/:variables" :demo (fn [request] "WHAT")]])
  
(def routes (polaris.core/build-routes route-definitions))
(polarise.core/reverse-route routes :demo {:with "now" :lots "formed" :of "from" :variables "map"})
;; --> "/path/now/formed/from/map"   !!
```

## License

Copyright © 2013 Ryan Spangler

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

# Examples

## Liberator

#### With lein-ring

```clj
(ns liberator-example.core
  (:require [polaris.core :as polaris]
            [liberator.core :refer [defresource]]))

(defresource hello-world
  :available-media-types ["text/plain" "application/json"]
  :handle-ok "Hello, world!")

(def routes
  [["/hello-world" :hello-world {:get hello-world} []]])

(def app (polaris/router (polaris/build-routes routes)))
```
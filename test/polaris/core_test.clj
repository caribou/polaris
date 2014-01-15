(ns polaris.core-test
  (:require [clojure.test :refer :all]
            [polaris.core :refer :all]))

(defn home
  [request]
  {:status 200 :body "YOU ARE HOME"})

(defn child
  [request]
  {:status 200 :body "child playing with routers"})

(defn grandchild
  [request]
  {:status 200 :body (str (-> request :params :face) " contains wisdom")})

(defn sibling
  [request]
  {:status 200 :body (str "there is a " (-> request :params :hand))})

(defn parallel
  [request]
  {:status 200 :body "ALTERNATE DIMENsion ---------"})

(defn lellarap
  [request]
  {:status 200 :body "--------- noisNEMID ETANRETLA"})

(defn orthogonal
  [request]
  {:status 200 :body (str "ORTHOGONAL TO " (-> request :params :vector))})

(defn perpendicular
  [request]
  {:status 200 :body (str (-> request :params :tensor) " IS PERPENDICULAR TO " (-> request :params :manifold))})

(defn further
  [request]
  {:status 200 :body (str "What are you doing out here " (-> request :params :further) "?")})

(def test-routes
  ["/" :home home
   ["/child" :child child
    ["/grandchild/:face" :grandchild grandchild]]
   ["/sibling/:hand" :sibling sibling]
   ["/parallel" :parallel {:GET parallel :POST lellarap}
    ["/orthogonal/:vector" :orthogonal {:PUT orthogonal}]
    ["/perpendicular/:tensor/:manifold" :perpendicular perpendicular]]
   ["/:further" :further further]])

(deftest build-routes-test
  (let [routes (build-routes test-routes)
        handler (router routes)]
    #_(println routes)
    (is (= "YOU ARE HOME" (:body (handler {:uri ""}))))
    (is (= "YOU ARE HOME" (:body (handler {:uri "/"}))))
    (is (= "child playing with routers" (:body (handler {:uri "/child"}))))
    (is (= "child playing with routers" (:body (handler {:uri "/child/"}))))
    (is (= "water contains wisdom" (:body (handler {:uri "/child/grandchild/water"}))))
    (is (= "fire contains wisdom" (:body (handler {:uri "/child/grandchild/fire/"}))))
    (is (= "there is a dragon" (:body (handler {:uri "/sibling/dragon/"}))))
    (is (= "ALTERNATE DIMENsion ---------" (:body (handler {:uri "/parallel/"}))))
    (is (= "--------- noisNEMID ETANRETLA" (:body (handler {:uri "/parallel/" :request-method :post}))))
    (is (= "ORTHOGONAL TO OVOID" (:body (handler {:uri "/parallel/orthogonal/OVOID" :request-method :put}))    ))
    (is (= 405 (:status (handler {:uri "/parallel/orthogonal/OVOID" :request-method :delete}))))
    (is (= 405 (:status (handler {:uri "/parallel/orthogonal/OVOID"}))))
    (is (= "A IS PERPENDICULAR TO XORB" (:body (handler {:uri "/parallel/perpendicular/A/XORB"}))))
    (is (= "What are you doing out here wasteland?" (:body (handler {:uri "/wasteland"}))))
    (is (= 404 (:status (handler {:uri "/wasteland/further/nothing/here/monolith"}))))
    (is (= "/parallel/perpendicular/line/impossible" (reverse-route routes :perpendicular {:tensor "line" :manifold "impossible"})))))

(ns metrics-playground
  (:require [io.pedestal.metrics :as m]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.tracing :as tel]
            [net.lewisship.trace :refer [trace]]
            [clojure.core.async :refer [go <! timeout]]
            [io.pedestal.tracing :as t])
  (:import (io.opentelemetry.context Context)))


(defonce server nil)


(defn status-handler
  [request]
  (trace :request request)
  {:status  200
   :headers {}
   :body    {:state :running}})

(defn work-in-span
  [label delay-ms time-ms]
  (go
    (<! (timeout delay-ms))

    (let [span (-> (tel/create-span label nil)
                   (tel/start))]
      (<! (timeout time-ms))
      (tel/end-span span))
    [:ok label]))

(def async
  {:name  ::async
   :enter (fn [context]
            (go
              (let [c1 (work-in-span "alpha" 500 2000)
                    c2 (work-in-span "beta" 1000 2000)
                    c3 (work-in-span "gamma" 750 1000)]
                (assoc context
                       :response {:status  200
                                  :headers {}
                                  :body    {:c1 (<! c1)
                                            :c2 (<! c2)
                                            :c3 (<! c3)}}))))})

(def routes
  #{["/status" :get status-handler :route-name ::status]
    ["/async" :get async :route-name ::async]})

(defn- create-and-start-server
  [_]
  (->> {::http/port   8080
        ::http/type   :jetty
        ::http/join?  false
        ::http/routes (route/routes-from routes)}
       http/create-server
       http/start))

(defn start
  []
  (if server
    :already-started
    (do
      (alter-var-root #'server create-and-start-server)
      :ok)))

(defn stop
  []
  (if server
    (do
      (http/stop server)
      (alter-var-root #'server (constantly nil))
      :stopped)
    :not-running))


(comment
  (start)
  (stop)


  (m/increment-counter ::hit-rate nil)
  (m/advance-counter ::request-time {:path "/api"} 47)
  ((m/histogram ::request-size nil) 35)

  (Context/current)

  (def sb (t/create-span :request {:path "/bazz"}))

  (def span (t/start sb))

  (t/end-span span)

  )


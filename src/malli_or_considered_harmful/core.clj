(ns malli-or-considered-harmful.core
  "Malli allows us to write specifications that define the shape of our data.
  It lets us express ideas like 'the field `foo` should be an integer'.

  One neat feature of Malli is that it also allows the user to define
  polymorphic data using `:or` and `:multi`. Using `:or`, you can say 'This data
  is an integer OR a string'. But `:or` can interact poorly with Reitit coercion
  if the `:or` statement is not in a leaf node of the schema."
  (:require [muuntaja.core :as muuntaja]
            [muuntaja.middleware :as mw.m]
            [reitit.coercion :as rc]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.coercion.malli :as rcm]
            [ring.mock.request :as mock])
  (:import (java.util UUID)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Define Schemas
;; - In order to illustrate why Malli `:or` can be dangerous, we'll create two
;;   schemas - one with the dangerous `:or` and one without. We'll compare how
;;   the two handle Coercion to illustrate the problems with `:or`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def Marzlevane
  "Marzlevanes and Encabulators share the same `:foo` and `:bar` specs
  However, they have different `:type` and `:baz` specs"
  [:map
   [:type [:= :marzlevane]]
   [:foo :uuid]
   [:bar :int]
   [:baz [:enum :a :b :c]]])

(def Encabulator
  "Encabulators and Marzlevans share the same `:foo` and `:bar` specs
  However, they have different `:type` and `:baz` specs"
  [:map
   [:type [:= :encabulator]]
   [:foo :uuid]
   [:bar :int]
   [:baz [:enum :e :f :g]]])

(def DangerousOr
  "This is dangerous because the `:or` statement is not in a leaf node in the
  spec.

  Because the elements in the `:or` have children, this schema will interact
  poorly with Reitit coercion.  Reitit will try to decide 'is this a
  `Marzlevane` or an `Encabulator`?' and it won't know.  So it won't coerce any
  child keys (such as `:foo` or `:bar`), which will cause the error messaging to
  warn that ALL of the data corresponding to the `:or` statement are invalid."
  [:map
   [:id :uuid]
   [:data [:or Marzlevane Encabulator]]])

(def SafeSchemaWithMulti
  "This is safe because there is no `:or` statement in the spec.

  Because the spec uses a `:multi` to dispatch and pre-determine whether to
  expect a `Marzlevane` or an `Encabulator`, it will interact nicely with Reitit
  coercion.

  Reitit will decide 'this a Marzlevane' and it will know how to coerce the data
  in the child schemas. So it will make targeted error messaging describing
  exactly what problems existed with the data."
  [:map
   [:id :uuid]
   [:data [:multi {:dispatch    :type
                   :decode/json #(update % :type keyword)}
           [:marzlevane Marzlevane]
           [:encabulator Encabulator]]]])

(def malli-coercion
  (-> rcm/default-options
      (assoc :encode-error (fn [{:keys [humanized in errors] :as malli-error}]
                             (println "Schema validation errors: " malli-error)
                             ;; Return `errors` if you want a very descriptive
                             ;; response that also includes what invalid input
                             ;; was supplied
                             ;;errors

                             ;; Return `humanized` for a short description of
                             ;; what was expected. Does not tell you what value
                             ;; was supplied.
                             humanized))
      (update :error-keys conj :transformed :errors)))

(def app
  "We will hit these HTTP routes to illustrate the differences between
  `DangerousOr` and `SafeSchemaWithMulti`. The routes are identical except for
  the request coercion schemas attached to the routes."
  (ring/ring-handler
   (ring/router
    [["/dangerous-or" {:post {:parameters {:body DangerousOr}
                              :handler    (fn [{:keys [parameters] :as request}]
                                            {:status 200
                                             :body   {:msg "Yay!"}})}}]

     ["/safe-or"      {:post {:parameters {:body SafeSchemaWithMulti}
                              :handler    (fn [{:keys [parameters] :as request}]
                                            {:status 200
                                             :body   {:msg "Yay!"}})}}]]
    {:data {:coercion   (rcm/create malli-coercion)
            :muuntaja   (muuntaja/create)
            :middleware [mw.m/wrap-format                 ;; Convert JSON input to Clojure maps
                         rrc/coerce-exceptions-middleware ;; Handle exceptions during coercion
                         rrc/coerce-request-middleware    ;; Attempt to coerce request inputs to match schema
                         ]}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Demonstration of why `:or` can be dangerous when combining with Coercion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clojurize
  [b]
  (muuntaja/decode "application/json" b))

(def valid-request
  {:id   (UUID/randomUUID)
   :data {:type :encabulator
          :foo  (UUID/randomUUID)
          :bar  1
          :baz  "e"}})






;; Send a request to `/safe-or`. We have a valid body and we'll change `:baz` to
;; make the payload invalid. Here, because we don't use a dangerous `:or`, we
;; will get a targeted error message telling us that `:baz` was invalid.
(-> (mock/request :post "/safe-or")
    (mock/json-body (assoc-in valid-request
                              [:data :baz] "invalid-baz"))
    (app)
    (update :body (comp clojurize slurp)))
;; => {:status  400
;;     :headers {"Content-Type" "application/json; charset=utf-8"}
;;     :body    {:data {:baz ["should be either :e, :f or :g"]}}}    ;; Targeted message. Nice!







;; Send a request to `/dangerous-or`. We have a valid body and we'll change `:baz` to
;; make the payload invalid. Here, because we use a dangerous `:or`, we will get
;; a messy error message telling us that the entire contents of the `data` input
;; were invalid. For most use caess, this is extremely unhelpful, because it
;; obscures the actual error (`:baz` alone was the problem).
(-> (mock/request :post "/dangerous-or")
    (mock/json-body (assoc-in valid-request
                              [:data :baz] "invalid-baz"))
    (app)
    (update :body (comp clojurize slurp)))
;; => {:status  400
;;     :headers {"Content-Type" "application/json; charset=utf-8"}
;;     :body
;;     {:data
;;      {:baz ["should be either :a, :b or :c" "should be either :e, :f or :g"],
;;       :type ["should be :marzlevane" "should be :encabulator"],
;;       :foo ["should be a uuid" "should be a uuid"]}},
;;     }

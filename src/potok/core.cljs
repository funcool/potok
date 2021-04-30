;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns potok.core
  "Stream & Events based state management toolkit for ClojureScript."
  (:refer-clojure :exclude [update reify type resolve])
  (:require [beicon.core :as rx]
            [okulary.core :as l])
  (:require-macros [potok.core :refer [reify]]))

;; --- Protocols

;; An abstraction that represents a simple state
;; transformation. The `update` function will receive
;; the current state and will return the transformend
;; state. It also can be interpreted like a reducer.

(defprotocol UpdateEvent
  (update [event state] "Apply a transformation to the state."))

;; An abstraction that represents an asynchronous
;; computation. The `watch` function receives the
;; current state and the stream and should return
;; an other stream with events (that can be of
;; `UpdateEvent`, `WatchEvent` or `EffectEvent`.

(defprotocol WatchEvent
  (watch [event state stream]))

;; An abstraction for perform just side effects. It
;; receives state and its return value is completly
;; ignored.

(defprotocol EffectEvent
  (effect [event state stream]))

;; An abstraction for define the Event type and facilitate filtering
;; of events by type (only useful when type is defined using reify).

(defprotocol Event
  (-type [_] "Returns the type of the event."))

;; --- Predicates & Helpers

(defn ^boolean update?
  "Return `true` when `e` satisfies
  the UpdateEvent protocol."
  [e]
  (satisfies? UpdateEvent e))

(defn ^boolean watch?
  "Return `true` when `e` satisfies
  the WatchEvent protocol."
  [e]
  (satisfies? WatchEvent e))

(defn ^boolean effect?
  "Return `true` when `e` satisfies
  the EffectEvent protocol."
  [e]
  (satisfies? EffectEvent e))

(defn ^boolean event?
  "Return `true` if `v` is an event."
  [v]
  (or (satisfies? Event v)
      (update? v)
      (watch? v)
      (effect? v)))

(defn ^boolean promise?
  "Return `true` if `v` is a promise instance or is a thenable
  object."
  [v]
  (or (instance? js/Promise v)
      (and (goog.isObject v)
           (fn? (unchecked-get v "then")))))

(defn type
  [o]
  (if (implements? Event o)
    (-type o)
    ::undefined))

(defn type?
  ([t]
   (fn ^boolean [v] (= (type v) t)))
  (^boolean [t v]
   (= (type v) t)))

;; --- Constructors

(defn data-event
  "Creates an event instance that only contains data."
  ([t] (data-event t nil))
  ([t d]
   (reify t
     IDeref
     (-deref [_] d))))

(defmulti resolve (fn [type params] type))
(defmethod resolve :default
  [type params]
  (data-event type params))

(defn event
  ([type]
   (resolve type nil))
  ([type params]
   (resolve type params)))

;; --- Implementation Details

(extend-protocol UpdateEvent
  function
  (update [func state]
    (func state)))

(defmulti handle-error :type)
(defmethod handle-error :default
  [error]
  (js/console.warn "Using default error handler, consider using your own!")
  (js/console.error error))


(def ^:private noop (constantly nil))

;; --- Public API

(defn repr-event
  [event]
  (cond
    (satisfies? Event event)
    (str "typ:(" (pr-str (-type event)) ")")

    (and (fn? event)
         (pos? (count (.-name event))))
    (str "fn:(" (demunge (.-name event)) ")")

    :else
    (str "unk:(" (pr-str event) ")")))

(defn store
  "Start a new store.

  This function initializes a new event processing stream
  loop and returns a bi-directional rx stream that should
  be used to push new events and subscribe to state changes."
  ([] (store nil))
  ([{:keys [on-error state validate-fn]
     :or {on-error handle-error
          validate-fn map?}
     :as params}]
   (let [input-sb (rx/subject)
         failset  (js/WeakSet.)

         process-error
         (fn [error]
           (let [res (on-error error)]
             (if (rx/observable? res) res (rx/empty))))

         process-watch
         (fn [input-sm output-sb state event]
           (let [result (try
                          (watch event state input-sm)
                          (catch :default e
                            (.add ^js failset event)
                            (process-error e)))]

             (cond
               (rx/observable? result)
               (->> result
                    (rx/catch process-error)
                    (rx/subs #(rx/push! output-sb %)))

               (promise? result)
               (->> (rx/from result)
                    (rx/catch process-error)
                    (rx/subs #(rx/push! output-sb %)))

               (nil? result)
               nil

               :else
               (js/console.warn "Event returned unexpected object from `watch` method (ignoring)."
                                (pr-str {:event event :event-type (type event)})))))

         process-effect
         (fn [input-sm state event]
           (try
             (effect event state input-sm)
             (catch :default e
               (process-error e))))

         ;; This steps allow an optional layer of indirection:
         ;; defining events in a multimethod style registry.
         input-sm (rx/to-observable input-sb)

         ;; A shared observable is used here for avoid repeating
         ;; resolution process for each subscription.
         input-sm (rx/share input-sm)
         state*   (l/atom state)]

     ;; a sync state transformation subscription loop
     (->> (rx/filter update? input-sm)
          (rx/subs (fn [event]
                     (try
                       (swap! state* (fn [state]
                                       (let [result (update event state)]
                                         (when-not (validate-fn result)
                                           (let [hint (str "seems like the event '" (repr-event event) "' "
                                                           "does not pass validation")]
                                             (throw (js/Error. hint))))
                                         result)))
                       (catch :default e
                         (.add failset event)
                         (process-error e))))))

     ;; side effectful subscription for watch events
     (->> (rx/filter watch? input-sm)
          (rx/filter (fn [e] (not ^boolean (.has ^js failset e))))
          (rx/subs #(process-watch input-sm input-sb @state* %)))

     ;; side effectful subscription for effect events
     (->> (rx/filter effect? input-sm)
          (rx/filter (fn [e] (not ^boolean (.has ^js failset e))))
          (rx/subs #(process-effect input-sm @state* %)))

     (specify! state*
       ;; Implement rxjs subject interface
       Object
       (next [_ event]
         (.next ^js input-sb event))

       (error [_ error]
         (rx/end! input-sb))

       (complete [_]
         (rx/end! input-sb))

       (getInputStream [_]
         input-sm)

       ;; Implement the beicon disposable protocol (for convenience)
       rx/IDisposable
       (-dispose [_]
         (rx/end! input-sb))))))

(defn emit!
  "Emits an event or a collection of them into the default store.

  If you have instanciated your own store, this function provides
  2-arity that allows specify a user defined store."
  ([store event]
   (.next ^js store event))
  ([store event & more]
   (run! #(.next ^js store %) (cons event more))))

(defn input-stream
  "Returns the internal input stream of the store. Should
  be used by third party integration that want use store
  as event bus not only with defined events."
  [store]
  (.getInputStream ^js store))

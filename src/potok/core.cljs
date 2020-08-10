;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions are met:
;;
;; * Redistributions of source code must retain the above copyright notice, this
;;   list of conditions and the following disclaimer.
;;
;; * Redistributions in binary form must reproduce the above copyright notice,
;;   this list of conditions and the following disclaimer in the documentation
;;   and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
;; AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
;; IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
;; DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
;; FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;; DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
;; SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
;; CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
;; OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
;; OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns potok.core
  "Stream & Events based state management toolkit for ClojureScript."
  (:refer-clojure :exclude [update reify type resolve])
  (:require [beicon.core :as rx])
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

;; An abstraction used for send data to the store.

(defprotocol Store
  (^:private -push [_ event] "Push event into the stream.")
  (^:private -input-stream [_] "Returns the internal input stream/subject."))

;; --- Types

(defrecord EventRef [type params])

;; --- Predicates & Helpers

(defn update?
  "Return `true` when `e` satisfies
  the UpdateEvent protocol."
  [e]
  (satisfies? UpdateEvent e))

(defn watch?
  "Return `true` when `e` satisfies
  the WatchEvent protocol."
  [e]
  (satisfies? WatchEvent e))

(defn effect?
  "Return `true` when `e` satisfies
  the EffectEvent protocol."
  [e]
  (satisfies? EffectEvent e))

(defn event?
  "Return `true` if `v` is an event."
  [v]
  (or (satisfies? Event v)
      (update? v)
      (watch? v)
      (effect? v)))

(defn event-ref?
  [v]
  (instance? EventRef v))

(defn promise?
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
   (fn [v] (= (type v) t)))
  ([t v]
   (= (type v) t)))

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

(defmulti resolve (fn [type params] type))
(defmethod resolve :default
  [type params]
  (throw (ex-info "No implementation found"
                  {:type :not-implemented
                   :code :resolve-not-implemented
                   :context {:type type
                             :params params}})))

(def ^:private noop (constantly nil))

;; --- Public API

(defn store
  "Start a new store.

  This function initializes a new event processing stream
  loop and returns a bi-directional rx stream that should
  be used to push new events and subscribe to state changes."
  ([] (store nil))
  ([{:keys [on-error state resolve]
     :or {on-error handle-error}
     :as params}]
   (letfn [(process-update [input-sub state event]
             (update event state))

           (process-watch [input-sub [event state]]
             (let [result (watch event state input-sub)]
               (cond
                 (rx/observable? result) result
                 (nil? result) (rx/empty)
                 (promise? result) (rx/from result)
                 :else
                 (do
                   (js/console.warn "Event returned unexpected object from `watch` method (ignoring)."
                                    (pr-str {:event event :event-type (type event)}))
                   (rx/empty)))))

           (handle-effect [input-sub [event state]]
             (effect event state input-sub))

           (handle-error [error source]
             (let [res (try
                         (on-error error)
                         (catch :default e))]
               (if (rx/observable? res)
                 res
                 source)))
           ]

     (let [input-sb  (rx/subject)
           input-sm  (cond->> (rx/to-observable input-sb)
                       (or (fn? resolve) (ifn? resolve))
                       (rx/map #(if (event-ref? %)
                                  (resolve (:type %) (:params %))
                                  %)))

           input-sm  (rx/share input-sm)
           state-sb  (rx/behavior-subject state)

           update-sm (->> (rx/filter update? input-sm)
                          (rx/scan #(process-update input-sm %1 %2) state)
                          (rx/catch handle-error))
           watch-sm  (->> (rx/filter watch? input-sm)
                          (rx/with-latest vector state-sb)
                          (rx/flat-map #(process-watch input-sm %))
                          (rx/catch handle-error))
           effect-sm (->> (rx/filter effect? input-sm)
                          (rx/with-latest vector state-sb)
                          (rx/do #(handle-effect input-sm %))
                          (rx/catch handle-error))

           subs (rx/subscribe-with update-sm state-sb)
           subw (rx/subscribe-with watch-sm input-sb)
           sube (rx/subscribe effect-sm noop)]

       (specify! state-sb
         Store
         (-push [_ event]
           (rx/push! input-sb event))

         (-input-stream [_]
           input-sm)

         rx/IDisposable
         (-dispose [_]
           (rx/dispose! subs)
           (rx/dispose! subw)
           (rx/dispose! sube)
           (rx/end! input-sb)))))))

(defn event
  ([type]
   (EventRef. type {}))
  ([type params]
   (EventRef. type params)))

(defn emit!
  "Emits an event or a collection of them into the default store.

  If you have instanciated your own store, this function provides
  2-arity that allows specify a user defined store."
  ([store event]
   (-push store event))
  ([store event & more]
   (run! (partial -push store) (cons event more))))

(defn input-stream
  "Returns the internal input stream of the store. Should
  be used by third party integration that want use store
  as event bus not only with defined events."
  [store]
  (-input-stream store))

(defn data-event
  "Creates an event instance that only contains data."
  ([t] (data-event t nil))
  ([t d]
   (reify t
     IDeref
     (-deref [_] d))))

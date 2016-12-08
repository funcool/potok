;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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
  (:refer-clojure :exclude [update])
  (:require [beicon.core :as rx]))

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

;; An abstraction used for send data to the store.

(defprotocol Store
  (^:private -push [_ event] "Push event into the stream."))

;; --- Predicates

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

;; --- Implementation Details

(extend-protocol UpdateEvent
  function
  (update [func state]
    (func state)))

(defn- default-error-handler
  [error]
  (js/console.warn "Using default error handler, consider using your own!")
  (js/console.error error)
  (rx/throw error))

(enable-console-print!)

;; --- Public API

(defn store
  "Start a new store.

  This function initializes a new event processing stream
  loop and returns a bi-directional rx stream that should
  be used to push new events and subscribe to state changes.

  You probably should not be using this function directly,
  because a default store is already instanciated
  under the `stream` var. This function is indented to be
  used for advanced use cases, where one store is not
  enough."
  ([] (store nil))
  ([{:keys [on-error state]
     :or {on-error default-error-handler}}]
   {:pre [(fn? on-error)]}
   (let [bus (rx/bus)
         state-s  (->> (rx/filter update? bus)
                       (rx/scan #(update %2 %1) state)
                       (rx/merge (rx/just state))
                       (rx/catch on-error)
                       (rx/retry 1024)
                       (rx/share))
         watch-s  (->> (rx/filter watch? bus)
                       (rx/with-latest-from vector state-s)
                       (rx/flat-map (fn [[event state]] (watch event state bus)))
                       (rx/catch on-error)
                       (rx/retry 1024))
         effect-s (->> (rx/filter effect? bus)
                       (rx/with-latest-from vector state-s))
         subw (rx/on-value watch-s #(rx/push! bus %))
         sube (rx/on-value effect-s (fn [[event state]] (effect event state bus)))
         stoped? (volatile! false)]
     (specify! state-s
       Store
       (-push [_ event]
         (when @stoped? (throw (ex-info "store already terminated" {})))
         (rx/push! bus event))

       Object
       (close [_]
         (vreset! stoped? true)
         (.unsubscribe subw)
         (.unsubscribe sube)
         (rx/end! bus))))))

(defn emit!
  "Emits an event or a collection of them into the default store.

  If you have instanciated your own store, this function provides
  2-arity that allows specify a user defined store."
  ([store event]
   {:pre [(satisfies? Store store)]}
   (-push store event))
  ([store event & more]
   {:pre [(satisfies? Store store)]}
   (run! (partial -push store) (cons event more))))

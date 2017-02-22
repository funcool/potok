;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
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
  (^:private -push [_ event] "Push event into the stream.")
  (^:private -input-stream [_] "Returns the internal input stream/subject."))

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

(def ^:private noop (constantly nil))
(def ^:private max-safe-integer js/Number.MAX_SAFE_INTEGER)

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
  ([{:keys [on-error state] :or {on-error default-error-handler}}]
   {:pre [(fn? on-error)]}
   (letfn [(process-watch [input-sub [event state]]
             (watch event state input-sub))
           (process-update [input-sub state event]
             (update event state))
           (handle-effect [input-sub [event state]]
             (effect event state input-sub))
           (handle-error [error]
             (on-error error)
             (rx/throw error))]
     (let [input-sb (rx/subject)
           state-sb (rx/behavior-subject state)
           state-sm (->> (rx/filter update? input-sb)
                         (rx/scan (partial process-update input-sb) state)
                         (rx/catch handle-error)
                         (rx/retry max-safe-integer))
           watch-sm (->> (rx/filter watch? input-sb)
                         (rx/with-latest vector state-sb)
                         (rx/flat-map (partial process-watch input-sb))
                         (rx/catch handle-error)
                         (rx/retry max-safe-integer))
           effct-sm (->> (rx/filter effect? input-sb)
                         (rx/with-latest vector state-sb)
                         (rx/do (partial handle-effect input-sb))
                         (rx/catch handle-error)
                         (rx/retry max-safe-integer))
           subs     (rx/subscribe-with state-sm state-sb)
           subw     (rx/subscribe-with watch-sm input-sb)
           sube     (rx/subscribe effct-sm noop)
           stoped?  (volatile! false)]
       (specify! state-sb
         Store
         (-push [_ event]
           (when @stoped? (throw (ex-info "store already terminated" {})))
           (rx/push! input-sb event))

         (-input-stream [_]
           (->> input-sb
                (rx/map identity)
                (rx/share)))

         rx/ICancellable
         (-cancel [_]
           (vreset! stoped? true)
           (rx/cancel! subs)
           (rx/cancel! subw)
           (rx/cancel! sube)
           (rx/end! input-sb)))))))

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

(defn input-stream
  "Returns the internal input stream of the store. Should
  be used by third party integration that want use store
  as event bus not only with defined events."
  [store]
  {:pre [(satisfies? Store store)]}
  (-input-stream store))

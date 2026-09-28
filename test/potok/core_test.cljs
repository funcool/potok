(ns potok.core-test
  (:require
   [cljs.test :as t]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(enable-console-print!)

(defrecord IncrementBy [n]
  ptk/UpdateEvent
  (update [_ state]
    (update state :counter + n)))

(defrecord AsyncIncrementBy [n]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (->IncrementBy n))))

(t/deftest synchronous-state-transformation-test
  (t/async done
    (let [store  (ptk/store {:state {:counter 0}})]
      (add-watch store "test" (fn [_ _ _ state]
                                (t/is (= 1 (:counter state)))
                                (remove-watch store "test")
                                (done)))
      (ptk/emit! store (->IncrementBy 1)))))


(t/deftest asynchronous-state-transformation-test
  (t/async done
    (let [store (ptk/store {:state {:counter 0}})]
      (add-watch store "test" (fn [_ _ _ state]
                                (t/is (= 2 (:counter state)))
                                (remove-watch store "test")
                                (done)))
      (ptk/emit! store (->AsyncIncrementBy 2)))))

(t/deftest data-only-events
  (let [event (ptk/data-event ::foobar {:some "data"})]
    (t/is (ptk/type? ::foobar event))
    (t/is (= {:some "data"} @event))))

(def ^:private current-store (atom nil))
(def ^:private seen (atom []))

(defn- in-flight-types
  []
  (mapv type (ptk/in-flight-events @current-store)))

(defrecord RecordInFlight []
  ptk/UpdateEvent
  (update [_ state]
    (swap! seen conj (in-flight-types))
    state))

(defrecord EmitRecord []
  ptk/WatchEvent
  (watch [_ _ _]
    (rx/of (->RecordInFlight))))

(defrecord EmitRecordLater []
  ptk/WatchEvent
  (watch [_ _ _]
    (->> (rx/of (->RecordInFlight))
         (rx/delay 10))))

(defrecord Fail []
  ptk/UpdateEvent
  (update [_ _]
    (throw (js/Error. "boom"))))

(defn- setup-store
  ([] (setup-store {}))
  ([params]
   (reset! seen [])
   (reset! current-store (ptk/store (merge {:state {}} params)))))

(t/deftest in-flight-events-during-update
  (let [store (setup-store)]
    (ptk/emit! store (->RecordInFlight))
    (t/is (= [[RecordInFlight]] @seen))))

(t/deftest in-flight-events-include-the-emitting-event
  (let [store (setup-store)]
    (ptk/emit! store (->EmitRecord))
    (t/is (= [[EmitRecord RecordInFlight]] @seen))))

(t/deftest in-flight-events-do-not-nest-async-results
  (t/async done
    (let [store (setup-store)]
      (ptk/emit! store (->EmitRecordLater))
      (js/setTimeout
       (fn []
         (t/is (= [[RecordInFlight]] @seen))
         (done))
       50))))

(t/deftest in-flight-events-seen-by-on-error
  (let [store (setup-store {:on-error (fn [_] (swap! seen conj (in-flight-types)) nil)})]
    (ptk/emit! store (->Fail))
    (t/is (= [[Fail]] @seen))))

(t/deftest in-flight-events-empty-after-processing
  (let [store (setup-store)]
    (ptk/emit! store (->EmitRecord))
    (ptk/emit! store (->Fail))
    (t/is (= [] (ptk/in-flight-events store)))))

;; (set! *main-cli-fn* #(t/run-tests))

;; (defmethod t/report [:cljs.test/default :end-run-tests]
;;   [m]
;;   (if (t/successful? m)
;;     (set! (.-exitCode js/process) 0)
;;     (set! (.-exitCode js/process) 1)))

# User Guide

## Introduction

Potok is a tiny (100LOC) reactive streams based state management toolkit for
ClojureScript.

Potok lies on top of two concepts: *events* and *reactive store*.


## Install

Just add this to your dependencies:

```clojure
funcool/potok2
{:git/tag "v2.0"
 :git/sha "2bb377b"
 :git/url "https://github.com/funcool/potok.git"}
```

## Getting Started

### Events

The events are entities that your application will emit in order to send data or
action to the store. They will be emitted using `potok.v2.core/emit!` function.
There are three types of events:

- *update*: that represents a synchronous state transformation.
- *watch*: that represents an asynchronous operation.
- *effect*: that represents a side effectful operation.

Let's see a detailed explanation of each event type:

#### Update Event

The *update* event represents the simple synchronous state
transformation. It just consists of a type defined using *defrecord*,
builtin `reify` or the potok provided `reify` helper that already
implements some additional protocols.

The `update` function receives the current state as argument and
should return the transformed state. Let's see an example:

```clojure
(require '[potok.v2.core :as ptk])

(defn increment
  []
  (ptk/reify ::increment
    ptk/UpdateEvent
    (update [_ state]
      (update state :counter (fnil inc 0)))))

;; or

(defrecord Increment []
  ptk/UpdateEvent
  (update [_ state]
    (update state :counter (fnil inc 0))))

```

The `::increment` keyword is a type tag that can be used later to
filter events using the `ptk/type?` higher-order predicate `ptk/type`
to obtain the type of the instance.

You may be thinking, the signature of the `update` function is very
similar to a reduce function. And in fact, it does just that the state
reduction, and it can be defined using a plain ClojureScript function:

```clojure
(defn increment
  [state]
  (update state :counter (fnil inc 0))
```

#### Watch Event

Apart from the simple state transformations, applications usually need
to perform asynchronous operations such as call remote API, access
local database, etc. This is where the *watch* events play their
role. They are designed to handle asynchronous operations.

Let's see how it looks:

```clojure
(require '[beicon.core :as rx])

(defn delayed-increment-by
  [n]
  (ptk/reify ::delayed-increment-by
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of (increment-by n)) ; create a instance of `Increment` event
           (rx/delay 100))))          ; delay the stream for 100ms
```

The responsibility of the `watch` function is to perform an
asynchronous operation and return a stream of one or more events. In
the example, you can observe, that it just returns a stream of one
`::increment` event instance delayed 100 milliseconds (thus emulating
some latency).

That stream will be re-injected into the main stream and those events
will be processed in the same way as if you emitted them with
`potok.v2.core/emit!` function.

The additional `stream` parameter to the `watch` function represents
the main stream where all events will arrive, so you can build logic
needed for a synchronization with other events or just a handling of
some kind of a cancellation.


#### Effect Event

The *effect* event represents a side effectfull action. In the same
way as the *watch* event, it receives the current state and the main
stream as arguments.

Let's see how it look:

```clojure
(defn notify
  [title message]
  (ptk/reify ::notify
    ptk/EffectEvent
    (effect [_ state stream]
      (let [params #js {:body message}]
        (js/Notification. title params)))))
```

The return value of the `effect` function is completely ignored.


### Store

In the previous section we have seen events, the *store* is the object
that processes them. It has the following responsibilities:

- Hold the application state.
- Process incoming events.
- Emit the changes using reactive streams.

To create store you just need to execute the `potok.v2.core/store`
function:

```clojure
(def store (ptk/store))
```

If no arguments is passed to `store` function, the initial state is
initialized as `nil`. This is how you can provide an initial state:

```clojure
(def store (ptk/store {:state {:counter 0}}))
```

The `store` object from the user perspective is an atom that implements
RX Subject interface. The atom interface allows synchronous acces to the
latest state and the Subject interface allow emits events into.

You can emit events into the store using the `ptk/emit!` or
`beicon.core/push!` functions:

```clojure
(ptk/emit! store (increment-by 1))
```

Now if you deref(erence) the state, you will see it transformed:


```clojure
@store
;; => {:counter 1}
```

### Error Handling

In many circumstances we found, that exception is raised inside the
event. For this case *potok* comes with the built-in mechanism for
handling errors.

Let's see some code:

```clojure
(defn- on-error
  [error]
  (js/console.error error))

(def store (ptk/store {:on-error on-error}))
```

Now, if an exception is raised inside an event it will report it to
this function. The return value of on-error callback is ignored with exception
of `watch` event, where the error handler can return an observable.


## Developers Guide

### Philosophy

Five most important rules:

- Beautiful is better than ugly.
- Explicit is better than implicit.
- Simple is better than complex.
- Complex is better than complicated.
- Readability counts.

All contributions to _potok_ should keep these important rules in mind.


### Contributing

Unlike Clojure and other Clojure contributed libraries _potok_ does not have many
restrictions on contributions. Just open an issue or pull request.


### Source Code

_potok_ is open source and can be found on
link:https://github.com/funcool/potok[github].

You can clone the public repository with this command:

```bash
git clone https://github.com/funcool/potok
```


## FAQ

### What is the motivation behind *potok*?

My main motivation is just to simplify a number of concepts that user needs to
learn in order to use one-way-flow state management. Reactive streams fit very
well this purpose, so I decided not to reinvent the wheel and just use them (in
contrast to re-frame or redux as an example).

*Potok* has very small amount of the code and can be understood and maintained
by almost anyone which makes the decision to include it in the production,
without the fear of this library becomes unmaintained, easier.

It is just 100 lines of the pretty well-commented code.


### Can I implement more than one event protocol at the same time?

Yes, in fact, it is a very useful approach to performing optimistic
updates, because the *update* event is always the first processed and
the *watch* and *effect* events will receive the state already
transformed by the `update` function.


### How can I use *potok* with React.js based web applications?

Very easy, once you have materialized the state into an atom, you can
consume this atom from any react based toolkit (*rumext*, *reagent*,
etc) in the same way, as you will consume a plain atom with the state.

The unique difference is that if you want to perform a state transformation, you
need to define and emit an event for it, instead of direct state atom's
transformation.


###  Are there some real applications using this pattern?

Yes, many of them are private, but there is one public:
link:https://github.com/penpot/penpot[penpot]. It is the pretty big
project and it demonstrates that this approach scales very well.

Also, there are some open source projects not connected to the funcool
organization:

- link:https://github.com/pepe/potok-rumu[potok-rumu] - just example project
  with the simple structure, for showing the potok capabilities. It also uses rum
  for rendering.
- link:https://github.com/pepe/showrum[showrum] - presentation software, which
  uses potok for state management
- link:https://github.com/LastStar/proud[proud] - highly opinionated boot
  template for generating new projects with potok and rum setup


## License


_potok_ is licensed under BSD (2-Clause) license:

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright (c) Andrey Antukh <niwi@niwi.nz>
```

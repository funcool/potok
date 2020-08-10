# Changelog #

## Version 2020.08.10-1 ##

- Revert dispatching in microtask the `watch` and `effect` because it breaks
  the contract that watch is executed just after update.
- Update to beicon 2020.05.08-2
- Change version numering.
- Add `event-ref?` predicate.
- Add `resolve` mechanism (indirection for reference events by keywords and resolve
  they using a multimethod or any other mechanism).


## Version 2.7.0 ##

Date: 2019-12-04

- Add `data-event` constructor (useful for creating add-hoc data-only
  events).
- Add `type?` predicate.


## Version 2.6.0 ##

Date: 2019-09-20

- Dispatch `watch` and `effect` observation on a microtask.
- Add `event?` predicate.
- Rename `EventType` protocol to `Event` (breaking change).
- Add `potoc.core/reify` macro for define events with associated type;
  using the `Event` protocol.


## Version 2.5.0 ##

Date: 2019-08-26

- Add facilities for attach type to events defined with `reify`.


## Version 2.4.0 ##

Date: 2019-08-20

- Update beicon to 5.1.0
- Convert project to cli tools.
- Improve error handling.
- Minor performance improvements.

## Version 2.3.0 ##

Date: 2017-11-17

- Update beicon to 4.1.0
- Update cljs compiler to 1.9.946


## Version 2.2.0 ##

Date: 2017-08-03

- Update beicon to 4.0.0
- Update cljs compiler to 1.9.854


## Version 2.1.0 ##

Date: 2017-03-14

- Update to beicon 3.2.0
- Update cljs compiler to 1.9.495


## Version 2.0.0 ##

Date: 2017-02-22

- Update to beicon 3.1.0.
- Store now implement `beicon.core/ICancellable` protocol
  instead of plain `.close` method.
- Set error retry count to `Number.MAX_SAFE_INTEGER`
  instead of small `1024` number.
- Remove `enable-console-print!`.


## Version 1.2.0 ##

Date: 2017-01-12

- Add the ability to retrieve the input stream from the store.


## Version 1.1.0 ##

Date: 2016-12-08

- Improve error recovery of store internal streams.
- The store now is behavior-subject and always returns the last
  state on new subscription.


## Version 1.0.0 ##

Date: 2016-11-27

- Initial release.

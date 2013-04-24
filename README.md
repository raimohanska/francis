## Francis

A straightforward Scala port for Bacon.js.

Done so far:

- Bacon.once, fromList, fromValues, sequentially
- Bus: a stream you can push() events into
- EventStream.map, filter, merge, flatMap, delay
- Nice multithreading: only the Dispatcher class has to deal with it. All combinators are single-threaded.
- New thread per stream by default (maybe not the best default)
- Specs2 tests for everything

Next up:

- Property
- Property.combine
- Smarter multithreading: configurable ExecutorService with nice defaults?


## Francis

A straightforward Scala port for Bacon.js.

Done so far:

- Bacon.once, fromList, fromValues, sequentially
- EventStream.map, filter, merge, flatMap, delay
- Naive multithreading: start new Timer for each Stream
- Nice multithreading: only the Dispatcher class has to deal with it. All combinators are single-threaded.

Next up:

- Property
- Property.combine
- Smarter multithreading: configurable ExecutorService with nice defaults?


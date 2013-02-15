package bacon

import org.specs2.mutable._

import Bacon._

class BaconSpec extends Specification {
  sequential

  "Bacon.once" should {
    "produce one value" in {
      expectStreamEvents(Bacon.once("bacon"), "bacon")
    }
  }
  "Bacon.fromValues" should {
    "produce values from list" in {
      expectStreamEvents(Bacon.fromValues(1, 2, 3), 1, 2, 3)
    }
  }
  "Bacon.later" should {
    "produce one value" in {
      expectStreamEvents(Bacon.later(T(1), "hello"), "hello")
    }
  }
  "Bacon.sequentially" should {
    "produce a list of values" in {
      expectStreamEvents(series(1, List(1,2,3)), 1,2,3)
    }
  }
  "EventStream.map" should {
    "map values using given function" in {
      expectStreamEvents(series(1, List(1,2,3)).map(_ * 10), 10, 20, 30)
    }
  }
  "EventStream.filter" should {
    "filter values using given function" in {
      expectStreamEvents(series(1, List(1,2,3,4,5)).filter(_ % 2 == 0), 2, 4)
    }
  }
  "EventStream.merge" should {
    "merge values from two streams" in {
      expectStreamEvents(
        series(2, List("a", "b")).merge(series(3, List("x", "y"))),
        "a", "x", "b", "y")
    }
  }
  "EventStream.flatMap" should {
    "spawn new streams and collect results" in {
      expectStreamEvents(
        series(1, List(1,2)).flatMap {
          value => series(2, List(value, value))
        },
        1, 2, 1, 2
      )
      expectStreamEvents(
        series(3, List(1,2)).flatMap {
          value => series(1, List(value, value))
        },
        1, 1, 2, 2
      )
    }
  }
  "EventStream.delay" should {
    "delay each event by given number of millis" in {
      expectStreamEvents(
        { val left = series(2, List(1, 2, 3))
          val right = series(1, List(4, 5, 6)).delay(T(6))
          left.merge(right)
        },
        1, 2, 3, 4, 5, 6)
    }
  }

  val unitTime = 10
  def T(units: Int): Int = units * unitTime

  def series[T](delay: Int, values: List[T]) = {
    val stream = Bacon.sequentially(T(delay), values)
    seqs = stream :: seqs
    stream
  }
  var seqs: List[EventStream[_]] = Nil

  def expectStreamEvents[T](src: => EventStream[T], expectedValues: T*) = {
    verifySingleObserver(src, expectedValues : _*)
    verifySwitching(src, expectedValues : _*)
  }

  def verifySingleObserver[T](src: => EventStream[T], expectedValues: T*) = {
    val stream = src
    val values = drain(stream)
    values must_== expectedValues
    verifyExhausted(stream)
    verifyCleanup
  }

  def verifySwitching[T](src: => EventStream[T], expectedValues: T*) = {
    val stream = src
    var vs: List[T] = Nil
    var result = new MVar[List[T]]
    def newObserver: Observer[T] = {
      case End()   => result.put(vs)
                      false
      case Next(v) => vs = vs :+ v
                      stream.subscribe(newObserver)
                      false
      case _ => throw new IllegalArgumentException()
    }
    stream.subscribe(newObserver)
    val values = result.take
    values must_== expectedValues
    verifyExhausted(stream)
    verifyCleanup
  }

  def verifyExhausted[T](src: Observable[T]) = {
    drain(src) must_== Nil
  }

  def verifyCleanup = {
    try {
      seqs.foreach { seq => seq.hasObservers must_== false }
    } finally {
      seqs = Nil
    }
    success
  }

  def drain[T](stream: Observable[T]): List[T] = {
    var vs: List[T] = Nil
    var result = new MVar[List[T]]
    stream.subscribe {
      case End() => result.put(vs)
                    false
      case Next(v) => vs = vs :+ v
                      true
      case _ => throw new IllegalArgumentException()
    }
    result.take
  }

  class MVar[T] {
    import java.util.concurrent._
    private val queue = new ArrayBlockingQueue[T](1)
    def put(value: T) { queue.put(value) }
    def take: T = { 
      val result = queue.poll(unitTime * 100, TimeUnit.MILLISECONDS) 
      if (result == null) throw new IllegalArgumentException("stream did not end")
      result
    }
  }
}

import org.specs2.mutable._

import Bacon._

class BaconSpec extends Specification {
  "Bacon.once" should {
    "produce one value" in {
      expectStreamEvents(() => Bacon.once("bacon"), "bacon")
    }
  }
  "Bacon.fromValues" should {
    "produce values from list" in {
      expectStreamEvents(() => Bacon.fromValues(1, 2, 3), 1, 2, 3)
    }
  }
  "Bacon.later" should {
    "produce one value" in {
      expectStreamEvents(() => Bacon.later(T(1), "hello"), "hello")
    }
  }
  "Bacon.sequentially" should {
    "produce a list of values" in {
      expectStreamEvents(() => Bacon.sequentially(T(1), List(1,2,3)), 1,2,3)
    }
  }
  "EventStream.map" should {
    "map values using given function" in {
      expectStreamEvents(() => Bacon.sequentially(T(1), List(1,2,3)).map(_ * 10), 10, 20, 30)
    }
  }

  val unitTime = 10
  def T(units: Int): Int = units * unitTime

  def expectStreamEvents[T](src: () => EventStream[T], expectedValues: T*) = {
    verifySingleObserver(src, expectedValues : _*)
    verifySwitching(src, expectedValues : _*)
  }

  def verifySingleObserver[T](src: () => EventStream[T], expectedValues: T*) = {
    val stream = src()
    val values = drain(stream)
    values must_== expectedValues
    verifyExhausted(stream)
  }

  def verifySwitching[T](src: () => EventStream[T], expectedValues: T*) = {
    val stream = src()
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
  }

  def verifyExhausted[T](src: Observable[T]) = {
    drain(src) must_== Nil
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
}

class MVar[T] {
  import java.util.concurrent._
  private val queue = new ArrayBlockingQueue[T](1)
  def put(value: T) { queue.put(value) }
  def take: T = { 
    val result = queue.poll(1, TimeUnit.SECONDS) 
    if (result == null) throw new IllegalArgumentException("stream did not end")
    result
  }
}

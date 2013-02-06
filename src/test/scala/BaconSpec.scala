import org.specs2.mutable._

import Bacon._

class BaconSpec extends Specification {
  "Bacon.once" should {
    "produce one value" in {
      expectStreamEvents(Bacon.once("bacon"), "bacon")
    }
  }

  def expectStreamEvents[T](stream: EventStream[T], expectedValues: T*) = {
    var vs: List[T] = Nil
    var end = new MVar[List[T]]
    stream.subscribe {
      case End() => end.put(vs)
                    false
      case Next(v) => vs = vs :+ v
                      true
      case _ => throw new IllegalArgumentException()
    }
    val values = end.take
    values must_== expectedValues
  }
}

class MVar[T] {
  import java.util.concurrent._
  private val queue = new ArrayBlockingQueue[T](1)
  def put(value: T) { queue.put(value) }
  def take: T = { queue.poll(1, TimeUnit.SECONDS) }
}

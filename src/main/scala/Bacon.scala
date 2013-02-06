object Bacon {
  def once[T](value: T): EventStream[T] = fromList(List(value))
  def fromList[T](values: Seq[T]): EventStream[T] = new EventStream[T]({
    observer: Observer[T] => {
      values.foreach { value => observer(Next(value)) }
      observer(End())
      nop
    }
  })
  def fromValues[T](values: T*): EventStream[T] = fromList(values)

  trait Observable[T] {
    def subscribe(obs: Observer[T]): Dispose
  }
  class EventStream[T](subscribeFunc: (Observer[T] => Dispose)) extends Observable[T] {
    private val dispatcher = new Dispatcher[T](subscribeFunc)
    def subscribe(obs: Observer[T]) = dispatcher.subscribe(obs)
  }

  sealed trait Event[T] {
    def hasValue: Boolean = false
    def isEnd: Boolean = false
    def isNext: Boolean = false
  }

  trait ValueEvent[T] extends Event[T] {
    override def hasValue = true
    def value: T
  }

  case class Next[T](value: T) extends ValueEvent[T] {
    override def isNext = true
  }

  case class End[T] extends Event[T] {
    override def isEnd = true
  }

  type Observer[T] = (Event[T] => Boolean)
  type Dispose = (() => Unit)

  class Dispatcher[T](subscribeFunc: (Observer[T] => Dispose)) {
    private var unsubFromSrc: Option[Dispose] = None
    private var observers: List[Observer[T]] = Nil
    private var ended = false

    def subscribe(obs: Observer[T]): Dispose = {
      // TODO: queue these
      if (ended) {
        obs(End())
        nop
      } else {
        observers = observers :+ obs
        if (observers.length == 1) {
            unsubFromSrc = Some(subscribeFunc(handleEvent))
        }
        val unsubThis: Dispose = () => {
          removeObserver(obs)
          checkUnsub
        }
        unsubThis
      }
    }
    private def removeObserver(o: Observer[T]) {
      observers = observers.filterNot(_ == o)
    }
    private def handleEvent(event: Event[T]) = {
      if (event.isEnd) ended = true
      observers.foreach { obs =>
        val continue = obs(event)
        if (!continue) removeObserver(obs)
      }
      if (event.isEnd) observers = Nil
      !observers.isEmpty
    }
    private def checkUnsub = (observers.length, unsubFromSrc) match {
      case (1, Some(f)) => f
      case _            =>
    }
  }

  object Scheduler {
    private val timer = new java.util.Timer()
    def delay(delay: Long)(block: => Unit) {
      timer.schedule(new java.util.TimerTask {
        def run = block
      }, delay)
    }
  }

  val nop: Dispose = () => {}
}

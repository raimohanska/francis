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
  def later[T](delay: Long, value: T) = sequentially(delay, value :: Nil)
  def sequentially[T](delay: Long, values: List[T]): EventStream[T] = {
    var index = -1
    def poll: Event[T] = {
      index = index + 1
      if (index < values.length)
        Next(values(index))
      else
        End()
    }
    fromPoll(delay, () => poll)
  }
  def fromPoll[T](delay: Long, poll: (() => Event[T])) = {
    new EventStream[T]({
      dispatcher: Observer[T] => {
        val ended = new Flag
        def schedule {
          Scheduler.delay(delay) {
            if (!ended.get) {
              val event = poll()
              val continue = dispatcher(event)
              if (continue && !event.isEnd) {
                schedule
              }
            }
          }
        }
        schedule
        () => { ended.set(true) }
      }
    })
  }

  type Handler[A,B] = ((Event[A], (Event[B] => Boolean)) => Boolean)

  trait Observable[A] {
    def subscribe(obs: Observer[A]): Dispose
    def withHandler[B](handler: Handler[A, B]): EventStream[B]
  }
  class EventStream[A](subscribeFunc: (Observer[A] => Dispose)) extends Observable[A] {
    private val dispatcher = new Dispatcher[A, A](subscribeFunc, { (event: Event[A], push: (Event[A] => Boolean)) => push(event)})
    def subscribe(obs: Observer[A]) = dispatcher.subscribe(obs)

    def withHandler[B](handler: Handler[A, B]): EventStream[B] = {
      val dispatcher = new Dispatcher[A, B]({ o: Observer[A] => this.subscribe(o)}, handler)
      new EventStream({ o: Observer[B] => dispatcher.subscribe(o) })
    }
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

  class Dispatcher[A, B](subscribeFunc: (Observer[A] => Dispose), handler: Handler[A, B]) {
    private var unsubFromSrc: Option[Dispose] = None
    private var observers: List[Observer[B]] = Nil
    private var ended = false
    private var eventQueue: Queue[Event[B]] = new Queue(1000)
    private var commandQueue: Queue[() => Unit] = new Queue(1000)

    def subscribe(obs: Observer[B]): Dispose = {
      queued {
        if (ended) {
          obs(End())
        } else {
          observers = observers :+ obs
          if (observers.length == 1) {
              unsubFromSrc = Some(subscribeFunc(handleSourceEvent))
          }
        }
      }

      () => queued {
        removeObserver(obs)
        checkUnsub
      }
    }
    private def handleSourceEvent(event: Event[A]) = {
      handler(event, push)
    }
    private def queued(block: => Unit) {
      commandQueue.add(() => block)
      scheduleProcessing
    }
    private def removeObserver(o: Observer[B]) {
      observers = observers.filterNot(_ == o)
    }
    def push(event: Event[B]): Boolean = {
      eventQueue.add(event)
      scheduleProcessing
      true
    }
    private def checkUnsub = (observers.length, unsubFromSrc) match {
      case (1, Some(f)) => f
      case _            =>
    }
    private def scheduleProcessing {
      Scheduler.delay(0) {
        while (!commandQueue.isEmpty || !eventQueue.isEmpty) {
          if (!commandQueue.isEmpty) {
            val task = commandQueue.poll()
            task()
          } else {
            val event = eventQueue.poll()
            if (event.isEnd) ended = true
            observers.foreach { obs =>
              val continue = obs(event)
              if (!continue) removeObserver(obs)
            }
            if (event.isEnd) observers = Nil
            checkUnsub
          }
        }
      }
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

  type Flag = java.util.concurrent.atomic.AtomicBoolean
  type Queue[T] = java.util.concurrent.ArrayBlockingQueue[T]

  val nop: Dispose = () => {}
}

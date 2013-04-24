package bacon

object Bacon {
  def once[T](value: T): EventStream[T] = fromList(List(value))
  def fromList[T](values: Seq[T]): EventStream[T] = new EventStreamWithDispatcher[T]({
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
    fromPoll(delay, poll)
  }
  def fromPoll[T](delay: Long, poll: => Event[T], scheduler: Scheduler = Scheduler.newScheduler) = {
    var nextEvent = System.currentTimeMillis + delay
    new EventStreamWithDispatcher[T]({
      dispatcher: Observer[T] => {
        val ended = new Flag
        def schedule {
          val timeToNext = math.max(nextEvent - System.currentTimeMillis, 0)
          scheduler.queue(timeToNext) {
            if (!ended.get) {
              val event = poll
              val continue = dispatcher(event)
              if (continue && !event.isEnd) {
                nextEvent = System.currentTimeMillis + delay
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

  type Handler[A,B] = ((Event[A], (Event[B] => WantMore)) => WantMore)

  trait Observable[A] {
    protected[bacon] def scheduler: Scheduler
    def subscribe(obs: Observer[A]): Dispose
    def withHandler[B](handler: Handler[A, B], scheduler: Scheduler = this.scheduler): EventStream[B]
    def onValue(callback: (A => Any)): Dispose = subscribe {
      case Next(a) => callback(a); true
      case _ => true
    }
  }

  class Bus[A](scheduler: Scheduler = Scheduler.newScheduler) extends EventStream[A](scheduler) {
    private def subscribeInternal(observer: Observer[A]): Dispose = {
      nop
    }
    protected val dispatcher = new Dispatcher[A, A](
      subscribeInternal, 
      { (event: Event[A], push: (Event[A] => WantMore)) => push(event)}, 
      scheduler)

    def push(value: A) = {
      dispatcher.push(Next(value))
    }
  }

  class EventStreamWithDispatcher[A](subscribeFunc: (Observer[A] => Dispose),
                                     scheduler: Scheduler = Scheduler.newScheduler)
                                     extends EventStream[A](scheduler) {
    protected val dispatcher = new Dispatcher[A, A](subscribeFunc, { (event: Event[A], push: (Event[A] => WantMore)) => push(event)}, scheduler)
  }

  abstract class EventStream[A](protected[bacon] val scheduler: Scheduler) extends Observable[A] {
    def subscribe(obs: Observer[A]): Dispose = dispatcher.subscribe(obs)
    protected def dispatcher: Dispatcher[A, A]

    def withHandler[B](handler: Handler[A, B], scheduler: Scheduler = this.scheduler): EventStream[B] = {
      val dispatcher = new Dispatcher[A, B]({ o: Observer[A] => this.subscribe(o)}, handler)
      new EventStreamWithDispatcher({ o: Observer[B] => dispatcher.subscribe(o) }, scheduler)
    }

    def map[B](f: (A => B)): EventStream[B] = withHandler(Handlers.map(f))

    def filter(f: (A => Boolean)): EventStream[A] = withHandler(Handlers.filter(f))

    def withScheduler(scheduler: Scheduler): EventStream[A] = {
      if (scheduler == this.scheduler) this else withHandler((event, push) => push(event), scheduler)
    }
    def merge(other: EventStream[A]) = {
      val left = this
      val right = other.withScheduler(this.scheduler)
      new EventStreamWithDispatcher[A]({
        observer: Observer[A] => {
          var unsubLeft = nop
          var unsubRight = nop
          var unsubscribed = false
          def unsubBoth {
            unsubLeft()
            unsubRight()
            unsubscribed = true
          }
          var ends = 0
          def handleEvent(event: Event[A]) = {
            if (event.isEnd) {
              ends = ends + 1
              if (ends == 2) {
                observer(End())
              } else {
                true
              }
            } else {
              val continue = observer(event)
              if (!continue) unsubBoth
              continue
            }
          }
          unsubLeft = left.subscribe(handleEvent)
          if (!unsubscribed) unsubRight = right.subscribe(handleEvent)
          () => unsubBoth
        }
      })
    }

    def flatMap[B](f: (A => EventStream[B])) = {
      val scheduler = Scheduler.newScheduler
      val root = this.withScheduler(scheduler)
      new EventStreamWithDispatcher[B]({ observer: Observer[B] => 
        var children: List[Dispose] = Nil
        var rootEnd = false
        var unsubRoot = nop
        def unbind {
          unsubRoot()
          children.foreach(_())
          children = Nil
        }
        def checkEnd {
          if (rootEnd && children.isEmpty)
            observer(End())
        }
        def spawn(event: Event[A]): WantMore = event match {
          case End() => 
            rootEnd = true
            checkEnd
            false
          case Next(value) =>
            val child = f(value).withScheduler(scheduler)
            var unsubChild: Option[Dispose] = None
            var childEnded = false
            def removeChild {
              unsubChild.foreach { f => children = remove(f, children) }
              checkEnd
            }
            def handle(event: Event[B]): WantMore = event match {
              case End() =>
                removeChild
                childEnded = true
                false
              case e@Next(value) =>
                val continue = observer(e)
                if (!continue) {
                  unbind
                }
                continue
            }
            val unsub = child.subscribe(handle)
            unsubChild = Some(unsub)
            if (!childEnded) children = children :+ unsub
            true
        }
        unsubRoot = root.subscribe(spawn)
        () => unbind
      })
    }

    def delay(millis: Int): EventStream[A] = {
      flatMap { value => later(millis, value) }
    }

    protected[bacon] def hasObservers = dispatcher.hasObservers
  }

  sealed trait Event[A] {
    def hasValue: Boolean = false
    def isEnd: Boolean = false
    def isNext: Boolean = false
    def fmap[B](f: (A => B)): Event[B]
    def filter(f: (A => Boolean)): Boolean = true
  }

  trait ValueEvent[A] extends Event[A] {
    override def hasValue = true
    def value: A
    override def toString = value.toString
    override def filter(f: (A => Boolean)) = f(value)
  }

  case class Next[A](value: A) extends ValueEvent[A] {
    override def isNext = true
    def fmap[B](f: (A => B)) = Next(f(value))
  }

  case class End[A] extends Event[A] {
    override def isEnd = true
    def fmap[B](f: (A => B)) = End()
    override def toString = "<end>"
  }

  type Observer[A] = (Event[A] => WantMore)
  type Dispose = (() => Unit)
  type WantMore = Boolean
  
  protected [bacon] object Handlers {
    def map[A, B](f: (A => B)): Handler[A, B] = {
      (event, push) => push(event.fmap(f))
    }
    def filter[A](f: (A => Boolean)): Handler[A, A] = {
      (event, push) => {
        if (event.filter(f))
          push(event)
        else
          true
      }
    }
  }

  class Dispatcher[A, B](subscribeFunc: (Observer[A] => Dispose), 
                         handler: Handler[A, B],
                         scheduler: Scheduler = Scheduler.newScheduler) {
    private var unsubFromSrc: Option[Dispose] = None
    private var observers: List[Observer[B]] = Nil
    private var ended = false
    private var eventQueue: Queue[Event[A]] = new Queue(1000)
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

      () => removeObserver(obs)
    }
    private def handleSourceEvent(event: Event[A]) = {
      eventQueue.add(event)
      scheduleProcessing
      true
    }
    private def queued(block: => Unit) {
      commandQueue.add(() => block)
      scheduleProcessing
    }
    private def removeObserver(o: Observer[B]) = queued {
      observers = remove(o, observers)
      checkUnsub
    }
    def push(event: Event[B]): WantMore = {
      observers.foreach { obs =>
        val continue = obs(event)
        if (!continue || event.isEnd) removeObserver(obs)
      }
      true
    }
    protected[bacon] def hasObservers = !observers.isEmpty

    private def checkUnsub = (observers.length, unsubFromSrc) match {
      case (0, Some(f)) => 
        f()
      case _            =>
    }
    private def scheduleProcessing {
      scheduler.queue {
        while (!commandQueue.isEmpty || !eventQueue.isEmpty) {
          if (!commandQueue.isEmpty) {
            val task = commandQueue.poll()
            task()
          } else {
            val event = eventQueue.poll()
            if (event.isEnd) ended = true
            handler(event, push)
          }
        }
      }
    }
  }

  trait Scheduler {
    def queue(block: => Unit)
    def queue(delay: Long)(block: => Unit)
  }

  object Scheduler {
    def newScheduler: Scheduler = new TimerScheduler
  }

  class TimerScheduler extends Scheduler {
    private val timer = new java.util.Timer()

    def queue(block: => Unit) = queue(0)(block)
    def queue(delay: Long)(block: => Unit) {
      timer.schedule(new java.util.TimerTask {
        def run = block
      }, delay)
    }
  }

  type Flag = java.util.concurrent.atomic.AtomicBoolean
  type Queue[T] = java.util.concurrent.ArrayBlockingQueue[T]

  val nop: Dispose = () => {}
  private def remove[T](x: T, xs : List[T]): List[T] = {
    xs.filterNot(_ == x)
  }
}

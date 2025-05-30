/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor

import java.util.UUID.{ randomUUID => newUuid }
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic._

import scala.concurrent.{ Await, Future }

import org.scalatest.BeforeAndAfterEach

import org.apache.pekko
import pekko.actor.Actor._
import pekko.pattern.ask
import pekko.testkit._

object ActorLifeCycleSpec {

  class LifeCycleTestActor(testActor: ActorRef, id: String, generationProvider: AtomicInteger) extends Actor {
    def report(msg: Any) = testActor ! message(msg)
    def message(msg: Any): (Any, String, Int) = (msg, id, currentGen)
    val currentGen = generationProvider.getAndIncrement()
    override def preStart(): Unit = { report("preStart") }
    override def postStop(): Unit = { report("postStop") }
    def receive = { case "status" => sender() ! message("OK") }
  }

  final case class Become(recv: ActorContext => Receive)

}

class ActorLifeCycleSpec extends PekkoSpec with BeforeAndAfterEach with ImplicitSender with DefaultTimeout {
  import ActorLifeCycleSpec._

  "An Actor" must {

    "invoke preRestart, preStart, postRestart when using OneForOneStrategy" in {
      filterException[ActorKilledException] {
        val id = newUuid.toString
        val supervisor =
          system.actorOf(Props(classOf[Supervisor], OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(new LifeCycleTestActor(testActor, id, gen) {
          override def preRestart(reason: Throwable, message: Option[Any]): Unit = { report("preRestart") }
          override def postRestart(reason: Throwable): Unit = { report("postRestart") }
        }).withDeploy(Deploy.local)
        val restarter = Await.result((supervisor ? restarterProps).mapTo[ActorRef], timeout.duration)

        expectMsg(("preStart", id, 0))
        restarter ! Kill
        expectMsg(("preRestart", id, 0))
        expectMsg(("postRestart", id, 1))
        restarter ! "status"
        expectMsg(("OK", id, 1))
        restarter ! Kill
        expectMsg(("preRestart", id, 1))
        expectMsg(("postRestart", id, 2))
        restarter ! "status"
        expectMsg(("OK", id, 2))
        restarter ! Kill
        expectMsg(("preRestart", id, 2))
        expectMsg(("postRestart", id, 3))
        restarter ! "status"
        expectMsg(("OK", id, 3))
        restarter ! Kill
        expectMsg(("postStop", id, 3))
        expectNoMessage()
        system.stop(supervisor)
      }
    }

    "default for preRestart and postRestart is to call postStop and preStart respectively" in {
      filterException[ActorKilledException] {
        val id = newUuid().toString
        val supervisor =
          system.actorOf(Props(classOf[Supervisor], OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(classOf[LifeCycleTestActor], testActor, id, gen)
        val restarter = Await.result((supervisor ? restarterProps).mapTo[ActorRef], timeout.duration)

        expectMsg(("preStart", id, 0))
        restarter ! Kill
        expectMsg(("postStop", id, 0))
        expectMsg(("preStart", id, 1))
        restarter ! "status"
        expectMsg(("OK", id, 1))
        restarter ! Kill
        expectMsg(("postStop", id, 1))
        expectMsg(("preStart", id, 2))
        restarter ! "status"
        expectMsg(("OK", id, 2))
        restarter ! Kill
        expectMsg(("postStop", id, 2))
        expectMsg(("preStart", id, 3))
        restarter ! "status"
        expectMsg(("OK", id, 3))
        restarter ! Kill
        expectMsg(("postStop", id, 3))
        expectNoMessage()
        system.stop(supervisor)
      }
    }

    "not invoke preRestart and postRestart when never restarted using OneForOneStrategy" in {
      val id = newUuid().toString
      val supervisor =
        system.actorOf(Props(classOf[Supervisor], OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
      val gen = new AtomicInteger(0)
      val props = Props(classOf[LifeCycleTestActor], testActor, id, gen)
      val a = Await.result((supervisor ? props).mapTo[ActorRef], timeout.duration)
      expectMsg(("preStart", id, 0))
      a ! "status"
      expectMsg(("OK", id, 0))
      system.stop(a)
      expectMsg(("postStop", id, 0))
      expectNoMessage()
      system.stop(supervisor)
    }

    "log failures in postStop" in {
      val a = system.actorOf(Props(new Actor {
        def receive = Actor.emptyBehavior
        override def postStop(): Unit = { throw new Exception("hurrah") }
      }))
      EventFilter[Exception]("hurrah", occurrences = 1).intercept {
        a ! PoisonPill
      }
    }

    "clear the behavior stack upon restart" in {
      val a = system.actorOf(Props(new Actor {
        def receive: Receive = {
          case Become(beh) => { context.become(beh(context), discardOld = false); sender() ! "ok" }
          case _           => sender() ! 42
        }
      }))
      a ! "hello"
      expectMsg(42)
      a ! Become(ctx => {
        case "fail" => throw new RuntimeException("buh")
        case _      => ctx.sender() ! 43
      })
      expectMsg("ok")
      a ! "hello"
      expectMsg(43)
      EventFilter[RuntimeException]("buh", occurrences = 1).intercept {
        a ! "fail"
      }
      a ! "hello"
      expectMsg(42)
    }

    "not break supervisor strategy due to unhandled exception in preStart" in {
      val id = newUuid.toString
      val gen = new AtomicInteger(0)
      val maxRetryNum = 3

      val childProps = Props(new LifeCycleTestActor(testActor, id, gen) {
        override def preStart(): Unit = {
          report("preStart")
          throw new Exception("test exception")
        }
      }).withDeploy(Deploy.local)

      val supervisorStrategy: SupervisorStrategy =
        OneForOneStrategy(maxNrOfRetries = maxRetryNum, timeout.duration) {
          case _: ActorInitializationException => SupervisorStrategy.Restart
          case _                               => SupervisorStrategy.Escalate
        }
      val supervisor = system.actorOf(Props(classOf[Supervisor], supervisorStrategy))
      Await.result((supervisor ? childProps).mapTo[ActorRef], timeout.duration)

      (0 to maxRetryNum).foreach(i => {
        expectMsg(("preStart", id, i))
      })
      expectNoMessage()
      system.stop(supervisor)
    }
  }

  "have a non null context after termination" in {
    class StopBeforeFutureFinishes(val latch: CountDownLatch) extends Actor {
      import context.dispatcher

      import pekko.pattern._

      override def receive: Receive = {
        case "ping" =>
          val replyTo = sender()

          context.stop(self)

          Future {
            latch.await()
            Thread.sleep(50)
            "po"
          }
            // Here, we implicitly close over the actor instance and access the context
            // when the flatMap thunk is run. Previously, the context was nulled when the actor
            // was terminated. This isn't done any more. Still, the pattern of `import context.dispatcher`
            // is discouraged as closing over `context` is unsafe in general.
            .flatMap(x => Future { x + "ng" } /* implicitly: (this.context.dispatcher) */ )
            .recover { case _: NullPointerException => "npe" }
            .pipeTo(replyTo)
      }
    }

    val latch = new CountDownLatch(1)
    val actor = system.actorOf(Props(new StopBeforeFutureFinishes(latch)))
    watch(actor)

    actor ! "ping"

    expectTerminated(actor)
    latch.countDown()

    expectMsg("pong")
  }
}

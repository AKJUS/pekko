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

import scala.concurrent.duration._

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach

import org.apache.pekko
import pekko.actor.ActorSystem.Settings
import pekko.dispatch.BoundedDequeBasedMailbox
import pekko.testkit._
import pekko.testkit.DefaultTimeout
import pekko.testkit.TestEvent._
import pekko.util.unused

object ActorWithBoundedStashSpec {

  class StashingActor extends Actor with Stash {
    def receive = {
      case msg: String if msg.startsWith("hello") =>
        stash()
        sender() ! "ok"

      case "world" =>
        context.become(afterWorldBehavior)
        unstashAll()

    }

    def afterWorldBehavior: Receive = {
      case _ => stash()
    }
  }

  class StashingActorWithOverflow extends Actor with Stash {
    var numStashed = 0

    def receive = {
      case msg: String if msg.startsWith("hello") =>
        numStashed += 1
        try {
          stash(); sender() ! "ok"
        } catch {
          case _: StashOverflowException =>
            if (numStashed == 21) {
              sender() ! "STASHOVERFLOW"
              context.stop(self)
            } else {
              sender() ! "Unexpected StashOverflowException: " + numStashed
            }
        }
    }
  }

  // bounded deque-based mailbox with capacity 10
  class Bounded10(@unused settings: Settings, @unused config: Config) extends BoundedDequeBasedMailbox(10, 500.millis)

  class Bounded100(@unused settings: Settings, @unused config: Config) extends BoundedDequeBasedMailbox(100, 500.millis)

  val dispatcherId1 = "my-dispatcher-1"
  val dispatcherId2 = "my-dispatcher-2"
  val aliasedDispatcherId1 = "my-aliased-dispatcher-1"
  val aliasedDispatcherId2 = "my-aliased-dispatcher-2"
  val mailboxId1 = "my-mailbox-1"
  val mailboxId2 = "my-mailbox-2"

  val testConf: Config = ConfigFactory.parseString(s"""
    $dispatcherId1 {
      mailbox-type = "${classOf[Bounded10].getName}"
      stash-capacity = 20
    }
    $dispatcherId2 {
      mailbox-type = "${classOf[Bounded100].getName}"
      stash-capacity = 20
    }
    $aliasedDispatcherId1 = $dispatcherId1
    $aliasedDispatcherId2 = $aliasedDispatcherId1
    $mailboxId1 {
      mailbox-type = "${classOf[Bounded10].getName}"
      stash-capacity = 20
    }
    $mailboxId2 {
      mailbox-type = "${classOf[Bounded100].getName}"
      stash-capacity = 20
    }
    """)
}

class ActorWithBoundedStashSpec
    extends PekkoSpec(ActorWithBoundedStashSpec.testConf)
    with BeforeAndAfterEach
    with DefaultTimeout
    with ImplicitSender {
  import ActorWithBoundedStashSpec._

  override def atStartup(): Unit = {
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*hello.*")))
  }

  override def beforeEach(): Unit =
    system.eventStream.subscribe(testActor, classOf[DeadLetter])

  override def afterEach(): Unit =
    system.eventStream.unsubscribe(testActor, classOf[DeadLetter])

  def testDeadLetters(stasher: ActorRef): Unit = {
    // fill up stash
    for (n <- 1 to 11) {
      stasher ! "hello" + n
      expectMsg("ok")
    }

    // cause unstashAll with capacity violation
    stasher ! "world"
    expectMsg(DeadLetter("hello1", testActor, stasher))

    stasher ! PoisonPill
    // stashed messages are sent to deadletters when stasher is stopped
    for (n <- 2 to 11) expectMsg(DeadLetter("hello" + n, testActor, stasher))
  }

  def testStashOverflowException(stasher: ActorRef): Unit = {
    // fill up stash
    for (n <- 1 to 20) {
      stasher ! "hello" + n
      expectMsg("ok")
    }

    stasher ! "hello21"
    expectMsg("STASHOVERFLOW")

    // stashed messages are sent to deadletters when stasher is stopped,
    for (n <- 1 to 20) expectMsg(DeadLetter("hello" + n, testActor, stasher))
  }

  "An Actor with Stash" must {

    "end up in DeadLetters in case of a capacity violation when configured via dispatcher" in {
      val stasher = system.actorOf(Props[StashingActor]().withDispatcher(dispatcherId1))
      testDeadLetters(stasher)
    }

    "end up in DeadLetters in case of a capacity violation when configured via mailbox" in {
      val stasher = system.actorOf(Props[StashingActor]().withMailbox(mailboxId1))
      testDeadLetters(stasher)
    }

    "throw a StashOverflowException in case of a stash capacity violation when configured via dispatcher" in {
      val stasher = system.actorOf(Props[StashingActorWithOverflow]().withDispatcher(dispatcherId2))
      testStashOverflowException(stasher)
    }

    "throw a StashOverflowException in case of a stash capacity violation when configured via mailbox" in {
      val stasher = system.actorOf(Props[StashingActorWithOverflow]().withMailbox(mailboxId2))
      testStashOverflowException(stasher)
    }

    "get stash capacity from aliased dispatchers" in {
      val stasher = system.actorOf(Props[StashingActor]().withDispatcher(aliasedDispatcherId2))
      testDeadLetters(stasher)
    }
  }
}

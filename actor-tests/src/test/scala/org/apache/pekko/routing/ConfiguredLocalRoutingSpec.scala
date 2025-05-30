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

package org.apache.pekko.routing

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

import scala.annotation.nowarn
import com.typesafe.config.Config

import org.apache.pekko
import pekko.ConfigurationException
import pekko.actor.{ Actor, ActorRef, Deploy, Props }
import pekko.actor.ActorPath
import pekko.actor.ActorSystem
import pekko.actor.ExtendedActorSystem
import pekko.actor.UnstartedCell
import pekko.pattern.gracefulStop
import pekko.testkit.{ DefaultTimeout, ImplicitSender, PekkoSpec }
import pekko.testkit.TestActors.echoActorProps
import pekko.testkit.TestProbe

object ConfiguredLocalRoutingSpec {
  val config = """
    pekko {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 8
            core-pool-size-max = 16
          }
        }
        deployment {
          /config {
            router = random-pool
            nr-of-instances = 4
            pool-dispatcher {
              fork-join-executor.parallelism-min = 4
              fork-join-executor.parallelism-max = 4
            }
          }
          /paths {
            router = random-group
            routees.paths = ["/user/service1", "/user/service2"]
          }
          /weird {
            router = round-robin-pool
            nr-of-instances = 3
          }
          "/weird/*" {
            router = round-robin-pool
            nr-of-instances = 2
          }
          /myrouter {
            router = "org.apache.pekko.routing.ConfiguredLocalRoutingSpec$MyRouter"
            foo = bar
          }
          /sys-parent/round {
            router = round-robin-pool
            nr-of-instances = 6
          }
        }
      }
    }
  """

  class MyRouter(config: Config) extends CustomRouterConfig {
    override def createRouter(system: ActorSystem): Router = Router(MyRoutingLogic(config))
  }

  final case class MyRoutingLogic(config: Config) extends RoutingLogic {
    override def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee =
      MyRoutee(config.getString(message.toString))
  }

  final case class MyRoutee(reply: String) extends Routee {
    override def send(message: Any, sender: ActorRef): Unit =
      sender ! reply
  }

  class EchoProps extends Actor {
    def receive = {
      case "get" => sender() ! context.props
    }
  }

  class SendRefAtStartup(testActor: ActorRef) extends Actor {
    testActor ! self
    def receive = { case _ => }
  }

  class Parent extends Actor {
    def receive = {
      case (p: Props, name: String) =>
        sender() ! context.actorOf(p, name)
    }
  }

}

class ConfiguredLocalRoutingSpec
    extends PekkoSpec(ConfiguredLocalRoutingSpec.config)
    with DefaultTimeout
    with ImplicitSender {
  import ConfiguredLocalRoutingSpec._

  def routerConfig(ref: ActorRef): pekko.routing.RouterConfig = ref match {
    case r: RoutedActorRef =>
      r.underlying match {
        case c: RoutedActorCell => c.routerConfig
        case _: UnstartedCell   => awaitCond(r.isStarted, interval = 10.millis); routerConfig(ref)
        case _                  => throw new IllegalArgumentException(s"Unexpected underlying cell ${r.underlying}")
      }
    case _ => throw new IllegalArgumentException(s"Unexpected actorref $ref")
  }

  def collectRouteePaths(probe: TestProbe, router: ActorRef, n: Int): immutable.Seq[ActorPath] = {
    for (i <- 1 to n) yield {
      val msg = i.toString
      router.tell(msg, probe.ref)
      probe.expectMsg(msg)
      probe.lastSender.path
    }
  }

  "RouterConfig" must {

    "be picked up from Props" in {
      val actor = system.actorOf(RoundRobinPool(12).props(routeeProps = Props[EchoProps]()), "someOther")
      routerConfig(actor) should ===(RoundRobinPool(12))
      Await.result(gracefulStop(actor, 3.seconds), 3.seconds)
    }

    "be overridable in config" in {
      val actor = system.actorOf(RoundRobinPool(12).props(routeeProps = Props[EchoProps]()), "config")
      routerConfig(actor) should ===(RandomPool(nrOfInstances = 4, usePoolDispatcher = true))
      Await.result(gracefulStop(actor, 3.seconds), 3.seconds)
    }

    "use routees.paths from config" in {
      val actor = system.actorOf(RandomPool(12).props(routeeProps = Props[EchoProps]()), "paths")
      routerConfig(actor) should ===(RandomGroup(List("/user/service1", "/user/service2")))
      Await.result(gracefulStop(actor, 3.seconds), 3.seconds)
    }

    "be overridable in explicit deployment" in {
      val actor = system.actorOf(
        FromConfig.props(routeeProps = Props[EchoProps]()).withDeploy(Deploy(routerConfig = RoundRobinPool(12))),
        "someOther")
      routerConfig(actor) should ===(RoundRobinPool(12))
      Await.result(gracefulStop(actor, 3.seconds), 3.seconds)
    }

    "be overridable in config even with explicit deployment" in {
      val actor = system.actorOf(
        FromConfig.props(routeeProps = Props[EchoProps]()).withDeploy(Deploy(routerConfig = RoundRobinPool(12))),
        "config")
      routerConfig(actor) should ===(RandomPool(nrOfInstances = 4, usePoolDispatcher = true))
      Await.result(gracefulStop(actor, 3.seconds), 3.seconds)
    }

    "fail with an exception if not correct" in {
      intercept[ConfigurationException] {
        system.actorOf(FromConfig.props())
      }
    }

    "not get confused when trying to wildcard-configure children" in {
      system.actorOf(FromConfig.props(routeeProps = Props(classOf[SendRefAtStartup], testActor)), "weird")
      val recv = (for (_ <- 1 to 3) yield expectMsgType[ActorRef].path.elements.mkString("/", "/", "")).toSet
      @nowarn
      val expc = Set('a', 'b', 'c').map(i => "/user/weird/$" + i)
      recv should ===(expc)
      expectNoMessage(1.second)
    }

    "support custom router" in {
      val myrouter = system.actorOf(FromConfig.props(), "myrouter")
      myrouter ! "foo"
      expectMsg("bar")
    }

    "load settings from config for local child router of system actor" in {
      // we don't really support deployment configuration of system actors, but
      // it's used for the pool of the SimpleDnsManager "/IO-DNS/inet-address"
      val probe = TestProbe()
      val parent = system.asInstanceOf[ExtendedActorSystem].systemActorOf(Props[Parent](), "sys-parent")
      parent.tell((FromConfig.props(echoActorProps), "round"), probe.ref)
      val router = probe.expectMsgType[ActorRef]
      val replies = collectRouteePaths(probe, router, 10)
      val children = replies.toSet
      children should have size 6
      system.stop(router)
    }

  }

}

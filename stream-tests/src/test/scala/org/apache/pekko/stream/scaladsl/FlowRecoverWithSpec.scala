/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.scaladsl

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.util.control.NoStackTrace

import org.apache.pekko
import pekko.stream._
import pekko.stream.stage.{ GraphStage, GraphStageLogic }
import pekko.stream.testkit.StreamSpec
import pekko.stream.testkit.Utils._
import pekko.stream.testkit.scaladsl.TestSink

@nowarn // tests deprecated APIs
class FlowRecoverWithSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer: ActorMaterializer = ActorMaterializer(settings)

  val ex = new RuntimeException("ex") with NoStackTrace

  "A RecoverWith" must {
    "recover when there is a handler" in {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recoverWith { case _: Throwable => Source(List(0, -1)) }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectNext(0)
        .request(1)
        .expectNext(-1)
        .expectComplete()
    }

    "recover with empty source" in {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recoverWith { case _: Throwable => Source.empty }
        .runWith(TestSink[Int]())
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectComplete()
    }

    "recover with a completed future source" in {
      Source.failed(ex)
        .recoverWith { case _: Throwable => Source.future(Future.successful(3)) }
        .runWith(TestSink[Int]())
        .request(1)
        .expectNext(3)
        .expectComplete()
    }

    "recover with a failed future source" in {
      Source.failed(ex)
        .recoverWith { case _: Throwable => Source.future(Future.failed(ex)) }
        .runWith(TestSink[Int]())
        .request(1)
        .expectError(ex)
    }

    "recover with a java stream source" in {
      Source.failed(ex)
        .recoverWith { case _: Throwable => Source.fromJavaStream(() => java.util.stream.Stream.of(1, 2, 3)) }
        .runWith(TestSink[Int]())
        .request(4)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "recover with single source" in {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recoverWith { case _: Throwable => Source.single(3) }
        .runWith(TestSink[Int]())
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectNext(3)
        .expectComplete()
    }

    "cancel substream if parent is terminated when there is a handler" in {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recoverWith { case _: Throwable => Source(List(0, -1)) }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(1)
        .expectNext(0)
        .cancel()
    }

    "failed stream if handler is not for such exception type" in {
      Source(1 to 3)
        .map { a =>
          if (a == 2) throw ex else a
        }
        .recoverWith { case _: IndexOutOfBoundsException => Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(1)
        .request(1)
        .expectError(ex)
    }

    "be able to recover with the same unmaterialized source if configured" in {
      val src = Source(1 to 3).map { a =>
        if (a == 3) throw ex else a
      }
      src
        .recoverWith { case _: Throwable => src }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(1 to 2)
        .request(2)
        .expectNextN(1 to 2)
        .request(2)
        .expectNextN(1 to 2)
        .cancel()
    }

    "not influence stream when there is no exceptions" in {
      Source(1 to 3)
        .map(identity)
        .recoverWith { case _: Throwable => Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "finish stream if it's empty" in {
      Source
        .empty[Int]
        .map(identity)
        .recoverWith { case _: Throwable => Source.single(0) }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectComplete()
    }

    "switch the second time if alternative source throws exception" in {
      Source(1 to 3)
        .map { a =>
          if (a == 3) throw new IndexOutOfBoundsException() else a
        }
        .recoverWith {
          case t: IndexOutOfBoundsException =>
            Source(List(11, 22)).map(m => if (m == 22) throw new IllegalArgumentException() else m)
          case t: IllegalArgumentException => Source(List(33, 44))
        }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(List(1, 2))
        .request(2)
        .expectNextN(List(11, 33))
        .request(1)
        .expectNext(44)
        .expectComplete()
    }

    "terminate with exception if partial function fails to match after an alternative source failure" in {
      Source(1 to 3)
        .map { a =>
          if (a == 3) throw new IndexOutOfBoundsException() else a
        }
        .recoverWith {
          case t: IndexOutOfBoundsException =>
            Source(List(11, 22)).map(m => if (m == 22) throw ex else m)
        }
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNextN(List(1, 2))
        .request(1)
        .expectNextN(List(11))
        .request(1)
        .expectError(ex)
    }

    "terminate with exception after set number of retries" in {
      Source(1 to 3)
        .map { a =>
          if (a == 3) throw new IndexOutOfBoundsException() else a
        }
        .recoverWithRetries(3,
          {
            case t: Throwable =>
              Source(List(11, 22, 33)).map(m => if (m == 33) throw ex else m)
          })
        .runWith(TestSink.probe[Int])
        .request(100)
        .expectNextN(List(1, 2))
        .expectNextN(List(11, 22))
        .expectNextN(List(11, 22))
        .expectNextN(List(11, 22))
        .expectError(ex)
    }

    "not attempt recovering when attempts is zero" in {
      Source(1 to 3)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recoverWithRetries(0, { case t: Throwable => Source(List(22, 33)) })
        .runWith(TestSink.probe[Int])
        .request(100)
        .expectNextN(List(1, 2))
        .expectError(ex)
    }

    "recover infinitely when negative (-1) number of attempts given" in {
      val oneThenBoom = Source(1 to 2).map { a =>
        if (a == 2) throw ex else a
      }

      oneThenBoom
        .recoverWithRetries(-1, { case t: Throwable => oneThenBoom })
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNextN(List(1, 2, 3, 4, 5).map(_ => 1))
        .cancel()
    }

    "recover infinitely when negative (smaller than -1) number of attempts given" in {
      val oneThenBoom = Source(1 to 2).map { a =>
        if (a == 2) throw ex else a
      }

      oneThenBoom
        .recoverWithRetries(-10, { case t: Throwable => oneThenBoom })
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNextN(List(1, 2, 3, 4, 5).map(_ => 1))
        .cancel()
    }

    "fail correctly when materialization of recover source fails" in {
      val matFail = TE("fail!")
      object FailingInnerMat extends GraphStage[SourceShape[String]] {
        val out = Outlet[String]("out")
        val shape = SourceShape(out)
        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          throw matFail
        }
      }

      val result = Source
        .failed(TE("trigger"))
        .recoverWithRetries(1,
          {
            case _: TE => Source.fromGraph(FailingInnerMat)
          })
        .runWith(Sink.ignore)

      result.failed.futureValue should ===(matFail)

    }
  }
}

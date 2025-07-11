/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.scaladsl

import java.util.concurrent.CompletionStage

import scala.annotation.{ nowarn, tailrec }
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.{ immutable, AbstractIterator }
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.{ Done, NotUsed }
import pekko.actor.{ ActorRef, Cancellable }
import pekko.annotation.InternalApi
import pekko.stream._
import pekko.stream.impl._
import pekko.stream.impl.Stages.DefaultAttributes
import pekko.stream.impl.fusing.{ GraphStages, IterableSource, LazyFutureSource, LazySingleSource }
import pekko.stream.impl.fusing.GraphStages._
import pekko.stream.stage.GraphStageWithMaterializedValue
import pekko.util.ConstantFun
import pekko.util.FutureConverters._

import org.reactivestreams.{ Publisher, Subscriber }

/**
 * A `Source` is a set of stream processing steps that has one open output. It can comprise
 * any number of internal sources and transformations that are wired together, or it can be
 * an “atomic” source, e.g. from a collection or a file. Materialization turns a Source into
 * a Reactive Streams `Publisher` (at least conceptually).
 */
final class Source[+Out, +Mat](
    override val traversalBuilder: LinearTraversalBuilder,
    override val shape: SourceShape[Out])
    extends FlowOpsMat[Out, Mat]
    with Graph[SourceShape[Out], Mat] {

  override type Repr[+O] = Source[O, Mat @uncheckedVariance]
  override type ReprMat[+O, +M] = Source[O, M]

  override type Closed = RunnableGraph[Mat @uncheckedVariance]
  override type ClosedMat[+M] = RunnableGraph[M]

  override def toString: String = s"Source($shape)"

  override def via[T, Mat2](flow: Graph[FlowShape[Out, T], Mat2]): Repr[T] = viaMat(flow)(Keep.left)

  override def viaMat[T, Mat2, Mat3](flow: Graph[FlowShape[Out, T], Mat2])(
      combine: (Mat, Mat2) => Mat3): Source[T, Mat3] = {
    if (flow.traversalBuilder eq Flow.identityTraversalBuilder)
      if (combine == Keep.left)
        // optimization by returning this
        this.asInstanceOf[Source[T, Mat3]] // Mat == Mat3, due to Keep.left
      else if (combine == Keep.right || combine == Keep.none) // Mat3 = NotUsed
        // optimization with LinearTraversalBuilder.empty()
        new Source[T, Mat3](
          traversalBuilder.append(LinearTraversalBuilder.empty(), flow.shape, combine),
          SourceShape(shape.out).asInstanceOf[SourceShape[T]])
      else
        new Source[T, Mat3](
          traversalBuilder.append(flow.traversalBuilder, flow.shape, combine),
          SourceShape(flow.shape.out))
    else
      new Source[T, Mat3](
        traversalBuilder.append(flow.traversalBuilder, flow.shape, combine),
        SourceShape(flow.shape.out))
  }

  /**
   * Connect this [[pekko.stream.scaladsl.Source]] to a [[pekko.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def to[Mat2](sink: Graph[SinkShape[Out], Mat2]): RunnableGraph[Mat] = toMat(sink)(Keep.left)

  /**
   * Connect this [[pekko.stream.scaladsl.Source]] to a [[pekko.stream.scaladsl.Sink]],
   * concatenating the processing steps of both.
   */
  def toMat[Mat2, Mat3](sink: Graph[SinkShape[Out], Mat2])(combine: (Mat, Mat2) => Mat3): RunnableGraph[Mat3] = {
    RunnableGraph(traversalBuilder.append(sink.traversalBuilder, sink.shape, combine))
  }

  /**
   * Transform only the materialized value of this Source, leaving all other properties as they were.
   */
  override def mapMaterializedValue[Mat2](f: Mat => Mat2): ReprMat[Out, Mat2] =
    new Source[Out, Mat2](traversalBuilder.transformMat(f.asInstanceOf[Any => Any]), shape)

  /**
   * Materializes this Source, immediately returning (1) its materialized value, and (2) a new Source
   * that can be used to consume elements from the newly materialized Source.
   */
  def preMaterialize()(implicit materializer: Materializer): (Mat, ReprMat[Out, NotUsed]) = {
    val (mat, pub) = toMat(Sink.asPublisher(fanout = true))(Keep.both).run()
    (mat, Source.fromPublisher(pub))
  }

  /**
   * Connect this `Source` to the `Sink.ignore` and run it. Elements from the stream will be consumed and discarded.
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  def run()(implicit materializer: Materializer): Future[Done] =
    toMat(Sink.ignore)(Keep.right).run()

  /**
   * Connect this `Source` to a `Sink` and run it. The returned value is the materialized value
   * of the `Sink`, e.g. the `Publisher` of a [[pekko.stream.scaladsl.Sink#publisher]].
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  def runWith[Mat2](sink: Graph[SinkShape[Out], Mat2])(implicit materializer: Materializer): Mat2 =
    toMat(sink)(Keep.right).run()

  /**
   * Shortcut for running this `Source` with a fold function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  def runFold[U](zero: U)(f: (U, Out) => U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.fold(zero)(f))

  /**
   * Shortcut for running this `Source` with a foldAsync function.
   * The given function is invoked for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  def runFoldAsync[U](zero: U)(f: (U, Out) => Future[U])(implicit materializer: Materializer): Future[U] =
    runWith(Sink.foldAsync(zero)(f))

  /**
   * Shortcut for running this `Source` with a reduce function.
   * The given function is invoked for every received element, giving it its previous
   * output (from the second element) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   *
   * If the stream is empty (i.e. completes before signalling any elements),
   * the reduce operator will fail its downstream with a [[NoSuchElementException]],
   * which is semantically in-line with that Scala's standard library collections
   * do in such situations.
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  def runReduce[U >: Out](f: (U, U) => U)(implicit materializer: Materializer): Future[U] =
    runWith(Sink.reduce(f))

  /**
   * Shortcut for running this `Source` with a foreach procedure. The given procedure is invoked
   * for each received element.
   * The returned [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream.
   *
   * Note that the `ActorSystem` can be used as the implicit `materializer` parameter to use the
   * [[pekko.stream.SystemMaterializer]] for running the stream.
   */
  // FIXME: Out => Unit should stay, right??
  def runForeach(f: Out => Unit)(implicit materializer: Materializer): Future[Done] = runWith(Sink.foreach(f))

  /**
   * Replace the attributes of this [[Source]] with the given ones. If this Source is a composite
   * of multiple graphs, new attributes on the composite will be less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def withAttributes(attr: Attributes): Repr[Out] =
    new Source(traversalBuilder.setAttributes(attr), shape)

  /**
   * Add the given attributes to this Source. If the specific attribute was already on this source
   * it will replace the previous value. If this Source is a composite
   * of multiple graphs, the added attributes will be on the composite and therefore less specific than attributes
   * set directly on the individual graphs of the composite.
   */
  override def addAttributes(attr: Attributes): Repr[Out] = withAttributes(traversalBuilder.attributes and attr)

  /**
   * Add a ``name`` attribute to this Source.
   */
  override def named(name: String): Repr[Out] = addAttributes(Attributes.name(name))

  /**
   * Put an asynchronous boundary around this `Source`
   */
  override def async: Repr[Out] = super.async.asInstanceOf[Repr[Out]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher Run the graph on this dispatcher
   */
  override def async(dispatcher: String): Repr[Out] =
    super.async(dispatcher).asInstanceOf[Repr[Out]]

  /**
   * Put an asynchronous boundary around this `Graph`
   *
   * @param dispatcher      Run the graph on this dispatcher
   * @param inputBufferSize Set the input buffer to this size for the graph
   */
  override def async(dispatcher: String, inputBufferSize: Int): Repr[Out] =
    super.async(dispatcher, inputBufferSize).asInstanceOf[Repr[Out]]

  /**
   * Converts this Scala DSL element to it's Java DSL counterpart.
   */
  def asJava: javadsl.Source[Out @uncheckedVariance, Mat @uncheckedVariance] = new javadsl.Source(this)

  /**
   * Combines several sources with fan-in strategy like `Merge` or `Concat` and returns `Source`.
   */
  @deprecated("Use `Source.combine` on companion object instead", "Akka 2.5.5")
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(
      strategy: Int => Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.combine(first, second, rest: _*)(strategy)

  /**
   * Transform this source whose element is ``e`` into a source producing tuple ``(e, f(e))``
   */
  def asSourceWithContext[Ctx](f: Out => Ctx): SourceWithContext[Out, Ctx, Mat] =
    new SourceWithContext(this.map(e => (e, f(e))))

  override def getAttributes: Attributes = traversalBuilder.attributes
}

object Source {

  /** INTERNAL API */
  def shape[T](name: String): SourceShape[T] = SourceShape(Outlet(name + ".out"))

  /**
   * Helper to create [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def fromPublisher[T](publisher: Publisher[T]): Source[T, NotUsed] =
    fromGraph(new PublisherSource(publisher, DefaultAttributes.publisherSource, shape("PublisherSource")))

  /**
   * Helper to create [[Source]] from `Iterator`.
   * Example usage: `Source.fromIterator(() => Iterator.from(0))`
   *
   * Start a new `Source` from the given function that produces an Iterator.
   * The produced stream of elements will continue until the iterator runs empty
   * or fails during evaluation of the `next()` method.
   * Elements are pulled out of the iterator in accordance with the demand coming
   * from the downstream transformation steps.
   */
  def fromIterator[T](f: () => Iterator[T]): Source[T, NotUsed] =
    apply(new immutable.Iterable[T] {
      override def iterator: Iterator[T] = f()
      override def toString: String = "() => Iterator"
    })

  /**
   * Creates a source that wraps a Java 8 ``Stream``. ``Source`` uses a stream iterator to get all its
   * elements and send them downstream on demand.
   *
   * You can use [[Source.async]] to create asynchronous boundaries between synchronous Java ``Stream``
   * and the rest of flow.
   */
  def fromJavaStream[T, S <: java.util.stream.BaseStream[T, S]](
      stream: () => java.util.stream.BaseStream[T, S]): Source[T, NotUsed] =
    StreamConverters.fromJavaStream(stream)

  /**
   * Creates [[Source]] that will continually produce given elements in specified order.
   *
   * Starts a new 'cycled' `Source` from the given elements. The producer stream of elements
   * will continue infinitely by repeating the sequence of elements provided by function parameter.
   */
  def cycle[T](f: () => Iterator[T]): Source[T, NotUsed] = {
    val iterator = Iterator.continually {
      val i = f(); if (i.isEmpty) throw new IllegalArgumentException("empty iterator") else i
    }.flatten
    fromIterator(() => iterator).withAttributes(DefaultAttributes.cycledSource)
  }

  /**
   * Creates a Source from an existing base Source outputting an optional element
   * and applying an additional viaFlow only if the element in the stream is defined.
   *
   * '''Emits when''' the provided viaFlow runs with defined elements
   *
   * '''Backpressures when''' the viaFlow runs for the defined elements and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param source The base source that outputs an optional element
   * @param viaFlow The flow that gets used if the optional element in is defined.
   * @param combine How to combine the materialized values of source and viaFlow
   * @return a Source with the viaFlow applied onto defined elements of the flow. The output value
   *         is contained within an Option which indicates whether the original source's element had viaFlow
   *         applied.
   * @since 1.1.0
   */
  def optionalVia[SOut, FOut, SMat, FMat, Mat](source: Source[Option[SOut], SMat],
      viaFlow: Flow[SOut, FOut, FMat])(
      combine: (SMat, FMat) => Mat
  ): Source[Option[FOut], Mat] =
    Source.fromGraph(GraphDSL.createGraph(source, viaFlow)(combine) { implicit b => (s, viaF) =>
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[Option[SOut]](2))
      val merge = b.add(Merge[Option[FOut]](2))

      val filterAvailable = Flow[Option[SOut]].collect {
        case Some(f) => f
      }

      val filterUnavailable = Flow[Option[SOut]].collect {
        case None => Option.empty[FOut]
      }

      val mapIntoOption = Flow[FOut].map {
        f => Some(f)
      }

      s ~> broadcast.in

      broadcast.out(0) ~> filterAvailable   ~> viaF ~> mapIntoOption ~> merge.in(0)
      broadcast.out(1) ~> filterUnavailable ~> merge.in(1)

      SourceShape(merge.out)
    })

  /**
   * A graph with the shape of a source logically is a source, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SourceShape[T], M]): Source[T, M] = g match {
    case s: Source[T, M]                                       => s
    case s: javadsl.Source[T, M] @unchecked                    => s.asScala
    case g: GraphStageWithMaterializedValue[SourceShape[T], M] =>
      // move these from the stage itself to make the returned source
      // behave as it is the stage with regards to attributes
      val attrs = g.traversalBuilder.attributes
      val noAttrStage = g.withAttributes(Attributes.none)
      new Source(
        LinearTraversalBuilder.fromBuilder(noAttrStage.traversalBuilder, noAttrStage.shape, Keep.right),
        noAttrStage.shape).withAttributes(attrs)
    case other =>
      // composite source shaped graph
      new Source(LinearTraversalBuilder.fromBuilder(other.traversalBuilder, other.shape, Keep.right), other.shape)
  }

  /**
   * Defers the creation of a [[Source]] until materialization. The `factory` function
   * exposes [[Materializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Source]] returned by this method.
   */
  def fromMaterializer[T, M](factory: (Materializer, Attributes) => Source[T, M]): Source[T, Future[M]] =
    Source.fromGraph(new SetupSourceStage(factory))

  /**
   * Defers the creation of a [[Source]] until materialization. The `factory` function
   * exposes [[ActorMaterializer]] which is going to be used during materialization and
   * [[Attributes]] of the [[Source]] returned by this method.
   */
  @deprecated("Use 'fromMaterializer' instead", "Akka 2.6.0")
  def setup[T, M](factory: (ActorMaterializer, Attributes) => Source[T, M]): Source[T, Future[M]] =
    Source.fromGraph(new SetupSourceStage((materializer, attributes) =>
      factory(ActorMaterializerHelper.downcast(materializer), attributes)))

  /**
   * Helper to create [[Source]] from `Iterable`.
   * Example usage: `Source(Seq(1,2,3))`
   *
   * Starts a new `Source` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): Source[T, NotUsed] =
    fromGraph(new IterableSource[T](iterable)).withAttributes(DefaultAttributes.iterableSource)

  /**
   * Starts a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  @deprecated("Use 'Source.future' instead", "Akka 2.6.0")
  def fromFuture[T](future: Future[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future))

  /**
   * Starts a new `Source` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with a failure if the `Future` is completed with a failure.
   */
  @deprecated("Use 'Source.completionStage' instead", "Akka 2.6.0")
  def fromCompletionStage[T](future: CompletionStage[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource(future.asScala))

  /**
   * Streams the elements of the given future source once it successfully completes.
   * If the [[Future]] fails the stream is failed with the exception from the future. If downstream cancels before the
   * stream completes the materialized `Future` will be failed with a [[StreamDetachedException]]
   */
  @deprecated("Use 'Source.futureSource' (potentially together with `Source.fromGraph`) instead", "Akka 2.6.0")
  def fromFutureSource[T, M](future: Future[Graph[SourceShape[T], M]]): Source[T, Future[M]] =
    fromGraph(new FutureFlattenSource(future))

  /**
   * Streams the elements of an asynchronous source once its given `completion` operator completes.
   * If the [[CompletionStage]] fails the stream is failed with the exception from the future.
   * If downstream cancels before the stream completes the materialized `Future` will be failed
   * with a [[StreamDetachedException]]
   */
  @deprecated("Use scala-compat CompletionStage to future converter and 'Source.futureSource' instead", "Akka 2.6.0")
  def fromSourceCompletionStage[T, M](
      completion: CompletionStage[_ <: Graph[SourceShape[T], M]]): Source[T, CompletionStage[M]] =
    fromFutureSource(completion.asScala).mapMaterializedValue(_.asJava)

  /**
   * Elements are emitted periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def tick[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: T): Source[T, Cancellable] =
    fromGraph(new TickSource[T](initialDelay, interval, tick))

  /**
   * Create a `Source` with one element.
   * Every connected `Sink` of this stream will see an individual stream consisting of one element.
   */
  def single[T](element: T): Source[T, NotUsed] =
    fromGraph(new GraphStages.SingleSource(element))

  /**
   * Create a `Source` that will continually emit the given element.
   */
  def repeat[T](element: T): Source[T, NotUsed] = {
    fromIterator(() => Iterator.continually(element)).withAttributes(DefaultAttributes.repeat)
  }

  /**
   * Create a `Source` that will unfold a value of type `S` into
   * a pair of the next state `S` and output elements of type `E`.
   *
   * For example, all the Fibonacci numbers under 10M:
   *
   * {{{
   *   Source.unfold(0 -> 1) {
   *    case (a, _) if a > 10000000 => None
   *    case (a, b) => Some((b -> (a + b)) -> a)
   *   }
   * }}}
   */
  def unfold[S, E](s: S)(f: S => Option[(S, E)]): Source[E, NotUsed] =
    Source.fromGraph(new Unfold(s, f))

  /**
   * Same as [[unfold]], but uses an async function to generate the next state-element tuple.
   *
   * async fibonacci example:
   *
   * {{{
   *   Source.unfoldAsync(0 -> 1) {
   *    case (a, _) if a > 10000000 => Future.successful(None)
   *    case (a, b) => Future{
   *      Thread.sleep(1000)
   *      Some((b -> (a + b)) -> a)
   *    }
   *   }
   * }}}
   */
  def unfoldAsync[S, E](s: S)(f: S => Future[Option[(S, E)]]): Source[E, NotUsed] =
    Source.fromGraph(new UnfoldAsync(s, f))

  /**
   * Creates a sequential `Source` by iterating with the given predicate and function,
   * starting with the given `seed` value. If the predicate returns `false` for the seed,
   * the `Source` completes with empty.
   *
   * @see [[unfold]]
   * @since 1.1.0
   */
  def iterate[T](seed: T)(p: T => Boolean, f: T => T): Source[T, NotUsed] =
    fromIterator(() =>
      new AbstractIterator[T] {
        private var first = true
        private var acc = seed
        override def hasNext: Boolean = p(acc)
        override def next(): T = {
          if (first) {
            first = false
          } else {
            acc = f(acc)
          }
          acc
        }
      }).withAttributes(DefaultAttributes.iterateSource)

  /**
   * A `Source` with no elements, i.e. an empty stream that is completed immediately for every connected `Sink`.
   */
  def empty[T]: Source[T, NotUsed] = _empty
  private[this] val _empty: Source[Nothing, NotUsed] =
    Source.fromGraph(EmptySource)

  /**
   * Create a `Source` which materializes a [[scala.concurrent.Promise]] which controls what element
   * will be emitted by the Source.
   * If the materialized promise is completed with a Some, that value will be produced downstream,
   * followed by completion.
   * If the materialized promise is completed with a None, no value will be produced downstream and completion will
   * be signalled immediately.
   * If the materialized promise is completed with a failure, then the source will fail with that error.
   * If the downstream of this source cancels or fails before the promise has been completed, then the promise will be completed
   * with None.
   */
  def maybe[T]: Source[T, Promise[Option[T]]] =
    Source.fromGraph(MaybeSource.asInstanceOf[Graph[SourceShape[T], Promise[Option[T]]]])

  /**
   * Create a `Source` that immediately ends the stream with the `cause` error to every connected `Sink`.
   */
  def failed[T](cause: Throwable): Source[T, NotUsed] =
    Source.fromGraph(new FailedSource[T](cause))

  /**
   * Creates a `Source` that is not materialized until there is downstream demand, when the source gets materialized
   * the materialized future is completed with its value, if downstream cancels or fails without any demand the
   * create factory is never called and the materialized `Future` is failed.
   */
  @deprecated("Use 'Source.lazySource' instead", "Akka 2.6.0")
  def lazily[T, M](create: () => Source[T, M]): Source[T, Future[M]] =
    Source.fromGraph(new LazySource[T, M](create))

  /**
   * Creates a `Source` from supplied future factory that is not called until downstream demand. When source gets
   * materialized the materialized future is completed with the value from the factory. If downstream cancels or fails
   * without any demand the create factory is never called and the materialized `Future` is failed.
   *
   * @see [[Source.lazily]]
   */
  @deprecated("Use 'Source.lazyFuture' instead", "Akka 2.6.0")
  def lazilyAsync[T](create: () => Future[T]): Source[T, Future[NotUsed]] =
    lazily(() => fromFuture(create()))

  /**
   * Emits a single value when the given `Future` is successfully completed and then completes the stream.
   * The stream fails if the `Future` is completed with a failure.
   */
  def future[T](futureElement: Future[T]): Source[T, NotUsed] =
    fromGraph(new FutureSource[T](futureElement))

  /**
   * Never emits any elements, never completes and never fails.
   * This stream could be useful in tests.
   */
  def never[T]: Source[T, NotUsed] = _never
  private[this] val _never: Source[Nothing, NotUsed] = fromGraph(GraphStages.NeverSource)

  /**
   * Emits a single value when the given `CompletionStage` is successfully completed and then completes the stream.
   * If the `CompletionStage` is completed with a failure the stream is failed.
   *
   * Here for Java interoperability, the normal use from Scala should be [[Source.future]]
   */
  def completionStage[T](completionStage: CompletionStage[T]): Source[T, NotUsed] =
    future(completionStage.asScala)

  /**
   * Turn a `Future[Source]` into a source that will emit the values of the source when the future completes successfully.
   * If the `Future` is completed with a failure the stream is failed.
   */
  def futureSource[T, M](futureSource: Future[Source[T, M]]): Source[T, Future[M]] =
    fromGraph(new FutureFlattenSource(futureSource))

  /**
   * Defers invoking the `create` function to create a single element until there is downstream demand.
   *
   * If the `create` function fails when invoked the stream is failed.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   */
  def lazySingle[T](create: () => T): Source[T, NotUsed] =
    fromGraph(new LazySingleSource(create))

  /**
   * Defers invoking the `create` function to create a future element until there is downstream demand.
   *
   * The returned future element will be emitted downstream when it completes, or fail the stream if the future
   * is failed or the `create` function itself fails.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   */
  def lazyFuture[T](create: () => Future[T]): Source[T, NotUsed] =
    fromGraph(new LazyFutureSource(create))

  /**
   * Defers invoking the `create` function to create a future source until there is downstream demand.
   *
   * The returned source will emit downstream and behave just like it was the outer source. Downstream completes
   * when the created source completes and fails when the created source fails.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and will trigger the factory immediately.
   *
   * The materialized future value is completed with the materialized value of the created source when
   * it has been materialized. If the function throws or the source materialization fails the future materialized value
   * is failed with the thrown exception.
   *
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[pekko.stream.NeverMaterializedException]]
   */
  def lazySource[T, M](create: () => Source[T, M]): Source[T, Future[M]] =
    fromGraph(new LazySource(create))

  /**
   * Defers invoking the `create` function to create a future source until there is downstream demand.
   *
   * The returned future source will emit downstream and behave just like it was the outer source when the future completes
   * successfully. Downstream completes when the created source completes and fails when the created source fails.
   * If the future or the `create` function fails the stream is failed.
   *
   * Note that asynchronous boundaries (and other operators) in the stream may do pre-fetching which counter acts
   * the laziness and triggers the factory immediately.
   *
   * The materialized future value is completed with the materialized value of the created source when
   * it has been materialized. If the function throws or the source materialization fails the future materialized value
   * is failed with the thrown exception.
   *
   * If downstream cancels or fails before the function is invoked the materialized value
   * is failed with a [[pekko.stream.NeverMaterializedException]]
   */
  def lazyFutureSource[T, M](create: () => Future[Source[T, M]]): Source[T, Future[M]] =
    lazySource(() => futureSource(create())).mapMaterializedValue(_.flatten)

  /**
   * Creates a `Source` that is materialized as a [[org.reactivestreams.Subscriber]]
   */
  def asSubscriber[T]: Source[T, Subscriber[T]] =
    fromGraph(new SubscriberSource[T](DefaultAttributes.subscriberSource, shape("SubscriberSource")))

  /**
   * Creates a `Source` that is materialized as an [[pekko.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[pekko.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The strategy [[pekko.stream.OverflowStrategy.backpressure]] is not supported, and an
   * IllegalArgument("Backpressure overflowStrategy not supported") will be thrown if it is passed as argument.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped if there is no demand
   * from downstream. When `bufferSize` is 0 the `overflowStrategy` does not matter.
   *
   * The stream can be completed successfully by sending the actor reference a message that is matched by
   * `completionMatcher` in which case already buffered elements will be signaled before signaling
   * completion.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[java.lang.Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * Note that terminating the actor without first completing it, either with a success or a
   * failure, will prevent the actor triggering downstream completion and the stream will continue
   * to run even though the source actor is dead. Therefore you should **not** attempt to
   * manually terminate the actor such as with a [[pekko.actor.PoisonPill]].
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[pekko.stream.scaladsl.Source.queue]].
   *
   * @param completionMatcher catches the completion message to end the stream
   * @param failureMatcher catches the failure message to fail the stream
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def actorRef[T](
      completionMatcher: PartialFunction[Any, CompletionStrategy],
      failureMatcher: PartialFunction[Any, Throwable],
      bufferSize: Int,
      overflowStrategy: OverflowStrategy): Source[T, ActorRef] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    require(!overflowStrategy.isBackpressure, "Backpressure overflowStrategy not supported")
    Source
      .fromGraph(new ActorRefSource(bufferSize, overflowStrategy, completionMatcher, failureMatcher))
      .withAttributes(DefaultAttributes.actorRefSource)
  }

  /**
   * Creates a `Source` that is materialized as an [[pekko.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   *
   * Depending on the defined [[pekko.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * The strategy [[pekko.stream.OverflowStrategy.backpressure]] is not supported, and an
   * IllegalArgument("Backpressure overflowStrategy not supported") will be thrown if it is passed as argument.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received messages are dropped if there is no demand
   * from downstream. When `bufferSize` is 0 the `overflowStrategy` does not matter.
   *
   * The stream can be completed successfully by sending the actor reference a [[pekko.actor.Status.Success]].
   * If the content is [[pekko.stream.CompletionStrategy.immediately]] the completion will be signaled immediately.
   * Otherwise, if the content is [[pekko.stream.CompletionStrategy.draining]] (or anything else)
   * already buffered elements will be sent out before signaling completion.
   * Using [[pekko.actor.PoisonPill]] or [[pekko.actor.ActorSystem.stop]] to stop the actor and complete the stream is *not supported*.
   *
   * The stream can be completed with failure by sending a [[pekko.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * a [[pekko.actor.Status.Success]]) before signaling completion and it receives a [[pekko.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   *
   * See also [[pekko.stream.scaladsl.Source.queue]].
   *
   * @param bufferSize The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @deprecated("Use variant accepting completion and failure matchers instead", "Akka 2.6.0")
  def actorRef[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, ActorRef] =
    actorRef(
      {
        case pekko.actor.Status.Success(s: CompletionStrategy) => s
        case pekko.actor.Status.Success(_)                     => CompletionStrategy.Draining
        case pekko.actor.Status.Success                        => CompletionStrategy.Draining
      }, { case pekko.actor.Status.Failure(cause) => cause }, bufferSize, overflowStrategy)

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] def actorRefWithAck[T](
      ackTo: Option[ActorRef],
      ackMessage: Any,
      completionMatcher: PartialFunction[Any, CompletionStrategy],
      failureMatcher: PartialFunction[Any, Throwable]): Source[T, ActorRef] = {
    Source.fromGraph(new ActorRefBackpressureSource(ackTo, ackMessage, completionMatcher, failureMatcher))
  }

  /**
   * Creates a `Source` that is materialized as an [[pekko.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed with failure by sending a message that is matched by `failureMatcher`. The extracted
   * [[java.lang.Throwable]] will be used to fail the stream. In case the Actor is still draining its internal buffer (after having received
   * a message matched by `completionMatcher`) before signaling completion and it receives a message matched by `failureMatcher`,
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   */
  def actorRefWithBackpressure[T](
      ackMessage: Any,
      completionMatcher: PartialFunction[Any, CompletionStrategy],
      failureMatcher: PartialFunction[Any, Throwable]): Source[T, ActorRef] = {
    Source.fromGraph(new ActorRefBackpressureSource(None, ackMessage, completionMatcher, failureMatcher))
  }

  /**
   * Creates a `Source` that is materialized as an [[pekko.actor.ActorRef]].
   * Messages sent to this actor will be emitted to the stream if there is demand from downstream,
   * and a new message will only be accepted after the previous messages has been consumed and acknowledged back.
   * The stream will complete with failure if a message is sent before the acknowledgement has been replied back.
   *
   * The stream can be completed successfully by sending the actor reference a [[pekko.actor.Status.Success]].
   * If the content is [[pekko.stream.CompletionStrategy.immediately]] the completion will be signaled immediately,
   * otherwise if the content is [[pekko.stream.CompletionStrategy.draining]] (or anything else)
   * already buffered element will be signaled before signaling completion.
   *
   * The stream can be completed with failure by sending a [[pekko.actor.Status.Failure]] to the
   * actor reference. In case the Actor is still draining its internal buffer (after having received
   * a [[pekko.actor.Status.Success]]) before signaling completion and it receives a [[pekko.actor.Status.Failure]],
   * the failure will be signaled downstream immediately (instead of the completion signal).
   *
   * The actor will be stopped when the stream is completed, failed or canceled from downstream,
   * i.e. you can watch it to get notified when that happens.
   */
  @deprecated("Use actorRefWithBackpressure accepting completion and failure matchers instead", "Akka 2.6.0")
  def actorRefWithAck[T](ackMessage: Any): Source[T, ActorRef] =
    actorRefWithAck(None, ackMessage,
      {
        case pekko.actor.Status.Success(s: CompletionStrategy) => s
        case pekko.actor.Status.Success(_)                     => CompletionStrategy.Draining
        case pekko.actor.Status.Success                        => CompletionStrategy.Draining
      }, { case pekko.actor.Status.Failure(cause) => cause })

  /**
   * Combines several sources with fan-in strategy like [[Merge]] or [[Concat]] into a single [[Source]].
   */
  def combine[T, U](first: Source[T, _], second: Source[T, _], rest: Source[T, _]*)(
      @nowarn
      @deprecatedName(Symbol("strategy"))
      fanInStrategy: Int => Graph[UniformFanInShape[T, U], NotUsed]): Source[U, NotUsed] =
    Source.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._
      val c = b.add(fanInStrategy(rest.size + 2))
      first  ~> c.in(0)
      second ~> c.in(1)

      @tailrec def combineRest(idx: Int, i: Iterator[Source[T, _]]): SourceShape[U] =
        if (i.hasNext) {
          i.next() ~> c.in(idx)
          combineRest(idx + 1, i)
        } else SourceShape(c.out)

      combineRest(2, rest.iterator)
    })

  /**
   * Combines several sources with fan-in strategy like [[Merge]] or [[Concat]] into a single [[Source]].
   * @since 1.1.0
   */
  def combine[T, U, M](sources: immutable.Seq[Graph[SourceShape[T], M]])(
      fanInStrategy: Int => Graph[UniformFanInShape[T, U], NotUsed]): Source[U, immutable.Seq[M]] =
    sources match {
      case immutable.Seq()       => Source.empty.mapMaterializedValue(_ => Nil)
      case immutable.Seq(source) => source.asInstanceOf[Source[U, M]].mapMaterializedValue(_ :: Nil)
      case _                     =>
        Source.fromGraph(GraphDSL.create(sources) { implicit b => shapes =>
          import GraphDSL.Implicits._
          val c = b.add(fanInStrategy(sources.size))
          for ((shape, i) <- shapes.zipWithIndex) {
            shape ~> c.in(i)
          }
          SourceShape(c.out)
        })
    }

  /**
   * Combines several sources with fan-in strategy like [[Merge]] or [[Concat]] into a single [[Source]] with a materialized value.
   */
  def combineMat[T, U, M1, M2, M](first: Source[T, M1], second: Source[T, M2])(
      @nowarn
      @deprecatedName(Symbol("strategy"))
      fanInStrategy: Int => Graph[UniformFanInShape[T, U], NotUsed])(matF: (M1, M2) => M): Source[U, M] =
    Source.fromGraph(GraphDSL.createGraph(first, second)(matF) { implicit b => (shape1, shape2) =>
      import GraphDSL.Implicits._
      val c = b.add(fanInStrategy(2))
      shape1 ~> c.in(0)
      shape2 ~> c.in(1)
      SourceShape(c.out)
    })

  /**
   * Combine the elements of multiple streams into a stream of sequences.
   */
  def zipN[T](sources: immutable.Seq[Source[T, _]]): Source[immutable.Seq[T], NotUsed] =
    zipWithN(ConstantFun.scalaIdentityFunction[immutable.Seq[T]])(sources).addAttributes(DefaultAttributes.zipN)

  /**
   * Combine the elements of multiple streams into a stream of sequences using a combiner function.
   */
  def zipWithN[T, O](zipper: immutable.Seq[T] => O)(sources: immutable.Seq[Source[T, _]]): Source[O, NotUsed] = {
    val source = sources match {
      case immutable.Seq()       => empty[O]
      case immutable.Seq(source) => source.map(t => zipper(immutable.Seq(t))).mapMaterializedValue(_ => NotUsed)
      case s1 +: s2 +: ss        => combine(s1, s2, ss: _*)(ZipWithN(zipper))
      case _                     => throw new IllegalArgumentException() // just to please compiler completeness check
    }

    source.addAttributes(DefaultAttributes.zipWithN)
  }

  /**
   * Creates a `Source` that is materialized as an [[pekko.stream.BoundedSourceQueue]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. The buffer size is passed in as a parameter.
   * Elements in the buffer will be discarded if downstream is terminated.
   *
   * Pushed elements may be dropped if there is no space available in the buffer. Elements will also be dropped if the
   * queue is failed through the materialized `BoundedQueueSource` or the `Source` is cancelled by the downstream.
   * An element that was reported to be `enqueued` is not guaranteed to be processed by the rest of the stream. If the
   * queue is failed by calling `BoundedQueueSource.fail` or the downstream cancels the stream, elements in the buffer
   * are discarded.
   *
   * Acknowledgement of pushed elements is immediate.
   * [[pekko.stream.BoundedSourceQueue.offer]] returns [[pekko.stream.QueueOfferResult]] which is implemented as:
   *
   * `QueueOfferResult.Enqueued`       element was added to buffer, but may still be discarded later when the queue is
   *                                   failed or cancelled
   * `QueueOfferResult.Dropped`        element was dropped
   * `QueueOfferResult.QueueComplete`  the queue was completed with [[pekko.stream.BoundedSourceQueue.complete]]
   * `QueueOfferResult.Failure`        the queue was failed with [[pekko.stream.BoundedSourceQueue.fail]] or if
   *                                   the stream failed
   *
   * @param bufferSize size of the buffer in number of elements
   */
  def queue[T](bufferSize: Int): Source[T, BoundedSourceQueue[T]] =
    Source.fromGraph(new BoundedSourceQueueStage[T](bufferSize))

  /**
   * Creates a Source that will immediately execute the provided function `producer` with a [[BoundedSourceQueue]] when materialized.
   * This allows defining element production logic at Source creation time.
   *
   * The function `producer` can push elements to the stream using the provided queue. The queue behaves the same as in [[Source.queue]]:
   * <br>
   * - Elements are emitted when there is downstream demand, buffered otherwise
   * <br>
   * - Elements are dropped if the buffer is full
   * <br>
   * - Buffered elements are discarded if downstream terminates
   * <br>
   * You should never block the producer thread, as it will block the stream from processing elements.
   * If the function `producer` throws an exception, the queue will be failed and the exception will be propagated to the stream.
   *
   * Example usage:
   * {{{
   * Source.create[Int](10) { queue =>
   *   // This code is executed when the source is materialized
   *   queue.offer(1)
   *   queue.offer(2)
   *   queue.offer(3)
   *   queue.complete()
   * }
   * }}}
   *
   * @param bufferSize the size of the buffer (number of elements)
   * @param producer function that receives the queue and defines how to produce data
   * @return a Source that emits elements pushed to the queue
   * @since 1.2.0
   */
  def create[T](bufferSize: Int)(producer: BoundedSourceQueue[T] => Unit): Source[T, NotUsed] =
    Source.fromGraph(new BoundedSourceQueueStage[T](bufferSize).mapMaterializedValue(queue => {
      try {
        producer(queue)
        NotUsed
      } catch {
        case NonFatal(e) =>
          queue.fail(e)
          NotUsed
      }
    }).withAttributes(DefaultAttributes.create))

  /**
   * Creates a `Source` that is materialized as an [[pekko.stream.scaladsl.SourceQueueWithComplete]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. Elements in the buffer will be discarded
   * if downstream is terminated.
   *
   * Depending on the defined [[pekko.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[pekko.stream.scaladsl.SourceQueueWithComplete.offer]] returns `Future[QueueOfferResult]` which completes with
   * `QueueOfferResult.Enqueued` if element was added to buffer or sent downstream. It completes with
   * `QueueOfferResult.Dropped` if element was dropped. Can also complete  with `QueueOfferResult.Failure` -
   * when stream failed or `QueueOfferResult.QueueClosed` when downstream is completed.
   *
   * The strategy [[pekko.stream.OverflowStrategy.backpressure]] will not complete last `offer():Future`
   * call when buffer is full.
   *
   * Instead of using the strategy [[pekko.stream.OverflowStrategy.dropNew]] it's recommended to use
   * `Source.queue(bufferSize)` instead which returns a [[QueueOfferResult]] synchronously.
   *
   * You can watch accessibility of stream with [[pekko.stream.scaladsl.SourceQueueWithComplete.watchCompletion]].
   * It returns future that completes with success when the operator is completed or fails when the stream is failed.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received message will wait
   * for downstream demand unless there is another message waiting for downstream demand, in that case
   * offer result will be completed according to the overflow strategy.
   *
   * The materialized SourceQueue may only be used from a single producer.
   *
   * @param bufferSize size of buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def queue[T](bufferSize: Int, overflowStrategy: OverflowStrategy): Source[T, SourceQueueWithComplete[T]] =
    queue(bufferSize, overflowStrategy, maxConcurrentOffers = 1)

  /**
   * Creates a `Source` that is materialized as an [[pekko.stream.scaladsl.SourceQueueWithComplete]].
   * You can push elements to the queue and they will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received. Elements in the buffer will be discarded
   * if downstream is terminated.
   *
   * Depending on the defined [[pekko.stream.OverflowStrategy]] it might drop elements if
   * there is no space available in the buffer.
   *
   * Acknowledgement mechanism is available.
   * [[pekko.stream.scaladsl.SourceQueueWithComplete.offer]] returns `Future[QueueOfferResult]` which completes with
   * `QueueOfferResult.Enqueued` if element was added to buffer or sent downstream. It completes with
   * `QueueOfferResult.Dropped` if element was dropped. Can also complete  with `QueueOfferResult.Failure` -
   * when stream failed or `QueueOfferResult.QueueClosed` when downstream is completed.
   *
   * The strategy [[pekko.stream.OverflowStrategy.backpressure]] will not complete `maxConcurrentOffers` number of
   * `offer():Future` call when buffer is full.
   *
   * Instead of using the strategy [[pekko.stream.OverflowStrategy.dropNew]] it's recommended to use
   * `Source.queue(bufferSize)` instead which returns a [[QueueOfferResult]] synchronously.
   *
   * You can watch accessibility of stream with [[pekko.stream.scaladsl.SourceQueueWithComplete.watchCompletion]].
   * It returns future that completes with success when the operator is completed or fails when the stream is failed.
   *
   * The buffer can be disabled by using `bufferSize` of 0 and then received message will wait
   * for downstream demand unless there is another message waiting for downstream demand, in that case
   * offer result will be completed according to the overflow strategy.
   *
   * The materialized SourceQueue may be used by up to maxConcurrentOffers concurrent producers.
   *
   * @param bufferSize size of buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   * @param maxConcurrentOffers maximum number of pending offers when buffer is full, should be greater than 0, not
   *                            applicable when `OverflowStrategy.dropNew` is used
   */
  def queue[T](
      bufferSize: Int,
      overflowStrategy: OverflowStrategy,
      maxConcurrentOffers: Int): Source[T, SourceQueueWithComplete[T]] =
    Source.fromGraph(
      new QueueSource(bufferSize, overflowStrategy, maxConcurrentOffers).withAttributes(DefaultAttributes.queueSource))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * Interaction with resource happens in a blocking way.
   *
   * Example:
   * {{{
   * Source.unfoldResource(
   *   () => new BufferedReader(new FileReader("...")),
   *   reader => Option(reader.readLine()),
   *   reader => reader.close())
   * }}}
   *
   * You can use the supervision strategy to handle exceptions for `read` function. All exceptions thrown by `create`
   * or `close` will fail the stream.
   *
   * `Restart` supervision strategy will close and create blocking IO again. Default strategy is `Stop` which means
   * that stream will be terminated on error in `read` function by default.
   *
   * You can configure the default dispatcher for this Source by changing the `pekko.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * @param create - function that is called on stream start and creates/opens resource.
   * @param read - function that reads data from opened resource. It is called each time backpressure signal
   *             is received. Stream calls close and completes when `read` returns None.
   * @param close - function that closes resource
   */
  def unfoldResource[T, S](create: () => S, read: (S) => Option[T], close: (S) => Unit): Source[T, NotUsed] =
    Source.fromGraph(new UnfoldResourceSource(create, read, close))

  /**
   * Start a new `Source` from some resource which can be opened, read and closed.
   * It's similar to `unfoldResource` but takes functions that return `Futures` instead of plain values.
   *
   * You can use the supervision strategy to handle exceptions for `read` function or failures of produced `Futures`.
   * All exceptions thrown by `create` or `close` as well as fails of returned futures will fail the stream.
   *
   * `Restart` supervision strategy will close and create resource. Default strategy is `Stop` which means
   * that stream will be terminated on error in `read` function (or future) by default.
   *
   * You can configure the default dispatcher for this Source by changing the `pekko.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * @param create - function that is called on stream start and creates/opens resource.
   * @param read - function that reads data from opened resource. It is called each time backpressure signal
   *             is received. Stream calls close and completes when `Future` from read function returns None.
   * @param close - function that closes resource
   */
  def unfoldResourceAsync[T, S](
      create: () => Future[S],
      read: (S) => Future[Option[T]],
      close: (S) => Future[Done]): Source[T, NotUsed] =
    Source.fromGraph(new UnfoldResourceSourceAsync(create, read, close))

  /**
   * Merge multiple [[Source]]s. Prefer the sources depending on the 'priority' parameters.
   * The provided sources and priorities must have the same size and order.
   *
   * '''emits''' when one of the inputs has an element available, preferring inputs based on the 'priority' parameters if both have elements available
   *
   * '''backpressures''' when downstream backpressures
   *
   * '''completes''' when both upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)
   *
   * '''Cancels when''' downstream cancels
   */
  def mergePrioritizedN[T](
      sourcesAndPriorities: immutable.Seq[(Source[T, _], Int)],
      eagerComplete: Boolean): Source[T, NotUsed] = {
    sourcesAndPriorities match {
      case immutable.Seq()            => Source.empty
      case immutable.Seq((source, _)) => source.mapMaterializedValue(_ => NotUsed)
      case sourcesAndPriorities       =>
        val (sources, priorities) = sourcesAndPriorities.unzip
        combine(sources.head, sources(1), sources.drop(2): _*)(_ => MergePrioritized(priorities, eagerComplete))
    }
  }
}

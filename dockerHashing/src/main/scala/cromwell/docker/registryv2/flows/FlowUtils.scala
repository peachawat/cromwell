package cromwell.docker.registryv2.flows

import akka.stream.FanOutShape2
import akka.stream.scaladsl.{Flow, GraphDSL, Partition}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object FlowUtils {

  /**
    * Takes an input of the form (Try[T], U) and exposes 2 output ports.
    * U is the type of the context value to be passed along.
    * The first one will emit a pair of the form (value, context) if the try is a success.
    * The second one will emit a pair of the form (throwable, context) if the try is a failure.
    */
  def fanOutTry[T, U] = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val partition = builder.add(Partition[(Try[T], U)](2, {
      case (Success(_), _) => 0
      case (Failure(_), _) => 1
    }))

    val successOut: PortOps[(T, U)] = partition.out(0) collect {
      case (Success(value), flowContext) => (value, flowContext)
    }

    val failureOut: PortOps[(Throwable, U)] = partition.out(1) collect {
      case (Failure(failure), flowContext) => (failure, flowContext)
    }

    new FanOutShape2[(Try[T], U), (T, U), (Throwable, U)](partition.in, successOut.outlet, failureOut.outlet)
  }

  /**
    * Takes an input of the form (Try[T], U) and exposes 2 output ports.
    * U is the type of the context value to be passed along.
    * The first one will emit a pair of the form (value, context) if the try is a success.
    * The second one will emit a pair of the form (throwable, context) if the try is a failure.
    */
  def fanOutFuture[T, U](implicit ec: ExecutionContext) = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val futureIn = builder.add(Flow[(Future[T], U)].mapAsync(1)({
      case (f, c) =>
        f flatMap {
          result => Future.successful(Success(result) -> c)
        } recoverWith {
          case failure => Future.successful(Failure(failure) -> c)
        }
    }))
    
    val tryFan = builder.add(fanOutTry[T, U])

    futureIn.out ~> tryFan.in

    new FanOutShape2[(Future[T], U), (T, U), (Throwable, U)](futureIn.in, tryFan.out0, tryFan.out1)
  }
}

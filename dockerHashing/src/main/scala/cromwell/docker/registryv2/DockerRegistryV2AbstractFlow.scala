package cromwell.docker.registryv2

import akka.NotUsed
import akka.actor.Scheduler
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, Merge}
import cats.data.NonEmptyList
import cromwell.docker.DockerInfoActor._
import cromwell.docker.registryv2.DockerRegistryV2AbstractFlow._
import cromwell.docker.registryv2.flows.HttpFlowWithRetry.ContextWithRequest
import cromwell.docker.registryv2.flows.{FlowUtils, HttpFlowWithRetry}
import cromwell.docker.{DockerFlow, DockerHashResult, DockerImageIdentifier}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object DockerRegistryV2AbstractFlow {
  type HttpDockerFlow = Flow[(HttpRequest, ContextWithRequest[DockerHashContext]), (Try[HttpResponse], ContextWithRequest[DockerHashContext]), NotUsed]
  val StrictTimeout = 30 seconds
  val HashAlgorithm = "sha256"
  
  val DigestHeaderName = "Docker-Content-Digest".toLowerCase
  val AcceptHeader = HttpHeader.parse("Accept", "application/vnd.docker.distribution.manifest.v2+json") match {
    case ParsingResult.Ok(header, errors) =>
      errors foreach { err => logger.warn(err.formatPretty) }
      header
    case ParsingResult.Error(error) => throw new RuntimeException(error.formatPretty)
  }

  case class UnsuccessfulHttpResponseException(httpResponse: HttpResponse) extends Exception
}

/**
  * Implements logic to build a flow that can retrieve a docker hash 
  * from a registry server conforming to the docker registry API v2 Specification:
  * https://docs.docker.com/registry/spec/api/
  */
abstract class DockerRegistryV2AbstractFlow(httpClientFlow: HttpDockerFlow)(implicit ec: ExecutionContext, materializer: ActorMaterializer, scheduler: Scheduler) extends DockerFlow {
  // Wraps the Http flow in a retryable flow to enable auto retries
  final private val httpFlowWithRetry = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._
    val retryHttpFlow = builder.add(new HttpFlowWithRetry[DockerHashContext](httpClientFlow).flow)
    
    // Force the response entity to be strict. This makes sure that whatever happens to the response later we
    // won't leave it hanging and potentially lock the pool. 
    // See http://doc.akka.io/docs/akka-http/10.0.5/scala/http/client-side/request-level.html#using-the-future-based-api-in-actors
    // Note that in this particular case it's ok to force the loading of the entity in memory
    // because we use HEAD Http method when we only care about the headers. Therefore there's no unnecessary memory usage.
    /* Returns a (Try[HttpResponse], DockerHashContext) */
    val strictHttpResponse = retryHttpFlow.out0.mapAsync(1){
      case (response, context) => response.toStrict(StrictTimeout) map { Success(_) -> context } recoverWith {
        case failure => Future.successful(Failure(failure) -> context)
      }
    }
    
    // Splits successful `toStrict` responses from failures
    val partitionStrictResponse = builder.add(FlowUtils.fanOutTry[HttpResponse, DockerHashContext])

    // Merge failures from retryHttpFlow.out1 (failed http responses)
    // and partitionStrictResponse.out1 (failed to `toStrict` the response)
    val mergeFailures = builder.add(Merge[(Throwable, DockerHashContext)](2))
    
    strictHttpResponse.outlet ~> partitionStrictResponse.in

    retryHttpFlow.out1 ~> mergeFailures
    partitionStrictResponse.out1 ~> mergeFailures
    
    new FanOutShape2(retryHttpFlow.in, partitionStrictResponse.out0, mergeFailures.out)
  }

  private [registryv2] val tokenFlow = {
    val responseHandlerFlow = Flow[(HttpResponse, DockerHashContext)]
      .map(Function.tupled(tokenResponseHandler))
      // Map the Future[String] token to a Future[Option[String]].
      // This allows potential users of this class to override this flow and return an empty token
      .map { case (futureToken, context) => (futureToken map Option.apply, context) }
    
    requestTransformFlow(buildTokenRequest, responseHandlerFlow)
  }

  private [registryv2] val manifestFlow = {
    val responseHandlerFlow = Flow[(HttpResponse, DockerHashContext)].map(Function.tupled(manifestResponseHandler))
    requestTransformFlow(Function.tupled(buildManifestRequest _), responseHandlerFlow)
  }
  
  /**
    * Builds a flow for this docker registry
    */
  def buildFlow() = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val token = builder.add(tokenFlow)
    val manifest = builder.add(manifestFlow)
    val mergeResponses = builder.add(Merge[(DockerHashResponse, DockerHashContext)](3))
    
    token.out0 ~> manifest.in
                  manifest.out0 ~> mergeResponses.in(0)
                  manifest.out1 ~> mergeResponses.in(1)
    token.out1       ~>            mergeResponses.in(2)
    
    FlowShape(token.in, mergeResponses.out)
  }

  /**
    * Returns true if this flow is able to process this docker image,
    * false otherwise
    */
  def accepts(dockerImageIdentifier: DockerImageIdentifier) = dockerImageIdentifier.host.contains(registryHostName)
  
  /* Methods that must to be implemented by a subclass */

  /**
    * (e.g registry-1.docker.io)
    */
  protected def registryHostName: String

  /**
    * (e.g auth.docker.io)
    */
  protected def authorizationServerHostName: String

  /**
    * Builds the list of headers for the token request
    */
  protected def buildTokenRequestHeaders(dockerHashContext: DockerHashContext): List[HttpHeader]
  
  /* Methods that may be overridden by a subclass */

  /**
    * service parameter that can be used in the token request
    * (e.g https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/ubuntu:pull)
    */
  protected def serviceName: Option[String] = None

  /**
    * Builds the token URI to be queried based on a DockerImageID
    */
  protected def buildTokenRequestUri(dockerImageID: DockerImageIdentifier): String = {
    val service = serviceName map { name => s"service=$name&" } getOrElse ""
    s"https://$authorizationServerHostName/token?${service}scope=repository:${dockerImageID.nameWithDefaultRepository}:pull"
  }

  /**
    * Http method used for the manifest request
    */
  protected def manifestRequestHttpMethod: HttpMethod = HttpMethods.GET

  /**
    * Generic method to build a flow that creates a request, sends it,
    * gets the result and performs some transformation on the result
    * @param requestBuilderFunction Function that builds a request from an input object of type A
    * @param responseHandlerFlow Function that transforms a response in an object of type Try[B]
    * @tparam A type of the input value of the flow
    * @tparam B type of the output value of the flow, if successful
    * @return flow
    */
  private def requestTransformFlow[A, B](
                                          requestBuilderFunction: A => (HttpRequest, DockerHashContext),
                                          responseHandlerFlow: Flow[(HttpResponse, DockerHashContext), (Future[B], DockerHashContext), NotUsed]
                                        ) = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    // Build a request from an A
    val request = builder.add(Flow.fromFunction(requestBuilderFunction))

    // Perform http requests
    val http = builder.add(httpFlowWithRetry)
    
    // We got a HttpResponse, it might still be an unsuccessful response though.
    // Regardless send it through the responseHandlerFlow
    val finalResponse = http.out0.via(responseHandlerFlow)
    
    // The request could not be executed, map it to a failure message
    val failedRequest = http.out1.map({
      case (failure, commandContext) => throwableToFailureMessage(failure, commandContext)
    })

    // Partition processed responses
    val processResponse = builder.add(FlowUtils.fanOutFuture[B, DockerHashContext])
    
    val successfulProcessing = processResponse.out0
    val failedProcessing = processResponse.out1.map(Function.tupled(throwableToFailureMessage))

    // Merge failures from failed response and failed parsing
    val mergeFailures = builder.add(Merge[(DockerHashResponse, DockerHashContext)](2))

    request ~> http.in
    finalResponse ~> processResponse.in

    failedRequest ~> mergeFailures.in(0)
    failedProcessing ~> mergeFailures.in(1)

    new FanOutShape2(request.in, successfulProcessing, mergeFailures.out)
  }

  /**
    * Builds the token request
    */
  private def buildTokenRequest(dockerHashContext: DockerHashContext) = {
    val authorizationHeaders = buildTokenRequestHeaders(dockerHashContext)

    val tokenHttpRequest = HttpRequest(
      method = HttpMethods.GET,
      uri = buildTokenRequestUri(dockerHashContext.dockerImageID),
      headers = authorizationHeaders
    )

    (tokenHttpRequest, dockerHashContext)
  }

  /**
    * Parse the http response coming back from the token request and returns the body as JsObject
    */
  private def tokenResponseHandler(response: HttpResponse, dockerHashContext: DockerHashContext): (Future[String], DockerHashContext) = response match {
    case httpResponse if httpResponse.status.isSuccess() =>
      (
        Unmarshal(httpResponse.entity).to[String] flatMap { body =>
          Future.fromTry(extractToken(body.parseJson.asJsObject))
        },
        dockerHashContext
      )
    case httpResponse =>
        (
          Future.failed(UnsuccessfulHttpResponseException(httpResponse)),
          dockerHashContext
        )
  }

  /**
    * Extract the access token from the json body of the http response
    */
  private def extractToken(jsObject: JsObject): Try[String] = {
    jsObject.fields.get("token") match {
      case Some(token: JsString) => Success(token.value)
      case Some(_) => Failure(new Exception("Token response contains a non-string token field"))
      case None => Failure(new Exception("Token response did not contain a token field"))
    }
  }

  /**
    * Builds the manifest URI to be queried based on a DockerImageID
    */
  private def buildManifestUri(dockerImageID: DockerImageIdentifier): String = {
    s"https://$registryHostName/v2/${dockerImageID.nameWithDefaultRepository}/manifests/${dockerImageID.reference}"
  }

  /**
    * Builds the manifest http request
    */
  private def buildManifestRequest(token: Option[String], dockerHashContext: DockerHashContext) = {
    val manifestRequest = token match {
      case Some(authToken) =>
        HttpRequest(
          method = manifestRequestHttpMethod,
          uri = buildManifestUri(dockerHashContext.dockerImageID),
          headers = scala.collection.immutable.Seq(AcceptHeader, Authorization(OAuth2BearerToken(authToken)))
        )
      case None =>
        HttpRequest(
          method = manifestRequestHttpMethod,
          uri = buildManifestUri(dockerHashContext.dockerImageID),
          headers = scala.collection.immutable.Seq(AcceptHeader)
        )
    }

    (manifestRequest, dockerHashContext)
  }

  /**
    * Handles the http response from the manifest request
    */
  private def manifestResponseHandler(response: HttpResponse,
                                      dockerHashContext: DockerHashContext
                                     ): (Future[DockerHashSuccessResponse], DockerHashContext) = response match {
    case httpResponse if httpResponse.status.isSuccess() =>
      (
        httpResponseToDockerResponse(response, dockerHashContext),
        dockerHashContext
        )
    case httpResponse => 
      (
        Future.failed(UnsuccessfulHttpResponseException(httpResponse)),
        dockerHashContext
      )
  }
  
  protected final def isManifestV2(response: HttpResponse): Boolean = response.entity.contentType.mediaType.value == AcceptHeader.value()
  
  protected def parseV2Manifest(response: HttpResponse, dockerHashContext: DockerHashContext): Future[Option[DockerManifest]] = if (isManifestV2(response)) {
    def errorMessage(errors: NonEmptyList[String]) = 
      s"Docker manifest for ${dockerHashContext.request.dockerImageID.fullName} could not be parsed: ${errors.toList.mkString(", ")}"


    def parsedManifest(bodyAsString: String) =
      DockerManifest.parseManifest(bodyAsString) match {
        case Right(manifest) => Option(manifest)
        case Left(errors) =>
          logger.warn(errorMessage(errors))
          None
      }

    Unmarshal(response.entity).to[String].map(parsedManifest)
  } else Future.successful(None)

  /**
    * Extracts the digest from the response headers
    */
  protected def httpResponseToDockerResponse(response: HttpResponse, dockerHashContext: DockerHashContext) = {
    for {
      digest <- Future.fromTry(extractDigestFromHeader(response.headers))
      manifest <- parseV2Manifest(response, dockerHashContext)
    } yield DockerHashSuccessResponse(digest, manifest.map(_.compressedSize).map(DockerSize.apply), dockerHashContext.request)
  }

  protected final def extractDigestFromHeader(headers: Seq[HttpHeader]): Try[DockerHashResult] = {
    headers find { _.is(DigestHeaderName) } map { _.value() } map DockerHashResult.fromString getOrElse Failure(new Exception("Cannot find digest header"))
  }

  /**
    * Maps a throwable into a DockerHashFailedResponse
    */
  private def throwableToFailureMessage(throwable: Throwable, dockerHashContext: DockerHashContext): (DockerHashResponse, DockerHashContext) = {
    throwable match {
      case UnsuccessfulHttpResponseException(httpResponse) => httpFailureMapper(httpResponse, dockerHashContext)
      case other => (DockerHashFailedResponse(other, dockerHashContext.request), dockerHashContext)
    }
  }

  /**
    * Maps a failed http response to a DockerHashFailureResponse
    */
  protected def httpFailureMapper(response: HttpResponse, dockerHashContext: DockerHashContext) = {
    response.status match {
      case StatusCodes.NotFound => (DockerHashNotFound(dockerHashContext.request), dockerHashContext)
      case StatusCodes.Unauthorized => (DockerHashUnauthorized(dockerHashContext.request), dockerHashContext)
      case other => (
        DockerHashFailedResponse(
          new Exception(s"Docker hash lookup failed with code ${other.intValue()}. ${other.defaultMessage()}"),
          dockerHashContext.request
        ),
        dockerHashContext
        )
    }
  }
}

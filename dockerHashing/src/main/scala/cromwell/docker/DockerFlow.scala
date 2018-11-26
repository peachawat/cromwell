package cromwell.docker

import akka.NotUsed
import akka.stream.{FlowShape, Graph}

/**
  * Interface used by the docker hash actor to build a flow and validate whether or not it can accept an image.
  */
trait DockerFlow {
  def buildFlow(): Graph[FlowShape[DockerInfoActor.DockerHashContext, (DockerInfoActor.DockerHashResponse, DockerInfoActor.DockerHashContext)], NotUsed]
  def accepts(dockerImageIdentifier: DockerImageIdentifier): Boolean
}

package cromwell.docker.registryv2

import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.show._
import common.Checked
import io.circe._
import io.circe.generic.auto._

case class DockerManifest(layers: Array[DockerLayer]) {
  lazy val compressedSize: Long = layers.map(_.size).sum
}

case class DockerLayer(size: Long)

object DockerManifest {
  def parseManifest(payload: String): Checked[DockerManifest] =
    parser.parse(payload)
      .flatMap(_.as[DockerManifest])
      .leftMap(_.show).leftMap(NonEmptyList.one)
}

package cloud.nio.impl.drs

import cloud.nio.spi.{CloudNioFileProvider, CloudNioFileSystemProvider}
import com.typesafe.config.Config


class DrsCloudNioFileSystemProvider(rootConfig: Config) extends CloudNioFileSystemProvider {

  val drsPathResolver = DrsPathResolver(rootConfig)

  override def config: Config = rootConfig

  override def fileProvider: CloudNioFileProvider = new DrsCloudNioFileProvider(rootConfig, getScheme, drsPathResolver)

  override def isFatal(exception: Exception): Boolean = false

  override def isTransient(exception: Exception): Boolean = false

  override def getScheme: String = "dos"
}

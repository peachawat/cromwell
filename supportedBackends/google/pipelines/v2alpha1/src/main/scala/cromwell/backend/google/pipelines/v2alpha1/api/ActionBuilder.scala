package cromwell.backend.google.pipelines.v2alpha1.api

import akka.http.scaladsl.model.ContentTypes
import com.google.api.services.genomics.v2alpha1.model.{Action, Mount, Secret}
import cromwell.backend.google.pipelines.common.api.PipelinesApiRequestFactory.CreatePipelineDockerKeyAndToken
import cromwell.backend.google.pipelines.common.{PipelinesApiInput, PipelinesApiOutput, PipelinesParameter}
import cromwell.backend.google.pipelines.v2alpha1.api.ActionBuilder.Labels._
import cromwell.backend.google.pipelines.v2alpha1.api.ActionFlag.ActionFlag
import cromwell.docker.DockerImageIdentifier
import cromwell.docker.registryv2.flows.dockerhub.DockerHub
import mouse.all._
import org.apache.commons.text.StringEscapeUtils

import scala.collection.JavaConverters._

/**
  * Utility singleton to create high level actions.
  */
object ActionBuilder {
  object Labels {
    object Key {
      /**
        * Very short description of the action
        */
      val Tag = "tag"
      val Logging = "logging"
      val InputName = "inputName"
      val OutputName = "outputName"
    }
    object Value {
      val ContainerSetup = "ContainerSetup"
      val UserAction = "UserAction"
      val Localization = "Localization"
      val Delocalization = "Delocalization"
      val Background = "Background"
    }
  }

  implicit class EnhancedAction(val action: Action) extends AnyVal {
    private def javaFlags(flags: List[ActionFlag]) = flags.map(_.toString).asJava

    def withCommand(command: String*): Action = action.setCommands(command.toList.asJava)
    def withFlags(flags: List[ActionFlag]): Action = action.setFlags(flags |> javaFlags)
    def withMounts(mounts: List[Mount]): Action = action.setMounts(mounts.asJava)
    def withLabels(labels: Map[String, String]): Action = action.setLabels(labels.asJava)

    def scalaLabels: Map[String, String] = {
      val list = for {
        keyValueList <- Option(action.getLabels).toList
        keyValue <- keyValueList.asScala
      } yield keyValue
      list.toMap
    }
  }

  object Gsutil {
    private val contentTypeText = ContentTypes.`text/plain(UTF-8)`.toString()
    val ContentTypeTextHeader = s"Content-Type: $contentTypeText"
  }

  val cloudSdkImage = "google/cloud-sdk:slim"
  def cloudSdkAction: Action = new Action().setImageUri(cloudSdkImage)

  def withImage(image: String) = new Action()
    .setImageUri(image)

  def userAction(docker: String,
                 scriptContainerPath: String,
                 mounts: List[Mount],
                 jobShell: String,
                 privateDockerKeyAndToken: Option[CreatePipelineDockerKeyAndToken]): Action = {

    val dockerImageIdentifier = DockerImageIdentifier.fromString(docker)

    val secret = for {
      imageId <- dockerImageIdentifier.toOption
      if DockerHub.isValidDockerHubHost(imageId.host) // This token only works for Docker Hub and not other repositories.
      keyAndToken <- privateDockerKeyAndToken
      s = new Secret().setKeyName(keyAndToken.key).setCipherText(keyAndToken.encryptedToken)
    } yield s

    new Action()
      .setImageUri(docker)
      .setCommands(List(jobShell, scriptContainerPath).asJava)
      .setMounts(mounts.asJava)
      .setEntrypoint("")
      .setLabels(Map(Key.Tag -> Value.UserAction).asJava)
      .setCredentials(secret.orNull)
  }

  def cloudSdkShellAction(shellCommand: String)(mounts: List[Mount] = List.empty, flags: List[ActionFlag] = List.empty, labels: Map[String, String] = Map.empty): Action =
    cloudSdkAction
      .withCommand("/bin/sh", "-c", if (shellCommand.contains("\n")) shellCommand |> ActionCommands.multiLineCommand else shellCommand)
      .withFlags(flags)
      .withMounts(mounts)
      .withLabels(labels)

  /**
    * Returns a set of labels for a parameter.
    *
    * @param pipelinesParameter Input or output parameter to label.
    * @return The labels.
    */
  def parameterLabels(pipelinesParameter: PipelinesParameter): Map[String, String] = {
    pipelinesParameter match {
      case _: PipelinesApiInput =>
        Map(
          Key.Tag -> Value.Localization,
          Key.InputName -> pipelinesParameter.name
        )
      case _: PipelinesApiOutput =>
        Map(
          Key.Tag -> Value.Delocalization,
          Key.OutputName -> pipelinesParameter.name
        )
    }
  }

  /**
    * Surrounds the list of Actions with a pair of starting and done Actions.
    *
    * @param description       Description of the list of Actions.
    * @param loggingLabelValue An entry from Value that describes the list of Actions.
    * @param isAlwaysRun       If true the pair of starting and done Actions will always be run.
    * @param actions           The list of Actions to surround.
    * @return The starting Action, the passed in list, and then a done Action.
    */
  def annotateTimestampedActions(description: String, loggingLabelValue: String, isAlwaysRun: Boolean = false)
                                (actions: List[Action]): List[Action] = {
    val flags = if (isAlwaysRun) List(ActionFlag.AlwaysRun) else List()
    val labels = Map(Key.Logging -> loggingLabelValue)
    val starting = List(logTimestampedAction(s"Starting $description.", flags, labels))
    val done = List(logTimestampedAction(s"Done $description.", flags, labels))
    starting ++ actions ++ done
  }

  /** Creates an Action that describes the parameter localization or delocalization. */
  def describeParameter(pipelinesParameter: PipelinesParameter,
                        actionLabels: Map[String, String]): Action = {
    pipelinesParameter match {
      case _: PipelinesApiInput =>
        val message = "Localizing input %s -> %s".format(
          quoted(pipelinesParameter.cloudPath),
          quoted(pipelinesParameter.containerPath),
        )
        ActionBuilder.logTimestampedAction(message, List(), actionLabels)
      case _: PipelinesApiOutput =>
        val message = "Delocalizing output %s -> %s".format(
          quoted(pipelinesParameter.containerPath),
          quoted(pipelinesParameter.cloudPath),
        )
        ActionBuilder.logTimestampedAction(message, List(ActionFlag.AlwaysRun), actionLabels)
    }
  }

  /** Creates an Action that logs the docker command for the passed in action. */
  def describeDocker(description: String, action: Action): Action = {
    ActionBuilder.logTimestampedAction(
      s"Running $description: ${ActionBuilder.toDockerRun(action)}",
      Nil,
      action.scalaLabels
    )
  }
  
  def timestampedMessage(message: String) = s"""printf '%s %s\\n' "$$(date -u '+%Y/%m/%d %H:%M:%S')" ${quoted(message)}"""

  /**
    * Creates an Action that logs the time as UTC plus prints the message. The original actionLabels will also be
    * applied to the logged action, except that Key.Tag -> some-value will be replaced with Key.Logging -> some-value.
    *
    * @param message      Message to output.
    * @param actionFlags  Flags from the original Action to also apply to the logging Action.
    * @param actionLabels Labels from the original Action to modify and apply to the logging Action.
    * @return A new Action that will log the time and print the message.
    */
  private def logTimestampedAction(message: String,
                                   actionFlags: List[ActionFlag],
                                   actionLabels: Map[String, String]): Action = {
    // Uses the cloudSdk image as that image will be used for other operations as well.
    cloudSdkShellAction(
      timestampedMessage(message)
    )(
      flags = actionFlags,
      labels = actionLabels collect {
        case (key, value) if key == Key.Tag => Key.Logging -> value
        case (key, value) => key -> value
      }
    )
  }

  /** Converts an Action to a `docker run ...` command runnable in the shell. */
  private[api] def toDockerRun(action: Action): String = {
    val commandArgs: String = Option(action.getCommands) match {
      case Some(commands) =>
        commands.asScala map {
          case command if Option(command).isDefined => s" ${quoted(command)}"
          case _ => ""
        } mkString ""
      case None => ""
    }

    val entrypointArg: String = Option(action.getEntrypoint) match {
      case Some(entrypoint) => s" --entrypoint=${quoted(entrypoint)}"
      case None => ""
    }

    val environmentArgs: String = Option(action.getEnvironment) match {
      case Some(environment) =>
        environment.asScala map {
          case (key, value) if Option(key).isDefined && Option(value).isDefined => s" -e ${quoted(s"$key:$value")}"
          case (key, _) if Option(key).isDefined => s" -e ${quoted(key)}"
          case _ => ""
        } mkString ""
      case None => ""
    }

    val imageArg: String = Option(action.getImageUri) match {
      case None => " <no docker image specified>"
      case Some(imageUri) => s" ${quoted(imageUri)}"
    }

    val mountArgs: String = Option(action.getMounts) match {
      case None => ""
      case Some(mounts) =>
        mounts.asScala map {
          case mount if Option(mount).isEmpty => ""
          case mount if mount.getReadOnly => s" -v ${quoted(s"/mnt/${mount.getDisk}:${mount.getPath}:ro")}"
          case mount => s" -v ${quoted(s"/mnt/${mount.getDisk}:${mount.getPath}")}"
        } mkString ""
    }

    val nameArg: String = Option(action.getName) match {
      case None => ""
      case Some(name) => s" --name ${quoted(name)}"
    }

    val pidNamespaceArg: String = Option(action.getPidNamespace) match {
      case Some(pidNamespace) => s" --pid=${quoted(pidNamespace)}"
      case None => ""
    }

    val portMappingArgs: String = Option(action.getPortMappings) match {
      case Some(portMappings) =>
        portMappings.asScala map {
          case (key, value) if Option(key).isDefined => s" -p ${quoted(s"$key:$value")}"
          case (_, value) => s" -p $value"
        } mkString ""
      case None => ""
    }

    val flagsArgs: String = Option(action.getFlags) match {
      case Some(flags) =>
        flags.asScala map { arg =>
          ActionFlag.values.find(_.toString == arg) match {
            case Some(ActionFlag.PublishExposedPorts) => " -P"
            case _ => ""
          }
        } mkString ""
      case None => ""
    }

    Array("docker run",
      nameArg,
      mountArgs,
      environmentArgs,
      pidNamespaceArg,
      flagsArgs,
      portMappingArgs,
      entrypointArg,
      imageArg,
      commandArgs,
    ).mkString
  }

  /** Quotes a string such that it's compatible as a string argument in the shell. */
  private def quoted(any: Any): String = {
    val str = String.valueOf(any)
    /*
    NOTE: escapeXSI is more compact than wrapping in single quotes. Newlines are also stripped by the shell, as they
    are by escapeXSI. If for some reason escapeXSI doesn't 100% work, say because it ends up stripping some required
    newlines, then consider adding a check for newlines and then using:

    "'" + str.replace("'", "'\"'\"'") + "'"
     */
    StringEscapeUtils.escapeXSI(str)
  }
}

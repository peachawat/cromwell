package cromwell.backend.io

import com.typesafe.config.Config
import cromwell.backend.{BackendJobBreadCrumb, BackendSpec, BackendWorkflowDescriptor}
import cromwell.core.path.DefaultPathBuilder
import cromwell.core.{JobKey, WorkflowId}
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import wdl4s.wdl.WdlCall
import wdl4s.wom.callable.WorkflowDefinition

class WorkflowPathsSpec extends FlatSpec with Matchers with BackendSpec {

  val backendConfig = mock[Config]

  "WorkflowPaths" should "provide correct paths for a workflow" ignore {
    when(backendConfig.hasPath(any[String])).thenReturn(true)
    when(backendConfig.getString(any[String])).thenReturn("local-cromwell-executions") // This is the folder defined in the config as the execution root dir
    val wd = buildWorkflowDescriptor(TestWorkflows.HelloWorld)
    val workflowPaths = new WorkflowPathsWithDocker(wd, backendConfig)
    val id = wd.id
    workflowPaths.workflowRoot.pathAsString shouldBe
      DefaultPathBuilder.get(s"local-cromwell-executions/wf_hello/$id").toAbsolutePath.pathAsString
    workflowPaths.dockerWorkflowRoot.pathAsString shouldBe
      s"/cromwell-executions/wf_hello/$id"
  }

  "WorkflowPaths" should "provide correct paths for a sub workflow" ignore {
    when(backendConfig.hasPath(any[String])).thenReturn(true)
    when(backendConfig.getString(any[String])).thenReturn("local-cromwell-executions") // This is the folder defined in the config as the execution root dir
    
    val rootWd = mock[BackendWorkflowDescriptor]
    val rootWorkflow = mock[WorkflowDefinition]
    val rootWorkflowId = WorkflowId.randomId()
    rootWorkflow.name returns "rootWorkflow"
    rootWd.workflow returns rootWorkflow
    rootWd.id returns rootWorkflowId

    val subWd = mock[BackendWorkflowDescriptor]
    val subWorkflow = mock[WorkflowDefinition]
    val subWorkflowId = WorkflowId.randomId()
    subWorkflow.name returns "subWorkflow"
    subWd.workflow returns subWorkflow
    subWd.id returns subWorkflowId
    
    val call1 = mock[WdlCall]
    call1.unqualifiedName returns "call1"
    val call2 = mock[WdlCall]
    call2.unqualifiedName returns "call2"
    
    val jobKey = new JobKey {
      override def scope = null//call1
      override def tag: String = "tag1"
      override def index: Option[Int] = Option(1)
      override def attempt: Int = 2
    }

    subWd.breadCrumbs returns List(BackendJobBreadCrumb(rootWorkflow, rootWorkflowId, jobKey))
    subWd.id returns subWorkflowId
    
    val workflowPaths = new WorkflowPathsWithDocker(subWd, backendConfig)
    workflowPaths.workflowRoot.pathAsString shouldBe
      DefaultPathBuilder.get(
        s"local-cromwell-executions/rootWorkflow/$rootWorkflowId/call-call1/shard-1/attempt-2/subWorkflow/$subWorkflowId"
      ).toAbsolutePath.pathAsString
    workflowPaths.dockerWorkflowRoot.pathAsString shouldBe s"/cromwell-executions/rootWorkflow/$rootWorkflowId/call-call1/shard-1/attempt-2/subWorkflow/$subWorkflowId"
  }
}

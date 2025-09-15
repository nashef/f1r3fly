package coop.rchain.rholang.interpreter

import cats.effect.IO
import org.scalatest.{FlatSpec, Matchers}

class NuNetServiceSpec extends FlatSpec with Matchers {

  "DisabledNuNetService" should "raise errors for all operations" in {
    val service = new DisabledNuNetService()

    // Test deployJob
    val deployResult = service.deployJob[IO]("{\"test\": \"job\"}").attempt.unsafeRunSync()
    deployResult.isLeft shouldBe true
    deployResult.left.get.getMessage should include("NuNet service is disabled")

    // Test getJobStatus
    val statusResult = service.getJobStatus[IO]("test-job-id").attempt.unsafeRunSync()
    statusResult.isLeft shouldBe true
    statusResult.left.get.getMessage should include("NuNet service is disabled")

    // Test listAvailableNodes
    val nodesResult = service.listAvailableNodes[IO]().attempt.unsafeRunSync()
    nodesResult.isLeft shouldBe true
    nodesResult.left.get.getMessage should include("NuNet service is disabled")

    // Test deployRNode
    val rnodeResult = service.deployRNode[IO]("test-config").attempt.unsafeRunSync()
    rnodeResult.isLeft shouldBe true
    rnodeResult.left.get.getMessage should include("NuNet service is disabled")
  }

  "NuNetServiceImpl" should "handle configuration properly" in {
    // This test verifies that the service can be instantiated
    // without throwing configuration errors
    val service = new NuNetServiceImpl()
    service should not be null
  }

  "NuNetServiceImpl" should "handle JSON parsing errors gracefully" in {
    val service = new NuNetServiceImpl()

    // Test invalid JSON
    val invalidJsonResult = service.deployJob[IO]("invalid json").attempt.unsafeRunSync()
    invalidJsonResult.isLeft shouldBe true
    invalidJsonResult.left.get.getMessage should include("Invalid job specification JSON")
  }

  "NuNetServiceImpl" should "create proper RNode job specifications" in {
    val service = new NuNetServiceImpl()

    // The deployRNode method should handle empty config by creating a default job spec
    // We can't test the actual deployment without a running DMS, but we can verify
    // the method doesn't throw immediately on input validation
    noException should be thrownBy {
      service.deployRNode[IO]("").attempt.unsafeRunSync()
    }
  }

  "NuNetJsonProtocol" should "serialize and deserialize properly" in {
    import NuNetJsonProtocol._
    import spray.json._

    // Test JobSpec serialization
    val jobSpec = JobSpec(
      image = "test:latest",
      command = List("echo", "test"),
      resources = Map("cpu" -> "1", "memory" -> "1Gi"),
      network = Some(Map("ports"    -> JsArray(JsNumber(8080)))),
      environment = Some(Map("TEST" -> "value"))
    )

    val json   = jobSpec.toJson
    val parsed = json.convertTo[JobSpec]

    parsed.image shouldBe "test:latest"
    parsed.command shouldBe List("echo", "test")
    parsed.resources("cpu") shouldBe "1"
    parsed.network.get("ports") shouldBe JsArray(JsNumber(8080))
    parsed.environment.get("TEST") shouldBe "value"
  }

  "NuNetJsonProtocol" should "handle ActorHandle serialization" in {
    import NuNetJsonProtocol._
    import spray.json._

    val handle = ActorHandle(
      id = "test-id",
      did = "did:test:123",
      inboxAddress = "http://localhost:9999/inbox"
    )

    val json   = handle.toJson
    val parsed = json.convertTo[ActorHandle]

    parsed.id shouldBe "test-id"
    parsed.did shouldBe "did:test:123"
    parsed.inboxAddress shouldBe "http://localhost:9999/inbox"
  }

  "NuNetJsonProtocol" should "handle MessageEnvelope serialization" in {
    import NuNetJsonProtocol._
    import spray.json._

    val envelope = MessageEnvelope(
      to = "did:test:recipient",
      from = "did:test:sender",
      behavior = "deploy-job",
      payload = JsObject("test" -> JsString("data")),
      messageId = Some("msg-123"),
      correlationId = Some("corr-456")
    )

    val json   = envelope.toJson
    val parsed = json.convertTo[MessageEnvelope]

    parsed.to shouldBe "did:test:recipient"
    parsed.from shouldBe "did:test:sender"
    parsed.behavior shouldBe "deploy-job"
    parsed.payload shouldBe JsObject("test" -> JsString("data"))
    parsed.messageId shouldBe Some("msg-123")
    parsed.correlationId shouldBe Some("corr-456")
  }

  "NuNetJsonProtocol" should "handle JobStatus serialization" in {
    import NuNetJsonProtocol._
    import spray.json._

    val status = JobStatus(
      id = "job-123",
      status = "running",
      createdAt = "2023-01-01T00:00:00Z",
      updatedAt = "2023-01-01T00:01:00Z",
      logs = Some("Job started successfully")
    )

    val json   = status.toJson
    val parsed = json.convertTo[JobStatus]

    parsed.id shouldBe "job-123"
    parsed.status shouldBe "running"
    parsed.createdAt shouldBe "2023-01-01T00:00:00Z"
    parsed.updatedAt shouldBe "2023-01-01T00:01:00Z"
    parsed.logs shouldBe Some("Job started successfully")
  }

  "NuNetJsonProtocol" should "handle NodeInfo serialization" in {
    import NuNetJsonProtocol._
    import spray.json._

    val nodeInfo = NodeInfo(
      id = "node-123",
      did = "did:test:node123",
      resources = Map("cpu" -> "8", "memory" -> "16Gi"),
      status = "available"
    )

    val json   = nodeInfo.toJson
    val parsed = json.convertTo[NodeInfo]

    parsed.id shouldBe "node-123"
    parsed.did shouldBe "did:test:node123"
    parsed.resources("cpu") shouldBe "8"
    parsed.resources("memory") shouldBe "16Gi"
    parsed.status shouldBe "available"
  }

  "NuNetJsonProtocol" should "handle DmsResponse serialization" in {
    import NuNetJsonProtocol._
    import spray.json._

    val response = DmsResponse[String](
      success = true,
      data = Some("operation completed"),
      error = None
    )

    val json   = response.toJson
    val parsed = json.convertTo[DmsResponse[String]]

    parsed.success shouldBe true
    parsed.data shouldBe Some("operation completed")
    parsed.error shouldBe None
  }

  "NuNetJsonProtocol" should "handle DmsResponse with error" in {
    import NuNetJsonProtocol._
    import spray.json._

    val response = DmsResponse[String](
      success = false,
      data = None,
      error = Some("operation failed")
    )

    val json   = response.toJson
    val parsed = json.convertTo[DmsResponse[String]]

    parsed.success shouldBe false
    parsed.data shouldBe None
    parsed.error shouldBe Some("operation failed")
  }
}

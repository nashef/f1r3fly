package coop.rchain.rholang.interpreter

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

// JSON protocol for NuNet DMS API
object NuNetJsonProtocol extends DefaultJsonProtocol {
  // Actor handle response
  case class ActorHandle(id: String, did: String, inboxAddress: String)

  // Message envelope for actor communication
  case class MessageEnvelope(
      to: String,
      from: String,
      behavior: String,
      payload: JsValue,
      messageId: Option[String] = None,
      correlationId: Option[String] = None
  )

  // Job deployment request
  case class JobSpec(
      image: String,
      command: List[String],
      resources: Map[String, String],
      network: Option[Map[String, JsValue]] = None,
      environment: Option[Map[String, String]] = None
  )

  // Job status response
  case class JobStatus(
      id: String,
      status: String,
      createdAt: String,
      updatedAt: String,
      logs: Option[String] = None
  )

  // Node information
  case class NodeInfo(
      id: String,
      did: String,
      resources: Map[String, String],
      status: String
  )

  // DMS response wrapper
  case class DmsResponse[T](success: Boolean, data: Option[T], error: Option[String])

  implicit val actorHandleFormat                = jsonFormat3(ActorHandle)
  implicit val messageEnvelopeFormat            = jsonFormat6(MessageEnvelope)
  implicit val jobSpecFormat                    = jsonFormat5(JobSpec)
  implicit val jobStatusFormat                  = jsonFormat5(JobStatus)
  implicit val nodeInfoFormat                   = jsonFormat4(NodeInfo)
  implicit def dmsResponseFormat[T: JsonFormat] = jsonFormat3(DmsResponse[T])
}

trait NuNetService {

  /** Deploy a compute job to the NuNet DMS */
  def deployJob[F[_]](jobSpec: String)(
      implicit F: Concurrent[F]
  ): F[String]

  /** Get the status of a deployed job */
  def getJobStatus[F[_]](jobId: String)(
      implicit F: Concurrent[F]
  ): F[String]

  /** List available compute nodes in the network */
  def listAvailableNodes[F[_]]()(
      implicit F: Concurrent[F]
  ): F[List[String]]

  /** Deploy an RNode instance on the DMS */
  def deployRNode[F[_]](config: String)(
      implicit F: Concurrent[F]
  ): F[String]
}

class DisabledNuNetService extends NuNetService {

  private[this] val logger: Logger = Logger[this.type]

  def deployJob[F[_]](jobSpec: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug("NuNet service is disabled - deployJob request ignored")
    F.raiseError(new UnsupportedOperationException("NuNet service is disabled via configuration"))
  }

  def getJobStatus[F[_]](jobId: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug("NuNet service is disabled - getJobStatus request ignored")
    F.raiseError(new UnsupportedOperationException("NuNet service is disabled via configuration"))
  }

  def listAvailableNodes[F[_]]()(implicit F: Concurrent[F]): F[List[String]] = {
    logger.debug("NuNet service is disabled - listAvailableNodes request ignored")
    F.raiseError(new UnsupportedOperationException("NuNet service is disabled via configuration"))
  }

  def deployRNode[F[_]](config: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug("NuNet service is disabled - deployRNode request ignored")
    F.raiseError(new UnsupportedOperationException("NuNet service is disabled via configuration"))
  }
}

class NuNetServiceImpl extends NuNetService {
  import NuNetJsonProtocol._

  private[this] val logger: Logger = Logger[this.type]

  implicit private val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  private val system                              = ActorSystem()
  implicit private val materializer: Materializer = Materializer(system)

  // Build NuNet DMS client configuration
  private val config = ConfigFactory.load()
  private val dmsUrl: String =
    if (config.hasPath("nunet.dms-url")) config.getString("nunet.dms-url")
    else "http://localhost:9999"

  private val actorDid: String =
    if (config.hasPath("nunet.actor-did")) config.getString("nunet.actor-did")
    else ""

  private val timeoutSec: Int =
    if (config.hasPath("nunet.timeout-sec")) config.getInt("nunet.timeout-sec")
    else 30

  private val validateConnection: Boolean =
    if (config.hasPath("nunet.validate-connection")) config.getBoolean("nunet.validate-connection")
    else true

  logger.info(s"NuNet service configured with DMS URL: $dmsUrl")

  // Validate DMS connection on startup if enabled
  if (validateConnection) {
    validateDmsConnection()
  }

  private def validateDmsConnection(): Unit = {
    logger.info("Validating NuNet DMS connection...")
    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = s"$dmsUrl/actor/handle"
    )

    Http()(system)
      .singleRequest(request)
      .map { response =>
        if (response.status.isSuccess()) {
          logger.info("NuNet DMS connection validated successfully")
        } else {
          logger.warn(s"NuNet DMS connection validation failed: ${response.status}")
        }
      }
      .recover {
        case e => logger.warn(s"NuNet DMS connection validation error: ${e.getMessage}")
      }
  }

  private def sendActorMessage[F[_]](behavior: String, payload: JsValue)(
      implicit F: Concurrent[F]
  ): F[String] = {
    val envelope = MessageEnvelope(
      to = actorDid,
      from = actorDid,
      behavior = behavior,
      payload = payload,
      messageId = Some(java.util.UUID.randomUUID().toString),
      correlationId = Some(java.util.UUID.randomUUID().toString)
    )

    val requestEntity = HttpEntity(
      ContentTypes.`application/json`,
      envelope.toJson.compactPrint
    )

    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$dmsUrl/actor/send",
      entity = requestEntity
    )

    logger.debug(s"Sending NuNet actor message: $behavior")

    val future = Http()(system)
      .singleRequest(request)
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(response.entity).to[String]
        } else {
          response.entity.toStrict(timeoutSec.seconds).map { entity =>
            val errorMsg = entity.data.utf8String
            logger.warn(s"NuNet DMS request failed: ${response.status} - $errorMsg")
            throw new RuntimeException(s"NuNet DMS error: ${response.status} - $errorMsg")
          }
        }
      }
      .recover {
        case NonFatal(e) =>
          logger.error(s"NuNet DMS request error: ${e.getMessage}", e)
          throw new RuntimeException(s"NuNet DMS communication error: ${e.getMessage}", e)
      }

    F.async { cb =>
      future.onComplete {
        case Success(value) => cb(Right(value))
        case Failure(error) => cb(Left(error))
      }
    }
  }

  def deployJob[F[_]](jobSpec: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug(s"Deploying job to NuNet DMS: ${jobSpec.take(200)}...")

    try {
      val jobSpecJson = jobSpec.parseJson
      sendActorMessage("deploy-job", jobSpecJson).map { response =>
        logger.info(s"Job deployment response: ${response.take(200)}...")
        response
      }
    } catch {
      case e: JsonParser.ParsingException =>
        F.raiseError(
          new IllegalArgumentException(s"Invalid job specification JSON: ${e.getMessage}")
        )
      case NonFatal(e) =>
        F.raiseError(new RuntimeException(s"Job deployment failed: ${e.getMessage}", e))
    }
  }

  def getJobStatus[F[_]](jobId: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug(s"Getting job status for: $jobId")

    val payload = JsObject("jobId" -> JsString(jobId))
    sendActorMessage("get-job-status", payload).map { response =>
      logger.debug(s"Job status response: ${response.take(200)}...")
      response
    }
  }

  def listAvailableNodes[F[_]]()(implicit F: Concurrent[F]): F[List[String]] = {
    logger.debug("Listing available nodes")

    val payload = JsObject()
    sendActorMessage("list-nodes", payload).map { response =>
      logger.debug(s"Available nodes response: ${response.take(200)}...")
      try {
        val json = response.parseJson
        json match {
          case JsArray(elements) =>
            elements.map(_.convertTo[NodeInfo].id).toList
          case _ =>
            List(response) // Return raw response if not parseable as node list
        }
      } catch {
        case _: JsonParser.ParsingException =>
          List(response) // Return raw response if JSON parsing fails
      }
    }
  }

  def deployRNode[F[_]](config: String)(implicit F: Concurrent[F]): F[String] = {
    logger.debug(s"Deploying RNode with config: ${config.take(200)}...")

    // Create a specialized RNode job specification
    val rnodeJobSpec = JobSpec(
      image = "f1r3fly/rnode:latest",
      command = List("./rnode", "run", "--standalone"),
      resources = Map(
        "cpu"     -> "2",
        "memory"  -> "4Gi",
        "storage" -> "10Gi"
      ),
      network = Some(
        Map(
          "ports" -> JsArray(JsNumber(40400), JsNumber(50505), JsNumber(9095))
        )
      ),
      environment = Some(
        Map(
          "RNODE_CONFIG" -> config
        )
      )
    )

    deployJob(rnodeJobSpec.toJson.compactPrint).map { response =>
      logger.info(s"RNode deployment response: ${response.take(200)}...")
      response
    }
  }
}

object NuNetServiceImpl {
  lazy val instance: NuNetService = new NuNetServiceImpl()
  lazy val disabled: NuNetService = new DisabledNuNetService()
}

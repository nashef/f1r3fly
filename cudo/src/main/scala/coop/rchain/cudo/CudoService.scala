package coop.rchain.cudo

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.cudo.exception.CudoException
import coop.rchain.cudo.model.{CreateVMRequest, VirtualMachine}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Scala wrapper around Java CudoClient for functional effects.
  * Provides cats-effect friendly API for Cudo Compute operations.
  */
class CudoService[F[_]: Sync](client: CudoClient) {

  /**
    * List all VMs in the configured project.
    */
  def listVMs: F[List[VirtualMachine]] =
    Sync[F].delay(client.listVMs().asScala.toList)

  /**
    * Get details of a specific VM.
    */
  def getVM(vmId: String): F[Option[VirtualMachine]] =
    Sync[F].delay {
      val opt = client.getVM(vmId)
      if (opt.isPresent) Some(opt.get()) else None
    }

  /**
    * Create a new virtual machine.
    */
  def createVM(request: CreateVMRequest): F[VirtualMachine] =
    Sync[F].delay(client.createVM(request))

  /**
    * Terminate and delete a VM.
    */
  def terminateVM(vmId: String): F[Unit] =
    Sync[F].delay(client.terminateVM(vmId))

  /**
    * Wait for VM to receive public IP address.
    * Returns None if timeout is reached.
    */
  def waitForPublicIP(vmId: String, timeoutSeconds: Int): F[Option[String]] =
    Sync[F].delay {
      val opt = client.waitForPublicIP(vmId, timeoutSeconds)
      if (opt.isPresent) Some(opt.get()) else None
    }

  /**
    * Deploy a complete shard (bootstrap + validators).
    *
    * @param shardSize Total number of nodes (minimum 3 for consensus)
    * @param dataCenterId Cudo data center identifier
    * @param imageId Boot disk image (golden image or base Ubuntu)
    * @param machineType Machine type (e.g., "standard")
    * @param vcpus Number of vCPUs per VM
    * @param memoryGib Memory in GiB per VM
    * @param ttl Time to live in seconds (optional)
    * @return Map of role -> VM ID -> public IP
    */
  def deployShard(
      shardSize: Int,
      dataCenterId: String,
      imageId: String,
      machineType: String = "standard",
      vcpus: Int = 2,
      memoryGib: Int = 4,
      ttl: Option[Int] = None
  ): F[Map[String, (String, String)]] = {
    require(shardSize >= 3, "Shard size must be at least 3 for consensus")

    for {
      // Step 1: Create bootstrap node
      bootstrap                  <- createBootstrapNode(dataCenterId, imageId, machineType, vcpus, memoryGib, ttl)
      (bootstrapId, bootstrapIp) = bootstrap

      // Step 2: Create validator nodes
      validators <- (1 until shardSize).toList.traverse { i =>
                     createValidatorNode(
                       i,
                       bootstrapIp,
                       dataCenterId,
                       imageId,
                       machineType,
                       vcpus,
                       memoryGib,
                       ttl
                     )
                   }

      result = Map(
        "bootstrap" -> (bootstrapId, bootstrapIp)
      ) ++ validators.zipWithIndex.map {
        case ((id, ip), idx) =>
          s"validator-${idx + 1}" -> (id, ip)
      }.toMap

    } yield result
  }

  /**
    * Teardown a shard by terminating all VMs.
    */
  def teardownShard(vmIds: List[String]): F[Unit] =
    vmIds.traverse_(terminateVM)

  // Private helper methods

  private def createBootstrapNode(
      dataCenterId: String,
      imageId: String,
      machineType: String,
      vcpus: Int,
      memoryGib: Int,
      ttl: Option[Int]
  ): F[(String, String)] = {
    val vmId     = s"firefly-bootstrap-${System.currentTimeMillis()}"
    val metadata = Map("ROLE" -> "bootstrap").asJava

    val requestBuilder = CreateVMRequest
      .builder()
      .vmId(vmId)
      .dataCenterId(dataCenterId)
      .machineType(machineType)
      .vcpus(vcpus)
      .memoryGib(memoryGib)
      .bootDiskImageId(imageId)
      .sshKeySource("ACCOUNT")
      .metadata(metadata)

    val request = ttl match {
      case Some(seconds) => requestBuilder.ttl(seconds).build()
      case None          => requestBuilder.build()
    }

    for {
      vm    <- createVM(request)
      ipOpt <- waitForPublicIP(vmId, 120)
      ip <- ipOpt match {
             case Some(publicIp) => Sync[F].pure(publicIp)
             case None =>
               Sync[F].raiseError[String](
                 new RuntimeException(
                   s"Bootstrap node $vmId did not receive public IP within 120 seconds"
                 )
               )
           }
    } yield (vmId, ip)
  }

  private def createValidatorNode(
      index: Int,
      bootstrapIp: String,
      dataCenterId: String,
      imageId: String,
      machineType: String,
      vcpus: Int,
      memoryGib: Int,
      ttl: Option[Int]
  ): F[(String, String)] = {
    val vmId = s"firefly-validator-$index-${System.currentTimeMillis()}"
    val metadata = Map(
      "ROLE"         -> "validator",
      "BOOTSTRAP_IP" -> bootstrapIp
    ).asJava

    val requestBuilder = CreateVMRequest
      .builder()
      .vmId(vmId)
      .dataCenterId(dataCenterId)
      .machineType(machineType)
      .vcpus(vcpus)
      .memoryGib(memoryGib)
      .bootDiskImageId(imageId)
      .sshKeySource("ACCOUNT")
      .metadata(metadata)

    val request = ttl match {
      case Some(seconds) => requestBuilder.ttl(seconds).build()
      case None          => requestBuilder.build()
    }

    for {
      vm    <- createVM(request)
      ipOpt <- waitForPublicIP(vmId, 120)
      ip <- ipOpt match {
             case Some(publicIp) => Sync[F].pure(publicIp)
             case None =>
               Sync[F].raiseError[String](
                 new RuntimeException(
                   s"Validator node $vmId did not receive public IP within 120 seconds"
                 )
               )
           }
    } yield (vmId, ip)
  }
}

object CudoService {
  def apply[F[_]: Sync](config: CudoConfig): F[CudoService[F]] =
    Sync[F].delay {
      val client = new CudoClient(config)
      new CudoService[F](client)
    }
}

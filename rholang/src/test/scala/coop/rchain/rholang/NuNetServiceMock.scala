package coop.rchain.rholang

import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import coop.rchain.rholang.interpreter.NuNetService

class NuNetServiceMock extends NuNetService {

  def deployJob[F[_]](jobSpec: String)(implicit F: Concurrent[F]): F[String] =
    // Simulate job deployment with a mock job ID
    F.pure("mock-job-id-" + scala.util.Random.nextInt(10000))

  def getJobStatus[F[_]](jobId: String)(implicit F: Concurrent[F]): F[String] = {
    // Simulate different job statuses based on job ID
    val status = jobId match {
      case id if id.contains("running") => "running"
      case id if id.contains("failed")  => "failed"
      case id if id.contains("pending") => "pending"
      case _                            => "completed"
    }
    F.pure(
      s"""{"id":"$jobId","status":"$status","createdAt":"2023-01-01T00:00:00Z","updatedAt":"2023-01-01T00:01:00Z"}"""
    )
  }

  def listAvailableNodes[F[_]]()(implicit F: Concurrent[F]): F[List[String]] =
    // Return mock list of available nodes
    F.pure(List("mock-node-1", "mock-node-2", "mock-node-3"))

  def deployRNode[F[_]](config: String)(implicit F: Concurrent[F]): F[String] = {
    // Simulate RNode deployment
    val jobId = if (config.nonEmpty) {
      "rnode-job-" + config.hashCode.abs
    } else {
      "rnode-job-default-" + scala.util.Random.nextInt(10000)
    }
    F.pure(jobId)
  }
}

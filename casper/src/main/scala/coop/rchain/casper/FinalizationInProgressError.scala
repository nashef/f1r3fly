package coop.rchain.casper

/**
  * Exception raised when a snapshot creation is attempted while finalization is in progress.
  * This is a recoverable error - block proposals will retry on the next heartbeat.
  */
final case class FinalizationInProgressError()
    extends Exception("Finalization in progress - snapshot creation aborted. Will retry on next heartbeat.")

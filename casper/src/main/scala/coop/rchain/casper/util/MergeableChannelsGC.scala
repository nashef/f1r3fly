package coop.rchain.casper.util

import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.BlockDagRepresentation
import coop.rchain.casper.CasperShardConf
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.dag.DagOps
import coop.rchain.metrics.Metrics
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.shared.Log

object MergeableChannelsGC {

  /**
    * Garbage collects mergeable channel data for blocks that are provably unreachable.
    *
    * A block's mergeable data is safe to delete when:
    * 1. The block is finalized
    * 2. All validators' latest messages are descendants of the block's children
    * 3. The block is deeper than maxParentDepth + depthBuffer from current tips
    */
  def collectGarbage[F[_]: Sync: Log: Metrics: BlockStore](
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F],
      casperShardConf: CasperShardConf
  )(implicit ms: Metrics.Source): F[Unit] = {

    def isSafeToDelete(blockHash: BlockHash): F[Boolean] =
      for {
        blockMeta <- dag.lookupUnsafe(blockHash)

        // Check if block is finalized
        isFinalized <- dag.isFinalized(blockHash)

        result <- if (!isFinalized) {
          false.pure[F]
        } else {
          for {
            // Check depth constraint
            maxBlockNumber <- dag.latestBlockNumber
            depthFromTip   = maxBlockNumber - blockMeta.blockNum
            maxAllowedDepth = casperShardConf.maxParentDepth +
                              casperShardConf.mergeableChannelsGCDepthBuffer
            tooDeep        = depthFromTip > maxAllowedDepth

            allMovedPast <- if (!tooDeep) {
              false.pure[F]
            } else {
              // Check if all validators have moved past this block
              for {
                latestMessages <- dag.latestMessages
                childrenOpt    <- dag.children(blockHash)
                children       = childrenOpt.getOrElse(Set.empty)

                // For each validator's latest message, check if it's a descendant
                // of at least one child of this block
                allMovedPast <- if (children.isEmpty) {
                  // No children means no one can have moved past
                  false.pure[F]
                } else {
                  latestMessages.values.toList.traverse { latestMsgHash =>
                    if (latestMsgHash == blockHash) {
                      // Validator's latest is still this block
                      false.pure[F]
                    } else {
                      // Check if latest message is descendant of any child
                      children.toList.existsM { childHash =>
                        DagOps.isAncestor(dag, childHash, latestMsgHash)
                      }
                    }
                  }.map(_.forall(identity))
                }
              } yield allMovedPast
            }
          } yield tooDeep && allMovedPast
        }
      } yield result

    for {
      // Get all finalized blocks
      finalizedBlocks <- dag
                          .topoSort(0, None)
                          .map(_.flatten)
                          .flatMap(_.filterA(dag.isFinalized))

      // Find candidates that are safe to delete
      candidates <- finalizedBlocks.filterA(isSafeToDelete)

      // Delete mergeable data for safe candidates
      _ <- candidates.traverse { blockHash =>
        for {
          block     <- BlockStore[F].getUnsafe(blockHash)
          stateHash = block.body.state.postStateHash.toBlake2b256Hash.bytes
          _         <- runtimeManager.getMergeableStore.delete(stateHash)
          _         <- Log[F].debug(s"GC: Deleted mergeable data for block ${blockHash.show}")
        } yield ()
      }

      // Log and record metrics
      _ <- if (candidates.nonEmpty) {
        Metrics[F].incrementCounter("mergeable_channels_gc_deleted", candidates.size.toLong) >>
          Log[F].info(s"Mergeable channels GC: Deleted ${candidates.size} blocks' data")
      } else {
        Log[F].debug("Mergeable channels GC: No data to delete")
      }
    } yield ()
  }
}

package coop.rchain.casper.merging

import cats.effect.Concurrent
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.dag.BlockDagRepresentation
import coop.rchain.blockstorage.syntax._
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.rholang.interpreter.RhoRuntime.RhoHistoryRepository
import coop.rchain.rholang.interpreter.merging.RholangMergingLogic
import coop.rchain.rspace.HotStoreTrieAction
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.merger.MergingLogic.NumberChannelsDiff
import coop.rchain.rspace.merger.{ChannelChange, MergingLogic, StateChange, StateChangeMerger}
import coop.rchain.rspace.syntax._
import coop.rchain.shared.Log
import scodec.bits.ByteVector

object DagMerger {

  def costOptimalRejectionAlg: DeployChainIndex => Long =
    (r: DeployChainIndex) => r.deploysWithCost.map(_.cost).sum

  // Helper function to detect system deploy IDs
  // System deploy IDs are 33 bytes: [32-byte blockHash][1-byte marker]
  // Markers: 0x01 (slash), 0x02 (close block), 0x03 (empty/heartbeat)
  private def isSystemDeployId(id: ByteString): Boolean =
    id.size == 33 && {
      val lastByte = id.byteAt(32)
      lastByte == 1 || lastByte == 2 || lastByte == 3
    }

  def merge[F[_]: Concurrent: Log](
      dag: BlockDagRepresentation[F],
      lfb: BlockHash,
      lfbPostState: Blake2b256Hash,
      index: BlockHash => F[Vector[DeployChainIndex]],
      historyRepository: RhoHistoryRepository[F],
      rejectionCostF: DeployChainIndex => Long,
      scope: Option[Set[BlockHash]] = None
  ): F[(Blake2b256Hash, Seq[ByteString])] =
    for {
      // all not finalized blocks (conflict set)
      allNonFinalisedBlocks <- dag.nonFinalizedBlocks
      // Apply scope filter if provided - only include blocks visible from this scope
      nonFinalisedBlocks = scope.fold(allNonFinalisedBlocks)(allNonFinalisedBlocks.intersect)
      // blocks that see last finalized state
      allActualBlocks <- dag.descendants(lfb)
      // Apply scope filter to actual blocks as well
      actualBlocks = scope.fold(allActualBlocks)(allActualBlocks.intersect)
      // blocks that does not see last finalized state
      lateBlocks = nonFinalisedBlocks diff actualBlocks

      // Convert to sorted lists to ensure deterministic iteration order
      actualSet       <- actualBlocks.toList.traverse(index).map(_.flatten.toSet)
      actualSetSorted = actualSet.toList.sorted
      // TODO reject only late units conflicting with finalised body
      lateSet       <- lateBlocks.toList.traverse(index).map(_.flatten.toSet)
      lateSetSorted = lateSet.toList.sorted

      branchesAreConflicting = (as: Set[DeployChainIndex], bs: Set[DeployChainIndex]) => {
        // Filter out system deploy IDs - they should never be treated as conflicts
        // System deploys are deterministic and execute the same way in all branches
        val asUserDeploys = as.flatMap(_.deploysWithCost.map(_.id)).filterNot(isSystemDeployId)
        val bsUserDeploys = bs.flatMap(_.deploysWithCost.map(_.id)).filterNot(isSystemDeployId)

        // Also filter system deploys from event log comparison
        val asUserIndices =
          as.filter(idx => idx.deploysWithCost.forall(d => !isSystemDeployId(d.id)))
        val bsUserIndices =
          bs.filter(idx => idx.deploysWithCost.forall(d => !isSystemDeployId(d.id)))

        (asUserDeploys intersect bsUserDeploys).nonEmpty ||
        MergingLogic.areConflicting(
          asUserIndices.toList.sorted.map(_.eventLogIndex).combineAll,
          bsUserIndices.toList.sorted.map(_.eventLogIndex).combineAll
        )
      }
      historyReader <- historyRepository.getHistoryReader(lfbPostState)
      baseReader    = historyReader.readerBinary
      baseGetData   = historyReader.getData _
      overrideTrieAction = (
          hash: Blake2b256Hash,
          changes: ChannelChange[ByteVector],
          numberChs: NumberChannelsDiff
      ) =>
        numberChs.get(hash).traverse {
          RholangMergingLogic.calculateNumberChannelMerge(hash, _, changes, baseGetData)
        }
      computeTrieActions = (changes: StateChange, mergeableChs: NumberChannelsDiff) =>
        StateChangeMerger.computeTrieActions(
          changes,
          baseReader,
          mergeableChs,
          overrideTrieAction
        )

      applyTrieActions = (actions: Seq[HotStoreTrieAction]) =>
        historyRepository.reset(lfbPostState).flatMap(_.doCheckpoint(actions).map(_.root))

      r <- ConflictSetMerger.merge[F, DeployChainIndex](
            actualSeq = actualSetSorted,
            lateSeq = lateSetSorted,
            depends =
              (target, source) => MergingLogic.depends(target.eventLogIndex, source.eventLogIndex),
            conflicts = branchesAreConflicting,
            cost = rejectionCostF,
            stateChanges = _.stateChanges.pure,
            mergeableChannels = _.eventLogIndex.numberChannelsData,
            computeTrieActions = computeTrieActions,
            applyTrieActions = applyTrieActions,
            getData = historyReader.getData
          )

      (newState, rejected) = r
      rejectedDeploys      = rejected.flatMap(_.deploysWithCost.map(_.id))

      // Filter out system deploy IDs - they should not appear in block's rejected deploys
      // System deploys are deterministic and excluded from conflict detection
      rejectedUserDeploys = rejectedDeploys.filterNot(isSystemDeployId)

      // Sort rejected deploys to ensure deterministic ordering using ByteVector
      rejectedDeploysSorted = rejectedUserDeploys.toList.sorted(
        Ordering.by((bs: ByteString) => ByteVector(bs.toByteArray))
      )

      // Log merge summary at debug level
      _ <- Log[F].debug(
            s"DagMerger.merge: LFB=${ByteVector.view(lfb.toByteArray).toHex.take(16)}..., " +
              s"scope=${scope.fold("ALL")(s => s"${s.size}")}, " +
              s"actual=${actualBlocks.size}, late=${lateBlocks.size}, " +
              s"rejected=${rejectedDeploysSorted.size}"
          )
      // Log rejected deploys at info level only if there are any
      _ <- if (rejectedDeploysSorted.nonEmpty) {
            import coop.rchain.casper.PrettyPrinter
            Log[F].info(
              s"DagMerger rejected ${rejectedDeploysSorted.size} deploys: " +
                rejectedDeploysSorted.map(bs => PrettyPrinter.buildString(bs)).mkString(", ")
            )
          } else {
            ().pure[F]
          }
    } yield (newState, rejectedDeploysSorted)
}

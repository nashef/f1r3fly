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

  // Deterministic ordering for DeployChainIndex to ensure consistent merge results across validators
  implicit val deployChainIndexOrdering: Ordering[DeployChainIndex] =
    Ordering.by(_.preStateHash)

  def costOptimalRejectionAlg: DeployChainIndex => Long =
    (r: DeployChainIndex) => r.deploysWithCost.map(_.cost).sum

  def merge[F[_]: Concurrent: Log](
      dag: BlockDagRepresentation[F],
      lfb: BlockHash,
      lfbPostState: Blake2b256Hash,
      index: BlockHash => F[Vector[DeployChainIndex]],
      historyRepository: RhoHistoryRepository[F],
      rejectionCostF: DeployChainIndex => Long
  ): F[(Blake2b256Hash, Seq[ByteString])] =
    for {
      // all not finalized blocks (conflict set)
      nonFinalisedBlocks <- dag.nonFinalizedBlocks
      // blocks that see last finalized state
      actualBlocks <- dag.descendants(lfb)
      // blocks that does not see last finalized state
      lateBlocks = nonFinalisedBlocks diff actualBlocks

      // Convert to sorted lists to ensure deterministic iteration order
      actualSet       <- actualBlocks.toList.traverse(index).map(_.flatten.toSet)
      actualSetSorted = actualSet.toList.sorted
      // TODO reject only late units conflicting with finalised body
      lateSet       <- lateBlocks.toList.traverse(index).map(_.flatten.toSet)
      lateSetSorted = lateSet.toList.sorted

      branchesAreConflicting = (as: Set[DeployChainIndex], bs: Set[DeployChainIndex]) =>
        (as.flatMap(_.deploysWithCost.map(_.id)) intersect bs.flatMap(_.deploysWithCost.map(_.id))).nonEmpty ||
          MergingLogic.areConflicting(
            as.toList.sorted.map(_.eventLogIndex).combineAll,
            bs.toList.sorted.map(_.eventLogIndex).combineAll
          )
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
            actualSet = actualSetSorted.toSet,
            lateSet = lateSetSorted.toSet,
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
      // Sort rejected deploys to ensure deterministic ordering using ByteVector
      rejectedDeploysSorted = rejectedDeploys.toList.sorted(
        Ordering.by((bs: ByteString) => ByteVector(bs.toByteArray))
      )

      // Log merge results for debugging
      _ <- Log[F].debug(
            s"""DagMerger.merge completed:
            |  Non-finalized blocks: ${nonFinalisedBlocks.size}
            |  Actual blocks (descendants of LFB): ${actualBlocks.size}
            |  Late blocks (not seeing LFB): ${lateBlocks.size}
            |  Actual deploy chains: ${actualSet.size}
            |  Late deploy chains: ${lateSet.size}
            |  Rejected deploy chains: ${rejected.size}
            |  Total rejected deploys: ${rejectedDeploysSorted.size}
            |  New state hash: ${ByteVector.view(newState.bytes.toArray).toHex.take(16)}...
            |""".stripMargin
          )
      _ <- if (rejectedDeploysSorted.nonEmpty) {
            import coop.rchain.casper.PrettyPrinter
            Log[F].info(
              s"""DagMerger rejected ${rejectedDeploysSorted.size} deploys:
              |${rejectedDeploysSorted
                   .map(bs => "  " + PrettyPrinter.buildString(bs))
                   .mkString("\n")}
              |""".stripMargin
            )
          } else {
            ().pure[F]
          }
    } yield (newState, rejectedDeploysSorted)
}

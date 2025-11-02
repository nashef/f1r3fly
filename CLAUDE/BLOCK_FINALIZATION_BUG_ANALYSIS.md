# Block Finalization Bug Analysis

**Date**: 2025-11-02
**Issue**: System contracts not executing when blocks are finalized
**Severity**: High - Prevents NuNet system contracts from running
**Branch**: nunet-impl

---

## Problem Statement

System contracts (including NuNet contracts) are not being executed when blocks are finalized. The expected behavior is that when a block doesn't immediately finalize on the main chain and is later finalized (when parented by another block), its system contract traces should be replayed. This is not happening.

---

## Current Behavior

### 1. Block Creation (Works Correctly)
**File**: `casper/src/main/scala/coop/rchain/casper/blocks/proposer/BlockCreator.scala:113`

When a validator creates a block:
```scala
systemDeploys = slashingDeploys :+ CloseBlockDeploy(
  SystemDeployUtil.generateCloseDeployRandomSeed(selfId, nextSeqNum)
)
```

- `CloseBlockDeploy` is added as the last system deploy
- `computeDeploysCheckpoint()` executes all deploys including system deploys
- System contracts run and their traces (event logs) are stored in the block
- Block is proposed to the network

**Status**: ✅ Working correctly

---

### 2. Block Validation (Works Correctly)
**File**: `casper/src/main/scala/coop/rchain/casper/MultiParentCasperImpl.scala:324`

When a node receives and validates a block:
```scala
InterpreterUtil.validateBlockCheckpoint(b, s, RuntimeManager[F])
```

Which calls:
```scala
replayBlock(incomingPreStateHash, block, s.dag, runtimeManager)
```

**File**: `casper/src/main/scala/coop/rchain/casper/util/rholang/InterpreterUtil.scala:89-152`

- Extracts user deploys and system deploys from the block
- Calls `runtimeManager.replayComputeState()`
- **Replays all system deploys with their original traces** (line 123-129)
- Validates the final state hash matches the block's claimed state hash
- Retries up to 3 times on mismatch

**Status**: ✅ Working correctly

---

### 3. Block Addition to DAG (Works Correctly)
**File**: `casper/src/main/scala/coop/rchain/casper/MultiParentCasperImpl.scala:419-425`

When a validated block is added to the DAG:
```scala
override def handleValidBlock(block: BlockMessage): F[BlockDagRepresentation[F]] =
  for {
    updatedDag <- BlockDagStorage[F].insert(block, invalid = false)
    _          <- CasperBufferStorage[F].remove(block.blockHash)
    _          <- EventPublisher[F].publish(addedEvent(block))
    _          <- updateLastFinalizedBlock(block)  // Triggers finalizer every N blocks
  } yield updatedDag
```

**Status**: ✅ Working correctly

---

### 4. Block Finalization (BUG HERE)
**File**: `casper/src/main/scala/coop/rchain/casper/MultiParentCasperImpl.scala:117-169`

When the finalizer determines a new Last Finalized Block (LFB):

```scala
def processFinalised(finalizedSet: Set[BlockHash]): F[Unit] =
  finalizedSet.toList.traverse { h =>
    for {
      block   <- BlockStore[F].getUnsafe(h)
      deploys = block.body.deploys.map(_.deploy)

      // Remove block deploys from persistent store
      deploysRemoved   <- DeployStorage[F].remove(deploys)
      // ... logging ...

      // Remove block index from cache
      _ <- BlockIndex.cache.remove(h).pure

      // Remove block post-state mergeable channels from persistent store
      // ... GC logic ...

      // Publish BlockFinalised event for each newly finalized block
      _ <- EventPublisher[F].publish(RChainEvent.blockFinalised(h.toHexString))
    } yield ()
  }.void
```

**What it does:**
- ✅ Removes finalized deploys from storage
- ✅ Clears block index from cache
- ✅ Handles mergeable channel cleanup
- ✅ Publishes BlockFinalised event

**What it DOES NOT do:**
- ❌ Re-execute system contracts
- ❌ Replay system contract traces
- ❌ Trigger CloseBlockDeploy execution
- ❌ Execute any Rholang code

**Status**: ❌ **BUG - Missing system contract replay during finalization**

---

## Root Cause Analysis

### Expected Behavior
According to the problem statement, when a block is "parented by another block" and gets finalized, its system contract traces should be replayed.

### Actual Behavior
System contracts are only executed/replayed during:
1. **Block Creation** - by the proposer (real execution)
2. **Block Validation** - by validators receiving the block (replay with cached traces)

System contracts are **NOT** executed/replayed during:
3. **Block Finalization** - when the block becomes the LFB or is indirectly finalized

### The Missing Link

**File**: `block-storage/src/main/scala/coop/rchain/blockstorage/dag/BlockDagKeyValueStorage.scala:265-283`

```scala
def recordDirectlyFinalized(
    directlyFinalizedHash: BlockHash,
    finalizationEffect: Set[BlockHash] => F[Unit]
): F[Unit] =
  lock.withPermit(
    for {
      dag    <- representation
      errMsg = s"Attempting to finalize nonexistent hash..."
      _      <- dag.contains(directlyFinalizedHash).ifM(().pure, new Exception(errMsg).raiseError)

      // All non-finalized ancestors should be finalized as well (indirectly)
      indirectlyFinalized <- dag.ancestors(directlyFinalizedHash, dag.isFinalized(_).not)

      // Invoke effects
      _ <- finalizationEffect(indirectlyFinalized + directlyFinalizedHash)

      // Persist finalization
      _ <- blockMetadataIndex.recordFinalized(directlyFinalizedHash, indirectlyFinalized)
    } yield ()
  )
```

The `finalizationEffect` parameter receives **all blocks being finalized** (both direct and indirect), but the only effect being executed is `processFinalised()` which does storage cleanup, NOT trace replay.

---

## Why This Matters for NuNet

NuNet system contracts need to execute when blocks are finalized because:

1. **CloseBlockDeploy** calls `PoS!("closeBlock", ...)` which is where system state transitions happen
2. NuNet deployment contracts may be included in blocks that don't immediately finalize
3. When these blocks are later finalized (parented by subsequent blocks), the NuNet contracts should execute
4. Without replay on finalization, NuNet deployments are created but never actually processed

**Example Scenario:**
1. Block N contains NuNet deployment contract → validated and added to DAG
2. Block N doesn't immediately finalize (network conditions, timing, etc.)
3. Block N+5 parents Block N, making Block N finalized
4. **Expected**: NuNet contract in Block N is now executed/replayed
5. **Actual**: NuNet contract is never executed (only storage cleanup happens)

---

## Code Flow Comparison

### What SHOULD happen:
```
Block Created (proposer)
  → Execute system contracts (including CloseBlockDeploy)
  → Store traces in block

Block Validated (validators)
  → Replay system contracts with cached traces
  → Verify state hash matches

Block Added to DAG
  → Insert into DAG storage
  → Publish BlockAdded event
  → Check for finalization

Block Finalized (NEW STEP - MISSING)
  → Load block from storage
  → Replay system contracts with cached traces  ← MISSING!
  → Execute finalization logic (CloseBlockDeploy)
  → Clean up storage
  → Publish BlockFinalised event
```

### What ACTUALLY happens:
```
Block Created (proposer)
  → Execute system contracts ✅

Block Validated (validators)
  → Replay system contracts ✅

Block Added to DAG
  → Insert into storage ✅
  → Publish BlockAdded event ✅

Block Finalized
  → Clean up storage ✅
  → Publish BlockFinalised event ✅
  → (NO REPLAY - system contracts never execute) ❌
```

---

## Potential Solutions

### Solution 1: Add Replay to processFinalised (Recommended)

**Modify**: `casper/src/main/scala/coop/rchain/casper/MultiParentCasperImpl.scala:119-148`

```scala
def processFinalised(finalizedSet: Set[BlockHash]): F[Unit] =
  finalizedSet.toList.traverse { h =>
    for {
      block   <- BlockStore[F].getUnsafe(h)

      // NEW: Replay system contracts for finalized blocks
      _ <- replaySystemContractsOnFinalization(block)

      deploys = block.body.deploys.map(_.deploy)

      // Existing cleanup logic...
      deploysRemoved   <- DeployStorage[F].remove(deploys)
      // ... rest of cleanup ...

      _ <- EventPublisher[F].publish(RChainEvent.blockFinalised(h.toHexString))
    } yield ()
  }.void

// New helper method
private def replaySystemContractsOnFinalization(block: BlockMessage): F[Unit] = {
  val systemDeploys = ProtoUtil.systemDeploys(block)
  val preStateHash = ProtoUtil.preStateHash(block)
  val blockData = BlockData.fromBlock(block)

  // Replay with cached traces (same as validation replay)
  RuntimeManager[F].replaySystemDeploys(
    preStateHash,
    systemDeploys,
    blockData
  ).void
}
```

**Pros:**
- Minimal changes to existing code
- Reuses existing replay infrastructure
- Only replays system deploys (not user deploys - already executed)

**Cons:**
- Adds overhead to finalization process
- May slow down finalization if many blocks finalize at once

---

### Solution 2: Separate Finalization System Deploy

**Create new system deploy**: `FinalizeBlockDeploy`

Similar to `CloseBlockDeploy`, but executed specifically during finalization:

```scala
final case class FinalizeBlockDeploy(blockHash: BlockHash) extends SystemDeploy(...) {
  override val source: String =
    """#new rl(`rho:registry:lookup`), poSCh, return(`sys:casper:return`) in {
      #  rl!(`rho:rchain:pos`, *poSCh) |
      #  for(@(_, PoS) <- poSCh) {
      #    @PoS!("finalizeBlock", blockHash, *return)
      #  }
      #}""".stripMargin('#')
}
```

Execute during `processFinalised()`:
```scala
def processFinalised(finalizedSet: Set[BlockHash]): F[Unit] =
  finalizedSet.toList.traverse { h =>
    for {
      block <- BlockStore[F].getUnsafe(h)

      // Create and execute finalization deploy
      finalizeDeploypay = FinalizeBlockDeploy(h)
      _ <- RuntimeManager[F].playSystemDeploy(finalizeDeploypay)

      // Existing cleanup...
    } yield ()
  }.void
```

**Pros:**
- Clean separation of concerns
- No need to replay existing traces
- Can add finalization-specific logic to PoS contract

**Cons:**
- More invasive change
- Requires modifying PoS contract to handle "finalizeBlock" method
- New system deploy type to maintain

---

### Solution 3: Event-Based Finalization (Most Scalable)

**Add listener for BlockFinalised events** that triggers system contract execution:

```scala
// In a separate finalization processor
def handleBlockFinalised(event: BlockFinalised): F[Unit] = {
  for {
    blockHash <- event.blockHash.pure[F]
    block     <- BlockStore[F].getUnsafe(blockHash)
    _         <- replaySystemContractsOnFinalization(block)
  } yield ()
}
```

Register listener during node startup:
```scala
EventPublisher[F].subscribe[BlockFinalised](handleBlockFinalised)
```

**Pros:**
- Decouples finalization from cleanup
- Allows async processing
- Easier to add monitoring/metrics

**Cons:**
- More complex architecture
- Requires event system to be working correctly
- Timing/ordering concerns

---

## Recommended Approach

**Solution 1** (Add Replay to processFinalised) is recommended because:

1. ✅ Minimal code changes
2. ✅ Reuses existing, tested replay infrastructure
3. ✅ Synchronous execution ensures correct ordering
4. ✅ Easy to debug and reason about
5. ✅ Matches existing validation replay pattern

**Implementation Steps:**

1. Add `replaySystemContractsOnFinalization()` helper method
2. Call it from `processFinalised()` before cleanup
3. Add comprehensive logging for debugging
4. Add metrics for finalization replay timing
5. Test with NuNet contracts to verify execution

---

## Additional Considerations

### Performance Impact
- Replaying system contracts adds overhead to finalization
- Should be acceptable since finalization happens infrequently (every N blocks)
- Can be optimized later if needed (batch processing, async execution, etc.)

### Testing Strategy
1. Create test with NuNet deployment in block N
2. Create blocks N+1 through N+10 without finalizing N
3. Verify N is not finalized yet
4. Create block that finalizes N
5. **Verify**: NuNet system contract executes during finalization
6. **Verify**: NuNet deployment is processed

### Logging Additions Needed
Add to `processFinalised()`:
```scala
_ <- Log[F].info(s"Replaying system contracts for finalized block ${h.show}")
_ <- Log[F].info(s"Replayed ${systemDeploys.length} system deploys")
_ <- Log[F].debug(s"System deploy results: $results")
```

### Metrics to Add
- `casper_finalization_replay_duration_seconds` - Time to replay system contracts
- `casper_finalization_replay_count` - Number of system deploys replayed
- `casper_finalization_replay_errors_total` - Errors during replay

---

## Files to Modify

### Primary Changes:
1. **`casper/src/main/scala/coop/rchain/casper/MultiParentCasperImpl.scala`**
   - Modify `processFinalised()` (lines 119-148)
   - Add `replaySystemContractsOnFinalization()` method

2. **`casper/src/main/scala/coop/rchain/casper/rholang/RuntimeManager.scala`**
   - Add `replaySystemDeploys()` method if not already present
   - Expose system deploy replay without full checkpoint

### Secondary Changes (for testing):
3. **`casper/src/test/scala/coop/rchain/casper/FinalizationSpec.scala`**
   - Add test for system contract replay on finalization
   - Add test for NuNet contract execution on finalization

### Documentation:
4. **`CLAUDE/BLOCK_FINALIZATION_BUG_ANALYSIS.md`** (this file)
5. **`CLAUDE/NUNET_INTEGRATION_STATUS.md`** (update with findings)

---

## Next Steps

1. **Confirm understanding with user** - Verify this analysis matches the observed behavior
2. **Add logging** - Add detailed logging to confirm blocks are being finalized but contracts not executing
3. **Implement Solution 1** - Add replay to processFinalised()
4. **Test with NuNet** - Verify NuNet contracts execute on finalization
5. **Add regression tests** - Ensure fix doesn't break existing functionality
6. **Document behavior** - Update docs with finalization replay behavior

---

## Questions for Discussion

1. **Should all system deploys be replayed, or only CloseBlockDeploy?**
   - Currently assuming all system deploys should replay
   - Could optimize to only replay CloseBlockDeploy if that's the only one that matters

2. **Should replay happen before or after storage cleanup?**
   - Currently assuming before (so contracts can access deploy data if needed)
   - Could be after if cleanup doesn't affect contract execution

3. **How should replay failures be handled?**
   - Should finalization fail if replay fails?
   - Should we retry replay?
   - Should we continue with cleanup even if replay fails?

4. **Performance budget for finalization?**
   - How much time can we spend replaying contracts?
   - Should we limit the number of concurrent finalizations?
   - Should we batch finalization processing?

---

## References

- **Finalizer Algorithm**: `casper/src/main/scala/coop/rchain/casper/finality/Finalizer.scala:91-187`
- **Replay Infrastructure**: `casper/src/main/scala/coop/rchain/casper/rholang/RuntimeReplaySyntax.scala`
- **System Contract Execution**: `casper/src/main/scala/coop/rchain/casper/rholang/RuntimeSyntax.scala`
- **CloseBlockDeploy Definition**: `casper/src/main/scala/coop/rchain/casper/util/rholang/costacc/CloseBlockDeploy.scala`

---

## Status

- **Analysis**: Complete ✅
- **Root Cause Identified**: Yes ✅
- **Solution Designed**: Yes ✅
- **Implementation**: Pending user confirmation
- **Testing**: Not started
- **Documentation**: This document

**Waiting on**: User confirmation that this analysis matches observed behavior before implementing fix

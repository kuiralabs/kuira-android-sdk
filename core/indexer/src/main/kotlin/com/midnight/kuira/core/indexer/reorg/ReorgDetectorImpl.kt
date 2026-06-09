package com.midnight.kuira.core.indexer.reorg

import com.midnight.kuira.core.indexer.model.BlockInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

/**
 * Implementation of ReorgDetector using in-memory block history.
 *
 * **Thread Safety:** Uses Mutex for coroutine-safe operations.
 *
 * **Algorithm:**
 * 1. Maintain sliding window of recent blocks (map: height → BlockInfo)
 * 2. When new block arrives, check if parent hash matches previous block
 * 3. If mismatch, walk backwards to find common ancestor
 * 4. Emit ReorgEvent with old and new branches
 * 5. Update block history with new canonical blocks
 */
class ReorgDetectorImpl(
    private val config: ReorgConfig = ReorgConfig()
) : ReorgDetector {

    companion object {
        private const val TAG = "ReorgDetector"
    }

    // Recent block history (height → BlockInfo)
    // Only keeps last `historyDepth` blocks
    private val blockHistory = HashMap<Long, BlockInfo>()

    // Mutex for thread-safe access
    private val mutex = Mutex()

    // Flow for broadcasting reorg events
    private val reorgFlow = MutableSharedFlow<ReorgEvent>(replay = 0, extraBufferCapacity = 10)

    override suspend fun recordBlock(block: BlockInfo, parentHash: String): ReorgEvent? = mutex.withLock {
        val previousBlock = blockHistory[block.height - 1]

        // Case 1: First block we've seen (bootstrapping)
        if (previousBlock == null) {
            Log.d(TAG, "Recording first block: height=${block.height}")
            addBlockToHistory(block)
            return null
        }

        // Case 2: Parent hash matches - no reorg (normal case)
        if (previousBlock.hash == parentHash) {
            Log.d(TAG, "Block ${block.height} extends chain normally")
            addBlockToHistory(block)
            return null
        }

        // Case 3: Parent hash mismatch - REORG DETECTED!
        Log.w(TAG, """
            ⚠️ REORG DETECTED at height ${block.height}
            Expected parent: ${previousBlock.hash}
            Actual parent:   $parentHash
        """.trimIndent())

        // Find common ancestor by walking backwards
        val commonAncestorHeight = findCommonAncestor(parentHash)

        if (commonAncestorHeight == null) {
            Log.e(TAG, "Cannot find common ancestor - history insufficient")
            throw ReorgHandlingException(
                "Cannot find common ancestor for reorg at height ${block.height}. " +
                "History only goes back ${blockHistory.keys.minOrNull() ?: 0}."
            )
        }

        // Determine reorg type (shallow or deep)
        val reorgDepth = block.height - commonAncestorHeight
        val isBeyondFinality = reorgDepth > config.finalityThreshold

        val reorgEvent = if (isBeyondFinality) {
            // DEEP REORG - Critical failure
            Log.e(TAG, """
                🚨🚨🚨 DEEP REORG DETECTED 🚨🚨🚨
                Reorg depth: $reorgDepth blocks
                Finality threshold: ${config.finalityThreshold} blocks
                This should NEVER happen in a healthy blockchain!
            """.trimIndent())

            ReorgEvent.DeepReorg(
                reorgHeight = block.height,
                commonAncestorHeight = commonAncestorHeight,
                finalityThreshold = config.finalityThreshold
            )
        } else {
            // SHALLOW REORG - Recoverable
            Log.w(TAG, "Shallow reorg: $reorgDepth blocks, recovering...")

            val oldBranch = getBlocksInRange(commonAncestorHeight + 1, block.height - 1)
            val newBranch = listOf(block) // Will be extended as we sync forward

            ReorgEvent.ShallowReorg(
                reorgHeight = block.height,
                commonAncestorHeight = commonAncestorHeight,
                oldBranch = oldBranch,
                newBranch = newBranch
            )
        }

        // Update block history to new canonical chain
        rollbackToHeight(commonAncestorHeight)
        addBlockToHistory(block)

        // Emit reorg event
        reorgFlow.emit(reorgEvent)

        return reorgEvent
    }

    override suspend fun getChainTip(): BlockInfo? = mutex.withLock {
        val maxHeight = blockHistory.keys.maxOrNull() ?: return null
        return blockHistory[maxHeight]
    }

    override fun observeReorgs(): Flow<ReorgEvent> = reorgFlow

    override suspend fun reset() = mutex.withLock {
        Log.d(TAG, "Resetting reorg detector")
        blockHistory.clear()
    }

    /**
     * Add block to history, maintaining size limit.
     */
    private fun addBlockToHistory(block: BlockInfo) {
        blockHistory[block.height] = block

        // Prune old blocks beyond history depth
        val minHeight = block.height - config.historyDepth
        blockHistory.keys.filter { it < minHeight }.forEach { height ->
            blockHistory.remove(height)
        }
    }

    /**
     * Find common ancestor by comparing parent hash with block history.
     *
     * @param parentHash Parent hash from new block
     * @return Height of common ancestor, or null if not found
     */
    private fun findCommonAncestor(parentHash: String): Long? {
        // Walk backwards through history to find matching hash
        val sortedHeights = blockHistory.keys.sortedDescending()

        for (height in sortedHeights) {
            val block = blockHistory[height] ?: continue
            if (block.hash == parentHash) {
                return height
            }
        }

        return null
    }

    /**
     * Get all blocks in height range.
     */
    private fun getBlocksInRange(fromHeight: Long, toHeight: Long): List<BlockInfo> {
        return (fromHeight..toHeight).mapNotNull { height ->
            blockHistory[height]
        }
    }

    /**
     * Roll back block history to given height (discard everything after).
     */
    private fun rollbackToHeight(height: Long) {
        val heightsToRemove = blockHistory.keys.filter { it > height }
        heightsToRemove.forEach { h ->
            blockHistory.remove(h)
        }
        Log.d(TAG, "Rolled back to height $height (removed ${heightsToRemove.size} blocks)")
    }
}

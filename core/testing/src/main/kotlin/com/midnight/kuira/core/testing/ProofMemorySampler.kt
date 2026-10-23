package com.midnight.kuira.core.testing

import android.os.Debug

/** What one proof cost in memory: the baseline it started from, and the peak while it ran. */
data class ProofMemoryUse(
    val baseNativeKb: Long,
    val peakNativeKb: Long,
    val basePssKb: Long,
    val peakPssKb: Long,
) {
    val nativeDeltaKb get() = peakNativeKb - baseNativeKb
    val pssDeltaKb get() = peakPssKb - basePssKb

    /** `base 55MB -> peak 651MB (delta 596MB)` for the figure that decides whether a proof fits. */
    fun pssSummary(): String = "base ${mb(basePssKb)} -> peak ${mb(peakPssKb)} (delta ${mb(pssDeltaKb)})"

    fun nativeSummary(): String =
        "base ${mb(baseNativeKb)} -> peak ${mb(peakNativeKb)} (delta ${mb(nativeDeltaKb)})"

    private fun mb(kb: Long) = "%.0fMB".format(kb / 1024.0)
}

/**
 * Peak memory observed while a proof runs.
 *
 * Proving time says how long a circuit takes; peak memory says whether it FITS, which is what
 * decides viability on a smaller device and what the runtime's fallback-to-remote turns on. Here
 * rather than in one test because every proving path needs the same answer and the figures are
 * only comparable across circuits if they are gathered the same way.
 *
 * Two metrics, because one is not enough. [Debug.getNativeHeapAllocatedSize] is what the Rust
 * prover mallocs — the JVM heap sees almost none of it — but it is malloc-only and misses mapped
 * regions. Total PSS is what the OS kills on, and measured ~60 MB higher for a k=15 proof, so
 * reporting only the native figure understates the footprint.
 *
 * Sampled from a thread because a proof is one blocking call, with no point inside it to read a
 * high-water mark from, and both APIs report instantaneous values rather than peaks. PSS is the
 * expensive read, so it is polled an order of magnitude less often.
 *
 * The result is a FLOOR on real usage: a sampler can miss a spike between polls. In practice the
 * allocation is a sustained plateau rather than a spike — peaks for the same circuit agree to
 * within a couple of MB across runs — so the floor is close to the truth, but it is still a floor
 * and should not be quoted as a hard maximum.
 *
 * ```
 * val sampler = ProofMemorySampler().also { it.start() }
 * val proof = prove(tx)
 * val used = sampler.stopAndRead()
 * ```
 */
class ProofMemorySampler(
    private val nativeIntervalMs: Long = 20L,
    private val pssEveryNthSample: Int = 10,
) {
    @Volatile private var running = false
    @Volatile private var peakNative = 0L
    @Volatile private var peakPss = 0L
    private var baseNative = 0L
    private var basePss = 0L
    private var thread: Thread? = null

    fun start() {
        running = true
        baseNative = Debug.getNativeHeapAllocatedSize()
        basePss = readPssKb()
        peakNative = baseNative
        peakPss = basePss
        thread = Thread {
            var i = 0
            while (running) {
                val now = Debug.getNativeHeapAllocatedSize()
                if (now > peakNative) peakNative = now
                if (i % pssEveryNthSample == 0) {
                    val pss = readPssKb()
                    if (pss > peakPss) peakPss = pss
                }
                i++
                try {
                    Thread.sleep(nativeIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stopAndRead(): ProofMemoryUse {
        running = false
        thread?.interrupt()
        thread?.join(2_000)
        // One last read: the peak can land in the window between the final poll and the stop.
        val pss = readPssKb()
        if (pss > peakPss) peakPss = pss
        val native = Debug.getNativeHeapAllocatedSize()
        if (native > peakNative) peakNative = native
        return ProofMemoryUse(baseNative / 1024, peakNative / 1024, basePss, peakPss)
    }

    private fun readPssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }
}

package com.dd3boh.xenowave.playback.stream

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregate, privacy-safe transfer telemetry for download DataSources.
 *
 * Media3 emits these events around each cache or network range. The listener deliberately logs only
 * ids, sizes, timings, range coordinates, and whether a transfer used the network.
 */
class DownloadTransferListener(
    private val nowNs: () -> Long = System::nanoTime,
) : TransferListener {

    data class Summary(
        val networkBytes: Long,
        val cacheBytes: Long,
        val networkChunks: Long,
        val cacheChunks: Long,
        val elapsedMs: Long,
        val networkDurationMs: Long,
    ) {
        val averageNetworkBytesPerSecond: Long
            get() = if (networkDurationMs <= 0L) 0L else networkBytes * 1_000L / networkDurationMs

        override fun toString(): String =
            "networkBytes=$networkBytes cacheBytes=$cacheBytes " +
                "networkChunks=$networkChunks cacheChunks=$cacheChunks " +
                "elapsedMs=$elapsedMs networkDurationMs=$networkDurationMs " +
                "averageNetworkBytesPerSecond=$averageNetworkBytesPerSecond"
    }

    private class TransferState {
        var firstInitializingAtNs = 0L
        var firstNetworkInitializingAtNs = 0L
        var chunkInitializingAtNs = 0L
        var chunkStartedAtNs = 0L
        var chunkIsNetwork = false
        var chunkBytes = 0L
        var openTransfers = 0
        var networkBytes = 0L
        var cacheBytes = 0L
        var networkDurationNs = 0L
        var networkChunks = 0L
        var cacheChunks = 0L
        var nextProgressLogBytes = PROGRESS_LOG_INTERVAL_BYTES
        var firstNetworkByteLogged = false
        var firstCacheByteLogged = false
    }

    private val transfers = ConcurrentHashMap<String, TransferState>()

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        val mediaId = dataSpec.key ?: return
        val initializedAtNs = nowNs()
        val state = transfers.computeIfAbsent(mediaId) { TransferState() }
        synchronized(state) {
            if (state.openTransfers == 0) {
                state.firstInitializingAtNs =
                    if (state.firstInitializingAtNs == 0L) initializedAtNs else state.firstInitializingAtNs
                state.chunkInitializingAtNs = initializedAtNs
                state.chunkIsNetwork = isNetwork
                state.chunkStartedAtNs = 0L
                state.chunkBytes = 0L
            }
            if (isNetwork && state.firstNetworkInitializingAtNs == 0L) {
                state.firstNetworkInitializingAtNs = initializedAtNs
            }
            state.openTransfers++
        }

        Log.i(
            DOWNLOAD_LOG_TAG,
            "TRANSFER_INIT mediaId=$mediaId source=${sourceName(isNetwork)} " +
                "position=${dataSpec.position} requestedLength=${formatLength(dataSpec.length)}",
        )
    }

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        val mediaId = dataSpec.key ?: return
        val state = transfers[mediaId] ?: return
        val readyAfterMs = synchronized(state) {
            if (state.chunkStartedAtNs == 0L) {
                state.chunkStartedAtNs = nowNs()
            }
            elapsedMs(state.chunkInitializingAtNs, state.chunkStartedAtNs)
        }

        Log.i(
            DOWNLOAD_LOG_TAG,
            "TRANSFER_START mediaId=$mediaId source=${sourceName(isNetwork)} readyMs=$readyAfterMs",
        )
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        val mediaId = dataSpec.key ?: return
        val state = transfers[mediaId] ?: return
        val logLines = synchronized(state) {
            val now = nowNs()
            if (isNetwork) {
                state.networkBytes += bytesTransferred
            } else {
                state.cacheBytes += bytesTransferred
            }
            state.chunkBytes += bytesTransferred

            val lines = mutableListOf<String>()
            val firstByteStartNs = if (isNetwork) {
                state.firstNetworkInitializingAtNs
            } else {
                state.firstInitializingAtNs
            }
            val isFirstByte = if (isNetwork) {
                !state.firstNetworkByteLogged
            } else {
                !state.firstCacheByteLogged
            }
            if (isFirstByte) {
                if (isNetwork) {
                    state.firstNetworkByteLogged = true
                } else {
                    state.firstCacheByteLogged = true
                }
                lines +=
                    "TRANSFER_FIRST_BYTE mediaId=$mediaId source=${sourceName(isNetwork)} " +
                        "bytes=$bytesTransferred ttfbMs=${elapsedMs(firstByteStartNs, now)}"
            }
            if (state.networkBytes >= state.nextProgressLogBytes) {
                state.nextProgressLogBytes += PROGRESS_LOG_INTERVAL_BYTES
                lines +=
                    "TRANSFER_PROGRESS mediaId=$mediaId source=NETWORK bytes=${state.networkBytes} " +
                        "cacheBytes=${state.cacheBytes} " +
                        "averageBytesPerSecond=${averageBytesPerSecond(state, now)}"
            }
            lines
        }

        logLines.forEach { Log.i(DOWNLOAD_LOG_TAG, it) }
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        val mediaId = dataSpec.key ?: return
        val state = transfers[mediaId] ?: return
        val endLine = synchronized(state) {
            state.openTransfers = (state.openTransfers - 1).coerceAtLeast(0)
            if (state.openTransfers == 0) {
                if (state.chunkIsNetwork) state.networkChunks++ else state.cacheChunks++
                val endedAtNs = nowNs()
                val chunkDurationMs = elapsedMs(state.chunkInitializingAtNs, endedAtNs)
                if (state.chunkIsNetwork) {
                    state.networkDurationNs += endedAtNs - state.chunkInitializingAtNs
                    state.nextProgressLogBytes =
                        ((state.networkBytes / PROGRESS_LOG_INTERVAL_BYTES) + 1L) *
                            PROGRESS_LOG_INTERVAL_BYTES
                }
                "TRANSFER_CHUNK_END mediaId=$mediaId source=${sourceName(state.chunkIsNetwork)} " +
                    "chunkBytes=${state.chunkBytes} durationMs=$chunkDurationMs"
            } else {
                null
            }
        }

        endLine?.let { Log.i(DOWNLOAD_LOG_TAG, it) }
    }

    fun summary(mediaId: String): Summary? {
        val state = transfers[mediaId] ?: return null
        return synchronized(state) {
            Summary(
                networkBytes = state.networkBytes,
                cacheBytes = state.cacheBytes,
                networkChunks = state.networkChunks,
                cacheChunks = state.cacheChunks,
                elapsedMs = elapsedMs(state.firstInitializingAtNs, nowNs()),
                networkDurationMs = elapsedMs(0L, state.networkDurationNs),
            )
        }
    }

    fun remove(mediaId: String): Summary? {
        val summary = summary(mediaId)
        transfers.remove(mediaId)
        return summary
    }

    private fun averageBytesPerSecond(
        state: TransferState,
        now: Long,
    ): Long {
        val elapsedMs = if (state.firstNetworkInitializingAtNs == 0L) {
            0L
        } else {
            elapsedMs(state.firstNetworkInitializingAtNs, now)
        }
        return if (elapsedMs <= 0L) 0L else state.networkBytes * 1_000L / elapsedMs
    }

    private fun elapsedMs(
        startNs: Long,
        endNs: Long,
    ): Long = ((endNs - startNs).coerceAtLeast(0L) + 500_000L) / 1_000_000L

    private fun sourceName(isNetwork: Boolean) = if (isNetwork) "NETWORK" else "CACHE"

    private fun formatLength(length: Long): String =
        if (length == C.LENGTH_UNSET.toLong()) "UNSET" else length.toString()

    private companion object {
        const val PROGRESS_LOG_INTERVAL_BYTES = 1024L * 1024L
    }
}

internal const val DOWNLOAD_LOG_TAG = "XENOWAVE_DOWNLOAD"

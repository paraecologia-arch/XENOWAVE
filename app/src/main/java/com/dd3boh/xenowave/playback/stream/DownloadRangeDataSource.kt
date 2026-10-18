package com.dd3boh.xenowave.playback.stream

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal class DownloadRangeDataSource private constructor(
    private val upstream: DataSource,
    private val resolveStream: (String) -> ResolvedStream,
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var currentPosition = 0L
    private var logicalBytesRemaining = C.LENGTH_UNSET.toLong()
    private var currentChunkLength = 0L
    private var currentChunkBytesRemaining = 0L
    private var singleRequest = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(this.dataSpec == null) { "DataSource is already open" }
        val mediaId = dataSpec.key ?: error("No media id")
        val resolvedStream = resolveStream(mediaId)
        this.dataSpec = dataSpec
        currentPosition = dataSpec.position

        val streamBytesRemaining = resolvedStream.format.contentLength
            ?.let { contentLength -> (contentLength - dataSpec.position).coerceAtLeast(0L) }
        logicalBytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            streamBytesRemaining ?: C.LENGTH_UNSET.toLong()
        } else {
            streamBytesRemaining?.let { minOf(dataSpec.length, it) } ?: dataSpec.length
        }
        if (logicalBytesRemaining == 0L) {
            return 0L
        }

        val chunkSize = resolvedStream.rangePolicy.boundedBytes()
        if (chunkSize == null) {
            singleRequest = true
            val resolvedLength = upstream.open(dataSpec.withResolvedStream(resolvedStream))
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) resolvedLength else dataSpec.length
        }

        openChunk(resolvedStream)
        return logicalBytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (logicalBytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (singleRequest) {
            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead != C.RESULT_END_OF_INPUT && logicalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                logicalBytesRemaining -= bytesRead
            }
            return bytesRead
        }

        while (true) {
            if (currentChunkBytesRemaining == 0L) {
                openChunk()
            }

            val maxReadLength = minOf(length.toLong(), currentChunkBytesRemaining).toInt()
            val bytesRead = upstream.read(buffer, offset, maxReadLength)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return C.RESULT_END_OF_INPUT
            }

            currentPosition += bytesRead
            currentChunkBytesRemaining -= bytesRead
            if (logicalBytesRemaining != C.LENGTH_UNSET.toLong()) {
                logicalBytesRemaining -= bytesRead
                if (logicalBytesRemaining == 0L) return bytesRead
            }
            return bytesRead
        }
    }

    private fun openChunk(initialStream: ResolvedStream? = null) {
        val requestDataSpec = dataSpec ?: error("DataSource is not open")
        if (currentChunkLength != 0L) {
            upstream.close()
        }

        val resolvedStream = initialStream ?: resolveStream(requestDataSpec.key ?: error("No media id"))
        val chunkSize = resolvedStream.rangePolicy.boundedBytes()
            ?: error("Range policy changed to unbounded during download")
        val chunkLength = if (logicalBytesRemaining == C.LENGTH_UNSET.toLong()) {
            chunkSize
        } else {
            minOf(logicalBytesRemaining, chunkSize)
        }
        val chunkSpec = requestDataSpec
            .buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()
            .withResolvedStream(resolvedStream)

        upstream.open(chunkSpec)
        currentChunkLength = chunkLength
        currentChunkBytesRemaining = chunkLength
    }

    override fun getUri() = upstream.uri

    override fun close() {
        if (dataSpec != null) {
            upstream.close()
        }
        dataSpec = null
        logicalBytesRemaining = C.LENGTH_UNSET.toLong()
        currentChunkLength = 0L
        currentChunkBytesRemaining = 0L
        singleRequest = false
    }

    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val resolveStream: (String) -> ResolvedStream,
    ) : DataSource.Factory {
        override fun createDataSource() = DownloadRangeDataSource(
            upstream = upstreamFactory.createDataSource(),
            resolveStream = resolveStream,
        )
    }
}

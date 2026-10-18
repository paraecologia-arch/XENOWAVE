package com.dd3boh.xenowave.playback.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRangeDataSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    @Test
    fun boundedRangeContinuesUntilUnknownResourceEnds() {
        val upstream = RecordingDataSource(data)
        val dataSource = dataSource(upstream, contentLength = null)

        val openedLength = dataSource.open(dataSpec())
        val output = dataSource.readAll()

        assertEquals(C.LENGTH_UNSET.toLong(), openedLength)
        assertEquals(data.toList(), output.toList())
        assertEquals(
            listOf(0L to 4L, 4L to 4L, 8L to 4L),
            upstream.openedSpecs.map { it.position to it.length },
        )
    }

    @Test
    fun boundedRangeContinuesForKnownResourceLength() {
        val upstream = RecordingDataSource(data)
        val dataSource = dataSource(upstream, contentLength = data.size.toLong())

        val openedLength = dataSource.open(dataSpec())
        val output = dataSource.readAll()

        assertEquals(data.size.toLong(), openedLength)
        assertEquals(data.toList(), output.toList())
        assertEquals(
            listOf(0L to 4L, 4L to 4L, 8L to 2L),
            upstream.openedSpecs.map { it.position to it.length },
        )
    }

    @Test
    fun boundedRangePreservesStartPosition() {
        val upstream = RecordingDataSource(data)
        val dataSource = dataSource(upstream, contentLength = data.size.toLong())

        dataSource.open(dataSpec(position = 4L, length = 6L))
        val output = dataSource.readAll()

        assertEquals(data.copyOfRange(4, 10).toList(), output.toList())
        assertEquals(
            listOf(4L to 4L, 8L to 2L),
            upstream.openedSpecs.map { it.position to it.length },
        )
    }

    @Test
    fun unboundedPolicyUsesSingleUpstreamRequest() {
        val upstream = RecordingDataSource(data)
        val dataSource = DownloadRangeDataSource.Factory(
            object : DataSource.Factory {
                override fun createDataSource() = upstream
            },
        ) { stream(contentLength = data.size.toLong(), rangePolicy = RangePolicy.None) }
            .createDataSource()

        val openedLength = dataSource.open(dataSpec())
        val output = dataSource.readAll()

        assertEquals(data.size.toLong(), openedLength)
        assertEquals(data.toList(), output.toList())
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong()), upstream.openedSpecs.map { it.position to it.length })
    }

    @Test
    fun eachChunkReceivesResolvedHeaders() {
        val upstream = RecordingDataSource(data)
        val dataSource = dataSource(upstream, contentLength = data.size.toLong())

        dataSource.open(dataSpec())
        dataSource.readAll()

        assertEquals(
            listOf("ua", "ua", "ua"),
            upstream.openedSpecs.map { it.httpRequestHeaders["User-Agent"] },
        )
    }

    @Test
    fun cacheWriterCachesAllBoundedChunks() {
        val cache = SimpleCache(temporaryFolder.newFolder(), LeastRecentlyUsedCacheEvictor(1024L))
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DownloadRangeDataSource.Factory(
                    object : DataSource.Factory {
                        override fun createDataSource() = RecordingDataSource(data)
                    },
                ) { stream(contentLength = null, rangePolicy = RangePolicy.Bounded(4L)) },
            )
            .createDataSource()

        CacheWriter(
            cacheDataSource,
            dataSpec(),
            ByteArray(3),
        ) { _, _, _ -> }.cache()

        assertEquals(data.size.toLong(), cache.getCachedBytes("mediaId", 0L, C.LENGTH_UNSET.toLong()))
        assertEquals(
            data.size.toLong(),
            ContentMetadata.getContentLength(cache.getContentMetadata("mediaId")),
        )
        cache.release()
    }

    @Test
    fun completedCacheHitDoesNotStartAnotherUpstreamTransfer() {
        val cache = SimpleCache(temporaryFolder.newFolder(), LeastRecentlyUsedCacheEvictor(1024L))
        val firstDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DownloadRangeDataSource.Factory(
                    object : DataSource.Factory {
                        override fun createDataSource() = RecordingDataSource(data)
                    },
                ) { stream(contentLength = null, rangePolicy = RangePolicy.Bounded(4L)) }
            )
            .createDataSource()

        CacheWriter(
            firstDataSource,
            dataSpec(),
            ByteArray(3),
        ) { _, _, _ -> }.cache()

        val cacheHitDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DownloadRangeDataSource.Factory(
                    object : DataSource.Factory {
                        override fun createDataSource() = FailingDataSource()
                    },
                ) { stream(contentLength = data.size.toLong(), rangePolicy = RangePolicy.Bounded(4L)) }
            )
            .createDataSource()

        CacheWriter(
            cacheHitDataSource,
            dataSpec(),
            ByteArray(3),
        ) { _, _, _ -> }.cache()

        assertEquals(data.size.toLong(), cache.getCachedBytes("mediaId", 0L, C.LENGTH_UNSET.toLong()))
        cache.release()
    }

    private fun dataSource(
        upstream: RecordingDataSource,
        contentLength: Long?,
    ) = DownloadRangeDataSource.Factory(
        object : DataSource.Factory {
            override fun createDataSource() = upstream
        },
    ) { stream(contentLength, RangePolicy.Bounded(4L)) }
        .createDataSource()

    private fun dataSpec(
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec.Builder()
        .setUri(Uri.parse("https://example.invalid/audio"))
        .setKey("mediaId")
        .setPosition(position)
        .setLength(length)
        .build()

    private fun stream(
        contentLength: Long?,
        rangePolicy: RangePolicy,
    ) = ResolvedStream(
        url = "https://example.invalid/audio",
        headers = mapOf("User-Agent" to "ua"),
        clientName = "WEB_REMIX",
        expiresAtEpochMs = Long.MAX_VALUE,
        format = ResolvedFormat(
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bitrate = 128000,
            sampleRate = 44100,
            contentLength = contentLength,
            loudnessDb = null,
            perceptualLoudnessDb = null,
        ),
        rangePolicy = rangePolicy,
        playbackTrackingUrl = null,
        videoLengthSeconds = null,
    )

    private fun DataSource.readAll(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(3)
        while (true) {
            val bytesRead = read(buffer, 0, buffer.size)
            if (bytesRead == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, bytesRead)
        }
        close()
        return output.toByteArray()
    }

    private class RecordingDataSource(private val data: ByteArray) : DataSource {
        val openedSpecs = mutableListOf<DataSpec>()
        private var position = 0L

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openedSpecs.add(dataSpec)
            position = dataSpec.position
            val available = (data.size - position).coerceAtLeast(0L)
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(dataSpec.length, available)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            position += count
            return count
        }

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }

    private class FailingDataSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long =
            error("A complete cache hit must not open the upstream DataSource")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            error("A complete cache hit must not read from the upstream DataSource")

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }
}

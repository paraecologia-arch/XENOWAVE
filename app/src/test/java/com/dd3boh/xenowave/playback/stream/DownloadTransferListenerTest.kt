package com.dd3boh.xenowave.playback.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadTransferListenerTest {

    @Test
    fun aggregatesNetworkAndCacheTransfersSeparately() {
        val nowNs = AtomicLong(1_000_000L)
        val listener = DownloadTransferListener(nowNs = nowNs::incrementAndGet)
        val spec = dataSpec()
        val source = NoOpDataSource()

        listener.onTransferInitializing(source, spec, isNetwork = true)
        listener.onTransferStart(source, spec, isNetwork = true)
        listener.onBytesTransferred(source, spec, isNetwork = true, bytesTransferred = 100)
        listener.onTransferEnd(source, spec, isNetwork = true)

        listener.onTransferInitializing(source, spec, isNetwork = false)
        listener.onTransferStart(source, spec, isNetwork = false)
        listener.onBytesTransferred(source, spec, isNetwork = false, bytesTransferred = 25)
        listener.onTransferEnd(source, spec, isNetwork = false)

        val summary = listener.remove("mediaId")

        assertEquals(100L, summary?.networkBytes)
        assertEquals(25L, summary?.cacheBytes)
        assertEquals(1L, summary?.networkChunks)
        assertEquals(1L, summary?.cacheChunks)
    }

    private fun dataSpec() = DataSpec.Builder()
        .setUri(Uri.parse("https://example.invalid/audio"))
        .setKey("mediaId")
        .setPosition(0L)
        .setLength(C.LENGTH_UNSET.toLong())
        .build()

    private class NoOpDataSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }
}

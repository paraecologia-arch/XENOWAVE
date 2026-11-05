package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.constants.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class StreamResolutionCoordinatorTest {

    @Test
    fun simultaneousRequestsForSameMediaIdShareOneResolution() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<StreamResolutionEvent>()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                calls.incrementAndGet()
                gate.await()
                stream(mediaId)
            },
            onEvent = events::add,
        )

        val callers = mutableListOf(asyncCall(coordinator, "mediaId"))
        awaitEvent<StreamResolutionEvent.Started>(events)
        callers += asyncCall(coordinator, "mediaId")
        awaitEvent<StreamResolutionEvent.Joined>(events)
        assertEquals(1, calls.get())
        gate.complete(Unit)

        val results = callers.awaitAll()

        assertEquals(1, calls.get())
        assertEquals(2, results.size)
        assertSame(results[0].getOrThrow(), results[1].getOrThrow())
        assertEquals(1, events.countIsInstance<StreamResolutionEvent.Started>())
        assertEquals(1, events.countIsInstance<StreamResolutionEvent.Joined>())
    }

    @Test
    fun simultaneousRequestsSharePostResolutionWork() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transformations = AtomicInteger()
        val coordinator = newCoordinator(resolver = { mediaId ->
            gate.await()
            stream(mediaId)
        })

        val callers = listOf(
            CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                coordinator.resolve("mediaId", AudioQuality.AUTO, false) {
                    transformations.incrementAndGet()
                    it
                }
            },
            CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                coordinator.resolve("mediaId", AudioQuality.AUTO, false) {
                    transformations.incrementAndGet()
                    it
                }
            },
        )
        gate.complete(Unit)

        callers.awaitAll()

        assertEquals(1, transformations.get())
    }

    @Test
    fun differentMediaIdsResolveIndependently() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = newCoordinator(resolver = { mediaId ->
            calls.incrementAndGet()
            stream(mediaId)
        })

        val first = coordinator.resolve("first", AudioQuality.AUTO, false).getOrThrow()
        val second = coordinator.resolve("second", AudioQuality.AUTO, false).getOrThrow()

        assertEquals(2, calls.get())
        assertEquals("first", first.clientName)
        assertEquals("second", second.clientName)
    }

    @Test
    fun validResultIsReused() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = newCoordinator(resolver = { mediaId ->
            calls.incrementAndGet()
            stream(mediaId, contentLength = 123L)
        })

        val first = coordinator.resolve("mediaId", AudioQuality.AUTO, false).getOrThrow()
        val second = coordinator.resolve("mediaId", AudioQuality.AUTO, false).getOrThrow()

        assertEquals(1, calls.get())
        assertSame(first, second)
    }

    @Test
    fun expiredResultIsRenewed() = runBlocking {
        val nowMs = AtomicLong(0L)
        val calls = AtomicInteger()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                calls.incrementAndGet()
                stream(mediaId, expiresAtEpochMs = 10L)
            },
            nowMs = nowMs::get,
        )

        coordinator.resolve("mediaId", AudioQuality.AUTO, false)
        nowMs.set(11L)
        val renewed = coordinator.resolve("mediaId", AudioQuality.AUTO, false)

        assertEquals(2, calls.get())
        assertTrue(renewed.isSuccess)
    }

    @Test
    fun expiredResultIsRenewedWhenWaiterResumesInline() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val resolutionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val nowMs = AtomicLong(0L)
        val calls = AtomicInteger()
        val events = mutableListOf<StreamResolutionEvent>()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                val call = calls.incrementAndGet()
                stream(mediaId, expiresAtEpochMs = if (call == 1) 10L else 20L)
            },
            nowMs = nowMs::get,
            onEvent = events::add,
            scope = resolutionScope,
        )

        try {
            // Unconfined resumes the waiter inside complete(), before the producer returns.
            val renewal = async(Dispatchers.Unconfined) {
                coordinator.resolve("mediaId", AudioQuality.AUTO, false).getOrThrow()
                nowMs.set(11L)
                coordinator.resolve("mediaId", AudioQuality.AUTO, false).getOrThrow()
            }
            dispatcher.runAll()

            assertEquals(2, calls.get())
            assertEquals(20L, renewal.await().expiresAtEpochMs)
            assertEquals(
                listOf("Started", "Succeeded", "Expired", "Started", "Succeeded"),
                events.map { it.javaClass.simpleName },
            )
        } finally {
            resolutionScope.cancel()
        }
    }

    @Test
    fun failedResolutionCanBeRetriedWhenWaiterResumesInline() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val resolutionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val calls = AtomicInteger()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                check(calls.incrementAndGet() > 1) { "resolver unavailable" }
                stream(mediaId)
            },
            scope = resolutionScope,
        )

        try {
            val retry = async(Dispatchers.Unconfined) {
                assertTrue(coordinator.resolve("mediaId", AudioQuality.AUTO, false).isFailure)
                coordinator.resolve("mediaId", AudioQuality.AUTO, false)
            }
            dispatcher.runAll()

            assertEquals(2, calls.get())
            assertTrue(retry.await().isSuccess)
        } finally {
            resolutionScope.cancel()
        }
    }

    @Test
    fun cancellingScopeBeforeResolutionStartsReleasesWaiter() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val resolutionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val calls = AtomicInteger()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                calls.incrementAndGet()
                stream(mediaId)
            },
            scope = resolutionScope,
        )
        val waiter = async(Dispatchers.Unconfined) {
            runCatching { coordinator.resolve("mediaId", AudioQuality.AUTO, false) }
        }

        try {
            resolutionScope.cancel()
            dispatcher.runAll()

            assertTrue("Cancellation must release the waiting caller", waiter.isCompleted)
            assertTrue(waiter.await().exceptionOrNull() is CancellationException)
            assertEquals(0, calls.get())
        } finally {
            waiter.cancel()
            resolutionScope.cancel()
        }
    }

    @Test
    fun failedResolutionCanBeRetriedLater() = runBlocking {
        val calls = AtomicInteger()
        var nextCallFails = true
        val coordinator = newCoordinator(resolver = { mediaId ->
            calls.incrementAndGet()
            if (nextCallFails) {
                nextCallFails = false
                throw IllegalStateException("resolver unavailable")
            }
            stream(mediaId)
        })

        val failed = coordinator.resolve("mediaId", AudioQuality.AUTO, false)
        val retried = coordinator.resolve("mediaId", AudioQuality.AUTO, false)

        assertEquals(2, calls.get())
        assertTrue(failed.isFailure)
        assertTrue(retried.isSuccess)
    }

    @Test
    fun cancelledWaitersDoNotBlockAFutureRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<StreamResolutionEvent>()
        val coordinator = newCoordinator(
            resolver = { mediaId ->
                calls.incrementAndGet()
                gate.await()
                stream(mediaId)
            },
            onEvent = events::add,
        )

        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val callers = mutableListOf(
            callerScope.async { coordinator.resolve("mediaId", AudioQuality.AUTO, false) }
        )
        awaitEvent<StreamResolutionEvent.Started>(events)
        callers += callerScope.async { coordinator.resolve("mediaId", AudioQuality.AUTO, false) }
        awaitEvent<StreamResolutionEvent.Joined>(events)
        callers.forEach { it.cancel() }
        gate.complete(Unit)
        awaitEvent<StreamResolutionEvent.Succeeded>(events)

        val laterResult = coordinator.resolve("mediaId", AudioQuality.AUTO, false)

        assertEquals(1, calls.get())
        assertTrue(laterResult.isSuccess)
        callerScope.cancel()
    }

    @Test
    fun transformedResultIsCachedForLaterCallers() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = newCoordinator(resolver = { mediaId ->
            calls.incrementAndGet()
            stream(mediaId, contentLength = null)
        })

        val initial = coordinator.resolve("mediaId", AudioQuality.AUTO, false) {
            it.copy(format = it.format.copy(contentLength = 456L))
        }.getOrThrow()
        val reused = coordinator.resolve("mediaId", AudioQuality.AUTO, false).getOrThrow()

        assertEquals(1, calls.get())
        assertEquals(456L, initial.format.contentLength)
        assertEquals(456L, reused.format.contentLength)
        assertSame(initial, reused)
    }

    private fun newCoordinator(
        resolver: suspend (String) -> ResolvedStream,
        nowMs: () -> Long = { 0L },
        onEvent: (StreamResolutionEvent) -> Unit = {},
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) = StreamResolutionCoordinator(
        resolver = object : StreamResolver {
            override suspend fun resolve(
                videoId: String,
                audioQuality: AudioQuality,
                isActiveNetworkMetered: Boolean,
                playlistId: String?,
            ): Result<ResolvedStream> = runCatching { resolver(videoId) }
        },
        scope = scope,
        nowMs = nowMs,
        onEvent = onEvent,
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    private fun asyncCall(
        coordinator: StreamResolutionCoordinator,
        mediaId: String,
    ) = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        .async { coordinator.resolve(mediaId, AudioQuality.AUTO, false) }

    private suspend inline fun <reified T : StreamResolutionEvent> awaitEvent(
        events: List<StreamResolutionEvent>,
    ): T {
        return withTimeout(5_000L) {
            while (true) {
                events.filterIsInstance<T>().firstOrNull()?.let { return@withTimeout it }
                delay(10L)
            }
            error("Unreachable")
        }
    }

    private inline fun <reified T : StreamResolutionEvent> List<StreamResolutionEvent>.countIsInstance(): Int =
        filterIsInstance<T>().size

    private fun stream(
        mediaId: String,
        expiresAtEpochMs: Long = Long.MAX_VALUE,
        contentLength: Long? = 10L,
    ) = ResolvedStream(
        url = "https://example.invalid/audio",
        headers = emptyMap(),
        clientName = mediaId,
        expiresAtEpochMs = expiresAtEpochMs,
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
        rangePolicy = RangePolicy.Chunked(4096L),
        playbackTrackingUrl = null,
        videoLengthSeconds = null,
    )
}

/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import android.util.Log
import com.dd3boh.xenowave.constants.AudioQuality
import com.dd3boh.xenowave.utils.potoken.PoTokenGenerator
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.models.YouTubeLocale as InnerTubeXYouTubeLocale
import com.zionhuang.innertube.YouTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Thin adapter that owns an InnerTubeX extraction stack, dedicated to stream resolution only.
 *
 * It does not touch the legacy browse/search/login InnerTube module. Every [resolve] call
 * re-syncs the InnerTubeX session with the current XENOWAVE session and then delegates to
 * [InnerTubeExtractor.extract].
 */
class InnerTubeXEngine {

    private val tag = "InnerTubeXEngine"

    private val poTokenGenerator = PoTokenGenerator()

    private val httpClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                },
            )
        }

        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }

        YouTube.proxy?.let { proxyValue ->
            engine {
                proxy = proxyValue
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }

        defaultRequest {
            url("https://music.youtube.com/youtubei/v1/")
            header("Accept", "application/json")
            header("Cache-Control", "no-cache")
        }
    }

    private val tokenProvider: TokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult? =
            poTokenGenerator
                .getWebClientPoToken(videoId, visitorData)
                ?.let { token ->
                    PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }
    }

    private val innerTubeX: InnerTube = InnerTube(httpClient)

    private val cipherService: YouTubeCipherService = YouTubeCipherService(
        httpClient = httpClient,
        remotePlayerConfigStore = null,
    )

    private val configParser: YtConfigParserImpl = YtConfigParserImpl(
        httpClient = httpClient,
        innerTube = innerTubeX,
        remotePlayerConfigStore = null,
    )

    private val extractor: InnerTubeExtractor = InnerTubeExtractor(
        configParser = configParser,
        cipherService = cipherService,
        innerTube = innerTubeX,
        tokenProvider = tokenProvider,
    )

    suspend fun resolve(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        isActiveNetworkMetered: Boolean,
    ): ExtractedStream {
        Log.d(tag, "InnerTubeX resolve start: videoId=$videoId")

        syncSession()

        val hints = ContentHints(
            isUploaded = playlistId == "MLPT" || playlistId?.contains("MLPT") == true,
            wantVideo = false,
        ).withStreamCapabilities(
            allowHls = false,
            allowSabr = false,
            allowBoundedRange = true,
        )

        val stream = extractor.extract(
            videoId = videoId,
            hints = hints,
            excludedClients = emptySet(),
            audioQuality = mapAudioQuality(audioQuality, isActiveNetworkMetered),
            clientPlaybackNonce = generateClientPlaybackNonce(),
        )

        Log.d(
            tag,
            "InnerTubeX resolve ${if (stream != null) "success" else "failure"}: " +
                "videoId=$videoId client=${stream?.clientName}",
        )
        return stream ?: error("InnerTubeX returned no playable stream")
    }

    private fun syncSession() {
        innerTubeX.locale = InnerTubeXYouTubeLocale(
            gl = YouTube.locale.gl,
            hl = YouTube.locale.hl,
        )
        innerTubeX.visitorData = YouTube.visitorData
        innerTubeX.dataSyncId = YouTube.dataSyncId
        innerTubeX.cookie = YouTube.cookie
        innerTubeX.useLoginForBrowse = YouTube.useLoginForBrowse
        innerTubeX.authUser = "0"
    }

    private fun mapAudioQuality(
        quality: AudioQuality,
        isActiveNetworkMetered: Boolean,
    ): InnerTubeXAudioQuality = when (quality) {
        AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
        AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
        AudioQuality.AUTO ->
            if (isActiveNetworkMetered) InnerTubeXAudioQuality.LOW else InnerTubeXAudioQuality.AUTO
    }
}

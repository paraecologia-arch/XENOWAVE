package com.dd3boh.xenowave.insights.musicalage.metadata


data class MetadataRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class MetadataResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

fun interface MetadataTransport {
    suspend fun execute(
        request: MetadataRequest,
        maxResponseBytes: Long,
        timeoutMillis: Long,
    ): MetadataResponse
}

object MusicBrainzUserAgent {
    const val VALUE =
        "XENOWAVE/0.10.3-beta.2 (https://paraecologia-arch.github.io/XENOWAVE/)"
}

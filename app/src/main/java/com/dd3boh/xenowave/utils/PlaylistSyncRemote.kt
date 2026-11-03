package com.dd3boh.xenowave.utils

import android.content.Context
import com.dd3boh.xenowave.constants.*
import com.dd3boh.xenowave.extensions.isInternetConnected
import com.dd3boh.xenowave.extensions.toEnum
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.pages.PlaylistPage
import com.zionhuang.innertube.utils.completed
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface PlaylistSyncSession {
    val changes: Flow<Unit>
    fun owner(): String?
    fun canWrite(owner: String): Boolean
}

interface PlaylistSyncRemote {
    suspend fun read(id: String): PlaylistPage
    suspend fun library(): List<PlaylistItem>
    suspend fun create(title: String): String
    suspend fun add(id: String, videoId: String)
    suspend fun remove(id: String, videoId: String, setId: String)
    suspend fun move(id: String, setId: String, successor: String)
    suspend fun rename(id: String, title: String)
    suspend fun delete(id: String)
}

internal class PlaylistWriteRejected : Exception()

private class AccountPlaylistSyncSession(private val context: Context) : PlaylistSyncSession {
    override val changes = context.dataStore.data.map {
        listOf(it[DataSyncIdKey], it[AccountEmailKey], it[AccountChannelHandleKey], it[InnerTubeCookieKey],
            it[YtmSyncModeKey], it[YtmSyncKey], it[YtmSyncContentKey])
    }.distinctUntilChanged().map { Unit }

    override fun owner(): String? {
        val cookie = context.dataStore.get(InnerTubeCookieKey).orEmpty()
        if (!cookie.contains("SAPISID=") || YouTube.cookie != cookie) return null
        if (YouTube.dataSyncId != context.dataStore.get(DataSyncIdKey)) return null
        return context.playlistSyncOwner()
    }

    override fun canWrite(owner: String): Boolean = this.owner() == owner && context.isInternetConnected() &&
        context.dataStore.get(YtmSyncKey, true) &&
        context.dataStore.get(YtmSyncModeKey, SyncMode.RW.name).toEnum(SyncMode.RW) == SyncMode.RW &&
        SyncContent.PLAYLISTS in decodeSyncString(context.dataStore.get(YtmSyncContentKey, "ARPLSC"))
}

private class YouTubePlaylistSyncRemote : PlaylistSyncRemote {
    override suspend fun read(id: String) = YouTube.playlist(id).completed().getOrThrow()
    override suspend fun library() = YouTube.library("FEmusic_liked_playlists").completed().getOrThrow().items.filterIsInstance<PlaylistItem>()
    override suspend fun create(title: String) = YouTube.createPlaylist(title)

    private suspend fun checked(result: Result<HttpResponse>) {
        val body = result.getOrThrow().body<JsonObject>()
        val status = body["status"]?.jsonPrimitive?.contentOrNull
        if (body["error"] != null || (status != null && status != "STATUS_SUCCEEDED")) throw PlaylistWriteRejected()
    }

    override suspend fun add(id: String, videoId: String) = checked(YouTube.addToPlaylist(id.removePrefix("VL"), videoId))
    override suspend fun remove(id: String, videoId: String, setId: String) = checked(YouTube.removeFromPlaylist(id.removePrefix("VL"), videoId, setId))
    override suspend fun move(id: String, setId: String, successor: String) = checked(YouTube.moveSongPlaylist(id.removePrefix("VL"), setId, successor))
    override suspend fun rename(id: String, title: String) = checked(YouTube.renamePlaylist(id.removePrefix("VL"), title))
    override suspend fun delete(id: String) = checked(YouTube.deletePlaylist(id.removePrefix("VL")))
}

@Module
@InstallIn(SingletonComponent::class)
object PlaylistSyncModule {
    @Provides
    fun session(@ApplicationContext context: Context): PlaylistSyncSession = AccountPlaylistSyncSession(context)

    @Provides
    fun remote(): PlaylistSyncRemote = YouTubePlaylistSyncRemote()
}

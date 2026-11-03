package com.dd3boh.xenowave.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A durable outbox, deliberately without a foreign key so deletions survive process death. */
@Entity(tableName = "playlist_sync_state")
data class PlaylistSyncState(
    @PrimaryKey val playlistId: String,
    val browseId: String?,
    val ownerId: String?,
    val baseline: String,
    val pending: Boolean = false,
    val deleted: Boolean = false,
    val revision: Long = 0,
    val orderPending: Boolean = false,
    val inFlightAdd: String? = null,
    val error: String? = null,
)

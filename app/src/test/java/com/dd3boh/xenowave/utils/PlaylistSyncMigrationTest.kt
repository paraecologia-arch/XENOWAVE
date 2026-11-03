package com.dd3boh.xenowave.utils

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlaylistSyncMigrationTest {
    @Test
    fun upgradingVersion20PreservesPlaylistsAndAddsAnEmptyOutbox() {
        val context = RuntimeEnvironment.getApplication()
        val name = "playlist-sync-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val relative = "schemas/com.dd3boh.xenowave.db.InternalDatabase/20.json"
        val schemaFile = listOf(File(relative), File("app/$relative")).first { it.exists() }
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val table = element.jsonObject
                val tableName = table.getValue("tableName").jsonPrimitive.content
                old.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                table["indices"]?.jsonArray?.forEach { index ->
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName))
                }
            }
            schema.getValue("views").jsonArray.forEach { element ->
                val view = element.jsonObject
                old.execSQL(view.getValue("createSql").jsonPrimitive.content.replace("\${VIEW_NAME}", view.getValue("viewName").jsonPrimitive.content))
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO playlist (id, name, browseId, isEditable, isLocal) VALUES ('existing', 'Keep me', 'remote', 1, 0)")
            old.execSQL("INSERT INTO song (id, title, duration, liked, isLocal) VALUES ('song', 'Keep song', 60, 0, 0)")
            old.execSQL("INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId) VALUES ('existing', 'song', 0, 'occurrence')")
            old.version = 20
        }
        val migrated = Room.databaseBuilder(context, InternalDatabase::class.java, name).allowMainThreadQueries().build()
        try {
                assertEquals("Keep me", migrated.dao.playlistEntityForSync("existing")!!.name)
                assertEquals("occurrence", migrated.dao.playlistSongMaps("existing").single().setVideoId)
                assertNull(migrated.dao.playlistSyncState("existing"))
                migrated.dao.update(migrated.dao.playlistEntityForSync("existing")!!.copy(name = "Queued rename"))
                assertTrue(migrated.dao.playlistSyncState("existing")!!.pending)
                assertEquals("Keep me", PlaylistSyncSnapshot.decode(migrated.dao.playlistSyncState("existing")!!.baseline).name)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}

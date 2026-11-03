package com.dd3boh.xenowave.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PlaylistSyncEntry(val mapId: Int, val videoId: String, val setVideoId: String?)

data class PlaylistSyncSnapshot(val name: String, val entries: List<PlaylistSyncEntry>) {
    fun encode(): String = buildJsonObject {
        put("name", name)
        put("entries", JsonArray(entries.map { entry ->
            buildJsonObject {
                put("mapId", entry.mapId)
                put("videoId", entry.videoId)
                put("setVideoId", entry.setVideoId?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
    }.toString()

    companion object {
        fun decode(value: String): PlaylistSyncSnapshot {
            val root = Json.parseToJsonElement(value).jsonObject
            return PlaylistSyncSnapshot(root.getValue("name").jsonPrimitive.content, root.getValue("entries").jsonArray.map {
                val entry = it.jsonObject
                PlaylistSyncEntry(
                    entry.getValue("mapId").jsonPrimitive.int,
                    entry.getValue("videoId").jsonPrimitive.content,
                    entry.getValue("setVideoId").jsonPrimitive.contentOrNull,
                )
            })
        }
    }
}

/** Pure reconciliation rules. Occurrences are identified by setVideoId, never by song ID. */
object PlaylistSyncPlan {
    fun removals(baseline: PlaylistSyncSnapshot, desired: List<PlaylistSyncEntry>): Set<String> =
        baseline.entries.mapNotNull { it.setVideoId }.toSet() - desired.mapNotNull { it.setVideoId }.toSet()

    fun order(remote: List<String>, desired: List<String>, reorder: Boolean): List<String> {
        if (!reorder) return remote
        val wanted = desired.filter { it in remote }.distinct()
        val wantedSet = wanted.toSet()
        val iterator = wanted.iterator()
        // Remote-only additions retain their relative order and their slots.
        return remote.map { if (it in wantedSet) iterator.next() else it }
    }

    /** Move from right to left: no nullable successor and no special case that loses the last song. */
    fun moves(remote: List<String>, target: List<String>): List<Pair<String, String>> {
        require(remote.toSet() == target.toSet() && remote.size == target.size)
        val working = remote.toMutableList()
        val moves = mutableListOf<Pair<String, String>>()
        for (index in target.lastIndex - 1 downTo 0) {
            val item = target[index]
            val successor = target[index + 1]
            if (working.indexOf(item) + 1 != working.indexOf(successor)) {
                working.remove(item)
                working.add(working.indexOf(successor), item)
                moves += item to successor
            }
        }
        check(working == target)
        return moves
    }

    fun recoveredAddition(before: Set<String>, remote: List<PlaylistSyncEntry>, videoId: String): String? =
        remote.filter { it.videoId == videoId && it.setVideoId != null && it.setVideoId !in before }
            .singleOrNull()?.setVideoId
}

data class PlaylistAddAttempt(val mapId: Int, val videoId: String, val before: Set<String>) {
    fun encode(): String = buildJsonObject {
        put("mapId", mapId)
        put("videoId", videoId)
        put("before", JsonArray(before.map(::JsonPrimitive)))
    }.toString()

    companion object {
        fun decode(value: String): PlaylistAddAttempt {
            val root: JsonObject = Json.parseToJsonElement(value).jsonObject
            return PlaylistAddAttempt(root.getValue("mapId").jsonPrimitive.int,
                root.getValue("videoId").jsonPrimitive.content,
                root.getValue("before").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }
}

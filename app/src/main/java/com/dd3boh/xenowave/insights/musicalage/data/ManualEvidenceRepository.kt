package com.dd3boh.xenowave.insights.musicalage.data

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.content.InMemoryManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists user corrections separately from the disposable technical cache.
 *
 * It lives in `noBackupFilesDir`, is versioned, bounded and atomically
 * rewritten. Resolvers never touch this file: they receive
 * [snapshotStore], an immutable in-memory copy.
 */
class ManualEvidenceRepository(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(directory, "musical-age-manual-evidence-v1.json")
    private val pathLock = ManualFileMutex.forPath(file.absolutePath)
    private val store = InMemoryManualEvidenceStore()
    private var loaded = false
    private var lastLoadFailed = false
    private var lastWriteFailed = false

    suspend fun load(): Boolean {
        if (loaded) return true
        return withContext(ioDispatcher) {
            synchronized(pathLock) {
                if (loaded) return@synchronized true
                AtomicFileWriter.recover(file)
                if (!file.isFile) {
                    loaded = true
                    lastLoadFailed = false
                    return@synchronized true
                }
                val text = try {
                    readLimited(file, MAX_BYTES).toString(Charsets.UTF_8)
                } catch (_: Exception) {
                    lastLoadFailed = true
                    return@synchronized false
                }
                return@synchronized try {
                    val entries = ManualJson.decode(text)
                    store.clear()
                    entries.forEach(store::put)
                    loaded = true
                    lastLoadFailed = false
                    true
                } catch (_: Exception) {
                    lastLoadFailed = true
                    false
                }
            }
        }
    }

    fun snapshotStore(): ManualEvidenceStore =
        InMemoryManualEvidenceStore(store.snapshot())

    suspend fun put(evidence: ManualEvidence): Boolean = withContext(ioDispatcher) {
        if (!loaded && !load()) return@withContext false
        synchronized(pathLock) {
            val next = InMemoryManualEvidenceStore(store.snapshot())
            next.put(evidence)
            if (!persist(next.snapshot())) {
                lastWriteFailed = true
                return@synchronized false
            }
            store.put(evidence)
            lastWriteFailed = false
            true
        }
    }

    suspend fun replace(evidence: ManualEvidence): Boolean = put(evidence)

    suspend fun remove(songId: String): Boolean = withContext(ioDispatcher) {
        if (!loaded && !load()) return@withContext false
        synchronized(pathLock) {
            val next = InMemoryManualEvidenceStore(store.snapshot())
            next.remove(songId)
            if (!persist(next.snapshot())) {
                lastWriteFailed = true
                return@synchronized false
            }
            store.remove(songId)
            lastWriteFailed = false
            true
        }
    }

    suspend fun clear(): Boolean = withContext(ioDispatcher) {
        synchronized(pathLock) {
            val primaryDeleted = !file.exists() || file.delete()
            val backup = AtomicFileWriter.backupFile(file)
            val backupDeleted = !backup.exists() || backup.delete()
            if (!primaryDeleted || !backupDeleted) {
                lastWriteFailed = true
                return@synchronized false
            }
            store.clear()
            loaded = true
            lastWriteFailed = false
            true
        }
    }

    fun count(): Int = store.snapshot().values.sumOf { it.size }

    fun lastLoadFailed(): Boolean = lastLoadFailed

    fun lastWriteFailed(): Boolean = lastWriteFailed

    private fun persist(snapshot: Map<String, List<ManualEvidence>>): Boolean {
        val bytes = ManualJson.encode(snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        return AtomicFileWriter.write(file, bytes)
    }

    private fun readLimited(file: File, limit: Long): ByteArray {
        if (file.length() > limit) throw IllegalStateException("manual evidence file too large")
        FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalStateException("manual evidence file too large")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private companion object {
        const val MAX_BYTES = 1_048_576L
    }
}

private object ManualFileMutex {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forPath(path: String): Any = locks.computeIfAbsent(path) { Any() }
}

private object ManualJson {
    private const val SCHEMA_VERSION = 1
    private val json = Json

    fun encode(entries: Map<String, List<ManualEvidence>>): String =
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("entries", buildJsonArray {
                entries.values.flatten().forEach { add(encodeEntry(it)) }
            })
        }.toString()

    fun decode(text: String): List<ManualEvidence> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != SCHEMA_VERSION) return emptyList()
        return (root["entries"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(::decodeEntry)
            .orEmpty()
    }

    private fun encodeEntry(value: ManualEvidence): JsonObject =
        buildJsonObject {
            put("songId", value.songId)
            value.contentState?.let { put("contentState", it.name) }
            value.recordingKey?.let { put("recordingKey", it) }
            value.canonicalRecordingId?.let { put("canonicalRecordingId", it) }
            value.year?.let { put("year", it) }
            put("precision", value.precision.name)
            put("resolvedVersion", value.resolvedVersion.name)
            put("chosenAt", value.chosenAt.toEpochMilli())
            value.replacesEvidence?.let { put("replacesEvidence", it) }
            put(
                "policyVersion",
                value.policyVersion.ifBlank {
                    MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION
                },
            )
        }

    private fun decodeEntry(obj: JsonObject): ManualEvidence? {
        val songId = obj.string("songId") ?: return null
        if (songId.length > 512) return null
        val chosenAt = obj.long("chosenAt")?.let(Instant::ofEpochMilli) ?: return null
        val precision = enumValueOrNull<YearEvidencePrecision>(obj.string("precision"))
            ?: YearEvidencePrecision.UNKNOWN
        val rawVersion = obj.string("resolvedVersion")
        val version = enumValueOrNull<RecordingVersion>(rawVersion)
            ?: RecordingVersion.entries.firstOrNull { it.label == rawVersion }
            ?: RecordingVersion.UNKNOWN
        return ManualEvidence(
            songId = songId,
            contentState = enumValueOrNull<ContentEvidenceState>(obj.string("contentState")),
            recordingKey = obj.string("recordingKey"),
            canonicalRecordingId = obj.string("canonicalRecordingId"),
            year = (obj["year"] as? JsonPrimitive)?.intOrNull,
            precision = precision,
            resolvedVersion = version,
            chosenAt = chosenAt,
            replacesEvidence = obj.string("replacesEvidence"),
            policyVersion = obj.string("policyVersion")
                ?: MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}

package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import com.dd3boh.xenowave.insights.musicalage.data.AtomicFileWriter
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
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class CacheEntryKind {
    CONFIRMED,
    ABSENCE,
    AMBIGUITY,
    TRANSIENT_ERROR,
}

data class RecordingEvidenceCacheEntry(
    val key: String,
    val kind: CacheEntryKind,
    val evidence: List<YearEvidence>,
    val storedAt: Instant,
    val expiresAt: Instant,
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
)

data class CacheLookup(
    val entry: RecordingEvidenceCacheEntry?,
    val hit: Boolean,
    val expired: Boolean,
)

interface RecordingEvidenceCache {
    fun get(key: String, now: Instant): CacheLookup

    /**
     * @return true only when the entry was accepted and durably persisted.
     * A failed write must not be treated as a successful renewal.
     */
    fun put(entry: RecordingEvidenceCacheEntry): Boolean

    fun clear(): Boolean

    /**
     * Monotonic cache epoch. A clear increments it so a write prepared by an
     * older operation cannot repopulate the cache after the user cleared it.
     */
    fun generation(): Long = 0L

    fun put(entry: RecordingEvidenceCacheEntry, expectedGeneration: Long): Boolean =
        generation() == expectedGeneration && put(entry)
}

class InMemoryRecordingEvidenceCache(
    private val maxEntries: Int = 500,
    private val maxBytes: Long = 2L * 1_048_576L,
) : RecordingEvidenceCache {
    private val entries = LinkedHashMap<String, RecordingEvidenceCacheEntry>()
    private val lock = Any()
    private var cacheGeneration = 0L

    override fun get(key: String, now: Instant): CacheLookup = synchronized(lock) {
        val entry = entries[key]
        when {
            entry == null -> CacheLookup(null, hit = false, expired = false)
            !entry.expiresAt.isAfter(now) ->
                // Keep the stale entry so a failed refresh can reuse it.
                CacheLookup(entry, hit = false, expired = true)
            else -> CacheLookup(entry, hit = true, expired = false)
        }
    }

    override fun put(entry: RecordingEvidenceCacheEntry): Boolean {
        synchronized(lock) {
            entries[entry.key] = entry
            while (entries.size > maxEntries ||
                entries.values.sumOf { estimateBytes(it) } > maxBytes
            ) {
                val oldest = entries.values.minByOrNull { it.storedAt } ?: break
                entries.remove(oldest.key)
            }
        }
        return true
    }

    override fun put(
        entry: RecordingEvidenceCacheEntry,
        expectedGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (cacheGeneration != expectedGeneration) return false
        return put(entry)
    }

    override fun clear(): Boolean {
        synchronized(lock) {
            entries.clear()
            cacheGeneration++
        }
        return true
    }

    override fun generation(): Long = synchronized(lock) { cacheGeneration }

    private fun estimateBytes(entry: RecordingEvidenceCacheEntry): Long =
        CacheJson.encode(listOf(entry)).toByteArray(Charsets.UTF_8).size.toLong()
}

/**
 * Private, versioned, bounded file cache. The caller supplies a private
 * directory (for Android, `noBackupFilesDir`).
 *
 * - the file is never read as an unbounded String: bytes are limited during
 *   the read;
 * - instances sharing the same path use one process-wide mutex;
 * - temporary files are cleaned and a failed write returns false instead of
 *   being reported as success;
 * - expired entries are preserved and returned with `expired = true`.
 */
class FileRecordingEvidenceCache(
    private val directory: File,
    private val maxEntries: Int = 500,
    private val maxBytes: Long = 2L * 1_048_576L,
) : RecordingEvidenceCache {
    private val file = File(directory, "musical-age-evidence-cache-v1.json")
    private val pathLock = FileMutex.forPath(file.absolutePath)
    private var loaded = false
    private val entries = LinkedHashMap<String, RecordingEvidenceCacheEntry>()
    private var lastLoadCorrupt = false
    private var cacheGeneration = 0L

    override fun get(key: String, now: Instant): CacheLookup = synchronized(pathLock) {
        ensureLoaded()
        val entry = entries[key]
        when {
            entry == null -> CacheLookup(null, hit = false, expired = false)
            !entry.expiresAt.isAfter(now) ->
                CacheLookup(entry, hit = false, expired = true)
            else -> CacheLookup(entry, hit = true, expired = false)
        }
    }

    override fun put(entry: RecordingEvidenceCacheEntry): Boolean = synchronized(pathLock) {
        ensureLoaded()
        if (!isValidEntry(entry)) return false
        val next = LinkedHashMap(entries)
        next[entry.key] = entry
        evict(next)
        if (!persist(next)) return false
        entries.clear()
        entries.putAll(next)
        true
    }

    override fun put(
        entry: RecordingEvidenceCacheEntry,
        expectedGeneration: Long,
    ): Boolean = synchronized(pathLock) {
        if (cacheGeneration != expectedGeneration) return false
        return put(entry)
    }

    override fun clear(): Boolean = synchronized(pathLock) {
        entries.clear()
        cacheGeneration++
        loaded = true
        cleanTemporaryFiles()
        val primaryDeleted = !file.exists() || file.delete()
        val backup = AtomicFileWriter.backupFile(file)
        val backupDeleted = !backup.exists() || backup.delete()
        primaryDeleted && backupDeleted
    }

    override fun generation(): Long = synchronized(pathLock) { cacheGeneration }

    fun hadCorruptFile(): Boolean = synchronized(pathLock) {
        ensureLoaded()
        lastLoadCorrupt
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        AtomicFileWriter.recover(file)
        cleanTemporaryFiles()
        if (!file.isFile) return
        val bytes = try {
            readLimited(file, maxBytes)
        } catch (_: Exception) {
            lastLoadCorrupt = true
            return
        }
        try {
            CacheJson.decode(bytes.toString(Charsets.UTF_8))
                .filter(::isValidEntry)
                .forEach { entries[it.key] = it }
            evict(entries)
        } catch (_: Exception) {
            lastLoadCorrupt = true
            entries.clear()
        }
    }

    private fun evict(entries: LinkedHashMap<String, RecordingEvidenceCacheEntry>) {
        while (entries.size > maxEntries ||
            CacheJson.encode(entries.values.toList()).toByteArray(Charsets.UTF_8).size > maxBytes
        ) {
            val oldest = entries.values.minByOrNull { it.storedAt } ?: break
            entries.remove(oldest.key)
        }
    }

    private fun persist(entries: Map<String, RecordingEvidenceCacheEntry>): Boolean {
        val bytes = CacheJson.encode(entries.values.toList()).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return false
        return AtomicFileWriter.write(file, bytes)
    }

    private fun readLimited(file: File, limit: Long): ByteArray {
        val length = file.length()
        if (length > limit) throw IllegalStateException("cache file exceeds limit")
        FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalStateException("cache file exceeds limit")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun cleanTemporaryFiles() {
        runCatching {
            directory.listFiles()
                ?.filter { it.name.startsWith("musical-age-cache-") && it.name.endsWith(".tmp") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun isValidEntry(entry: RecordingEvidenceCacheEntry): Boolean {
        if (!CACHE_KEY.matches(entry.key)) return false
        if (entry.schemaVersion != CACHE_SCHEMA_VERSION) return false
        if (entry.storedAt.toEpochMilli() <= 0L || entry.expiresAt.toEpochMilli() <= 0L) return false
        if (entry.expiresAt.isBefore(entry.storedAt)) return false
        if (Duration.between(entry.storedAt, entry.expiresAt) > MAX_TTL) return false
        if (entry.evidence.size > MAX_EVIDENCE_PER_ENTRY) return false
        return entry.evidence.all(::isValidEvidence)
    }

    private fun isValidEvidence(value: YearEvidence): Boolean {
        if (value.recordingKey.isBlank() || value.recordingKey.length > MAX_TEXT) return false
        if (value.origin.length > MAX_TEXT) return false
        if (value.policyVersion.isBlank() || value.policyVersion.length > MAX_TEXT) return false
        if (value.policyVersion != MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION &&
            value.policyVersion != MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION
        ) {
            return false
        }
        if ((value.resolvedVersion?.length ?: 0) > MAX_TEXT) return false
        if ((value.sourceReference?.length ?: 0) > MAX_TEXT) return false
        if (value.conflictingYears.size > MAX_CONFLICTING_YEARS) return false
        if (value.year != null && value.year !in 1..10_000) return false
        if (value.state == YearEvidenceState.ACCEPTED) {
            if (value.year == null) return false
            if (value.precision == YearEvidencePrecision.UNKNOWN) return false
            if (value.source == YearEvidenceSource.NONE) return false
            if (value.origin.isBlank() || value.acceptanceRule.isNullOrBlank()) return false
            if (value.resolvedVersion.isNullOrBlank()) return false
        }
        return true
    }
}

object RecordingEvidenceCacheKey {
    fun forMetadata(metadata: SongMetadata, policyVersion: String): String {
        val raw = buildString {
            append(metadata.songId).append('|')
            append(metadata.title.lowercase(Locale.ROOT)).append('|')
            append(metadata.artists.map { it.lowercase(Locale.ROOT) }.sorted().joinToString(","))
            append('|').append(metadata.durationMillis ?: -1L)
            append('|').append(metadata.disambiguation.orEmpty())
            append('|').append(metadata.isrc.orEmpty())
            append('|').append(metadata.mbid.orEmpty())
            append('|').append(policyVersion)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun forIdentity(
        recordingKey: String,
        canonicalRecordingId: String?,
        version: String,
        policyVersion: String,
    ): String {
        val raw = "$recordingKey|${canonicalRecordingId.orEmpty()}|$version|$policyVersion"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * One lock object per canonical path, so two cache instances constructed over
 * the same directory cannot write concurrently.
 */
private object FileMutex {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forPath(path: String): Any = locks.computeIfAbsent(path) { Any() }
}

const val CACHE_SCHEMA_VERSION = 2
private val MAX_TTL: Duration = Duration.ofDays(366)
private const val MAX_EVIDENCE_PER_ENTRY = 8
private const val MAX_CONFLICTING_YEARS = 16
private const val MAX_TEXT = 2_048
private val CACHE_KEY = Regex("^[0-9a-f]{64}$")

private object CacheJson {
    private val json = Json

    fun encode(entries: List<RecordingEvidenceCacheEntry>): String =
        buildJsonObject {
            put("schemaVersion", CACHE_SCHEMA_VERSION)
            put("entries", buildJsonArray { entries.forEach { add(encodeEntry(it)) } })
        }.toString()

    fun decode(text: String): List<RecordingEvidenceCacheEntry> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != CACHE_SCHEMA_VERSION) return emptyList()
        return (root["entries"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(::decodeEntry)
            .orEmpty()
    }

    private fun encodeEntry(entry: RecordingEvidenceCacheEntry): JsonObject =
        buildJsonObject {
            put("key", entry.key)
            put("kind", entry.kind.name)
            put("storedAt", entry.storedAt.toEpochMilli())
            put("expiresAt", entry.expiresAt.toEpochMilli())
            put("evidence", buildJsonArray { entry.evidence.forEach { add(encodeEvidence(it)) } })
        }

    private fun decodeEntry(obj: JsonObject): RecordingEvidenceCacheEntry? {
        val key = obj.string("key") ?: return null
        val kind = enumValueOrNull<CacheEntryKind>(obj.string("kind")) ?: return null
        val storedAt = obj.long("storedAt")?.let(Instant::ofEpochMilli) ?: return null
        val expiresAt = obj.long("expiresAt")?.let(Instant::ofEpochMilli) ?: return null
        val evidence = (obj["evidence"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(::decodeEvidence)
            .orEmpty()
        return RecordingEvidenceCacheEntry(key, kind, evidence, storedAt, expiresAt)
    }

    private fun encodeEvidence(value: YearEvidence): JsonObject =
        buildJsonObject {
            put("recordingKey", value.recordingKey)
            value.year?.let { put("year", it) }
            put("state", value.state.name)
            put("origin", value.origin)
            put("precision", value.precision.name)
            value.resolvedVersion?.let { put("resolvedVersion", it) }
            value.canonicalRecordingId?.let { put("canonicalRecordingId", it) }
            value.obtainedAt?.let { put("obtainedAt", it.toEpochMilli()) }
            put("conflictingYears", buildJsonArray {
                value.conflictingYears.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("source", value.source.name)
            value.sourceReference?.let { put("sourceReference", it) }
            value.acceptanceRule?.let { put("acceptanceRule", it) }
            value.linkedRecordingId?.let { put("linkedRecordingId", it) }
            value.releaseId?.let { put("releaseId", it) }
            value.isrc?.let { put("isrc", it) }
            value.transientError?.let { put("transientError", it) }
            value.reason?.let { put("reason", it) }
            value.candidateTitle?.let { put("candidateTitle", it) }
            value.candidateArtist?.let { put("candidateArtist", it) }
            value.candidateDurationMillis?.let { put("candidateDurationMillis", it) }
            value.dateSource?.let { put("dateSource", it) }
            value.recordingDisambiguation?.let { put("recordingDisambiguation", it) }
            value.searchCompleteness?.let { put("searchCompleteness", it) }
            value.associationProof?.let { put("associationProof", it) }
            put("stale", value.stale)
            put("policyVersion", value.policyVersion)
        }

    private fun decodeEvidence(obj: JsonObject): YearEvidence? {
        val recordingKey = obj.string("recordingKey") ?: return null
        val state = enumValueOrNull<YearEvidenceState>(obj.string("state")) ?: return null
        val precision = enumValueOrNull<YearEvidencePrecision>(obj.string("precision"))
            ?: YearEvidencePrecision.UNKNOWN
        val source = enumValueOrNull<YearEvidenceSource>(obj.string("source"))
            ?: YearEvidenceSource.NONE
        val conflictingYears = (obj["conflictingYears"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?.toSet()
            .orEmpty()
        return YearEvidence(
            recordingKey = recordingKey,
            year = (obj["year"] as? JsonPrimitive)?.intOrNull,
            state = state,
            origin = obj.string("origin").orEmpty(),
            precision = precision,
            resolvedVersion = obj.string("resolvedVersion"),
            canonicalRecordingId = obj.string("canonicalRecordingId"),
            obtainedAt = obj.long("obtainedAt")?.let(Instant::ofEpochMilli),
            conflictingYears = conflictingYears,
            source = source,
            sourceReference = obj.string("sourceReference"),
            acceptanceRule = obj.string("acceptanceRule"),
            linkedRecordingId = obj.string("linkedRecordingId"),
            releaseId = obj.string("releaseId"),
            isrc = obj.string("isrc"),
            transientError = obj.string("transientError"),
            reason = obj.string("reason"),
            candidateTitle = obj.string("candidateTitle"),
            candidateArtist = obj.string("candidateArtist"),
            candidateDurationMillis = obj.long("candidateDurationMillis"),
            dateSource = obj.string("dateSource"),
            recordingDisambiguation = obj.string("recordingDisambiguation"),
            searchCompleteness = obj.string("searchCompleteness"),
            associationProof = obj.string("associationProof"),
            stale = (obj["stale"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false,
            policyVersion = obj.string("policyVersion") ?: "unknown",
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}

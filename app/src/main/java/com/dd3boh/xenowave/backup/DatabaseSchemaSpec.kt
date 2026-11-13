package com.dd3boh.xenowave.backup

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Expected structure of the app database, read from Room's exported schema.
 *
 * `user_version`, `identity_hash` and `PRAGMA integrity_check` only describe
 * the file itself; a database can be internally consistent and still carry the
 * right version/tables with the wrong columns. This spec is compared against
 * `sqlite_master` and the SQLite PRAGMAs before a backup is allowed to replace
 * the live database.
 */
data class ExpectedColumn(
    val name: String,
    val affinity: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int,
)

data class ExpectedIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

data class ExpectedForeignKey(
    val table: String,
    val columns: List<String>,
    val referencedColumns: List<String>,
    val onUpdate: String,
    val onDelete: String,
)

data class ExpectedTable(
    val name: String,
    val createSql: String,
    val columns: List<ExpectedColumn>,
    val primaryKey: List<String>,
    val indices: List<ExpectedIndex>,
    val foreignKeys: List<ExpectedForeignKey>,
)

data class ExpectedSchema(
    val version: Int,
    val identityHash: String,
    val tables: List<ExpectedTable>,
    val views: List<ExpectedView>,
)

data class ExpectedView(
    val name: String,
    val createSql: String,
)

object RoomSchemaSpec {
    /**
     * Internal SQLite/Room tables that are not part of the entity schema.
     * They are allowed to exist but are not compared.
     */
    val INTERNAL_TABLES = setOf("android_metadata", "sqlite_sequence", "room_master_table")

    fun parse(json: String): ExpectedSchema {
        val root = JSONObject(json)
        val database = root.getJSONObject("database")
        val version = database.getInt("version")
        val identityHash = database.getString("identityHash")
        val entities = database.getJSONArray("entities")
        val tables = buildList {
            for (i in 0 until entities.length()) {
                add(parseTable(entities.getJSONObject(i)))
            }
        }
        val views = buildList {
            val array = database.optJSONArray("views") ?: JSONArray()
            for (i in 0 until array.length()) {
                val view = array.getJSONObject(i)
                add(
                    ExpectedView(
                        name = view.getString("viewName"),
                        createSql = view.getString("createSql"),
                    )
                )
            }
        }
        return ExpectedSchema(version, identityHash, tables, views)
    }

    private fun parseTable(entity: JSONObject): ExpectedTable {
        val tableName = entity.getString("tableName")
        val primaryKey = entity.optJSONObject("primaryKey")
            ?.optJSONArray("columnNames")
            ?.toStringList()
            .orEmpty()
        val fields = entity.getJSONArray("fields")
        val columns = buildList {
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                val columnName = field.getString("columnName")
                val pkPosition = primaryKey.indexOf(columnName).let { if (it < 0) 0 else it + 1 }
                add(
                    ExpectedColumn(
                        name = columnName,
                        affinity = field.getString("affinity"),
                        notNull = field.optBoolean("notNull", false),
                        defaultValue = field.optString("defaultValue", "")
                            .takeIf { it.isNotEmpty() },
                        primaryKeyPosition = pkPosition,
                    )
                )
            }
        }
        val indicesJson = entity.optJSONArray("indices") ?: JSONArray()
        val indices = buildList {
            for (i in 0 until indicesJson.length()) {
                val index = indicesJson.getJSONObject(i)
                add(
                    ExpectedIndex(
                        name = index.getString("name"),
                        unique = index.optBoolean("unique", false),
                        columns = index.optJSONArray("columnNames")?.toStringList().orEmpty(),
                    )
                )
            }
        }
        val foreignKeysJson = entity.optJSONArray("foreignKeys") ?: JSONArray()
        val foreignKeys = buildList {
            for (i in 0 until foreignKeysJson.length()) {
                val key = foreignKeysJson.getJSONObject(i)
                add(
                    ExpectedForeignKey(
                        table = key.getString("table"),
                        columns = key.optJSONArray("columns")?.toStringList().orEmpty(),
                        referencedColumns = key.optJSONArray("referencedColumns")?.toStringList().orEmpty(),
                        onUpdate = key.optString("onUpdate", "NO ACTION"),
                        onDelete = key.optString("onDelete", "NO ACTION"),
                    )
                )
            }
        }
        return ExpectedTable(
            name = tableName,
            createSql = entity.getString("createSql"),
            columns = columns,
            primaryKey = primaryKey,
            indices = indices,
            foreignKeys = foreignKeys,
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        buildList { for (i in 0 until length()) add(getString(i)) }
}

object DatabaseSchemaValidator {
    /**
     * Throws [BackupArchiveException] when [database] does not match [expected]
     * exactly. Never modifies the database.
     */
    fun validate(database: SQLiteDatabase, expected: ExpectedSchema) {
        val version = queryInt(database, "PRAGMA user_version")
        if (version != expected.version) {
            throw BackupArchiveException("database schema version is $version, expected ${expected.version}")
        }
        val tables = mutableSetOf<String>()
        val views = mutableSetOf<String>()
        database.rawQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view')", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (cursor.getString(1) == "view") views += name else tables += name
                }
            }
        val expectedTables = expected.tables.map { it.name }.toSet()
        val missing = expectedTables - tables
        if (missing.isNotEmpty()) {
            throw BackupArchiveException("database is missing tables: ${missing.joinToString()}")
        }
        val unexpected = tables - expectedTables - RoomSchemaSpec.INTERNAL_TABLES
        if (unexpected.isNotEmpty()) {
            throw BackupArchiveException("database has unexpected tables: ${unexpected.joinToString()}")
        }
        val expectedViewNames = expected.views.map { it.name }.toSet()
        if (!views.containsAll(expectedViewNames)) {
            throw BackupArchiveException("database is missing views: ${expectedViewNames - views}")
        }
        val unexpectedViews = views - expectedViewNames
        if (unexpectedViews.isNotEmpty()) {
            throw BackupArchiveException("database has unexpected views: $unexpectedViews")
        }

        val identityHash = readIdentityHash(database)
            ?: throw BackupArchiveException("database has no Room identity hash")
        if (identityHash != expected.identityHash) {
            throw BackupArchiveException("database Room identity hash does not match this app")
        }

        expected.tables.forEach { table -> validateTable(database, table) }
    }

    private fun validateTable(database: SQLiteDatabase, expected: ExpectedTable) {
        val actualColumns = LinkedHashMap<String, ColumnInfo>()
        database.rawQuery("PRAGMA table_info(`${expected.name}`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                actualColumns[name] = ColumnInfo(
                    affinity = affinityOf(cursor.getString(cursor.getColumnIndexOrThrow("type")).orEmpty()),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                    primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                )
            }
        }
        if (actualColumns.keys != expected.columns.map { it.name }.toSet()) {
            throw BackupArchiveException(
                "table ${expected.name} has different columns than expected"
            )
        }
        expected.columns.forEach { column ->
            val actual = actualColumns.getValue(column.name)
            if (actual.affinity != affinityOf(column.affinity) ||
                actual.notNull != column.notNull ||
                actual.primaryKeyPosition != column.primaryKeyPosition ||
                !defaultMatches(actual.defaultValue, column.defaultValue)
            ) {
                throw BackupArchiveException(
                    "table ${expected.name} column ${column.name} does not match the expected schema"
                )
            }
        }
        val expectedPk = expected.primaryKey
        val actualPk = actualColumns.entries
            .filter { it.value.primaryKeyPosition > 0 }
            .sortedBy { it.value.primaryKeyPosition }
            .map { it.key }
        if (expectedPk != actualPk) {
            throw BackupArchiveException("table ${expected.name} has a different primary key")
        }
        validateIndices(database, expected)
        validateForeignKeys(database, expected)
    }

    private fun validateIndices(database: SQLiteDatabase, expected: ExpectedTable) {
        val actual = LinkedHashMap<String, Pair<Boolean, List<String>>>()
        database.rawQuery("PRAGMA index_list(`${expected.name}`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val origin = cursor.getString(cursor.getColumnIndexOrThrow("origin"))
                if (origin != "c") continue
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0
                actual[name] = unique to indexColumns(database, name)
            }
        }
        val expectedNames = expected.indices.map { it.name }.toSet()
        if (actual.keys != expectedNames) {
            throw BackupArchiveException("table ${expected.name} has different indexes than expected")
        }
        expected.indices.forEach { index ->
            val (unique, columns) = actual.getValue(index.name)
            if (unique != index.unique || columns != index.columns) {
                throw BackupArchiveException(
                    "index ${index.name} does not match the expected schema"
                )
            }
        }
    }

    private fun indexColumns(database: SQLiteDatabase, index: String): List<String> {
        val columns = mutableListOf<Pair<Int, String>>()
        database.rawQuery("PRAGMA index_info(`$index`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                columns += cursor.getInt(cursor.getColumnIndexOrThrow("seqno")) to
                    cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return columns.sortedBy { it.first }.map { it.second }
    }

    private fun validateForeignKeys(database: SQLiteDatabase, expected: ExpectedTable) {
        data class Key(
            val table: String,
            val from: String,
            val to: String,
            val onUpdate: String,
            val onDelete: String,
        )
        val rows = mutableListOf<Pair<Int, Key>>()
        database.rawQuery("PRAGMA foreign_key_list(`${expected.name}`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.getInt(cursor.getColumnIndexOrThrow("id")) to Key(
                    table = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                    from = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    to = cursor.getString(cursor.getColumnIndexOrThrow("to")) ?: "",
                    onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update")) ?: "NO ACTION",
                    onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")) ?: "NO ACTION",
                )
            }
        }
        val actual = rows.groupBy { it.first }
            .map { (_, group) ->
                val ordered = group.sortedBy { it.second.from }
                ExpectedForeignKey(
                    table = ordered.first().second.table,
                    columns = ordered.map { it.second.from },
                    referencedColumns = ordered.map { it.second.to },
                    onUpdate = ordered.first().second.onUpdate,
                    onDelete = ordered.first().second.onDelete,
                )
            }
            .sortedWith(compareBy({ it.table }, { it.columns.firstOrNull() ?: "" }))
        val expectedKeys = expected.foreignKeys
            .sortedWith(compareBy({ it.table }, { it.columns.firstOrNull() ?: "" }))
        if (actual != expectedKeys) {
            throw BackupArchiveException(
                "table ${expected.name} has different foreign keys than expected"
            )
        }
    }

    private fun readIdentityHash(database: SQLiteDatabase): String? {
        return runCatching {
            database.rawQuery("SELECT identity_hash FROM room_master_table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryInt(database: SQLiteDatabase, sql: String): Int =
        database.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }

    private fun defaultMatches(actual: String?, expected: String?): Boolean {
        if (expected == null) return true
        if (actual == null) return false
        return normalizeDefault(actual) == normalizeDefault(expected)
    }

    private fun normalizeDefault(value: String): String {
        var text = value.trim().lowercase()
        while (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text.replace(" ", "")
    }

    private data class ColumnInfo(
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    /** SQLite type affinity rules, section 3.1 of the SQLite documentation. */
    private fun affinityOf(declared: String): String {
        val type = declared.uppercase()
        return when {
            type.contains("INT") -> "INTEGER"
            type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT") -> "TEXT"
            type.contains("BLOB") || type.isBlank() -> "BLOB"
            type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> "REAL"
            else -> "NUMERIC"
        }
    }
}

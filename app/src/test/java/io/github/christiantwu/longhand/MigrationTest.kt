package io.github.christiantwu.longhand

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.christiantwu.longhand.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** Migrations must create exactly what Room exports for the new version, or opening the database fails. */
class MigrationTest {

    private fun schema(version: Int) = File("schemas/io.github.christiantwu.longhand.data.AppDatabase/$version.json").readText()

    /** The CREATE statements of [tables] (each table, then its indices) in an exported schema. */
    private fun exportedSql(version: Int, tables: List<String>): List<String> {
        val json = schema(version)
        val createSql = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        return tables.flatMap { table ->
            val start = json.indexOf("\"tableName\": \"$table\"").also { check(it >= 0) { "no $table in $version.json" } }
            val end = json.indexOf("\"tableName\": ", start + 1).takeIf { it >= 0 } ?: json.length
            createSql.findAll(json.substring(start, end)).map {
                it.groupValues[1].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            }.toList()
        }
    }

    /** Every table in an exported schema. */
    private fun tables(version: Int): List<String> =
        Regex("\"tableName\": \"(\\w+)\"").findAll(schema(version)).map { it.groupValues[1] }.toList()

    /** A CREATE TABLE statement's parts at the top level: column definitions, then constraints such as PRIMARY KEY(...). */
    private fun parts(createTable: String): List<String> {
        val body = createTable.substring(createTable.indexOf('(') + 1, createTable.lastIndexOf(')'))
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        body.forEachIndexed { i, c ->
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    out += body.substring(from, i).trim()
                    from = i + 1
                }
            }
        }
        return out + body.substring(from).trim()
    }

    /** The column definitions of [table] in an exported schema, by name. */
    private fun columns(version: Int, table: String): Map<String, String> =
        parts(exportedSql(version, listOf(table)).first()).filter { it.startsWith("`") }.associateBy { it.substringBefore(' ') }

    private fun executed(migrate: (SupportSQLiteDatabase) -> Unit): List<String> {
        val sql = ArrayList<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            check(method.name == "execSQL") { "unexpected ${method.name}" }
            sql += args[0] as String
            null
        } as SupportSQLiteDatabase
        migrate(db)
        return sql
    }

    @Test fun version3AddsTheRecognisedVoicesTablesAsRoomExportsThem() {
        assertEquals(
            exportedSql(3, listOf("known_voices", "voice_samples", "voice_rejections")),
            executed { AppDatabase.MIGRATION_2_3.migrate(it) },
        )
    }

    /**
     * [alters] add columns to version [from]'s tables exactly as Room declares them in the next version (type, NOT NULL,
     * default), and nothing else changed: the other columns, the keys and the indices are as in version [from].
     * @return the tables they add columns to.
     */
    private fun assertAddsColumns(from: Int, alters: List<String>): Set<String> {
        val added = alters.map {
            val m = checkNotNull(Regex("ALTER TABLE `(\\w+)` ADD COLUMN (.+)").matchEntire(it)) { "unexpected $it" }
            m.groupValues[1] to m.groupValues[2]
        }
        for (table in tables(from)) {
            val definitions = added.filter { it.first == table }.map { it.second }
            assertEquals(table, columns(from, table) + definitions.associateBy { it.substringBefore(' ') }, columns(from + 1, table))
            val (old, new) = listOf(from, from + 1).map { v -> exportedSql(v, listOf(table)) }
            assertEquals(table, parts(old.first()).filterNot { it.startsWith("`") }, parts(new.first()).filterNot { it.startsWith("`") })
            assertEquals(table, old.drop(1), new.drop(1))
        }
        return added.map { it.first }.toSet()
    }

    @Test fun version4AddsTheCorrectionsTableAndEditingColumnsAsRoomExportsThem() {
        val (alters, creates) = executed { AppDatabase.MIGRATION_3_4.migrate(it) }.partition { it.startsWith("ALTER TABLE") }
        assertEquals(tables(3) + "corrections", tables(4))
        assertEquals(exportedSql(4, listOf("corrections")), creates)
        assertEquals(setOf("segments", "recordings"), assertAddsColumns(3, alters))
    }

    @Test fun version5AddsTheLanguageColumnsAsRoomExportsThem() {
        val sql = executed { AppDatabase.MIGRATION_4_5.migrate(it) }
        assertEquals(tables(4), tables(5))
        assertEquals(setOf("recordings"), assertAddsColumns(4, sql))
        // Both may be null: earlier transcripts don't say which language they were made in, and no call has one chosen yet.
        assertEquals(
            mapOf("`language`" to "`language` TEXT", "`pinnedLanguage`" to "`pinnedLanguage` TEXT"),
            columns(5, "recordings") - columns(4, "recordings").keys,
        )
    }
}

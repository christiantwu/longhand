package io.github.christiantwu.longhand

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.christiantwu.longhand.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** Migrations must create exactly what Room exports for the new version, or opening the database fails. */
class MigrationTest {

    /** The CREATE statements of [tables] (each table, then its indices) in an exported schema. */
    private fun exportedSql(version: Int, tables: List<String>): List<String> {
        val json = File("schemas/io.github.christiantwu.longhand.data.AppDatabase/$version.json").readText()
        val createSql = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        return tables.flatMap { table ->
            val start = json.indexOf("\"tableName\": \"$table\"").also { check(it >= 0) { "no $table in $version.json" } }
            val end = json.indexOf("\"tableName\": ", start + 1).takeIf { it >= 0 } ?: json.length
            createSql.findAll(json.substring(start, end)).map {
                it.groupValues[1].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            }.toList()
        }
    }

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
}

package com.justdataplease.boringtravel.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Fails when a persisted table/column is added without an explicit portable-backup representation. */
@OptIn(ExperimentalSerializationApi::class)
class BackupSchemaCoverageTest {
    @Test fun everyPersistedTableAndColumnHasABackupFieldExceptGeneratedTrackIds() {
        val rows: Map<String, SerialDescriptor> = mapOf(
            "trips" to TripRow.serializer().descriptor, "plan_items" to PlanItemRow.serializer().descriptor,
            "entries" to EntryRow.serializer().descriptor, "media" to MediaRow.serializer().descriptor,
            "track_points" to TrackPointRow.serializer().descriptor, "stops" to StopRow.serializer().descriptor,
            "country_marks" to CountryMarkRow.serializer().descriptor,
            "books" to BookRow.serializer().descriptor,
        )
        val dir = File("schemas/com.justdataplease.boringtravel.data.db.BtDatabase")
        val schema = requireNotNull(dir.listFiles()).filter { it.extension == "json" }.maxBy { it.nameWithoutExtension.toInt() }
        val entities = JSONObject(schema.readText()).getJSONObject("database").getJSONArray("entities")
        val persisted = (0 until entities.length()).map { entities.getJSONObject(it) }.filterNot { it.has("ftsVersion") }
        assertEquals(persisted.map { it.getString("tableName") }.toSet(), rows.keys)
        persisted.forEach { entity ->
            val table = entity.getString("tableName")
            val fields = entity.getJSONArray("fields")
            val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
            val descriptor = rows.getValue(table)
            val portable = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
            assertEquals(table, columns - if (table == "track_points") setOf("id") else emptySet(), portable)
        }
    }
}

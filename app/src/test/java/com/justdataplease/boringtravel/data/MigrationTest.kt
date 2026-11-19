package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.db.Migrations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the Room migrations on a real SQLite (sqlite-jdbc) in the JVM: builds the old version from its exported schema
 * (app/schemas, written by Room at compile time), adds rows, runs the migration SQL, then checks every table, column
 * and index against the next exported schema, the same things Room checks when it opens the database.
 */
class MigrationTest {
    private val dir = File("schemas/com.justdataplease.boringtravel.data.db.BtDatabase")

    private fun schema(version: Int): JSONObject = JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")

    private fun create(conn: Connection, db: JSONObject) {
        val entities = db.getJSONArray("entities")
        conn.createStatement().use { st ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                st.execute(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) {
                        val ix = idx.getJSONObject(j)
                        st.execute(ix.getString("createSql").replace("\${TABLE_NAME}", table).replace("\${INDEX_NAME}", ix.getString("name")))
                    }
                }
                e.optJSONArray("contentSyncTriggers")?.let { t -> for (j in 0 until t.length()) st.execute(t.getString(j)) }
            }
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) st.execute(setup.getString(i))
        }
    }

    private data class Column(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    private fun columns(conn: Connection, table: String): Set<Column> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildSet { while (rs.next()) add(Column(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk"))) }
        }
    }

    private fun expectedColumns(entity: JSONObject): Set<Column> {
        val pk = entity.getJSONObject("primaryKey").getJSONArray("columnNames").let { a -> List(a.length()) { a.getString(it) } }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }.map { f ->
            val name = f.getString("columnName")
            Column(name, f.getString("affinity").uppercase(), f.optBoolean("notNull"), pk.indexOf(name) + 1)
        }.toSet()
    }

    private fun indices(conn: Connection, table: String): Map<String, List<String>> = conn.createStatement().use { st ->
        val names = st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
            buildList { while (rs.next()) if (rs.getString("origin") == "c") add(rs.getString("name")) }
        }
        names.associateWith { name ->
            st.executeQuery("PRAGMA index_info(`$name`)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }
    }

    private fun expectedIndices(entity: JSONObject): Map<String, List<String>> {
        val idx = entity.optJSONArray("indices") ?: return emptyMap()
        return (0 until idx.length()).map { idx.getJSONObject(it) }.associate { ix ->
            val cols = ix.getJSONArray("columnNames")
            ix.getString("name") to List(cols.length()) { cols.getString(it) }
        }
    }

    @Test fun version1To2AddsCountryMarksAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(1))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Cyclades', '', 1, 2)")
                st.execute(
                    "INSERT INTO stops (id, tripId, arrivedAt, lat, lng, name, countryCode, source, createdAt, updatedAt) " +
                        "VALUES ('s1', 't1', 10, 37.1, 25.4, 'Halki', 'GR', 'auto', 1, 2)",
                )
            }

            conn.createStatement().use { st -> Migrations.SQL_1_2.forEach { st.execute(it) } }

            val v2 = schema(2)
            assertEquals(2, v2.getInt("version"))
            val entities = v2.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue // Room checks FTS tables by their create SQL, unchanged here.
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(7, checked)

            conn.createStatement().use { st ->
                st.executeQuery("SELECT title FROM trips WHERE id = 't1'").use { assertTrue(it.next()); assertEquals("Cyclades", it.getString(1)) }
                st.executeQuery("SELECT countryCode FROM stops WHERE id = 's1'").use { assertTrue(it.next()); assertEquals("GR", it.getString(1)) }
                st.execute("INSERT INTO country_marks (id, code, source, addedAt, updatedAt) VALUES ('manual:PT', 'PT', 'manual', 5, 5)")
                st.executeQuery("SELECT code, deletedAt FROM country_marks").use {
                    assertTrue(it.next())
                    assertEquals("PT", it.getString(1))
                    assertEquals(null, it.getObject(2))
                }
            }
        }
    }

    @Test fun theMigrationSqlIsWhatRoomWouldCreate() {
        val marks = schema(2).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .single { it.getString("tableName") == "country_marks" }
        val create = marks.getString("createSql").replace("\${TABLE_NAME}", "country_marks")
        assertEquals(create, Migrations.SQL_1_2[0])
        val index = marks.getJSONArray("indices").getJSONObject(0)
        assertEquals(
            index.getString("createSql").replace("\${TABLE_NAME}", "country_marks").replace("\${INDEX_NAME}", index.getString("name")),
            Migrations.SQL_1_2[1],
        )
    }

    @Test fun version2To3KeepsExistingRowsAndAddsNullableTravelChoices() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(2))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Existing trip', 'Keep notes', 1, 2)")
                st.execute("INSERT INTO stops (id, tripId, arrivedAt, lat, lng, name, countryCode, source, createdAt, updatedAt) VALUES ('s1', 't1', 10, 37.1, 25.4, 'Port', 'GR', 'manual', 1, 2)")
                st.execute("INSERT INTO country_marks (id, code, source, addedAt, updatedAt) VALUES ('manual:PT', 'PT', 'manual', 5, 5)")
                Migrations.SQL_2_3.forEach { st.execute(it) }
            }
            val v3 = schema(3)
            assertEquals(3, v3.getInt("version"))
            val entities = v3.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, subtitle, updatedAt, travelModes FROM trips WHERE id='t1'").use {
                    assertTrue(it.next()); assertEquals("Existing trip", it.getString(1)); assertEquals("Keep notes", it.getString(2))
                    assertEquals(2L, it.getLong(3)); assertEquals(null, it.getString(4))
                }
                st.executeQuery("SELECT name FROM stops WHERE id='s1'").use { assertTrue(it.next()); assertEquals("Port", it.getString(1)) }
                st.executeQuery("SELECT code FROM country_marks").use { assertTrue(it.next()); assertEquals("PT", it.getString(1)) }
            }
            val json = """{"v":1,"legs":[{"source":"stops","from":"s1","to":"s2","vehicle":"train"}]}"""
            conn.prepareStatement("UPDATE trips SET travelModes=? WHERE id='t1'").use { it.setString(1, json); it.executeUpdate() }
            conn.createStatement().use { st -> st.executeQuery("SELECT travelModes FROM trips WHERE id='t1'").use { assertTrue(it.next()); assertEquals(json, it.getString(1)) } }
        }
    }

    @Test fun version3To4AddsBooksAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(3))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt, travelModes) VALUES ('t1', 'Cyclades', '', 1, 2, '{\"v\":1}')")
                Migrations.SQL_3_4.forEach { st.execute(it) }
            }
            val v4 = schema(4)
            assertEquals(4, v4.getInt("version"))
            val entities = v4.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, travelModes FROM trips WHERE id = 't1'").use { assertTrue(it.next()); assertEquals("Cyclades", it.getString(1)); assertEquals("{\"v\":1}", it.getString(2)) }
                st.execute("INSERT INTO books (id, tripId, format, theme, fontSet, bleed, pages, createdAt, updatedAt) VALUES ('b1', 't1', 'a5', 'notebook', 'theme', 0, '{\"v\":1,\"pages\":[]}', 5, 6)")
                st.executeQuery("SELECT tripId, bleed, deletedAt FROM books").use {
                    assertTrue(it.next()); assertEquals("t1", it.getString(1)); assertEquals(0, it.getInt(2)); assertEquals(null, it.getObject(3))
                }
            }
        }
    }

    @Test fun theBooksSqlIsWhatRoomWouldCreate() {
        val books = schema(4).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .single { it.getString("tableName") == "books" }
        assertEquals(books.getString("createSql").replace("\${TABLE_NAME}", "books"), Migrations.SQL_3_4[0])
        val index = books.getJSONArray("indices").getJSONObject(0)
        assertEquals(
            index.getString("createSql").replace("\${TABLE_NAME}", "books").replace("\${INDEX_NAME}", index.getString("name")),
            Migrations.SQL_3_4[1],
        )
    }

    @Test fun version4To5AddsAnEmptyLinkToEveryPlanItemAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(4))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt, travelModes) VALUES ('t1', 'Kyoto', '', 1, 2, NULL)")
                st.execute(
                    "INSERT INTO plan_items (id, tripId, day, position, timeMinutes, kind, title, note, placeName, lat, lng, done, source, externalId, createdAt, updatedAt) " +
                        "VALUES ('p1', 't1', 20000, 1.0, 540, 'place', 'Yasaka Shrine', 'Early', 'Gion, Kyoto', 35.0036, 135.7785, 0, 'manual', NULL, 3, 4)",
                )
                st.execute("INSERT INTO books (id, tripId, format, theme, fontSet, bleed, pages, createdAt, updatedAt) VALUES ('b1', 't1', 'a5', 'notebook', 'theme', 0, '{}', 5, 6)")
                Migrations.SQL_4_5.forEach { st.execute(it) }
            }
            val v5 = schema(5)
            assertEquals(5, v5.getInt("version"))
            val entities = v5.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, note, placeName, lat, updatedAt, url FROM plan_items WHERE id = 'p1'").use {
                    assertTrue(it.next())
                    assertEquals("Yasaka Shrine", it.getString(1)); assertEquals("Early", it.getString(2)); assertEquals("Gion, Kyoto", it.getString(3))
                    assertEquals(35.0036, it.getDouble(4), 1e-9); assertEquals(4L, it.getLong(5)); assertEquals(null, it.getObject(6))
                }
                st.executeQuery("SELECT tripId FROM books WHERE id = 'b1'").use { assertTrue(it.next()); assertEquals("t1", it.getString(1)) }
            }
            val url = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
            conn.prepareStatement("UPDATE plan_items SET url = ? WHERE id = 'p1'").use { it.setString(1, url); it.executeUpdate() }
            conn.createStatement().use { st -> st.executeQuery("SELECT url FROM plan_items WHERE id = 'p1'").use { assertTrue(it.next()); assertEquals(url, it.getString(1)) } }
        }
    }

    @Test fun version5To6AddsAuthorsAndSharedCopiesAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(5))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Patagonia', '', 1, 2)")
                st.execute("INSERT INTO entries (id, tripId, occurredAt, zoneId, title, body, plainText, placeName, createdAt, updatedAt) VALUES ('e1', 't1', 5, 'UTC', 'Day one', '', 'Wind', '', 3, 4)")
                st.execute("INSERT INTO media (id, tripId, localPath, mime, width, height, createdAt, updatedAt) VALUES ('m1', 't1', 'media/m1.jpg', 'image/jpeg', 10, 20, 5, 6)")
                Migrations.SQL_5_6.forEach { st.execute(it) }
            }
            val v6 = schema(6)
            val entities = v6.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, authorId FROM entries WHERE id = 'e1'").use {
                    assertTrue(it.next()); assertEquals("Day one", it.getString(1)); assertEquals(null, it.getObject(2))
                }
                st.executeQuery("SELECT localPath, authorId, sharedFileId FROM media WHERE id = 'm1'").use {
                    assertTrue(it.next()); assertEquals("media/m1.jpg", it.getString(1)); assertEquals(null, it.getObject(2)); assertEquals(null, it.getObject(3))
                }
            }
        }
    }

    @Test fun version6To7AddsAnEmptyEndToEveryPlanItemAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(6))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Spain', '', 1, 2)")
                st.execute(
                    "INSERT INTO plan_items (id, tripId, day, position, timeMinutes, kind, title, note, placeName, lat, lng, done, source, externalId, createdAt, updatedAt, url) " +
                        "VALUES ('p1', 't1', 20000, 1.0, 1140, 'activity', 'Yandel Sinfónico', '', 'Madrid', 40.4, -3.7, 0, 'manual', NULL, 3, 4, NULL)",
                )
                Migrations.SQL_6_7.forEach { st.execute(it) }
            }
            val v7 = schema(7)
            assertEquals(7, v7.getInt("version"))
            val entities = v7.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, timeMinutes, endDays, endMinutes, endTitle, endRow FROM plan_items WHERE id = 'p1'").use {
                    assertTrue(it.next()); assertEquals("Yandel Sinfónico", it.getString(1)); assertEquals(1140, it.getInt(2))
                    for (c in 3..6) assertEquals(null, it.getObject(c))
                }
            }
        }
    }

    @Test fun version7To8AddsEmptyTagsToEveryPlanItemAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(7))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Naples', '', 1, 2)")
                st.execute(
                    "INSERT INTO plan_items (id, tripId, day, position, timeMinutes, kind, title, note, placeName, lat, lng, done, source, externalId, createdAt, updatedAt, url, endDays, endMinutes, endTitle, endRow) " +
                        "VALUES ('p1', 't1', 20000, 1.0, 780, 'food', 'Da Michele', '', 'Naples', 40.85, 14.26, 0, 'manual', NULL, 3, 4, NULL, 0, 840, NULL, NULL)",
                )
                Migrations.SQL_7_8.forEach { st.execute(it) }
            }
            val v8 = schema(8)
            assertEquals(8, v8.getInt("version"))
            val entities = v8.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT title, timeMinutes, endMinutes, tags FROM plan_items WHERE id = 'p1'").use {
                    assertTrue(it.next()); assertEquals("Da Michele", it.getString(1)); assertEquals(780, it.getInt(2)); assertEquals(840, it.getInt(3))
                    assertEquals(null, it.getObject(4))
                }
            }
        }
    }

    /** Runs the 8 → 9 step as [Migrations.MIGRATION_8_9] does: the column, then the Maps links read and moved. */
    private fun migrate8To9(conn: Connection) {
        conn.createStatement().use { st -> Migrations.SQL_8_9.forEach { st.execute(it) } }
        val links = conn.createStatement().use { st ->
            st.executeQuery(Migrations.SQL_8_9_LINKS).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
        conn.prepareStatement(Migrations.SQL_8_9_MOVE).use { ps ->
            Migrations.moves(links).forEach { (site, maps, id) ->
                ps.setString(1, site); ps.setString(2, maps); ps.setString(3, id); ps.executeUpdate()
            }
        }
    }

    @Test fun version8To9AddsAMapsLinkAndMovesTheMapsLinksKeptAsWebsites() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(8))
            val short = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
            val long = "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z"
            val site = "https://www.pizzeriadamichele.net/"
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('t1', 'Naples', '', 1, 2)")
            }
            conn.prepareStatement(
                "INSERT INTO plan_items (id, tripId, day, position, timeMinutes, kind, title, note, placeName, lat, lng, done, source, externalId, createdAt, updatedAt, url, endDays, endMinutes, endTitle, endRow, tags) " +
                    "VALUES (?, 't1', 20000, 1.0, 780, 'food', ?, '', 'Naples', 40.85, 14.26, 0, 'manual', NULL, 3, 4, ?, NULL, NULL, NULL, NULL, 'pizza')",
            ).use { ps ->
                listOf(Triple("short", "Da Michele", short), Triple("long", "Yasaka Shrine", long), Triple("site", "Da Michele", site), Triple("none", "Walk", null))
                    .forEach { (id, title, url) -> ps.setString(1, id); ps.setString(2, title); ps.setString(3, url); ps.executeUpdate() }
            }

            migrate8To9(conn)

            val v9 = schema(9)
            assertEquals(9, v9.getInt("version"))
            val entities = v9.getJSONArray("entities")
            var checked = 0
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                if (e.has("ftsVersion")) continue
                val table = e.getString("tableName")
                assertEquals(table, expectedColumns(e), columns(conn, table))
                assertEquals(table, expectedIndices(e), indices(conn, table))
                checked++
            }
            assertEquals(8, checked)
            val rows = conn.createStatement().use { st ->
                st.executeQuery("SELECT id, url, mapsUrl, title, tags, updatedAt FROM plan_items").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), listOf(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6))) }
                }
            }
            // A Maps link moves (short or long), a site stays the website, no link stays none; the rest is untouched.
            assertEquals(listOf(null, short, "Da Michele", "pizza", 4L), rows["short"])
            assertEquals(listOf(null, long, "Yasaka Shrine", "pizza", 4L), rows["long"])
            assertEquals(listOf(site, null, "Da Michele", "pizza", 4L), rows["site"])
            assertEquals(listOf(null, null, "Walk", "pizza", 4L), rows["none"])
        }
    }

    @Test fun theMapsLinkColumnIsWhatRoomWouldCreate() {
        val plan = schema(9).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .single { it.getString("tableName") == "plan_items" }
        val maps = plan.getJSONArray("fields").let { f -> (0 until f.length()).map { f.getJSONObject(it) } }.single { it.getString("columnName") == "mapsUrl" }
        assertEquals("TEXT", maps.getString("affinity"))
        assertTrue(!maps.optBoolean("notNull"))
        assertTrue(plan.getString("createSql").endsWith("`tags` TEXT, `mapsUrl` TEXT, PRIMARY KEY(`id`))"))
        assertEquals("ALTER TABLE `plan_items` ADD COLUMN `mapsUrl` TEXT", Migrations.SQL_8_9.single())
    }

    @Test fun theLinkColumnIsWhatRoomWouldCreate() {
        val plan = schema(5).getJSONArray("entities").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .single { it.getString("tableName") == "plan_items" }
        val url = plan.getJSONArray("fields").let { f -> (0 until f.length()).map { f.getJSONObject(it) } }.single { it.getString("columnName") == "url" }
        assertEquals("TEXT", url.getString("affinity"))
        assertTrue(!url.optBoolean("notNull"))
        assertTrue(plan.getString("createSql").endsWith("`deletedAt` INTEGER, `url` TEXT, PRIMARY KEY(`id`))"))
    }

    @Test fun oldestDatabaseCanMigrateThroughBothVersionsWithoutReplacingItsTrip() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, schema(1))
            conn.createStatement().use { st ->
                st.execute("INSERT INTO trips (id, title, subtitle, createdAt, updatedAt) VALUES ('legacy', 'Oldest trip', '', 1, 2)")
                (Migrations.SQL_1_2 + Migrations.SQL_2_3).forEach { st.execute(it) }
                st.executeQuery("SELECT id, title, travelModes FROM trips").use {
                    assertTrue(it.next()); assertEquals("legacy", it.getString(1)); assertEquals("Oldest trip", it.getString(2)); assertEquals(null, it.getString(3))
                }
            }
        }
    }
}

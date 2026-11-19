package com.justdataplease.boringtravel.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.justdataplease.boringtravel.links.LinkPlaces

/**
 * Schema steps. Each migration's SQL is a plain list, so a JVM test can run it on a v1 database built from the exported
 * schema (app/schemas) and compare the result with the next exported schema.
 */
object Migrations {
    /** v2: country_marks (countries added by hand, wished, or removed). */
    val SQL_1_2: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `country_marks` (`id` TEXT NOT NULL, `code` TEXT NOT NULL, `source` TEXT NOT NULL, " +
            "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_country_marks_code` ON `country_marks` (`code`)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach(db::execSQL)
        }
    }

    /** v3: optional pair-keyed travel choices; old trips remain explicitly unspecified. */
    val SQL_2_3: List<String> = listOf("ALTER TABLE `trips` ADD COLUMN `travelModes` TEXT")

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_2_3.forEach(db::execSQL)
        }
    }

    /** v4: books (a trip laid out as a printed book, its pages as versioned JSON). */
    val SQL_3_4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `books` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL, `format` TEXT NOT NULL, `theme` TEXT NOT NULL, " +
            "`fontSet` TEXT NOT NULL, `bleed` INTEGER NOT NULL, `pages` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`deletedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_books_tripId` ON `books` (`tripId`)",
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_3_4.forEach(db::execSQL)
        }
    }

    /** v5: an optional link per plan item (a website or a Google Maps place); old items have none. */
    val SQL_4_5: List<String> = listOf("ALTER TABLE `plan_items` ADD COLUMN `url` TEXT")

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_4_5.forEach(db::execSQL)
        }
    }

    /** v6: shared trips (together/): who wrote an entry or added a photo, and where a photo's shared copy is. */
    val SQL_5_6: List<String> = listOf(
        "ALTER TABLE `entries` ADD COLUMN `authorId` TEXT",
        "ALTER TABLE `media` ADD COLUMN `authorId` TEXT",
        "ALTER TABLE `media` ADD COLUMN `sharedFileId` TEXT",
    )

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_5_6.forEach(db::execSQL)
        }
    }

    /** v7: when a plan item ends (days after its day, a time), its end row's name and whether it shows. */
    val SQL_6_7: List<String> = listOf(
        "ALTER TABLE `plan_items` ADD COLUMN `endDays` INTEGER",
        "ALTER TABLE `plan_items` ADD COLUMN `endMinutes` INTEGER",
        "ALTER TABLE `plan_items` ADD COLUMN `endTitle` TEXT",
        "ALTER TABLE `plan_items` ADD COLUMN `endRow` INTEGER",
    )

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_6_7.forEach(db::execSQL)
        }
    }

    /** v8: a plan item's tags ("pizza streetfood"; see tags/ItemTags). */
    val SQL_7_8: List<String> = listOf("ALTER TABLE `plan_items` ADD COLUMN `tags` TEXT")

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_7_8.forEach(db::execSQL)
        }
    }

    /**
     * v9: a plan item's Google Maps link apart from its website (owner, 2026-11-13: "a website and a google url"). A
     * Maps link kept as the website moves over ([moves]).
     */
    val SQL_8_9: List<String> = listOf("ALTER TABLE `plan_items` ADD COLUMN `mapsUrl` TEXT")

    /** The rows with a link, as (id, url) for [moves]. */
    const val SQL_8_9_LINKS = "SELECT `id`, `url` FROM `plan_items` WHERE `url` IS NOT NULL"

    /** One row's links after [moves]: the website, the Maps link, the row's id. */
    const val SQL_8_9_MOVE = "UPDATE `plan_items` SET `url` = ?, `mapsUrl` = ? WHERE `id` = ?"

    /**
     * The rows of [links] (id, url) whose link is a Google Maps link, with their links as they are kept from v9
     * ([LinkPlaces.sortLinks]: the Maps link moved out of the website).
     */
    fun moves(links: List<Pair<String, String>>): List<Triple<String?, String?, String>> = links.mapNotNull { (id, url) ->
        val (site, maps) = LinkPlaces.sortLinks(url, null)
        if (site == url) null else Triple(site, maps, id)
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_8_9.forEach(db::execSQL)
            val links = db.query(SQL_8_9_LINKS).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            moves(links).forEach { (site, maps, id) -> db.execSQL(SQL_8_9_MOVE, arrayOf(site, maps, id)) }
        }
    }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}

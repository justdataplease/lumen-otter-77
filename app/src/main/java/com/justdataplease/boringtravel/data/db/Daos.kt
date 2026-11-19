package com.justdataplease.boringtravel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE deletedAt IS NULL ORDER BY startDate IS NULL, startDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: String): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): Trip?

    /** Trips on that day, the one that started last first. */
    @Query("SELECT * FROM trips WHERE deletedAt IS NULL AND startDate <= :epochDay AND endDate >= :epochDay ORDER BY startDate DESC, createdAt DESC")
    suspend fun allOn(epochDay: Long): List<Trip>

    @Query("SELECT * FROM trips")
    suspend fun all(): List<Trip>

    @Query("SELECT COUNT(*) FROM trips WHERE deletedAt IS NULL")
    suspend fun count(): Int

    /** Trips (not deleted) with photo [mediaId] as their cover. */
    @Query("SELECT * FROM trips WHERE coverMediaId = :mediaId AND deletedAt IS NULL")
    suspend fun withCover(mediaId: String): List<Trip>

    @Upsert
    suspend fun upsert(trip: Trip)

    @Upsert
    suspend fun upsertAll(trips: List<Trip>)
}

@Dao
interface PlanItemDao {
    @Query("SELECT * FROM plan_items WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY day IS NULL, day, position, timeMinutes")
    fun observeForTrip(tripId: String): Flow<List<PlanItem>>

    @Query("SELECT * FROM plan_items WHERE id = :id")
    suspend fun get(id: String): PlanItem?

    @Query("SELECT * FROM plan_items WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY day IS NULL, day, position")
    suspend fun forTrip(tripId: String): List<PlanItem>

    /** Planned places per trip (notes left out), for "7 places planned". */
    @Query("SELECT tripId, COUNT(*) AS n FROM plan_items WHERE deletedAt IS NULL AND kind != 'note' GROUP BY tripId")
    fun observePlannedCounts(): Flow<List<TripCount>>

    @Query("SELECT * FROM plan_items")
    suspend fun all(): List<PlanItem>

    /** A trip's items, deleted ones too (shared trips). */
    @Query("SELECT * FROM plan_items WHERE tripId = :tripId")
    suspend fun forTripAll(tripId: String): List<PlanItem>

    /** Which of [ids] belong to another trip (shared trips never take them over). At most 900 ids. */
    @Query("SELECT id FROM plan_items WHERE id IN (:ids) AND tripId != :tripId")
    suspend fun idsElsewhere(tripId: String, ids: List<String>): List<String>

    /**
     * Every live item of every trip that isn't deleted (countries on the globe: planned places and the ones ticked
     * done). A deleted trip's items never show, even when they aren't marked themselves (a shared trip left, or a trip
     * deleted before its rows went with it).
     */
    @Query("SELECT * FROM plan_items WHERE deletedAt IS NULL AND tripId NOT IN (SELECT id FROM trips WHERE deletedAt IS NOT NULL)")
    fun observeAllLive(): Flow<List<PlanItem>>

    @Upsert
    suspend fun upsert(item: PlanItem)

    @Upsert
    suspend fun upsertAll(items: List<PlanItem>)
}

@Dao
interface EntryDao {
    /** Every live entry, outside trips or in a trip that isn't deleted (see [PlanItemDao.observeAllLive]). */
    @Query(
        "SELECT * FROM entries WHERE deletedAt IS NULL " +
            "AND (tripId IS NULL OR tripId NOT IN (SELECT id FROM trips WHERE deletedAt IS NOT NULL)) ORDER BY occurredAt DESC",
    )
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY occurredAt DESC")
    fun observeForTrip(tripId: String): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observe(id: String): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: String): Entry?

    @Query("SELECT * FROM entries WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY occurredAt")
    suspend fun forTrip(tripId: String): List<Entry>

    /** Entries per trip, for "23 entries" on past trips. */
    @Query("SELECT tripId, COUNT(*) AS n FROM entries WHERE deletedAt IS NULL AND tripId IS NOT NULL GROUP BY tripId")
    fun observeCountsByTrip(): Flow<List<TripCount>>

    /** Entries written outside any trip (the "Notes outside trips" card on Trips). */
    @Query("SELECT COUNT(*) FROM entries WHERE deletedAt IS NULL AND tripId IS NULL")
    fun observeLooseCount(): Flow<Int>

    @Query("SELECT * FROM entries")
    suspend fun all(): List<Entry>

    /** A trip's entries, deleted ones too (shared trips). */
    @Query("SELECT * FROM entries WHERE tripId = :tripId")
    suspend fun forTripAll(tripId: String): List<Entry>

    /** Which of [ids] belong to another trip or to none. At most 900 ids. */
    @Query("SELECT id FROM entries WHERE id IN (:ids) AND (tripId IS NULL OR tripId != :tripId)")
    suspend fun idsElsewhere(tripId: String, ids: List<String>): List<String>

    /**
     * The trip of each entry written in [from, to) ("" for none), for counting the traveller's own ones. Entries of a
     * deleted trip are left out.
     */
    @Query(
        "SELECT COALESCE(tripId, '') FROM entries WHERE deletedAt IS NULL AND occurredAt >= :from AND occurredAt < :to " +
            "AND (tripId IS NULL OR tripId NOT IN (SELECT id FROM trips WHERE deletedAt IS NOT NULL))",
    )
    suspend fun tripIdsBetween(from: Long, to: Long): List<String>

    @Upsert
    suspend fun upsert(entry: Entry)

    @Upsert
    suspend fun upsertAll(entries: List<Entry>)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY takenAt, createdAt")
    fun observeForTrip(tripId: String): Flow<List<Media>>

    /** Every live photo, outside trips or in a trip that isn't deleted (see [PlanItemDao.observeAllLive]). */
    @Query(
        "SELECT * FROM media WHERE deletedAt IS NULL " +
            "AND (tripId IS NULL OR tripId NOT IN (SELECT id FROM trips WHERE deletedAt IS NOT NULL)) ORDER BY takenAt DESC, createdAt DESC",
    )
    fun observeAll(): Flow<List<Media>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: String): Media?

    @Query("SELECT * FROM media WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY takenAt, createdAt")
    suspend fun forTrip(tripId: String): List<Media>

    @Query("SELECT * FROM media")
    suspend fun all(): List<Media>

    /** A trip's photos, deleted ones too (shared trips). */
    @Query("SELECT * FROM media WHERE tripId = :tripId")
    suspend fun forTripAll(tripId: String): List<Media>

    /** Which of [ids] belong to another trip or to none. At most 900 ids. */
    @Query("SELECT id FROM media WHERE id IN (:ids) AND (tripId IS NULL OR tripId != :tripId)")
    suspend fun idsElsewhere(tripId: String, ids: List<String>): List<String>

    /** The photos of entries [entryIds], deleted ones too. At most 900 ids. */
    @Query("SELECT * FROM media WHERE entryId IN (:entryIds)")
    suspend fun forEntries(entryIds: List<String>): List<Media>

    /** Which of [paths] a live photo still uses (two rows can share a file). At most 900 paths. */
    @Query("SELECT DISTINCT localPath FROM media WHERE deletedAt IS NULL AND localPath IN (:paths)")
    suspend fun livePaths(paths: List<String>): List<String>

    /** Each trip's first photo by time (trips without photos are left out): its picture when it has no cover. */
    @Query(
        "SELECT m.tripId AS tripId, m.id AS mediaId FROM media m WHERE m.deletedAt IS NULL AND m.tripId IS NOT NULL " +
            "AND m.id = (SELECT x.id FROM media x WHERE x.tripId = m.tripId AND x.deletedAt IS NULL AND x.mime LIKE 'image/%' " +
            "ORDER BY x.takenAt IS NULL, x.takenAt, x.createdAt, x.id LIMIT 1)",
    )
    fun observeFirstPhotos(): Flow<List<TripPhoto>>

    /** The ids of every live video (journal lists count them apart from photos: "2 photos, 1 video"). */
    @Query("SELECT id FROM media WHERE deletedAt IS NULL AND mime LIKE 'video/%'")
    fun observeVideoIds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(media: Media)

    @Upsert
    suspend fun upsertAll(media: List<Media>)
}

@Dao
interface TrackDao {
    @Insert
    suspend fun insertAll(points: List<TrackPoint>)

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY time")
    fun observeForTrip(tripId: String): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY time")
    suspend fun forTrip(tripId: String): List<TrackPoint>

    @Query("SELECT * FROM track_points ORDER BY time DESC LIMIT 1")
    suspend fun latest(): TrackPoint?

    @Query("SELECT * FROM track_points ORDER BY time DESC LIMIT 1")
    fun observeLatest(): Flow<TrackPoint?>

    /** Every point (backups). */
    @Query("SELECT * FROM track_points ORDER BY time")
    suspend fun all(): List<TrackPoint>

    /** A deleted trip's route goes: points have no deletedAt (a backup restored later only brings them back hidden). */
    @Query("DELETE FROM track_points WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String): Int

    /** The points recorded outside trips (a restore puts the backup's in their place). */
    @Query("DELETE FROM track_points WHERE tripId IS NULL")
    suspend fun deleteOutsideTrips(): Int
}

@Dao
interface StopDao {
    @Query("SELECT * FROM stops WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY arrivedAt")
    fun observeForTrip(tripId: String): Flow<List<Stop>>

    /** Every live stop, outside trips or in a trip that isn't deleted (see [PlanItemDao.observeAllLive]). */
    @Query(
        "SELECT * FROM stops WHERE deletedAt IS NULL " +
            "AND (tripId IS NULL OR tripId NOT IN (SELECT id FROM trips WHERE deletedAt IS NOT NULL)) ORDER BY arrivedAt",
    )
    fun observeAll(): Flow<List<Stop>>

    @Query("SELECT * FROM stops WHERE tripId = :tripId AND deletedAt IS NULL ORDER BY arrivedAt")
    suspend fun forTrip(tripId: String): List<Stop>

    @Query("SELECT * FROM stops")
    suspend fun all(): List<Stop>

    /** Deleted ones too: the stop detector must not suggest a stay the traveller removed. */
    @Query("SELECT * FROM stops WHERE tripId = :tripId ORDER BY arrivedAt")
    suspend fun forTripWithDeleted(tripId: String): List<Stop>

    @Upsert
    suspend fun upsertAll(stops: List<Stop>)
}

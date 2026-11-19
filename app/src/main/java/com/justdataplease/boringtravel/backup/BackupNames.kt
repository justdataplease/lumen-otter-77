package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.google.RemoteFile
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One backup in the owner's Drive. [items] is null for a backup that carries no counts (made elsewhere). */
data class BackupEntry(
    val id: String,
    val name: String,
    val createdAt: Long,
    val items: BackupCounts?,
    val size: Long? = null,
)

/** Names, Drive properties and rotation of backup files. Plain Kotlin, unit-tested. */
object BackupNames {
    /** A marker is only a shortcut; a later archive in the folder wins if marker writing previously failed. */
    fun newest(marker: BackupEntry?, listed: BackupEntry?): BackupEntry? =
        listOfNotNull(marker, listed).maxByOrNull { it.createdAt }

    const val PREFIX = "BoringTravel backup "
    const val SUFFIX = ".zip"
    const val MIME = "application/zip"

    /** Backups kept in Drive; older ones are deleted after each new backup (owner, 2026-11-12: "keep backups to 10"). */
    const val KEEP = 10

    /** The hidden marker's name in the app's Drive folder. */
    const val MARKER = "backup.json"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT)

    /** "BoringTravel backup 2026-11-05 22.00.zip", in the phone's time. */
    fun fileName(at: Long, zone: ZoneId): String = PREFIX + stamp.format(Instant.ofEpochMilli(at).atZone(zone)) + SUFFIX

    fun isBackup(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** The time in a backup's name, or null for other names. */
    fun timeFromName(name: String, zone: ZoneId): Long? {
        if (!isBackup(name)) return null
        val text = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        return runCatching { LocalDateTime.parse(text, stamp).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Private Drive properties on the uploaded zip, so a list of backups shows dates and counts without opening them. */
    fun properties(manifest: BackupManifest): Map<String, String> = mapOf(
        "format" to manifest.format.toString(),
        "createdAt" to manifest.createdAt.toString(),
        "trips" to manifest.items.trips.toString(),
        "entries" to manifest.items.entries.toString(),
        "photos" to manifest.items.photos.toString(),
        "appVersion" to manifest.appVersion.take(40),
    )

    /** A backup from a Drive list, or null when the file isn't one. */
    fun entry(file: RemoteFile, zone: ZoneId): BackupEntry? {
        if (!isBackup(file.name) || file.trashed) return null
        val p = file.appProperties
        val createdAt = p["createdAt"]?.toLongOrNull() ?: timeFromName(file.name, zone) ?: file.createdAtMs ?: file.modifiedAtMs ?: 0L
        val trips = p["trips"]?.toIntOrNull()
        val entries = p["entries"]?.toIntOrNull()
        val photos = p["photos"]?.toIntOrNull()
        val items = if (trips != null && entries != null && photos != null) BackupCounts(trips, entries, photos) else null
        return BackupEntry(file.id, file.name, createdAt, items, file.size)
    }

    fun fromMarker(marker: BackupMarker) = BackupEntry(marker.id, marker.name, marker.createdAt, marker.items)

    /** Newest first. */
    fun sorted(entries: List<BackupEntry>): List<BackupEntry> = entries.sortedWith(compareByDescending<BackupEntry> { it.createdAt }.thenByDescending { it.name })

    /** Whether [e] holds anything (a backup made before the counts were kept counts as full). */
    fun hasData(e: BackupEntry): Boolean = e.items?.let { it.trips + it.entries + it.photos > 0 } ?: true

    /**
     * The backup a new phone is offered: the newest that holds something, so an empty one made by mistake never
     * hides the full one before it; the newest of all when none holds anything.
     */
    fun best(entries: List<BackupEntry>): BackupEntry? = sorted(entries).let { all -> all.firstOrNull(::hasData) ?: all.firstOrNull() }

    /** The backups to delete so that the newest [keep] stay. */
    fun toPrune(entries: List<BackupEntry>, keep: Int = KEEP): List<BackupEntry> = sorted(entries).drop(keep)
}

/** Backup texts: "5 Nov 2026, 22:00", "2 trips · 31 entries · 148 photos", "Today, 22:00". */
object BackupText {
    fun counts(c: BackupCounts): String = listOf(
        plural(c.trips, "trip", "trips"),
        plural(c.entries, "entry", "entries"),
        plural(c.photos, "photo", "photos"),
    ).joinToString(" · ")

    /** "Backup from 5 Nov 2026, 22:00" uses this. */
    fun date(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(Instant.ofEpochMilli(at).atZone(zone))

    /** How long ago, for status lines: "Today, 22:00", "Yesterday, 22:00", "5 Nov, 22:00", "5 Nov 2025". */
    fun since(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val today: ZonedDateTime = Instant.ofEpochMilli(now).atZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(then)
        val days = today.toLocalDate().toEpochDay() - then.toLocalDate().toEpochDay()
        return when {
            days == 0L -> "Today, $time"
            days == 1L -> "Yesterday, $time"
            then.year == today.year -> DateTimeFormatter.ofPattern("d MMM", locale).format(then) + ", " + time
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(then)
        }
    }

    /**
     * "Nothing new since. Last checked: Today, 22:00": under the last backup's time when a later backup found nothing to
     * save ([checkedAt], Settings.backupCheckedAt); null when the last backup is the latest word.
     */
    fun nothingNew(lastBackupAt: Long?, checkedAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        if (lastBackupAt == null || checkedAt == null || checkedAt <= lastBackupAt) return null
        return "Nothing new since. Last checked: ${since(checkedAt, now, zone, locale)}"
    }

    /** Short form for a button's side: "22:00" today, "5 Nov" before. */
    fun short(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        return if (then.toLocalDate() == today.toLocalDate()) {
            DateTimeFormatter.ofPattern("HH:mm", locale).format(then)
        } else {
            DateTimeFormatter.ofPattern("d MMM", locale).format(then)
        }
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    /**
     * "Backed up: 2 trips · 31 entries · 148 photos." and what waits for the next backup; "Nothing new since the backup
     * of 12 Nov 2026, 22:00." when no new one was needed.
     */
    fun backedUp(r: BackupReport, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val main = if (r.unchanged) "Nothing new since the backup of ${date(r.entry.createdAt, zone, locale)}."
        else "Backed up: ${r.entry.items?.let(::counts) ?: date(r.entry.createdAt, zone, locale)}."
        val photos = if (r.photosLeft == 0) "" else " ${plural(r.photosLeft, "photo", "photos")} couldn't be saved this time; the next backup tries again."
        val videos = if (r.videos == 0) "" else " ${plural(r.videos, "video", "videos")} saved to BoringTravel › Videos."
        val waiting = if (r.videosLeft == 0) "" else " ${plural(r.videosLeft, "video waits", "videos wait")} for the next backup."
        return main + photos + videos + waiting
    }

    /** "Restored the backup of 11 Nov 2026, 21:40: 2 trips · 14 plan items · 31 entries · 148 photos." and what couldn't be fetched. */
    fun restored(r: RestoreReport, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val parts = listOfNotNull(
            plural(r.restored.trips, "trip", "trips").takeIf { r.restored.trips > 0 },
            plural(r.planItems, "plan item", "plan items").takeIf { r.planItems > 0 },
            plural(r.restored.entries, "entry", "entries").takeIf { r.restored.entries > 0 },
            plural(r.restored.photos, "photo", "photos").takeIf { r.restored.photos > 0 },
        )
        val main = (if (r.changed) "Restored the backup of " else "This phone already matched the backup of ") + date(r.backupAt, zone, locale) +
            (if (parts.isEmpty()) "." else ": ${parts.joinToString(" · ")}.")
        val videos = if (r.videos == 0) "" else " ${plural(r.videos, "video", "videos")} came back."
        val missing = when {
            r.photosMissing == 0 -> ""
            r.fromFile -> " ${plural(r.photosMissing, "photo wasn't", "photos weren't")} in the file."
            else -> " ${plural(r.photosMissing, "photo", "photos")} couldn't be downloaded. Restore again later to retry."
        }
        return main + videos + missing
    }

    /** Days without a backup before the app says so. */
    const val STALE_DAYS = 3

    /**
     * What Trips says when the daily backup isn't happening, or null when all is well: Drive wants the owner again, or
     * the last backup is [STALE_DAYS] days old or more. Nothing while backups are off or were never made. [lastBackupAt]:
     * when Drive last held everything (a daily check that found nothing new counts: Settings.backupFreshAt).
     */
    fun trouble(autoBackup: Boolean, driveConnected: Boolean, needsReconnect: Boolean, lastBackupAt: Long?, now: Long): String? {
        if (!autoBackup || !driveConnected) return null
        if (needsReconnect) return "Backups stopped. Reconnect Google Drive."
        val days = ((now - (lastBackupAt ?: return null)) / (24 * 60 * 60 * 1000L)).toInt()
        return if (days >= STALE_DAYS) "No backup for $days days." else null
    }
}

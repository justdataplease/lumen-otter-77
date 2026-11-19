package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.backup.PlanItemRow
import com.justdataplease.boringtravel.backup.TripRow
import com.justdataplease.boringtravel.data.repo.MediaTypes
import kotlinx.serialization.Serializable

/*
 * A shared trip as one member's phone knows it: `trip.btshare` in that member's own Drive folder
 * (BoringTravel/Shared/<trip>), link-shared so the other members' phones can read it without signing in to that Drive.
 *
 * Each member writes only their own file and reads everyone else's. Every row of the trip is in every file (not only
 * the writer's own), so a member who can reach one other member still gets everything. Rows reuse the backup's row
 * types: new fields get defaults, unknown fields are ignored, so older and newer apps read each other's files.
 */

/** One member of a shared trip: who, and where their copy is. */
@Serializable
data class Member(
    /** Random, made when they joined; stamps their entries and photos ([com.justdataplease.boringtravel.data.db.Entry.authorId]). */
    val id: String,
    /** The name they show ("Alexa"), at most [ShareCodec.MAX_NAME] characters. */
    val name: String = "",
    /** Their `trip.btshare` in their own Drive (link-shared); null while they only view (Drive not connected yet). */
    val fileId: String? = null,
    /** When they last wrote their file (epoch ms, on Google's clock). */
    val seenAt: Long = 0,
    /** They left the trip: their file stays readable for a while, but nobody needs to read it any more. */
    val left: Boolean = false,
)

/**
 * A photo or video as the other members see it: its copy in its author's folder (a photo's small JPEG, a video's own
 * compressed file), and the facts any phone can use.
 */
@Serializable
data class SharedPhoto(
    val id: String,
    val entryId: String? = null,
    /** Drive id of the copy in the author's shared folder; null until the author's phone uploaded it. */
    val fileId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val authorId: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    /**
     * What the copy is: "image/jpeg" (left out of the file, as older apps wrote it) or a video's type ("video/mp4").
     * Older apps ignore it; [ShareCodec.FORMAT] stays 1.
     */
    val mime: String = MediaTypes.JPEG,
    /**
     * A video's size in bytes (0: a photo, or not known), so the others can leave a big one for Wi-Fi when the owner's
     * "Only on Wi-Fi" is on (TogetherEngine.WAIT_FOR_WIFI_BYTES).
     */
    val bytes: Long = 0,
)

@Serializable
data class ShareFile(
    val format: Int = ShareCodec.FORMAT,
    val app: String = "BoringTravel",
    /** Who wrote this file. */
    val writer: String,
    /** When (epoch ms, on Google's clock). */
    val writtenAt: Long = 0,
    /** Everyone the writer knows of, the writer included. */
    val members: List<Member> = emptyList(),
    val trip: TripRow,
    val planItems: List<PlanItemRow> = emptyList(),
    /** Entries with [EntryRow.authorId] always set (a member's id, never null). */
    val entries: List<EntryRow> = emptyList(),
    val photos: List<SharedPhoto> = emptyList(),
)

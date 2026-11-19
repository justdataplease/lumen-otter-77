package com.justdataplease.boringtravel.clip

/** A journal video of the trip on this phone ([id]: its media row), offered in the Add media sheet. */
data class TripVideo(val id: String, val time: Long?, val mime: String)

/** One tile of the Add media sheet: a trip photo, or a journal video when [video]. */
data class MediaTile(val id: String, val time: Long?, val video: Boolean)

/** What tapping a journal video in a place's Add media sheet does. */
sealed interface VideoTap {
    /** Not in the clip yet: copy it in after the place. */
    data object Add : VideoTap

    /** Already plays after this place: take clip video [id] out. */
    data class Remove(val id: String) : VideoTap

    /** Plays after another place: clip video [id] moves here, its trim and sound kept. */
    data class Move(val id: String) : VideoTap
}

/**
 * A place's media in the clip's Media tool (owner, 2026-11-14: "in clips videos are added as photos and have the same
 * structure (so maybe remove photos and have media and delete videos specific wording)"): its photos and the videos
 * that play after it sit in one "Media here" list, and one sheet adds either. Pure.
 */
object PlaceMedia {
    /** The trip's photos and journal videos in one list for the sheet, oldest first, the undated last. */
    fun tiles(photos: List<ClipPhoto>, videos: List<TripVideo>): List<MediaTile> =
        (photos.map { MediaTile(it.id, it.time, video = false) } + videos.map { MediaTile(it.id, it.time, video = true) })
            .sortedWith(compareBy({ it.time == null }, { it.time }))

    /**
     * A place's quiet line on "Choose media per place": "3 of 5 photos · 1 video", "2 videos", "No media". [shown] of
     * [photos] show; [videos] play after the place.
     */
    fun countLine(shown: Int, photos: Int, videos: Int): String {
        val parts = buildList {
            if (photos > 0) add("$shown of $photos " + if (photos == 1) "photo" else "photos")
            if (videos > 0) add("$videos " + if (videos == 1) "video" else "videos")
        }
        return if (parts.isEmpty()) "No media" else parts.joinToString(" · ")
    }

    /** The clip video copied from journal video [mediaId], if one is in the clip. */
    fun added(videos: List<ClipVideo>, mediaId: String): ClipVideo? = videos.firstOrNull { it.source == mediaId }

    /**
     * Tapping journal video [mediaId] in place [key]'s sheet works like tapping a photo there: in at another place, it
     * moves here; already here, it comes out; not in the clip, it is added after this place.
     */
    fun tap(videos: List<ClipVideo>, mediaId: String, key: String): VideoTap {
        val v = added(videos, mediaId) ?: return VideoTap.Add
        return if (v.sceneKey == key) VideoTap.Remove(v.id) else VideoTap.Move(v.id)
    }
}

package com.justdataplease.boringtravel.data.settings

import com.justdataplease.boringtravel.doc.PhotoFrame
import java.util.Locale
import kotlin.math.abs

/**
 * How a new photo or video sits in a trip's notes (owner, 2026-11-14: "in trip when creating or edit add the defaults
 * for photos, i.e. medium, postcard … to have as defaults when writing"): its [width] in the text column and its
 * [frame]. Each photo can still be changed in its note.
 */
data class TripPhotoStyle(val width: Float, val frame: PhotoFrame) {
    /** The size this width is ([SIZES]), the nearest one for a width set by hand. */
    val size: Size get() = SIZES.minBy { abs(it.width - width) }

    /** A size by name; the widths are the note editor's S, M, L and Full. */
    data class Size(val label: String, val width: Float)

    companion object {
        val SIZES: List<Size> = listOf(Size("Small", 0.45f), Size("Medium", 0.65f), Size("Large", 0.85f), Size("Full", 1f))

        /** What every note did before trips had their own: the full column, rounded corners. */
        val Default = TripPhotoStyle(1f, PhotoFrame.Rounded)

        fun frameLabel(f: PhotoFrame): String = when (f) {
            PhotoFrame.None -> "None"
            PhotoFrame.Polaroid -> "Polaroid"
            PhotoFrame.Tape -> "Tape"
            PhotoFrame.Rounded -> "Rounded"
        }
    }
}

/**
 * Every trip's [TripPhotoStyle], by trip id, and the last one chosen ([last]: what a new trip starts from). Kept in the
 * settings as one text, "<trip>=<width>|<frame>;…", the last choice under [LAST]; it travels in backups. A trip
 * without one uses [TripPhotoStyle.Default], as do notes outside trips. Pure and unit tested.
 */
data class TripPhotoStyles(val byTrip: Map<String, TripPhotoStyle> = emptyMap(), val last: TripPhotoStyle? = null) {
    /** The style for notes of trip [tripId] (null: a note outside trips). */
    fun of(tripId: String?): TripPhotoStyle = tripId?.let(byTrip::get) ?: TripPhotoStyle.Default

    /** What the trip form shows: the trip's own, and for a new trip ([tripId] null) the last one chosen. */
    fun forForm(tripId: String?): TripPhotoStyle = if (tripId == null) last ?: TripPhotoStyle.Default else of(tripId)

    /** With [style] for [tripId], which is also the last one chosen. The oldest go once there are more than [MAX]. */
    fun with(tripId: String, style: TripPhotoStyle): TripPhotoStyles {
        val next = LinkedHashMap(byTrip)
        next.remove(tripId)
        next[tripId] = style
        while (next.size > MAX) next.remove(next.keys.first())
        return TripPhotoStyles(next, style)
    }

    fun encode(): String = (byTrip.entries.map { it.key to it.value } + listOfNotNull(last?.let { LAST to it }))
        .joinToString(";") { (id, s) -> "$id=${String.format(Locale.ROOT, "%.2f", s.width)}|${s.frame.id}" }

    companion object {
        const val MAX = 300
        const val MAX_CHARS = 30_000
        private const val LAST = "*"

        /** Reads [encode]'s text; a damaged part is skipped, a damaged or oversized text reads as empty. */
        fun decode(text: String?): TripPhotoStyles {
            if (text.isNullOrBlank() || text.length > MAX_CHARS) return TripPhotoStyles()
            val byTrip = LinkedHashMap<String, TripPhotoStyle>()
            var last: TripPhotoStyle? = null
            for (part in text.split(';')) {
                val id = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "")
                val width = value.substringBefore('|').toFloatOrNull()?.takeIf { it in 0.3f..1f } ?: continue
                val frame = PhotoFrame.entries.firstOrNull { it.id == value.substringAfter('|', "") } ?: continue
                if (id.isEmpty()) continue
                if (id == LAST) last = TripPhotoStyle(width, frame) else byTrip[id] = TripPhotoStyle(width, frame)
            }
            return TripPhotoStyles(byTrip, last)
        }
    }
}

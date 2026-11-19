package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.ui.traveller.FRONT
import kotlin.math.roundToInt

/**
 * A community picture made from a traveller (owner, 2026-11-12: "make it possible to select avatar and the viewpoint"):
 * who and how they look, turned to [heading] degrees, head and shoulders or the whole figure ([full]). Kept in the
 * profile's avatar string as the traveller's own encoding plus ";view=…" and ";frame=full", so an older app still
 * reads the traveller and ignores the rest.
 */
data class ProfileAvatar(val traveller: Traveller, val heading: Float = DEFAULT_HEADING, val full: Boolean = false) {
    fun encode(): String = traveller.encode() + ";view=" + normal(heading).roundToInt() + if (full) ";frame=full" else ""

    companion object {
        /** A three-quarter view from the front (the traveller preview's own). */
        const val DEFAULT_HEADING = FRONT

        /** [deg] within 0–360. */
        fun normal(deg: Float): Float = if (!deg.isFinite()) DEFAULT_HEADING else ((deg % 360f) + 360f) % 360f

        /** The picture in [raw], or null when it holds no traveller (the initial then). */
        fun decodeOrNull(raw: String?): ProfileAvatar? {
            val traveller = Traveller.decodeOrNull(raw) ?: return null
            val parts = Traveller.parts(raw.orEmpty())
            val view = parts["view"]?.toFloatOrNull()?.let(::normal) ?: DEFAULT_HEADING
            return ProfileAvatar(traveller, view, parts["frame"] == "full")
        }
    }
}

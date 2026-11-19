package com.justdataplease.boringtravel.clip

import android.content.Context
import androidx.core.content.edit

/**
 * The trip clip's last length ([ClipLength], or Pace), speed (×1, ×2, ×5, ×10), shape and style ([ClipDesign]), kept on
 * this phone like the map look ([MapStylePrefs]). The style is one "key=id;key=id" string, so new choices never break
 * an older one.
 */
object ClipPrefs {
    private const val SPEED = "speed"
    private const val LENGTH = "length"
    private const val SHAPE = "shape"
    private const val DESIGN = "design"
    private const val VIEW = "view"
    /** What [LENGTH] keeps for no length (the trip's own pace at a speed). */
    const val PACE = "pace"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("clip", Context.MODE_PRIVATE)

    fun speed(context: Context): Int = prefs(context).getInt(SPEED, 1).takeIf { it in ClipTiming.SPEEDS } ?: 1

    fun saveSpeed(context: Context, speed: Int) {
        prefs(context).edit { putInt(SPEED, speed) }
    }

    /** The last length: Auto the first time; null when the clip was last made at a pace ([speed]). */
    fun length(context: Context): ClipLength? = readLength(prefs(context).getString(LENGTH, null))

    fun saveLength(context: Context, length: ClipLength?) {
        prefs(context).edit { putString(LENGTH, length?.id ?: PACE) }
    }

    /** [length]'s stored value read back: [PACE] is no length, anything missing or unknown is Auto. */
    fun readLength(raw: String?): ClipLength? = if (raw == PACE) null else ClipLength.of(raw) ?: ClipLength.Auto

    /**
     * The last camera view: 3D the first time (owner, 2026-11-12: "the default in video should be globe 3d"; the sky
     * band stays off, [ClipDesign.sky]); Flat when it was last chosen.
     */
    fun view(context: Context): ClipView = readView(prefs(context).getString(VIEW, null))

    /** [view]'s stored value read back: "flat" is the flat view, anything else (nothing chosen yet) 3D. */
    fun readView(raw: String?): ClipView = if (raw == VIEW_FLAT) ClipView.Flat else ClipView.Perspective

    fun saveView(context: Context, view: ClipView) {
        prefs(context).edit { putString(VIEW, if (view == ClipView.Perspective) VIEW_3D else VIEW_FLAT) }
    }

    private const val VIEW_3D = "3d"
    private const val VIEW_FLAT = "flat"

    /** The last shape (9:16 the first time). */
    fun shape(context: Context): ClipShape = ClipShape.of(prefs(context).getString(SHAPE, null)) ?: ClipShape.Story

    fun saveShape(context: Context, shape: ClipShape) {
        prefs(context).edit { putString(SHAPE, shape.id) }
    }

    fun design(context: Context): ClipDesign = decode(prefs(context).getString(DESIGN, null))

    private const val DEFAULTS = "defaults"
    /** Raised when the defaults change enough that every phone should start again from them. */
    private const val DEFAULTS_VERSION = 4

    /**
     * Once per [DEFAULTS_VERSION]. From before 2: forgets the remembered speed, style, map, view and music so the next
     * clip starts on the current defaults (Globe in 3D, music from its best part, the steady camera, Auto length and
     * photos; owner, 2026-11-08: "keep the defaults strava like"). From 2: the photos' new defaults ([photoDefaults]).
     * From 3: the map and the view, so the next clip starts on the Globe in 3D (owner, 2026-11-12: "the default in video
     * should be globe 3d"). The shape and each trip's own edits stay.
     */
    fun newDefaults(context: Context) {
        val p = prefs(context)
        val was = p.getInt(DEFAULTS, 0)
        if (was >= DEFAULTS_VERSION) return
        val raw = p.getString(DESIGN, null)
        p.edit {
            if (was < 2) remove(SPEED).remove(DESIGN) else if (was < 3 && raw != null) putString(DESIGN, encode(photoDefaults(decode(raw))))
            remove(VIEW)
            putInt(DEFAULTS, DEFAULTS_VERSION)
        }
        if (was < 2) com.justdataplease.boringtravel.clip.music.MusicPrefs.forget(context)
        MapStylePrefs.forgetClip(context)
    }

    /**
     * Version 3's change to a remembered style (owner, 2026-11-09: "by default keep the full screen", cards "bottom
     * right"): photos fill the frame, and a card, when chosen, sits at the bottom right where the pad put none.
     */
    fun photoDefaults(d: ClipDesign): ClipDesign = d.copy(
        photoLayout = PhotoLayout.Full, photoRow = PhotoRow.Bottom, photoSide = PhotoSide.Right, photoX = null, photoY = null,
    )

    fun saveDesign(context: Context, design: ClipDesign) {
        prefs(context).edit { putString(DESIGN, encode(design)) }
    }

    /** The global choices as "opening=title;name=top;…" (per-scene overrides are not stored here). */
    fun encode(d: ClipDesign): String = listOf(
        "opening" to d.opening.id, "name" to d.name.id, "font" to d.font.id, "back" to d.back.id, "panel" to d.panel.id,
        "color" to d.color.id, "line" to d.line.id, "weight" to d.weight.id, "lineColor" to d.lineColor.id,
        "photoRow" to d.photoRow.id, "photoSide" to d.photoSide.id, "photos" to d.photoMode.id,
        "camera" to d.camera.id, "zoom" to d.zoom.id, "closeUp" to d.closeUp.id, "zoomOut" to d.zoomOut.id,
        "textSize" to d.textSize.id, "bars" to onOff(d.bars), "tone" to d.tone.id, "grain" to onOff(d.grain),
        "dayCards" to onOff(d.dayCards), "sky" to onOff(d.sky),
        "photoGap" to d.photoGapS.toString(), "stopTitles" to onOff(d.stopTitles), "photoLayout" to d.photoLayout.id,
        "textScale" to d.textScale.toString(), "nameX" to d.nameX?.toString(), "nameY" to d.nameY?.toString(),
        "photoX" to d.photoX?.toString(), "photoY" to d.photoY?.toString(), "photoHold" to d.photoHoldS.toString(),
        "photoScale" to d.photoScale.toString(), "photoFrame" to d.photoFrame.id,
        "stats" to TripStat.write(d.stats), "statsScale" to d.statsScale.toString(),
        "statsX" to d.statsX?.toString(), "statsY" to d.statsY?.toString(), "statsFont" to d.statsFont.id,
        "statsBack" to d.statsBack.id, "statsColor" to d.statsColor.id,
        "tags" to onOff(d.tags), "tagColor" to d.tagColor.id, "tagScale" to d.tagScale.toString(),
    ).filter { it.second != null }.joinToString(";") { (k, v) -> "$k=$v" }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /**
     * Reads [encode]'s string; anything missing or unknown is the default. A style saved before the steady camera
     * (no "camera" key) that still has the photo pauses the clip used to start with reads with Auto photos, the new
     * default (owner, 2026-11-08: "keep the defaults strava like").
     */
    fun decode(raw: String?): ClipDesign {
        val map = raw.orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        val d = ClipDesign()
        fun <T> pick(key: String, values: Array<T>, id: (T) -> String, default: T): T =
            map[key]?.let { v -> values.firstOrNull { id(it) == v } } ?: default
        fun flag(key: String, default: Boolean): Boolean = when (map[key]) { "on" -> true; "off" -> false; else -> default }
        fun num(v: String?, range: ClosedFloatingPointRange<Double>): Double? = v?.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }
        fun share(v: String?): Float? = num(v, 0.0..1.0)?.toFloat()
        // A style saved before the steady camera still had the 2 s pauses the clip used to start with: Auto's own.
        val oldMode = pick("photos", PhotoMode.entries.toTypedArray(), { it.id }, d.photoMode)
            .let { if (it == PhotoMode.Pause2 && "camera" !in map) d.photoMode else it }
        return ClipDesign(
            opening = pick("opening", Opening.entries.toTypedArray(), { it.id }, d.opening),
            name = pick("name", NamePlace.entries.toTypedArray(), { it.id }, d.name),
            font = pick("font", ClipFont.entries.toTypedArray(), { it.id }, d.font),
            back = pick("back", TextBack.entries.toTypedArray(), { it.id }, d.back),
            panel = pick("panel", PanelShape.entries.toTypedArray(), { it.id }, d.panel),
            color = pick("color", TextColor.entries.toTypedArray(), { it.id }, d.color),
            line = pick("line", LineStyle.entries.toTypedArray(), { it.id }, d.line),
            weight = pick("weight", LineWeight.entries.toTypedArray(), { it.id }, d.weight),
            lineColor = pick("lineColor", LineColor.entries.toTypedArray(), { it.id }, d.lineColor),
            photoRow = pick("photoRow", PhotoRow.entries.toTypedArray(), { it.id }, d.photoRow),
            photoSide = pick("photoSide", PhotoSide.entries.toTypedArray(), { it.id }, d.photoSide),
            // The editor offers Auto or Off (owner, 2026-11-09: "many many choices no need them all"): an older
            // style's On, Pause 2 s or Pause 3 s reads as Auto, with its pause on the slider.
            photoMode = if (oldMode == PhotoMode.Off) PhotoMode.Off else PhotoMode.Auto,
            camera = pick("camera", CameraMode.entries.toTypedArray(), { it.id }, d.camera),
            zoom = pick("zoom", ClipZoom.entries.toTypedArray(), { it.id }, d.zoom),
            closeUp = pick("closeUp", CloseUp.entries.toTypedArray(), { it.id }, d.closeUp),
            zoomOut = ZoomOut.of(map["zoomOut"]) ?: d.zoomOut,
            textSize = pick("textSize", TextSize.entries.toTypedArray(), { it.id }, d.textSize),
            bars = flag("bars", d.bars),
            tone = pick("tone", ClipTone.entries.toTypedArray(), { it.id }, d.tone),
            grain = flag("grain", d.grain),
            dayCards = flag("dayCards", d.dayCards),
            // A style saved before the switch has no sky: 1.4.0's band stays off unless switched on again.
            sky = flag("sky", d.sky),
            // A style saved before these has the half-second still map, the stops' titles and the card.
            photoGapS = map["photoGap"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..ClipDesign.MAX_GAP_S } ?: d.photoGapS,
            stopTitles = flag("stopTitles", d.stopTitles),
            photoLayout = PhotoLayout.of(map["photoLayout"]) ?: d.photoLayout,
            // A style saved before the sliders and the thumb pad has the look's type size, the spots by row and side,
            // the 3 s pause and the cards' own size and border.
            textScale = num(map["textScale"], Spots.TEXT_SCALE_ALL)?.toFloat() ?: d.textScale,
            nameX = share(map["nameX"]), nameY = share(map["nameY"]),
            photoX = share(map["photoX"]), photoY = share(map["photoY"]),
            photoHoldS = num(map["photoHold"], Spots.HOLD_MIN_S..ClipDesign.MAX_HOLD_S)
                ?: oldMode.holdSeconds.takeIf { oldMode == PhotoMode.Pause2 || oldMode == PhotoMode.Pause3 } ?: d.photoHoldS,
            photoScale = num(map["photoScale"], Spots.PHOTO_SCALE.start.toDouble()..Spots.PHOTO_SCALE.endInclusive.toDouble())?.toFloat() ?: d.photoScale,
            photoFrame = PhotoFrame.entries.firstOrNull { it.id == map["photoFrame"] } ?: d.photoFrame,
            // A style saved before the stats line shows it, small in the bottom left corner (1.3.0's switch off: none).
            stats = if (map["statsOn"] == "off") emptySet() else TripStat.read(map["stats"]) ?: d.stats,
            statsScale = num(map["statsScale"], Spots.STATS_SCALE.start.toDouble()..Spots.STATS_SCALE.endInclusive.toDouble())?.toFloat() ?: d.statsScale,
            statsX = share(map["statsX"]), statsY = share(map["statsY"]),
            statsFont = pick("statsFont", ClipFont.entries.toTypedArray(), { it.id }, d.statsFont),
            statsBack = pick("statsBack", TextBack.entries.toTypedArray(), { it.id }, d.statsBack),
            statsColor = pick("statsColor", TextColor.entries.toTypedArray(), { it.id }, d.statsColor),
            // A style saved before the plan's tags shows them, in the stickers' own colour and size.
            tags = flag("tags", d.tags),
            tagColor = pick("tagColor", TagColor.entries.toTypedArray(), { it.id }, d.tagColor),
            tagScale = num(map["tagScale"], ClipTags.SCALE.start.toDouble()..ClipTags.SCALE.endInclusive.toDouble())?.toFloat() ?: d.tagScale,
        )
    }
}

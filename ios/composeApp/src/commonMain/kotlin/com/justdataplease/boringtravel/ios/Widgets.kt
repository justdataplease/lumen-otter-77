package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.drawPlaneMark
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.Paper
import com.justdataplease.boringtravel.ui.trips.HomeStats
import com.justdataplease.boringtravel.widget.QuickBarLayout
import com.justdataplease.boringtravel.widget.TodayInfo
import com.justdataplease.boringtravel.widget.WidgetLook
import com.justdataplease.boringtravel.widget.WidgetText
import kotlinx.serialization.Serializable
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/*
 * The home-screen widgets (round 10), as the Android app's widget/ draws them: "Today" (the trip, "Day 4 of 9 · 2
 * written today", Write, Photo when wide, Voice), "Quick bar" (the theme's paper, the stamp, "Day 4 · Naxos" over the
 * trip, Note, Camera, Voice when wide) and "Quick bar, small" (Note and Camera). The words, colours, paper and layout
 * rules are the Android app's own files, read in (widget/WidgetModel.kt, widget/QuickBarLayout.kt; widget/PaperGrid.kt
 * copied). The widget itself is SwiftUI (iosApp/BoringTravelWidget): it links none of this Kotlin and draws only the
 * [WidgetSnapshot] the app writes into the App Group's folder through [HomeWidget], with the stamp as a picture. The
 * snapshot holds today and the next days, so the day's line changes at midnight without the app.
 */

/** A widget's buttons and the app's Home Screen quick actions: what each opens (the Android app's widget/Deeplink.kt). */
object Deeplink {
    /** A new note for today in today's trip (outside trips when none), ready to type. */
    const val WRITE = "write"

    /** The camera; the photo becomes a new note opened in Write. */
    const val CAMERA = "camera"

    /** Older widget buttons said "photo": the camera too. */
    const val PHOTO = "photo"
    const val VOICE = "voice"
    const val NEW_TRIP = "new_trip"

    /** Today's trip on its Journal (the app as it was when there is none). */
    const val TODAY = "today"

    /**
     * Me › Route tracking: a tap on the recording route's Live Activity on iOS 16.1 to 16.x, where it can't have its
     * own Stop (iOS 17 has the button; Android's notification has Stop and opens the app).
     */
    const val TRACKING = "tracking"

    /** The app's own links: boringtravel://write and so on (the widget's buttons, the quick actions). */
    const val SCHEME = "boringtravel"

    private val known = setOf(WRITE, CAMERA, PHOTO, VOICE, NEW_TRIP, TODAY, TRACKING)

    /** The action of a link into the app ("boringtravel://voice"), or null for any other link. */
    fun of(link: String): String? {
        val rest = link.trim().removePrefix("$SCHEME://").takeIf { it != link.trim() } ?: return null
        val action = rest.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        return action.takeIf { it in known }
    }

    fun link(action: String): String = "$SCHEME://$action"
}

/**
 * Links that came from outside (a widget's button, a Home Screen quick action), waiting for the app to take them: on
 * iPhone they can arrive before the first screen (a cold start from the Home Screen).
 */
class AppLinks {
    var pending by mutableStateOf<String?>(null)
        private set

    /** A link arrived; false when it isn't one of the app's actions (it is dropped). */
    fun open(link: String): Boolean {
        val action = Deeplink.of(link) ?: return false
        pending = action
        return true
    }

    /** The waiting action, taken (null when none waits). */
    fun take(): String? = pending.also { pending = null }
}

/** The app's links from outside; null in previews and tests without them. */
val LocalAppLinks = staticCompositionLocalOf<AppLinks?> { null }

/**
 * Where the widget's snapshot goes: the App Group's folder on iPhone (Swift's WidgetBridge, through iosMain's
 * IosHomeWidget, which also asks WidgetKit to draw again); kept in memory elsewhere ([MemoryHomeWidget]).
 */
interface HomeWidget {
    /** [snapshot] is [WidgetSnapshot]'s JSON; [mark] the stamp as a PNG, null when it didn't change. */
    fun saveWidget(snapshot: String, mark: ByteArray?)
}

/** A widget that only keeps what it was given: tests, the desktop's pictures. */
class MemoryHomeWidget : HomeWidget {
    var snapshot: String? = null
    var mark: ByteArray? = null
    var saves = 0

    override fun saveWidget(snapshot: String, mark: ByteArray?) {
        this.snapshot = snapshot
        if (mark != null) this.mark = mark
        saves++
    }
}

/** One day as the widgets show it, from local midnight [from] on. */
@Serializable
data class WidgetDay(
    val from: Long,
    /** The Today widget's title: the trip, else "Today". */
    val title: String,
    /** "Day 4 of 9 · 2 written today", "No trip today · Fri 6 Nov 2026". */
    val line: String,
    /** The Quick bar's two lines: "Day 4 · Naxos" over the trip, or "No trip today" over "Cyclades in 12 days". */
    val primary: String,
    val secondary: String,
)

/**
 * The widths (points) from which the Quick bar shows its text, its stamp and Voice ([QuickBarLayout]: as the bar
 * narrows, Voice goes first, then the stamp, then the text; Note and Camera stay).
 */
@Serializable
data class QuickWidths(val context: Int, val mark: Int, val voice: Int, val note: Int, val noteMax: Int)

/** What the SwiftUI widget draws: everything, words and colours included ("#AARRGGBB"). */
@Serializable
data class WidgetSnapshot(
    val v: Int = VERSION,
    val look: String,
    /** The Today widget's: surface, ink, muted, primary, onPrimary, chip. The Quick bar's: paper, texture, edge, shadow, note, onNote, button, buttonEdge, onButton (and ink, muted). */
    val colors: Map<String, String>,
    /** The Quick bar's paper: "dots", "lines", "grid" or "plain". */
    val paper: String,
    val dotStep: Float,
    val edgeWidth: Float,
    val noteTilt: Float,
    /** Round ends (Trail) or the theme's small corners. */
    val pill: Boolean,
    val quick: QuickWidths,
    /** Today and the next days, each from its local midnight. */
    val days: List<WidgetDay>,
    /** The stamp's picture in the App Group's folder. */
    val mark: String = MARK_FILE,
) {
    companion object {
        const val VERSION = 1
        const val MARK_FILE = "mark.png"

        /** Days ahead in the snapshot, so the widget turns the day itself while the app isn't opened. */
        const val DAYS = 8

        /** [build] as the JSON the widget reads. */
        fun json(s: WidgetSnapshot): String = BackupJson.encodeToString(serializer(), s)

        fun parse(text: String): WidgetSnapshot? = runCatching { BackupJson.decodeFromString(serializer(), text) }.getOrNull()

        /**
         * The widgets' snapshot for [today] and the next days (the Android app's WidgetUpdates.load, day by day): the
         * trip on each day as the app shows it (the examples in demo mode), the day's own name or its place, the
         * next trip, the notes written today, in [look]'s colours. [zoneId]: the phone's zone (null).
         */
        fun build(book: TripBook, look: Look, demoMode: Boolean, today: LocalDate, zoneId: String? = null): WidgetSnapshot {
            val trips = book.snapshot.trips.filter { it.deletedAt == null && DemoTrips.visible(it.id, demoMode) }
            val days = (0 until DAYS).map { i ->
                val day = today.plusDays(i.toLong())
                val trip = HomeStats.sections(trips, day).now
                val start = startOfDayMs(day, zoneId)
                val end = startOfDayMs(day.plusDays(1), zoneId)
                val wrote = if (i > 0) 0 else book.snapshot.entries.count {
                    it.deletedAt == null && it.occurredAt in start until end && DemoTrips.visible(it.tripId, demoMode)
                }
                val place = trip?.let { t ->
                    DayNames.of(t.travelModes, day)
                        ?: runCatching { DayPlace.label(day, book.snapshot.stops.filter { it.tripId == t.id }, book.items(t.id), zoneId) }.getOrNull()
                }
                val next = if (trip == null) WidgetText.nextTrip(trips, day) else null
                val info = TodayInfo(day, look, trip, place, next, wrote)
                val line = WidgetText.context(info)
                WidgetDay(start, trip?.title ?: "Today", WidgetText.todayLine(info), line.primary, line.secondary)
            }
            val w = WidgetLook.of(look)
            val c = BtLooks.of(look).colors
            fun hex(color: Color) = "#" + color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')
            val colors = linkedMapOf(
                "surface" to c.surface, "ink" to c.ink, "muted" to c.muted, "primary" to c.primary, "onPrimary" to c.onPrimary, "chip" to c.chip,
                "paper" to w.paper, "edge" to w.edge, "shadow" to w.shadow, "barInk" to w.ink, "barMuted" to w.muted,
                "note" to w.note, "onNote" to w.onNote, "button" to w.button, "onButton" to w.onButton,
            ).mapValues { hex(it.value) }.toMutableMap()
            w.textureColor?.let { colors["texture"] = hex(it) }
            w.buttonEdge?.let { colors["buttonEdge"] = hex(it) }
            val paper = when (w.texture) {
                Paper.Dots -> "dots"
                Paper.Lines -> "lines"
                Paper.Grid -> "grid"
                else -> "plain"
            }
            return WidgetSnapshot(
                look = look.id, colors = colors, paper = if (w.textureColor == null) "plain" else paper, dotStep = w.dotStep,
                edgeWidth = w.edgeWidth, noteTilt = w.noteTilt, pill = w.pill, quick = quickWidths(), days = days,
            )
        }

        /** The narrowest widths at which the Quick bar shows its text, stamp and Voice, from [QuickBarLayout] itself. */
        fun quickWidths(): QuickWidths {
            fun from(shows: (QuickBarLayout) -> Boolean): Int = (40..1000).firstOrNull { shows(QuickBarLayout.of(it.toFloat(), QuickBarLayout.MAX_HEIGHT)) } ?: Int.MAX_VALUE
            return QuickWidths(
                context = from { it.context }, mark = from { it.mark }, voice = from { it.voice },
                note = QuickBarLayout.NOTE.toInt(), noteMax = QuickBarLayout.NOTE_MAX.toInt(),
            )
        }

        /**
         * The stamp: the plane on the theme's yellow ([PlaneMark], as Android's PlaneMarkBitmap draws it for the
         * widgets), [px] pixels square, as a PNG.
         */
        fun markPng(look: Look, px: Int = 96): ByteArray {
            val colors = WidgetLook.of(look).mark
            val bitmap = ImageBitmap(px, px)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
                drawPlaneMark(colors)
            }
            return Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
        }
    }
}

/**
 * Keeps the widgets current (the Android app's WidgetUpdates.keepUpdated): the snapshot after a change to the trips,
 * notes, plan, stops, the theme or demo mode, and on every start; the stamp only when the theme changed.
 */
class WidgetWriter(private val widget: HomeWidget) {
    private var lastSnapshot: String? = null
    private var lastMarkLook: Look? = null

    fun write(book: TripBook, look: Look, demoMode: Boolean, today: LocalDate) {
        val json = runCatching { WidgetSnapshot.json(WidgetSnapshot.build(book, look, demoMode, today)) }.getOrNull() ?: return
        val mark = if (lastMarkLook != look) runCatching { WidgetSnapshot.markPng(look) }.getOrNull()?.takeIf { it.isNotEmpty() } else null
        if (json == lastSnapshot && mark == null) return
        widget.saveWidget(json, mark)
        lastSnapshot = json
        if (mark != null) lastMarkLook = look
    }
}

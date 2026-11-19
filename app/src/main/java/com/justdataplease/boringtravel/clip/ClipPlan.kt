package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.TravelTime
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.util.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clip shapes and their output sizes: a story or reel (9:16), a feed post (4:5), a square, a wide video (16:9). The
 * camera frames by [aspect], the texts by the frame's short side ([ClipLayout]); [id] is what the prefs keep.
 */
enum class ClipShape(val id: String, val label: String, val width: Int, val height: Int) {
    Story("story", "9:16", 1080, 1920),
    Portrait("portrait", "4:5", 1080, 1350),
    Square("square", "1:1", 1080, 1080),
    Wide("wide", "16:9", 1920, 1080);

    val aspect: Double get() = width.toDouble() / height

    companion object {
        fun of(id: String?): ClipShape? = entries.firstOrNull { it.id == id }
    }
}

/** Replay camera treatment; perspective tilts a geographic plane, without inventing terrain height. */
enum class ClipView(val label: String) { Flat("Flat"), Perspective("3D perspective") }

data class ClipOptions(
    /**
     * The whole clip's length ([ClipLength]; Auto: 25 s, 45 s for long trips and wide clips): the journey's speed
     * follows from it. Null is "Pace": the trip's travel time at [speed] sets the length.
     */
    val length: ClipLength? = ClipLength.Auto,
    /** Pace ([length] null): ×1, ×2, ×5 or ×10 ([ClipTiming.SPEEDS]); the trip's travel time sets the length, the speed divides it. */
    val speed: Int = 1,
    /**
     * A set length in seconds for the opening, journey and end (tests and tools; photo pauses and inserts add to it),
     * ahead of [length] and [speed]; null = from [length] or the trip at [speed].
     */
    val seconds: Int? = null,
    val shape: ClipShape = ClipShape.Story,
    val vehicle: Vehicle = Vehicle.Auto,
    val photos: Boolean = true,
    val fps: Int = 30,
    val view: ClipView = ClipView.Flat,
    /**
     * In 3D ([ClipView.Perspective]), a band of the look's paper at the top as sky ([ReplayProjection.horizonY]);
     * off by default, so the map fills the frame (owner, on 1.4.0: "remove it by default").
     */
    val sky: Boolean = false,
    /** The base map's look; Earth also keeps the camera from zooming past its pictures' detail. */
    val map: MapStyle = MapStyle.Street,
    /**
     * Seconds the journey pauses while each photo shows (0 = photos pop in over the moving route). Pauses lengthen
     * the clip ([ClipTiming.heldPhotos]).
     */
    val photoHoldSeconds: Double = 0.0,
    /**
     * Auto photos ([PhotoMode.Auto]): one photo per place, spread over the route as far as the pauses fit, the
     * journey stopping [photoHoldSeconds] for each and none over the moving map (only a photo whose own pause is 0);
     * photos off the route join the nearest stop.
     */
    val photoAuto: Boolean = false,
    /** Music hook (`clip/music`): the track added under the finished video ([com.justdataplease.boringtravel.clip.music.MusicCatalog] id); null = silent. */
    val music: String? = null,
    /** The scene editor's photo changes: left out, asked for, own pauses, order within places. */
    val photoEdits: PhotoEdits = PhotoEdits(),
    /** How the camera moves ([CameraMode]: one steady height per stretch, or the older adaptive follow). */
    val camera: CameraMode = CameraMode.Steady,
    /** How close the camera gets ([ClipZoom]; Normal is the camera the clip always had). */
    val zoom: ClipZoom = ClipZoom.Normal,
    /** The close, still view at stops ([CloseUp]); it takes its time from the journey, so the length stays. */
    val closeUp: CloseUp = CloseUp.Off,
    /** How far the camera pulls back ([ZoomOut]): the opening and close's view, the widest it goes. */
    val zoomOut: ZoomOut = ZoomOut.Auto,
    /** The scene editor's camera changes per place name. */
    val cameraEdits: CameraEdits = CameraEdits(),
    /** Phone videos played full frame after their place names ([ClipVideo]); each adds its length to the clip. */
    val videos: List<VideoSlot> = emptyList(),
    /** Which days open with a full-frame day card ([DayCardChoice]; none unless asked for); each adds [ClipPlan.DAY_CARD_S]. */
    val dayCards: DayCardChoice = DayCardChoice(),
    /** The music's beats to cut on ([ClipBeats], see [BeatFit]); null: cuts fall where the journey puts them. */
    val beats: ClipBeats? = null,
    /**
     * Seconds of still map before each photo pause and after it (owner, 2026-11-09: "when the movement ends wait like
     * half second and appear the photo. then photo disappears wait half a second and start the movement"; "maybe have
     * this as a slider"): the journey stops, the photo comes, goes, and the journey moves on after the same wait. Photos
     * paused one after another at one place share them. 0: the photo comes as the journey stops (as in 1.1.2).
     */
    val photoGapSeconds: Double = ClipDesign.PHOTO_GAP_S,
    /**
     * A planned route's stops within a place show their own titles (owner, 2026-11-09: "text should be able take the
     * sub items title also"): each stop its own place name, the item's title ("Lisbon Cathedral") under "Day 2 ·
     * Lisbon"; off, the stops in a row in one place and day share one name, as before.
     */
    val stopTitles: Boolean = true,
    /** Plan items left out of a planned route (owner: "deactivate a stop … and go directly to the next"; [ClipEdits.skipped]). */
    val skipped: Set<String> = emptySet(),
    /** How the photos the journey stops for show ([PhotoLayout]); Album shows several of a place in one pause. */
    val photoLayout: PhotoLayout = PhotoLayout.Card,
    /**
     * Each place name's own time at its stop ("Time here", seconds by its key; 0 = none); the others get Auto's, from
     * how long their planned items last ([StopStays]).
     */
    val stays: Map<String, Double> = emptyMap(),
)

data class ClipStop(val time: Long, val lat: Double, val lng: Double, val name: String, val countryCode: String?, val id: String? = null)

data class ClipPhoto(val id: String, val time: Long?, val lat: Double?, val lng: Double?)

/** Everything a clip is made from, already read from the database. */
data class ClipInput(
    val title: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val zone: ZoneId,
    val points: List<GeoPoint>,
    val stops: List<ClipStop>,
    val photos: List<ClipPhoto>,
    val source: ClipSource = ClipSource.Recorded,
    val planned: PlannedReplay? = null,
    val routeLegs: List<RouteLeg> = emptyList(),
) {
    companion object {
        fun from(trip: Trip, points: List<TrackPoint>, stops: List<Stop>, media: List<Media>, zone: ZoneId = ZoneId.systemDefault()) = ClipInput(
            title = trip.title,
            startDate = trip.startDate,
            endDate = trip.endDate,
            zone = zone,
            points = points.map { GeoPoint(it.time, it.lat, it.lng, it.accuracyM) },
            stops = RouteLegs.orderedStops(trip, stops).map { ClipStop(it.arrivedAt, it.lat, it.lng, it.name, it.countryCode, it.id) },
            photos = media.filter { it.deletedAt == null && it.mime.startsWith("image/") }.map { ClipPhoto(it.id, it.takenAt, it.lat, it.lng) },
        )
    }
}

/** What the camera sees: a centre and a width, in world units (height follows from the clip's shape). */
data class Camera(val cx: Double, val cy: Double, val spanX: Double) {
    fun spanY(aspect: Double) = spanX / aspect
    fun rect(aspect: Double): WorldRect {
        val hy = spanX / aspect / 2
        return WorldRect(cx - spanX / 2, cy - hy, cx + spanX / 2, cy + hy)
    }
}

/** One day of the route: path points [fromIdx]..[toIdx] drawn during frames [fromFrame, toFrame). */
class ClipDay(
    val date: LocalDate,
    /** "Day 4 · Naxos" ([ClipLabel.text]). */
    val label: String,
    val fromIdx: Int,
    val toIdx: Int,
    val fromFrame: Int,
    val toFrame: Int,
    val lengthM: Double,
)

/** A stop drawn on the route from [frame] on (snapped onto the path at [dist] metres). */
class StopMark(val x: Double, val y: Double, val dist: Double, val frame: Int, val name: String)

/**
 * A close-up ([CloseUp]): during frames [fromFrame, toFrame) the head waits at ([x], [y]) while the camera moves in to
 * a view [span] wide (world units), holds it and pulls back out (a photo pause there falls in the middle).
 */
class StillView(val fromFrame: Int, val toFrame: Int, val x: Double, val y: Double, val span: Double)

/**
 * A photo card shown during frames [fromFrame, toFrame); [slot] alternates its side and tilt. [held]: the journey
 * pauses for it. [album]: its place among the [albumOf] photos one album pause shows together ([PhotoLayout.Album];
 * 0 of 1 otherwise).
 */
class PhotoPop(
    val id: String, val fromFrame: Int, val toFrame: Int, val slot: Int, val held: Boolean = false, val album: Int = 0, val albumOf: Int = 1,
)

/** A plan item left out of a planned route's video ([ClipOptions.skipped]): its id, its title and its place, to put it back by. */
data class SkippedStop(val id: String, val title: String, val place: String?)

/**
 * A place name shown during frames [from, to), the [index]th in the clip. [key] ([ClipDesign.labelKey]) names it for
 * the scene editor and stays the same while the trip does: "label:item:<plan item>" for a planned place (the first
 * of a run with the same name), "label:day:<date>" for a day of a recording or of saved stops. [stops]: the plan items
 * a planned place name stands for (what "Skip stop" leaves out); none on other routes.
 */
class LabelRange(val from: Int, val to: Int, val label: ClipLabel, val index: Int, val key: String, val stops: List<String> = emptyList()) {
    fun sameAs(o: LabelRange) = from == o.from && to == o.to && label == o.label && key == o.key && stops == o.stops
}

/**
 * A photo on the route, for the scene editor: [frame] is its card's first frame, or where the head reaches it when
 * it is not in the clip ([shown] false: left out by hand, or past what the clip has room for); [placeKey] is the
 * place name it belongs to (photo order goes by place); [hold] its pause in seconds. [added]: the traveller moved it to
 * this place from another one, or from none ([ClipEdits.picks]).
 */
class PhotoScene(
    val id: String, val frame: Int, val placeKey: String?, val shown: Boolean, val excluded: Boolean, val hold: Double, val added: Boolean = false,
)

class PhotoShow(val pop: PhotoPop, val alpha: Double, val scale: Double)

data class EndCard(val title: String, val dates: String?, val stats: String, val countries: String?)

/**
 * The stats line's numbers at one frame ([ClipPlan.statsAt]): the distance the head has come ([km]) and the trip's day
 * it is on ([day] of [days]).
 */
data class TripStats(val km: Double, val day: Int, val days: Int)

/** One base-map picture to fetch: a world rectangle at a pixel size, with the map's own pixel ratio (label size). */
data class LayerSpec(val rect: WorldRect, val widthPx: Int, val heightPx: Int, val pixelRatio: Float) {
    /** Picture pixels per world unit. */
    val density: Double get() = widthPx / rect.w
    val pixels: Long get() = widthPx.toLong() * heightPx
}

/** Everything the renderer needs for one frame. */
class FrameState(
    val frame: Int,
    val camera: Camera,
    /** The route is drawn through path point [headSeg], then on to the head. */
    val headSeg: Int,
    val headX: Double,
    val headY: Double,
    val bearing: Double,
    val facingLeft: Boolean,
    val vehicle: Vehicle,
    /** The place name's text ("Day 2 · Naxos"); [place] has its parts. */
    val label: String?,
    val place: ClipLabel?,
    /** Which name it is, in order, and its key for per-scene overrides ([LabelRange.key]). */
    val labelIndex: Int,
    val labelKey: String?,
    val labelAlpha: Double,
    val titleAlpha: Double,
    val endAlpha: Double,
    /** How far the camera is zoomed in past the overview (1 = overview). */
    val zoom: Double,
    val stops: List<Pair<StopMark, Double>>,
    val photos: List<PhotoShow>,
)

/**
 * The whole clip worked out in advance, frame by frame, in plain Kotlin: the shaped route in world units, the day
 * each stretch belongs to and how long it gets on screen, the vehicle per stretch, the camera, stops, photo cards
 * and the end card. Renderers only read [frame]; nothing here touches Android.
 *
 * Timing ([ClipTiming]): a short opening with the trip's title over the moving map, then the journey, each leg on
 * screen for as long as it takes by its vehicle (a planned leg: the plan's estimate, so a train beats a car over the
 * same distance and a flight is quick for its length; a recording: its moving time), long legs counting less past four
 * hours and every leg or day getting a minimum, then the camera pulls back and the end card fades in. A chosen length
 * ([ClipOptions.length], Auto by default) holds the whole clip, photo pauses and inserts included, and the journey's
 * speed follows ([pace]); with music it ends on a bar line. Without one the length follows from the trip's travel time
 * and the chosen speed, plus a pause for each held photo ([ClipOptions.photoHoldSeconds]: the head, and so the camera,
 * stand still where the photo was taken while its card shows). Place names: a recorded journey or saved stops name
 * each day ("Day 4 · Naxos"); a planned route names the place the head is nearest along the route ([LabelRange]).
 *
 * Camera ([ClipOptions.camera]): the steady camera ([SteadyCamera], one height per run, the head kept in frame by
 * moving) or the older follow camera ([FollowCamera]), from the opening view ([overview]: the region around the route,
 * or space for a far or foreign trip) and back to it; as close as [ClipOptions.zoom] asks (or a place's own zoom),
 * never closer than the map's pictures allow ([MapStyle.maxZoom]). Close-ups ([ClipOptions.closeUp], [stills]) stop
 * the head at some places while the camera moves in, holds and pulls back; their time comes from the journey, so the
 * clip keeps its length.
 */
class ClipPlan private constructor(
    val options: ClipOptions,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val legVehicle: List<Vehicle>,
    val realCum: DoubleArray,
    val days: List<ClipDay>,
    val stops: List<StopMark>,
    val photos: List<PhotoPop>,
    val endCard: EndCard,
    val title: String,
    val source: ClipSource,
    val overview: Camera,
    val drawStart: Int,
    val drawEnd: Int,
    val endCardStart: Int,
    /** The trip's travel time that sets the clip's length (effective hours, see [ClipTiming.effectiveMinutes]). */
    val travelHours: Double,
    /** Photos that sit on the route (before the cap and the editor's choices). */
    val photoCandidates: Int,
    /** The photos on the route not left out, in route order, with their pauses: with [ClipTiming.seconds], the speed chips' lengths. */
    val photoHolds: List<PhotoHold>,
    /** Every photo on the route, in route order (the scene editor's photo scenes). */
    val photoScenes: List<PhotoScene>,
    /** The place names in order. */
    val labels: List<LabelRange>,
    /** The close-ups in order (none unless [ClipOptions.closeUp] or a place asks for them). */
    val stills: List<StillView>,
    /** Full-frame inserts in order (phone videos, day cards); the journey stands still under them. */
    val inserts: List<Insert>,
    /** Every day of the clip that can open with a day card, in order (shown or not; see [ClipOptions.dayCards]). */
    val dayStarts: List<DayCard>,
    /** The music's beats in the clip, as frames (none unless the clip cuts on them, [ClipOptions.beats]). */
    val beatFrames: IntArray,
    private val cams: Array<Camera>,
    private val headSeg: IntArray,
    private val headX: DoubleArray,
    private val headY: DoubleArray,
    private val headDist: DoubleArray,
    /**
     * The km the stats line shows at each frame (metres): [headDist] without the legs that don't count (a flight unless
     * switched on, a leg switched off; [com.justdataplease.boringtravel.model.TravelDefault.countsKm]).
     */
    private val statsDist: DoubleArray,
    /** The legs that count, in metres: the stats line's and the end card's total. */
    private val countedM: Double,
    private val bearings: DoubleArray,
    private val facing: BooleanArray,
    /** The whole route with its margins (the "whole route" zoom's view; the opening and the close look from wider). */
    val routeView: Camera,
    /**
     * Whether the opening and the close look from space (a route over [SPACE_FROM_M] across, or in two countries or
     * more); otherwise from the region around the route.
     */
    val fromSpace: Boolean,
    /** Where the opening's camera move ends: the steady camera's eased dive ([DIVE_S]); the follow camera's [drawStart]. */
    val openingEnd: Int,
    /** The steady camera's runs, in order ([CameraRun]; none with [CameraMode.Follow]). */
    val cameraRuns: List<CameraRun>,
    /** How fast the journey goes, as Pace would say it: ×1's drawing time over this clip's (×1 = 1.0, ×5 = 5.0). */
    val pace: Double,
    /** The trip's days (its dates, else the days the route covers): what Auto's length goes by ([ClipLength.target]). */
    val tripDays: Int,
    /** The planned stops left out of this video ([ClipOptions.skipped]), in plan order: where they can be put back from. */
    val skippedStops: List<SkippedStop> = emptyList(),
    /** The trip's first day, when it has dates: day numbers count from it ([statsAt]). */
    val startDate: LocalDate? = null,
    /**
     * Where each place name's day begins, in order: (first frame, its date), from the place names' own dates (a
     * planned place's day; a recording's or saved stops' day), else the route's days ([statsAt]).
     */
    val dayMarks: List<Pair<Int, LocalDate>> = emptyList(),
    /** Seconds the journey stands still at each place name's stop, by its key ([StopStays]; the editor's "Time here"). */
    val stays: Map<String, Double> = emptyMap(),
    /** Seconds the clip has for the places' own photos ([PhotoPicks]; the "Media here" panel's room). */
    val photoRoom: Double = 0.0,
    /** Seconds each place's own photos take, by its key (the ones that fit; [PhotoPicks.seconds]). */
    val pickedSeconds: Map<String, Double> = emptyMap(),
    /** Each place name's tags, by its key: its plan items' ([ClipTags.byPlace]); none for a recording's or saved stops' days. */
    val tags: Map<String, List<String>> = emptyMap(),
) {
    val fps: Int get() = options.fps
    val frameCount: Int get() = cams.size
    /** The clip's length. */
    val seconds: Double get() = frameCount.toDouble() / fps
    val totalKm: Double get() = realCum.last() / 1000.0

    /** Vehicles the route uses, in order of first use. */
    val vehiclesUsed: List<Vehicle> get() = legVehicle.distinct()

    /** Seconds the full-frame inserts add to the clip. */
    val insertSeconds: Double get() = inserts.sumOf { it.frames }.toDouble() / fps

    /** Seconds the stops' own time adds ([stays]). */
    val staySeconds: Double get() = stays.values.sum()

    /** What the clip adds whatever the speed (inserts and the stops' own time): the speed chips add it to the trip's own length. */
    val addedSeconds: Double get() = insertSeconds + staySeconds

    /**
     * Where the scene [key] starts (a place name's first frame, or its day card's when one opens it): where its voice
     * line plays from. Null when the clip has no such scene.
     */
    fun sceneStart(key: String): Int? =
        inserts.firstOrNull { it.kind == InsertKind.DayCard && it.card?.opens == key }?.fromFrame ?: labels.firstOrNull { it.key == key }?.from

    /** The full-frame insert showing at frame [f], if any. */
    fun insertAt(f: Int): Insert? {
        var lo = 0
        var hi = inserts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val i = inserts[mid]
            when {
                f < i.fromFrame -> hi = mid - 1
                f >= i.toFrame -> lo = mid + 1
                else -> return i
            }
        }
        return null
    }

    /**
     * Changes only when the base map's pictures could change (the route, its camera, shape, view, sky or map; not the
     * photos): keys the fetched base map.
     */
    val geometryKey: Int by lazy {
        var h = 31 * (31 * (31 * options.shape.hashCode() + options.view.hashCode()) + options.map.hashCode()) + source.hashCode()
        // The sky band hides the farthest ground: with it off the pictures must reach the top of the frame.
        h = 31 * h + options.sky.hashCode()
        for (i in xs.indices) {
            h = 31 * h + (xs[i] * 1e7).toLong().hashCode()
            h = 31 * h + (ys[i] * 1e7).toLong().hashCode()
        }
        for (c in cams) {
            h = 31 * h + c.cx.hashCode()
            h = 31 * h + c.cy.hashCode()
            h = 31 * h + c.spanX.hashCode()
        }
        h
    }

    fun camera(f: Int): Camera = cams[f.coerceIn(0, cams.size - 1)]

    /**
     * Whether [other] moves exactly like this plan (same frames, head, camera, stops and place names, the options
     * alike but for the photo edits and the music): then only frames whose photo cards differ need drawing again.
     */
    fun sameTimeline(other: ClipPlan): Boolean = other === this || (
        frameCount == other.frameCount && drawStart == other.drawStart && drawEnd == other.drawEnd &&
            endCardStart == other.endCardStart && source == other.source && title == other.title &&
            options.copy(photoEdits = PhotoEdits(), music = null) == other.options.copy(photoEdits = PhotoEdits(), music = null) &&
            headDist.contentEquals(other.headDist) && statsDist.contentEquals(other.statsDist) && endCard == other.endCard &&
            headSeg.contentEquals(other.headSeg) && cams.contentEquals(other.cams) &&
            xs.contentEquals(other.xs) && ys.contentEquals(other.ys) && stops.size == other.stops.size &&
            labels.size == other.labels.size && labels.indices.all { labels[it].sameAs(other.labels[it]) }
        )

    /**
     * A gentle pitched overview, leaning into the camera during the journey (the steady camera leans in as it dives,
     * then holds the full tilt: [ReplayProjection.MAX_TILT] with the sky band, [ReplayProjection.MAX_TILT_NO_SKY]
     * without it). On the globe the camera tilts only as far as the street map has come in ([globeDrape]): the globe
     * from space stays round.
     */
    fun projection(f: Int, widthPx: Int = options.shape.width, heightPx: Int = options.shape.height): ReplayProjection {
        val tilt = if (options.view == ClipView.Flat) 0.0 else {
            val into = if (options.camera != CameraMode.Follow) Ease.smoothstep(f.toDouble() / openingEnd.coerceAtLeast(1))
                else Ease.smoothstep((f - drawStart + r(0.4)).toDouble() / r(1.2).coerceAtLeast(1))
            val out = 1 - Ease.smoothstep((f - drawEnd).toDouble() / r(1.3).coerceAtLeast(1))
            val top = if (options.sky) ReplayProjection.MAX_TILT else ReplayProjection.MAX_TILT_NO_SKY
            (24.0 + (top - 24.0) * min(into, out)) * (if (globe) globeDrape(f) else 1.0)
        }
        return ReplayProjection(widthPx.toDouble(), heightPx.toDouble(), tilt, sky = options.sky)
    }

    /** A Globe clip ([MapStyle.Globe], [ClipGlobe]). */
    val globe: Boolean get() = options.map == MapStyle.Globe

    /** On the globe, how much of the street map shows over the globe's own shapes in frame [f] (0 from space, 1 close in). */
    fun globeDrape(f: Int): Double = ClipGlobe.drape(camera(f), options.shape.aspect)

    fun frame(index: Int): FrameState {
        val f = index.coerceIn(0, frameCount - 1)
        val fade = r(0.4)
        val endAlpha = Ease.window(f.toDouble(), endCardStart.toDouble(), (endCardStart + r(0.5)).toDouble())
        val titleAlpha = 1 - Ease.window(f.toDouble(), (drawStart - fade).toDouble(), drawStart.toDouble())
        var label: LabelRange? = null
        var labelAlpha = 0.0
        // A day card covers the frame with its own text: no place name over it (a video keeps its place's name).
        val underCard = inserts.isNotEmpty() && insertAt(f)?.kind == InsertKind.DayCard
        for (range in labels) {
            if (underCard) break
            val from = range.from
            val to = range.to
            if (f < from || f >= to) continue
            val dip = min(5, (to - from) / 3).coerceAtLeast(1).toDouble()
            label = range
            labelAlpha = min(Ease.window(f.toDouble(), from - 1.0, from + dip), 1 - Ease.window(f.toDouble(), to - dip - 1, to - 1.0))
            break
        }
        val pop = r(0.35).toDouble()
        val shownStops = stops.filter { it.frame <= f }.map { it to Ease.outBack((f - it.frame) / pop) }
        val inF = r(0.3).toDouble()
        val outF = r(0.25).toDouble()
        val shownPhotos = photos.filter { f >= it.fromFrame && f < it.toFrame }.map {
            val pin = (f - it.fromFrame + 1) / inF
            val pout = (f - (it.toFrame - outF)) / outF
            PhotoShow(
                it,
                alpha = min(Ease.clamp01(pin * 2), 1 - Ease.clamp01(pout)),
                scale = Ease.outBack(pin) * (1 - 0.08 * Ease.clamp01(pout)),
            )
        }
        val seg = headSeg[f]
        return FrameState(
            frame = f,
            camera = cams[f],
            headSeg = seg,
            headX = headX[f],
            headY = headY[f],
            bearing = bearings[f],
            facingLeft = facing[f],
            vehicle = legVehicle[seg.coerceIn(0, legVehicle.size - 1)],
            label = label?.label?.text,
            place = label?.label,
            labelIndex = label?.index ?: -1,
            labelKey = label?.key,
            labelAlpha = labelAlpha,
            titleAlpha = titleAlpha,
            endAlpha = endAlpha,
            zoom = overview.spanX / cams[f].spanX,
            stops = shownStops,
            photos = shownPhotos,
        )
    }

    /** Real distance along the route (metres) at the head in frame [f]. */
    fun headDistance(f: Int): Double = headDist[f.coerceIn(0, frameCount - 1)]

    /**
     * The stats line's numbers at frame [f] ([TripStats]), counting up with the head: the distance it has come and the
     * day of the stretch it is on (by the trip's dates, else the days in order; day 1 before the first, the last
     * after it).
     */
    fun statsAt(f: Int): TripStats {
        val fr = f.coerceIn(0, frameCount - 1)
        return TripStats(statsDist[fr] / 1000.0, dayAt(fr), statsDays)
    }

    /** The marks [statsAt] counts days by: [dayMarks], else the route's days. */
    private val marks: List<Pair<Int, LocalDate>> by lazy { dayMarks.ifEmpty { days.map { it.fromFrame to it.date } } }

    /** The distinct dates of [marks] in order (days counted in order when the trip has no dates). */
    private val markDates: List<LocalDate> by lazy { marks.map { it.second }.distinct() }

    /** How many days the stats line counts to: the trip's, or the days the route covers. */
    private val statsDays: Int get() = tripDays.coerceAtLeast(markDates.size).coerceAtLeast(1)

    /** The trip's day at frame [f]: the last mark's date at or before it (the first mark's before them all). */
    private fun dayAt(f: Int): Int {
        if (marks.isEmpty()) return 1
        var date = marks.first().second
        for ((frame, d) in marks) if (frame <= f) date = d else break
        val byDate = startDate?.let { (date.toEpochDay() - it.toEpochDay() + 1).toInt() }?.takeIf { it in 1..statsDays }
        return (byDate ?: (markDates.indexOf(date) + 1)).coerceIn(1, statsDays)
    }

    /** The numbers at the end of the journey: the stats line's widest ([statsAt] never passes them). */
    val statsAtEnd: TripStats by lazy { TripStats(countedKm, statsDays, statsDays) }

    /** The km of the legs that count ([statsDist]): what the stats line and the end card add up to. */
    val countedKm: Double get() = countedM / 1000.0

    /** The traveller's steps on legs on foot ([WalkCycle]: in pace with the ground, still while the head waits). */
    val walk: WalkCycle by lazy { WalkCycle.of(headX, headY, DoubleArray(cams.size) { cams[it].spanX }, options.shape.aspect, fps) }

    /**
     * The base-map pictures to fetch for a [widthPx]-wide clip, in drawing order: one for the whole clip at the
     * overview's sharpness, then closer ones from the coarsest to the sharpest (so the sharpest that covers a frame
     * is drawn on top).
     *
     * The closer pictures are chosen to make the most frames sharp for the pixels: candidates are runs of frames at
     * full and softer sharpness ([DETAIL_LEVELS]), each at most [maxSide] px a side; the planner keeps taking the
     * candidate that adds the most sharpness per pixel until [budgetPx] pixels (about 60 MB of bitmaps, the overview
     * included) or [maxLayers] pictures (each one network snapshot) are used. See [sharpness].
     *
     * Close-ups ([stills]) get their own pictures on top ([stillSpecs]), so the place the camera holds on is sharp
     * whatever else the trip needs; they are fetched with the rest, before the preview plays and before the export.
     *
     * On the globe only the frames close enough for the street map ([globeDrape]) need pictures: the first covers just
     * those, as sharp as the widest of them wants, and a clip that never comes that close needs none.
     */
    fun layerSpecs(
        widthPx: Int = options.shape.width,
        maxSide: Int = 2048,
        budgetPx: Long = LAYER_BUDGET_PX,
        maxLayers: Int = MAX_LAYERS,
    ): List<LayerSpec> {
        val aspect = options.shape.aspect
        val heightPx = (widthPx / aspect).roundToInt()
        val dpScale = min(widthPx, heightPx) / DESIGN_SHORT_DP
        // Perspective reveals farther ground at the top. Fetch its inverse-projected extent, not the flat crop.
        val projections = Array(frameCount) { projection(it, widthPx, heightPx) }
        val rects = Array(frameCount) { projections[it].groundRect(cams[it]) }
        val mapped = BooleanArray(frameCount) { !globe || globeDrape(it) > 0.0 }
        val firstMapped = mapped.indexOfFirst { it }
        if (firstMapped < 0) return emptyList()
        var all = rects[firstMapped]
        var baseSpan = 0.0
        for (f in 0 until frameCount) if (mapped[f]) { all = all.union(rects[f]); baseSpan = max(baseSpan, cams[f].spanX) }
        if (!globe) baseSpan = overview.spanX
        val d0 = widthPx / baseSpan
        val first = spec(all.padded(0.04), d0, d0, maxSide, dpScale)
        // What each frame wants (DETAIL_SHARPNESS of its pixels across the middle) and how sharp the overview makes it.
        val want = DoubleArray(frameCount) { widthPx / cams[it].spanX * projections[it].centreScale * DETAIL_SHARPNESS }
        val best = DoubleArray(frameCount) { ln(min(1.0, first.density / want[it])) }
        val counts = BooleanArray(frameCount) { mapped[it] && baseSpan / cams[it].spanX >= DETAIL_FROM_ZOOM }
        val held = stillSpecs(rects, want, first, maxSide, dpScale)
        for (s in held) for (g in 0 until frameCount) {
            if (!counts[g] || LayerBlend.inside(s.rect, rects[g]) < 0.999) continue
            best[g] = max(best[g], ln(min(1.0, s.density / want[g])))
        }

        class Candidate(val spec: LayerSpec, val frames: IntArray, val gain: DoubleArray)
        val candidates = ArrayList<Candidate>()
        for (level in DETAIL_LEVELS) {
            var cur: WorldRect? = null
            var curWant = 0.0
            var count = 0
            fun close() {
                val c = cur
                if (c != null && count >= 3) {
                    val s = spec(c, curWant * level, curWant / DETAIL_SHARPNESS, maxSide, dpScale)
                    // Every frame (not only this run's) whose whole view this picture covers gains from it.
                    val frames = ArrayList<Int>()
                    val gains = ArrayList<Double>()
                    for (g in 0 until frameCount) {
                        if (!counts[g] || LayerBlend.inside(s.rect, rects[g]) < 0.999) continue
                        val q = ln(min(1.0, s.density / want[g]))
                        if (q > best[g] + 1e-9) { frames += g; gains += q }
                    }
                    if (frames.isNotEmpty()) candidates += Candidate(s, frames.toIntArray(), gains.toDoubleArray())
                }
                cur = null; curWant = 0.0; count = 0
            }
            for (f in 0 until frameCount) {
                if (!counts[f] || want[f] * level <= first.density * 1.25) { close(); continue }
                val r = rects[f].padded(LAYER_PAD)
                val c = cur
                if (c == null) { cur = r; curWant = want[f]; count = 1; continue }
                val u = c.union(r)
                val nw = max(curWant, want[f])
                if (max(u.w, u.h) * nw * level > maxSide) {
                    close(); cur = r; curWant = want[f]; count = 1
                } else {
                    cur = u; curWant = nw; count++
                }
            }
            close()
        }

        val chosen = ArrayList<LayerSpec>()
        var used = first.pixels
        val minGain = fps * MIN_LAYER_GAIN_S
        while (chosen.size + 1 < maxLayers) {
            var pick: Candidate? = null
            var pickScore = 0.0
            for (c in candidates) {
                if (used + c.spec.pixels > budgetPx) continue
                var gain = 0.0
                for (i in c.frames.indices) gain += max(0.0, c.gain[i] - best[c.frames[i]])
                val score = gain / c.spec.pixels
                if (gain >= minGain && score > pickScore) { pick = c; pickScore = score }
            }
            val c = pick ?: break
            candidates.remove(c)
            chosen += c.spec
            used += c.spec.pixels
            for (i in c.frames.indices) best[c.frames[i]] = max(best[c.frames[i]], c.gain[i])
        }
        return listOf(first) + (chosen + held).sortedBy { it.density }
    }

    /**
     * The close-ups' own pictures: the held view at full detail sharpness, then (as [STILL_BUDGET_PX] allows) a
     * softer one over the views up to [STILL_MID_REACH] times wider, so the place sharpens in steps as the camera
     * moves in instead of all at once. A view the overview picture already draws sharp gets none.
     */
    private fun stillSpecs(rects: Array<WorldRect>, want: DoubleArray, first: LayerSpec, maxSide: Int, dpScale: Double): List<LayerSpec> {
        val held = ArrayList<LayerSpec>()
        val mids = ArrayList<LayerSpec>()
        fun picture(group: List<Int>, level: Double): LayerSpec? {
            if (group.isEmpty() || group.all { first.density >= want[it] * 0.95 }) return null
            var rect = rects[group[0]]
            var w = 0.0
            for (g in group) { rect = rect.union(rects[g]); w = max(w, want[g]) }
            return spec(rect.padded(LAYER_PAD), w * level, w / DETAIL_SHARPNESS, maxSide, dpScale)
        }
        for (s in stills) {
            val frames = (s.fromFrame until min(s.toFrame, frameCount)).toList()
            if (frames.isEmpty()) continue
            val closest = frames.minOf { cams[it].spanX }
            picture(frames.filter { cams[it].spanX <= closest * 1.06 }, 1.0)?.let { held += it }
            picture(frames.filter { cams[it].spanX > closest * 1.06 && cams[it].spanX <= closest * STILL_MID_REACH }, STILL_MID_LEVEL)?.let { mids += it }
        }
        val out = ArrayList<LayerSpec>()
        var used = 0L
        for (s in held + mids) if (used + s.pixels <= STILL_BUDGET_PX) { out += s; used += s.pixels }
        return out
    }

    /**
     * How sharp each frame's map is with [specs] (from [layerSpecs] at [widthPx]): the sharpest picture that covers
     * the whole view, as a share of [DETAIL_SHARPNESS] of the frame's own pixels across the middle of the picture
     * (1 = as planned, 0.5 = twice as soft; a tilted view draws the middle smaller, so it wants fewer pixels). On the
     * globe a frame too far out for the street map is drawn from the globe's own shapes: 1.
     */
    fun sharpness(specs: List<LayerSpec>, widthPx: Int = options.shape.width): DoubleArray {
        val heightPx = (widthPx / options.shape.aspect).roundToInt()
        val mapped = BooleanArray(frameCount) { !globe || globeDrape(it) > 0.0 }
        val baseSpan = if (!globe) overview.spanX else (0 until frameCount).filter { mapped[it] }.maxOfOrNull { cams[it].spanX } ?: overview.spanX
        return DoubleArray(frameCount) { f ->
            if (!mapped[f]) return@DoubleArray 1.0
            if (specs.isEmpty()) return@DoubleArray 0.0
            val projection = projection(f, widthPx, heightPx)
            val view = projection.groundRect(cams[f])
            val want = widthPx / cams[f].spanX * projection.centreScale * DETAIL_SHARPNESS
            val zoom = baseSpan / cams[f].spanX
            specs.withIndex().filter { (i, s) -> i == 0 || (zoom >= DETAIL_FROM_ZOOM && LayerBlend.inside(s.rect, view) >= 0.999) }
                .maxOf { min(1.0, it.value.density / want) }
        }
    }

    private fun spec(rect: WorldRect, wanted: Double, frameDensity: Double, maxSide: Int, dpScale: Double): LayerSpec {
        var d = wanted
        val longest = max(rect.w, rect.h) * d
        if (longest > maxSide) d *= maxSide / longest
        val ratio = (dpScale * d / frameDensity).coerceIn(1.0, 4.0)
        return LayerSpec(rect, max(1, (rect.w * d).roundToInt()), max(1, (rect.h * d).roundToInt()), ratio.toFloat())
    }

    private fun r(seconds: Double) = (seconds * fps).roundToInt()

    companion object {
        /** Detail pictures are fetched where the camera is zoomed in this far past the overview. */
        const val DETAIL_FROM_ZOOM = 1.5
        /** Detail pictures are made a little softer than the frame (80%) so each covers more of the route. */
        const val DETAIL_SHARPNESS = 0.8
        /**
         * Closer pictures come at full detail sharpness or softer (more ground per pixel): a long trip can then keep
         * every place readable within the budget instead of a few places sharp and the rest a blur.
         */
        val DETAIL_LEVELS = listOf(1.0, 0.7, 0.5, 0.35)
        /** All base-map pictures together: 30 Mpx ≈ 60 MB, kept as RGB_565 (see [BaseMaps]). */
        const val LAYER_BUDGET_PX = 30_000_000L
        /** At most this many pictures (each one network snapshot of up to 25 s). */
        const val MAX_LAYERS = 12
        /** A closer picture must improve at least this much screen time by a factor of e (≈ 2.7× sharper). */
        const val MIN_LAYER_GAIN_S = 0.15
        /** Room around each frame's view in a closer picture, so it shows fully (see [LayerBlend.inside]). */
        const val LAYER_PAD = 0.04
        /** The close-ups' own pictures together (on top of [LAYER_BUDGET_PX]): 20 Mpx ≈ 40 MB. */
        const val STILL_BUDGET_PX = 20_000_000L
        /** A close-up's softer picture covers the views up to this many times wider than the held one, at this sharpness. */
        const val STILL_MID_REACH = 2.8
        const val STILL_MID_LEVEL = 0.4

        fun build(input: ClipInput, options: ClipOptions, locale: Locale = Locale.getDefault()): ClipPlan? {
            val (route, skipped) = skipping(input, options.skipped)
            return Builder(route, options, locale, skipped).build()
        }

        /** Photos this close to a skipped stop (and nearer it than to any stop kept) go with it. */
        internal const val SKIP_PHOTO_M = 5_000.0

        /**
         * [input] without the planned stops [ids] (owner, 2026-11-09: "deactivate a stop … and go directly to the next"):
         * the route runs straight from the stop before to the one after, and the photos taken at a skipped stop go with
         * it. Only a planned route skips, and only while two stops stay; the stops left out come back with it.
         */
        internal fun skipping(input: ClipInput, ids: Set<String>): Pair<ClipInput, List<SkippedStop>> {
            val planned = input.planned
            if (ids.isEmpty() || input.source != ClipSource.Planned || planned == null) return input to emptyList()
            val all = planned.waypoints
            val gone = all.filter { it.itemId in ids }
            val keep = all.filter { it.itemId !in ids }
            if (gone.isEmpty()) return input to emptyList()
            val replay = planned.copy(waypoints = keep)
            val times = replay.timeline(input.zone)
            if (keep.size < 2 || TrackShaping.lengthM(times) < MIN_ROUTE_M) return input to emptyList()
            val stopById = input.stops.associateBy { it.id }
            // The kept stops in the new order (their times are only the route's order).
            val stops = keep.mapIndexed { i, w ->
                stopById[w.itemId]?.copy(time = times[i].time) ?: ClipStop(times[i].time, w.lat, w.lng, w.title, null, w.itemId)
            }
            val photos = input.photos.filter { p ->
                val lat = p.lat
                val lng = p.lng
                if (lat == null || lng == null) return@filter true
                val nearest = all.minBy { Geo.distanceM(lat, lng, it.lat, it.lng) }
                nearest.itemId !in ids || Geo.distanceM(lat, lng, nearest.lat, nearest.lng) > SKIP_PHOTO_M
            }
            return input.copy(planned = replay, stops = stops, photos = photos) to gone.map { SkippedStop(it.itemId, it.title, it.place) }
        }

        /** Spare room kept around the route in a framed view, as a share of the view on each side. */
        internal const val PAD_X = 0.12
        internal const val PAD_Y = 0.15
        /** The camera never shows less than this much ground on its short side (walking pace: a few streets). */
        internal const val MIN_VIEW_M = 1_200.0
        /** Fastest zoom in or out anywhere in the clip, in ln(scale) per frame at 30 fps (about 9% a frame). */
        internal const val MAX_ZOOM_RATE = 0.09
        /** The renderer's design unit: the frame's short side is 360 dp, and the map is drawn at one dp per map point. */
        internal const val DESIGN_SHORT_DP = 360.0
        /** Map points across one tile at zoom 0: a zoom-z map is 256·2^z points around the world. */
        internal const val MAP_TILE_PX = 256.0
        /** Flights at least this long, and planned or saved-stop connections at least [JUMP_STRAIGHT_M], show both ends. */
        internal const val JUMP_PLANE_M = 100_000.0
        internal const val JUMP_STRAIGHT_M = 300_000.0
        internal const val MIN_ROUTE_M = 50.0
        internal const val TINY_DAY_M = 20.0
        internal const val ARC_MIN_M = 30_000.0
        internal const val ARC_STEPS = 24
        internal const val MAX_POINTS = 3_000
        /** A recorded leg slower than this was the phone resting between points (a night, a café), not travel. */
        internal const val STILL_KMH = 1.0
        /**
         * A close-up holds its view this long, with [CLOSE_UP_EASE_S] more for the rounded start and end of the move
         * in and out, and lasts at least [CLOSE_UP_MIN_S] (a map that can't go closer still stops on the place).
         */
        internal const val CLOSE_UP_HOLD_S = 1.0
        internal const val CLOSE_UP_EASE_S = 0.5
        internal const val CLOSE_UP_MIN_S = 1.5
        /** Close-ups take at most this share of the journey's frames, and a clip has at most [MAX_CLOSE_UPS]. */
        internal const val CLOSE_UP_SHARE = 0.4
        internal const val MAX_CLOSE_UPS = 8
        /** A day card's length. */
        const val DAY_CARD_S = 1.5
        /** Places this close (seconds of journey) wait as one close-up. */
        internal const val STILL_MERGE_S = 0.5
        /** A place's own zoom lasts at least this long on screen, or the camera's smoothing would hide it. */
        internal const val ZOOM_MIN_S = 2.0
        /**
         * The marker faces along the route over this share of the screen's width (as ground metres along the route:
         * half behind the head, all of it ahead), and never less than [HEADING_MIN_M].
         */
        internal const val HEADING_REACH = 0.035
        internal const val HEADING_MIN_M = 15.0
        /** Wobbles of the way ahead under this many degrees leave the marker as it is (GPS zigzags, a kerb). */
        internal const val HEADING_DEADBAND_DEG = 6.0
        /** Once turning, it closes this share of what is left each frame (within its vehicle's [turnCap])... */
        internal const val HEADING_GAIN = 0.35
        /** ...until it has been within [HEADING_SETTLE_DEG] for [HEADING_CALM_S]. */
        internal const val HEADING_SETTLE_DEG = 1.0
        internal const val HEADING_CALM_S = 0.3
        /** A turn of more than [HEADING_UTURN_DEG] (doubling back) pivots in about [HEADING_PIVOT_S], whatever the vehicle. */
        internal const val HEADING_UTURN_DEG = 140.0
        internal const val HEADING_PIVOT_S = 0.25
        /** At a wait, the marker turns to the way on over its last this many seconds. */
        internal const val HEADING_PRETURN_S = 0.3

        /** How fast each vehicle's marker turns, in degrees a second: a walker on the spot, a plane or a ferry slowly. */
        internal fun turnCap(v: Vehicle): Double = when (v) {
            Vehicle.Walk -> 300.0
            Vehicle.Bike -> 240.0
            Vehicle.Car, Vehicle.Auto -> 180.0
            Vehicle.Train -> 120.0
            Vehicle.Ferry, Vehicle.Plane -> 90.0
        }

        /** The opening and the close look from space at a route more than this far across (or in two countries). */
        const val SPACE_FROM_M = 500_000.0
        /** Otherwise from the region: this many times the whole route's view, around it. */
        const val REGION_SPAN = 2.5
        /** "From space" on a flat map: this many times the whole route's view (a continent), kept on the map. */
        const val SPACE_SPAN = 5.0
        /** The widest a flat map's view gets: this share of the world's (Web Mercator's square) map. */
        internal const val WORLD_FILL = 0.98
        /** The steady camera's dive from the opening view ends by this many seconds (or as the journey starts, if later)... */
        const val DIVE_S = 2.0
        /** ...and at most this long after the journey starts. */
        internal const val DIVE_AFTER_S = 0.5
        /** The steady camera's pull-back at the end takes this long (at most half the close). */
        internal const val PULL_S = 1.0
        /** The close (pull-back and end card) never gets shorter than this when it moves onto a bar line. */
        internal const val OUTRO_MIN_S = 1.5
        /** A chosen length gives the journey at least this share of its own time, however many inserts. */
        internal const val MIN_JOURNEY_SHARE = 0.35
        /** Beats measured this far past a chosen length to find the bar line nearest it. */
        internal const val BAR_REACH_S = 10.0
        /**
         * Steady camera heights ([CameraRun]). What a stretch (a day on one vehicle) would like on its own ([runWidth]):
         * about [RUN_LOOK_S] of its travel across the view (at the Normal zoom; see [ClipZoom.look]) at its average pace,
         * wide enough that its fastest frame moves at most [RUN_STEP_MAX] of the view (closer zooms allow more:
         * × look^-[RUN_STEP_ZOOM], 7.3% at Very close; see [SteadyCamera.STEP_LIMIT]). [calmRuns] then keeps few of
         * those heights (owner, 2026-11-08, on the Argentina video with a height per day and vehicle: "it does many zoom
         * in zoom out, it's tiring and confusing"): a change costs [CALM_CHANGE_S]; a height holds at least [RUN_MIN_S]
         * (at most [RUN_MIN_SHARE] of a short journey), in the air (a flight, a ground hop over [LIFT_LEG_KM]) at least
         * [RUN_SEEN_S] (at most [RUN_SEEN_SHARE] of it), so a 15 s clip still lifts for its flight.
         */
        internal const val RUN_LOOK_S = 3.0
        internal const val RUN_STEP_MAX = 0.045
        internal const val RUN_STEP_ZOOM = 0.4
        internal const val RUN_MIN_S = 6.0
        internal const val RUN_SEEN_S = 2.0
        internal const val RUN_MIN_SHARE = 0.14
        internal const val RUN_SEEN_SHARE = 0.15
        /** What a change of the steady camera's height costs in [calmRuns]: seconds of a stretch one step (e) off its height. */
        internal const val CALM_CHANGE_S = 20.0
        /** A hop on the ground longer than this lifts the steady camera like a flight; the Earth's girth for the sums. */
        internal const val LIFT_LEG_KM = 250.0
        internal const val EARTH_KM = 40_075.0
        /** Auto photos ([PhotoMode.Auto], see Builder.autoPauses): photos within this much journey of each other at one place are one place. */
        internal const val AUTO_GROUP_S = 1.0
        /** Photo pauses take at most this share of a chosen length (Auto's too), so the journey keeps the rest. */
        internal const val HELD_SHARE = 0.35

        /** The shortest turn from bearing [from] to [to], in degrees (−180..180). */
        internal fun turnBy(from: Double, to: Double): Double = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
        /** How far the globe turns under the opening title before the camera comes down (degrees of longitude). */
        internal const val INTRO_TURN_DEG = 24.0
        /** On the globe, legs longer than this (radians, 1°) bulge off their straight Mercator line: bounds sample them. */
        internal val BULGE_RAD = Math.toRadians(1.0)
        internal val BULGE_AT = doubleArrayOf(0.25, 0.5, 0.75)

        /** Opening and closing lengths in frames for a clip of [seconds]. */
        fun introFrames(seconds: Double, fps: Int) = (ClipTiming.introSeconds(seconds) * fps).roundToInt()
        fun outroFrames(seconds: Double, fps: Int) = (ClipTiming.outroSeconds(seconds) * fps).roundToInt()

        /**
         * Splits [frames] among days by [weights] with a floor of half an equal share each; the result adds up to
         * [frames] exactly and every day gets at least one frame when there is room.
         */
        fun shareFrames(weights: List<Double>, frames: Int): IntArray {
            val n = weights.size
            if (n == 0) return IntArray(0)
            val sum = weights.sum()
            val floor = 0.5 / n
            val raw = weights.map { if (sum > 0) max(it / sum, floor) else 1.0 / n }
            val total = raw.sum()
            val out = IntArray(n)
            var acc = 0.0
            var prev = 0
            for (i in 0 until n) {
                acc += raw[i] / total
                val edge = if (i == n - 1) frames else (acc * frames).roundToInt().coerceIn(prev, frames)
                out[i] = edge - prev
                prev = edge
            }
            if (frames >= n) {
                // Lend a frame to any empty day from the longest one.
                for (i in 0 until n) if (out[i] == 0) {
                    val j = out.indices.maxBy { out[it] }
                    out[j]--; out[i]++
                }
            }
            return out
        }

        /**
         * Intentional destination pairs each get up to half a second before travel time shares the remaining time.
         * The floor shrinks for busy itineraries. When pairs outnumber drawing frames, some must have zero frames;
         * the chosen video duration/frame rate is never extended. A longer clip can give those pairs more time.
         */
        internal fun shareLegFrames(weights: List<Double>, frames: Int, fps: Int): IntArray {
            if (weights.isEmpty()) return IntArray(0)
            if (frames <= 0) return IntArray(weights.size)
            val floor = min((fps * 0.5).roundToInt().coerceAtLeast(1), frames / weights.size)
            val remaining = frames - floor * weights.size
            val total = weights.sum()
            var accumulated = 0.0
            var previous = 0
            return IntArray(weights.size) { i ->
                accumulated += if (total > 0.0) weights[i] / total else 1.0 / weights.size
                val edge = if (i == weights.lastIndex) remaining else (accumulated * remaining).roundToInt().coerceIn(previous, remaining)
                (floor + edge - previous).also { previous = edge }
            }
        }

        /** Box average with the window clamped at the ends. */
        internal fun boxSmooth(a: DoubleArray, radius: Int): DoubleArray {
            val n = a.size
            if (radius <= 0 || n < 2) return a.copyOf()
            val pre = DoubleArray(n + 1)
            for (i in 0 until n) pre[i + 1] = pre[i] + a[i]
            return DoubleArray(n) { i ->
                val lo = max(0, i - radius)
                val hi = min(n - 1, i + radius)
                (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
            }
        }
    }

    private class Builder(val input: ClipInput, val o: ClipOptions, val locale: Locale, val skipped: List<SkippedStop> = emptyList()) {
        val fps = o.fps
        val aspect = o.shape.aspect
        /**
         * Globe ([ClipGlobe]): the head flies along great circles, the route runs on past 180° (world columns outside
         * 0–1), the opening and the close of a far or foreign trip look down from space.
         */
        val globe = o.map == MapStyle.Globe

        /** The point [t] of the way along path leg [seg]: straight on a flat map, along the great circle on the globe. */
        fun pointOn(px: DoubleArray, py: DoubleArray, seg: Int, t: Double): Pair<Double, Double> =
            if (globe) ClipGlobe.along(px[seg], py[seg], px[seg + 1], py[seg + 1], t)
            else (px[seg] + (px[seg + 1] - px[seg]) * t) to (py[seg] + (py[seg + 1] - py[seg]) * t)

        /** The route's bounds over path points [from] until [to]; on the globe the great circles' bulges count too. */
        fun bounds(px: DoubleArray, py: DoubleArray, from: Int = 0, to: Int = px.size): WorldRect {
            var rect = WorldRect.around(px, py, from, to)
            if (!globe) return rect
            for (i in from until min(to, px.size) - 1) {
                if (ClipGlobe.angle(Mercator.lat(py[i]), Mercator.lng(px[i]), Mercator.lat(py[i + 1]), Mercator.lng(px[i + 1])) < BULGE_RAD) continue
                for (t in BULGE_AT) { val (x, y) = pointOn(px, py, i, t); rect = rect.union(x, y) }
            }
            return rect
        }

        /** Where the globe's opening and close look from: space, over the middle of the route ([ClipGlobe.spaceSpan]). */
        fun spaceView(px: DoubleArray, py: DoubleArray, realCum: DoubleArray): Camera {
            val n = 64
            val lats = DoubleArray(n + 1)
            val lngs = DoubleArray(n + 1)
            val total = realCum.last()
            for (k in 0..n) {
                val (x, y) = at(realCum, px, py, total * k / n, ::pointOn)
                lats[k] = Mercator.lat(y)
                lngs[k] = Mercator.lng(x)
            }
            val rect = bounds(px, py)
            val (lat, lng) = ClipGlobe.mean(lats, lngs) ?: (lats[0] to lngs[0])
            return Camera(ClipGlobe.near(Mercator.x(lng), rect.cx), Mercator.y(lat), ClipGlobe.spaceSpan(lat, aspect))
        }
        /** The clip's length, set in build() from the trip's travel time (or [ClipOptions.seconds]). */
        var total = 0
        var totalS = 0.0
        /** Whether the clip has a chosen length ([ClipOptions.length]) that everything must fit in. */
        var lengthSet = false
        fun r(seconds: Double) = (seconds * fps).roundToInt()

        // The path, filled by buildPath().
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ts = ArrayList<Long>()
        val legV = ArrayList<Vehicle>()
        val legDay = ArrayList<LocalDate>()
        val legReal = ArrayList<Double>()
        /** Whether each path leg counts in the km ([TravelDefault.countsKm]). */
        val legCount = ArrayList<Boolean>()
        /** Travel minutes per path leg (see [originalMinutes]). */
        val legMin = ArrayList<Double>()

        fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(input.zone).toLocalDate()

        fun build(): ClipPlan? {
            val pts = routePoints() ?: return null
            // Recorded routes can infer speed. A plan/stop pair uses its explicit, stable saved choice; an unchosen
            // planned leg travels as the plan shows it (TravelDefault: on foot between the items of one place, else Car
            // unless its name says plane, train or ferry). Past a skipped stop the leg into the next one counts: the
            // one its "How you got here" shows (the editor's Route tool).
            // The leg into each point's place, when the route has legs to choose for (planned places, saved stops).
            var legAt: (Int) -> RouteLeg? = { null }
            val vehicles = if (input.source == ClipSource.Recorded) VehicleGuess.legs(pts, o.vehicle) else {
                val planned = input.source == ClipSource.Planned
                val source = if (planned) RouteLegSource.Planned else RouteLegSource.Stops
                val waypoints = input.planned?.waypoints.orEmpty()
                val ids = if (planned) waypoints.map { it.itemId } else input.stops.mapNotNull { it.id }
                val legInto = input.routeLegs.filter { it.key.source == source }.associateBy { it.key.toId }
                if (ids.size == pts.size) legAt = { index -> legInto[ids[index + 1]] }
                List(pts.size - 1) { index ->
                    if (ids.size != pts.size) Vehicle.Auto
                    else {
                        val leg = legInto[ids[index + 1]]
                        leg?.vehicle ?: if (!planned) Vehicle.Auto else TravelDefault.between(
                            waypoints[index + 1].title, leg?.samePlace ?: RouteLegs.samePlace(waypoints[index], waypoints[index + 1]),
                            Geo.distanceM(waypoints[index].lat, waypoints[index].lng, waypoints[index + 1].lat, waypoints[index + 1].lng),
                            waypoints[index + 1].travel,
                        )
                    }
                }
            }
            // Owner, 2026-11-11: the km leave a flight out unless its leg says to count it ("Count in km").
            val counts = List(vehicles.size) { TravelDefault.countsKm(vehicles[it], legAt(it)?.countKm) }
            val pDay = pts.map { localDate(it.time) }
            // Every planned waypoint is intentional, including a collinear stop or a return visit.
            val kept = if (input.source != ClipSource.Recorded) pts.indices.toList() else thin(pts, vehicles, pDay)
            buildPath(pts, vehicles, counts, pDay, kept, originalMinutes(pts, vehicles))
            val n = xs.size
            val px = xs.toDoubleArray()
            val py = ys.toDoubleArray()
            val times = DoubleArray(n) { ts[it].toDouble() }
            val realCum = DoubleArray(n)
            for (i in 1 until n) realCum[i] = realCum[i - 1] + legReal[i - 1]
            val countCum = DoubleArray(n)
            for (i in 1 until n) countCum[i] = countCum[i - 1] + if (legCount[i - 1]) legReal[i - 1] else 0.0
            // Screen time follows travel time: each leg's effective minutes, accumulated along the route.
            val legEff = effectiveMinutes(legMin.toDoubleArray())
            val travelHours = legEff.sum() / 60.0
            val weights = if (legEff.sum() > 0) legEff else legReal.toDoubleArray()
            val effCum = DoubleArray(n)
            for (i in 1 until n) effCum[i] = effCum[i - 1] + weights[i - 1]
            val tripDays = if (input.startDate != null && input.endDate != null) {
                (input.endDate.toEpochDay() - input.startDate.toEpochDay() + 1).toInt().coerceAtLeast(1)
            } else pDay.distinct().size
            // The clip's length: a set length (tests, tools), the chosen length (everything fits inside it and the
            // journey's speed follows), or the trip's travel time at a speed (Pace: photo pauses and inserts add to it).
            val target = if (o.seconds != null) null else o.length?.target(tripDays, o.shape)
            val baseS = o.seconds?.toDouble() ?: target ?: ClipTiming.baseSeconds(travelHours, o.speed)
            totalS = baseS
            lengthSet = target != null
            // With music, a chosen length ends on the bar line nearest it.
            val barBeats = if (target == null) null else o.beats?.frames(fps, ClipTiming.frames(baseS, fps) + r(BAR_REACH_S))
            // A length within the free minute stays within it (else a free clip of "60 s" would need Pro).
            val barMost = if (target != null && target <= ClipTiming.FREE_MAX_SECONDS) ClipTiming.frames(ClipTiming.FREE_MAX_SECONDS, fps) else Int.MAX_VALUE
            val baseTotal = ClipTiming.frames(baseS, fps).let { if (barBeats != null) ClipTiming.onBar(it, barBeats, barMost) else it }

            // Days, merged when tiny, with their screen time.
            val groups = dayGroups()
            val intro = introFrames(baseS, fps).coerceAtMost(baseTotal / 4)
            val outro = outroFrames(baseS, fps).coerceAtMost(baseTotal / 3)
            // Travel frames: the drawing without the photo pauses ("k" below counts them from 0). Close-ups take some
            // of them for the head to wait at their places, so it moves over the rest ("moving" frames).
            val fullTravel = (baseTotal - outro).coerceAtLeast(intro + 1) - intro
            val dayLabels = groups.map { dayLabel(it, px, py) }
            val way = Way(groups, dayLabels, effCum, realCum, weights)

            // Photos on the route, where the head first reaches each (travel frame k), in route order.
            val edits = o.photoEdits
            val onRoute = if (o.photos) photosOnRoute(px, py, times, realCum) else emptyList()
            // The whole route; the opening and the close look at the region around it, or from space at a far or
            // foreign trip.
            val routeView = fit(bounds(px, py))
            // The follow camera keeps the views it always had: the whole route, or space on the globe.
            val follows = o.camera == CameraMode.Follow
            // A chosen pull-back ([ClipOptions.zoomOut]) sets the view whatever the camera.
            val fromSpace = when (o.zoomOut) {
                ZoomOut.Auto -> if (follows) globe else fromSpace(px, py)
                ZoomOut.Space -> true
                else -> false
            }
            val overview = when (o.zoomOut) {
                ZoomOut.Auto -> if (follows) (if (globe) spaceView(px, py, realCum) else routeView) else openingView(routeView, fromSpace, px, py, realCum)
                ZoomOut.Space, ZoomOut.Region -> openingView(routeView, fromSpace, px, py, realCum)
                ZoomOut.Route -> routeView
            }

            var arranged = arrange(way, fullTravel, onRoute, baseS, overview, px, py, times, realCum, outro)
            if (target != null) {
                // A chosen length holds everything: the photo pauses, videos and day cards come out of the journey's
                // own time (never below MIN_JOURNEY_SHARE of it), so the whole clip lands on the length.
                val least = (fullTravel * MIN_JOURNEY_SHARE).roundToInt().coerceIn(min(fullTravel, r(1.0)), fullTravel)
                repeat(2) {
                    var want = (fullTravel - arranged.stopFrames).coerceAtLeast(least)
                    // More inserts than the length holds: with music the clip still ends on a bar line, the next one.
                    val over = intro + want + arranged.stopFrames + outro
                    if (barBeats != null && over > baseTotal) want += ClipTiming.barFrom(over, barBeats) - over
                    if (want != arranged.travelFrames) arranged = arrange(way, want, onRoute, baseS, overview, px, py, times, realCum, outro)
                }
            }
            val journey = arranged.journey
            val photoPlan = arranged.photos
            val movingFrames = journey.frames
            val candidates = photoPlan.candidates
            val placeById = photoPlan.placeById
            val pool = photoPlan.pool
            val stillAt = arranged.stillAt
            val blocks = arranged.blocks
            val dayStarts = arranged.dayStarts

            // Cuts on the beat: each stop starts on a beat and its parts last whole beats; a day change without a card
            // is a cut too. The journey between stops takes up the difference (BeatFit), so the length stays.
            val outroFrames = (baseTotal - intro - fullTravel).coerceAtLeast(0)
            val beatTimes = o.beats?.frames(fps, baseTotal + blocks.values.sumOf { b -> b.sumOf { it.frames } } + r(60.0)) ?: DoubleArray(0)
            var runs: IntArray? = null
            var drawStart = intro
            if (beatTimes.size >= 2) {
                for ((_, fromK) in dayStarts) if (fromK in 1 until movingFrames) blocks.getOrPut(fromK - 1) { ArrayList() }
                val cuts = blocks.map { (k, items) -> BeatFit.Cut(k, items.map { BeatFit.Part(it.frames, beatRound(it.kind)) }) }
                BeatFit.fit(intro, movingFrames, cuts, beatTimes, minIntro = r(1.0))?.let { fit ->
                    blocks.values.forEachIndexed { b, items -> items.forEachIndexed { i, item -> item.frames = fit.parts[b][i] } }
                    runs = fit.runs
                    drawStart = fit.drawStart
                }
            }

            // Output frames: the opening, each moving frame once with what it stops for right after it, the close.
            var laid = layFrames(drawStart, movingFrames, blocks, stillAt, outroFrames, runs)
            if (barBeats != null && beatTimes.size >= 2) {
                // A chosen length with music ends on a bar line: where the stops' beats moved the journey's end, the
                // close takes up the difference to the nearest one (never shorter than OUTRO_MIN_S).
                val end = laid.drawEnd + outroFrames
                var bar = ClipTiming.onBar(end, beatTimes, barMost)
                if (bar - laid.drawEnd < r(OUTRO_MIN_S)) bar = ClipTiming.barFrom(laid.drawEnd + r(OUTRO_MIN_S), beatTimes)
                if (bar != end) laid = layFrames(drawStart, movingFrames, blocks, stillAt, bar - laid.drawEnd, runs)
            }
            val src = laid.src
            val outOf = laid.outOf
            val heldPops = laid.heldPops
            val stillFrames = laid.stills
            val inserts = laid.inserts
            val drawEnd = laid.drawEnd
            total = src.size
            val dayFrom = IntArray(groups.size) { outOf[journey.dayFromK[it]] }
            val dayTo = IntArray(groups.size) { outOf[journey.dayToK[it]] }

            val headSeg = IntArray(total)
            val hx = DoubleArray(total)
            val hy = DoubleArray(total)
            val hd = DoubleArray(total)
            val sd = DoubleArray(total)
            for (g in 0 until total) {
                val k = src[g]
                val eff = when {
                    k < 0 -> 0.0
                    k >= movingFrames -> effCum[n - 1]
                    else -> lerpAt(journey.travelEff, k)
                }
                val (seg, t) = locate(effCum, eff)
                headSeg[g] = seg
                val (x, y) = pointOn(px, py, seg, t)
                hx[g] = x
                hy[g] = y
                hd[g] = realCum[seg] + (realCum[seg + 1] - realCum[seg]) * t
                sd[g] = countCum[seg] + (countCum[seg + 1] - countCum[seg]) * t
            }
            val stills = stillFrames.map { StillView(it.fromFrame, it.toFrame, hx[it.fromFrame], hy[it.fromFrame], it.span) }

            // Camera: the steady camera (one height per run, the head kept in frame by moving: SteadyCamera) or the
            // older follow camera (FollowCamera), each as close as the clip's zoom or the place's own asks, in to each
            // close-up's place, from the opening view and back to it.
            val placeAt = Array(total) { g -> journey.placeOf(src[g].toInt().coerceIn(0, movingFrames - 1)) }
            val zoomAt = Array(total) { g -> zoomOf(placeAt[g]) }
            val beatFrames = beatTimes.map { it.roundToInt() }.filter { it in 0 until total }.distinct().toIntArray()
            val endCardStart: Int
            val cams: Array<Camera>
            var openingEnd = drawStart
            var cameraRuns: List<CameraRun> = emptyList()
            if (o.camera == CameraMode.Follow) {
                // A place's own zoom shows even on a short stay: its stretch grows to at least ZOOM_MIN_S around its
                // middle (the camera fills shorter dips, see FollowCamera.HOLD_S).
                for (span in journey.spans) {
                    val own = o.cameraEdits.zoom[span.key] ?: continue
                    val from = outOf[span.fromK.coerceIn(0, movingFrames)]
                    val to = if (span.last) drawEnd else outOf[span.toK.coerceIn(0, movingFrames)]
                    val least = r(ZOOM_MIN_S)
                    if (to - from >= least || drawEnd - drawStart < least) continue
                    val a = ((from + to) / 2 - least / 2).coerceIn(drawStart, drawEnd - least)
                    for (g in a until a + least) zoomAt[g] = own
                }
                val camera = follow(hx, hy, headSeg, zoomAt, drawStart, drawEnd, overview, stillFrames, px, py)
                // The pull-back to the end card: long enough to stay under the zoom speed limit where the outro allows
                // (the camera starts pulling back earlier when it must), the end card fading in over its last quarter.
                val zoomOut = ceil((ln(overview.spanX) - camera.followLn[drawEnd - 1]) / camera.rate * 1.25).toInt()
                    .coerceIn(r(0.8), r(3.0)).coerceAtMost(((total - drawEnd) * 0.6).toInt()).coerceAtLeast(1)
                val cardAt = (drawEnd + (zoomOut * 0.75).roundToInt() + r(0.1)).coerceAtMost(total - r(0.8)).coerceAtLeast(drawEnd)
                // On the beat nearest it, when the clip cuts on beats.
                endCardStart = if (beatTimes.isEmpty()) cardAt else BeatFit.nearestWithin(cardAt, beatTimes, drawEnd, total - r(0.8))
                cams = camera.cameras(overviewFrom = drawEnd + zoomOut)
                // From space the globe turns into place under the title (west to east, as the Earth turns), then the
                // camera dives.
                if (globe && fromSpace) for (f in 0 until drawStart) {
                    val left = 1 - Ease.outCubic(f.toDouble() / drawStart)
                    cams[f] = Camera(cams[f].cx + left * INTRO_TURN_DEG / 360.0, cams[f].cy, cams[f].spanX)
                }
            } else {
                // The day each frame belongs to: a planned place's own day, else the leg's.
                val spanDate = journey.spans.associate { it.key to it.date }
                val camera = steady(
                    hx, hy, headSeg, zoomAt, placeAt, { g -> dayKey(headSeg[g], placeAt[g], spanDate) }, drawStart, drawEnd, overview,
                    stillFrames, px, py,
                    waits = BooleanArray(total) { g -> g > drawStart && g < drawEnd && src[g] == src[g - 1] },
                    hidden = inserts.map { it.fromFrame until it.toFrame },
                )
                cameraRuns = camera.runs
                // The opening: the title over the moving map, the dive done by DIVE_S (or as the journey starts, if later).
                openingEnd = (drawStart + r(DIVE_AFTER_S)).coerceAtMost(max(drawStart, r(DIVE_S))).coerceIn(1, max(1, drawEnd))
                // The close: one eased pull-back to the opening view, the end card fading in over it.
                val pull = min(r(PULL_S), ((total - drawEnd) * 0.5).toInt()).coerceAtLeast(1)
                val cardAt = (drawEnd + (pull * 0.6).roundToInt()).coerceAtMost(total - r(0.8)).coerceAtLeast(drawEnd)
                endCardStart = if (beatTimes.isEmpty()) cardAt else BeatFit.nearestWithin(cardAt, beatTimes, drawEnd, total - r(0.8))
                cams = camera.cameras(openingEnd, drawEnd, min(total, drawEnd + pull), if (globe && fromSpace) INTRO_TURN_DEG / 360.0 else 0.0)
            }

            // Heading of the marker (owner, 2026-11-08: "when moving should have correct direction"): the way along the
            // route itself over a short stretch of it by distance (a share of the screen, HEADING_REACH), so GPS zigzags
            // and tiny segments don't flip the traveller round; small wobbles (HEADING_DEADBAND_DEG) are left alone, a
            // corner is turned through at the vehicle's pace (turnCap), a U-turn pivots quickly. While the journey waits
            // the traveller keeps facing as it arrived, turning to the way on in the wait's last HEADING_PRETURN_S.
            val bearings = DoubleArray(total)
            val facing = BooleanArray(total)
            var lastB = 90.0
            var left = false
            for (i in 0 until n - 1) {
                val dx = px[i + 1] - px[i]
                val dy = py[i + 1] - py[i]
                if (hypot(dx, dy) > 0) { lastB = bearingOf(dx, dy); left = dx < 0; break }
            }
            val tiny = overview.spanX * 1e-9
            val endM = realCum[n - 1]
            fun way(from: Double, to: Double): Double? {
                val (ax, ay) = at(realCum, px, py, from.coerceIn(0.0, endM), ::pointOn)
                val (bx, by) = at(realCum, px, py, to.coerceIn(0.0, endM), ::pointOn)
                return if (hypot(bx - ax, by - ay) > tiny) bearingOf(bx - ax, by - ay) else null
            }
            // The first frame at or after each one where the head moves on (total: it never does).
            val moveAt = IntArray(total)
            var next = total
            for (g in total - 1 downTo 0) {
                if (g < total - 1 && hd[g + 1] > hd[g] + 1e-9) next = g
                moveAt[g] = next
            }
            var shown = Double.NaN
            var engaged = false
            var calm = 0
            var pivot = 0.0
            for (g in 0 until total) {
                val reach = max(cams[g].spanX * HEADING_REACH / Mercator.unitsPerMeter(hy[g]), HEADING_MIN_M)
                val d = hd[g]
                val moving = (g > 0 && hd[g] > hd[g - 1] + 1e-9) || moveAt[g] == g
                val target = if (moving) way(d - reach * 0.5, d + reach) ?: way(d - reach, d) else {
                    // Waiting: it keeps facing as it arrived; before the journey and in a wait's last moments, the way on.
                    val leaving = moveAt[g] < total && (moveAt[g] - g < r(HEADING_PRETURN_S) || d <= 1e-9)
                    if (leaving) way(d - reach * 0.5, d + reach) ?: way(d, d + reach) else null
                }
                if (shown.isNaN()) shown = target ?: lastB
                else if (target != null) {
                    val err = turnBy(shown, target)
                    if (abs(err) > HEADING_DEADBAND_DEG) engaged = true
                    if (abs(err) > HEADING_UTURN_DEG) pivot = max(pivot, abs(err) / max(1.0, HEADING_PIVOT_S * fps))
                    if (engaged) {
                        val limit = max(turnCap(legV.getOrElse(headSeg[g]) { Vehicle.Auto }) / fps, pivot)
                        val step = (err * HEADING_GAIN).coerceIn(-limit, limit)
                        shown = (shown + step + 360.0) % 360.0
                        val rest = abs(err - step)
                        if (rest < HEADING_UTURN_DEG / 10) pivot = 0.0
                        calm = if (rest < HEADING_SETTLE_DEG) calm + 1 else 0
                        if (calm >= r(HEADING_CALM_S)) { engaged = false; calm = 0 }
                    }
                }
                bearings[g] = shown
                val east = sin(Math.toRadians(shown))
                if (east < -0.3) left = true else if (east > 0.3) left = false
                facing[g] = left
            }

            // Stops, photos, labels, end card.
            val stopMarks = stopMarks(px, py, times, realCum, hd, drawEnd)
            // Photos without a pause pop in over the moving route (in the traveller's order within each place); with
            // Auto photos only the ones whose own pause is 0. All cards are numbered in time order, which sets their
            // sides and tilt.
            val pops = if (!o.photos) emptyList() else {
                val busy = (heldPops.map { it.fromFrame until it.toFrame } + inserts.map { it.fromFrame until it.toFrame }).sortedBy { it.first }
                val over = if (o.photoAuto) pool.filter { edits.holds[it.id] == 0.0 } else pool.filter { holdOf(it.id) <= 0.0 }
                // A place's own photos without a pause show over the moving map, asked for like the rest of its picks.
                val asked = (if (o.photoAuto) over.mapTo(HashSet()) { it.id } else edits.included) + photoPlan.pickedAt.keys
                val moving = movingPops(over, asked, hd, drawStart, drawEnd, endCardStart, busy, beatFrames)
                val movingIds = ClipOrder.arrange(moving.map { it.id }, moving.map { placeById[it.id] }, photoPlan.order)
                (heldPops + moving.mapIndexed { i, p -> PhotoPop(movingIds[i], p.fromFrame, p.toFrame, 0) })
                    .sortedBy { it.fromFrame }
                    .mapIndexed { i, p -> PhotoPop(p.id, p.fromFrame, p.toFrame, i, p.held, p.album, p.albumOf) }
            }
            val popById = pops.associateBy { it.id }
            // A photo on an album page pauses as long as the page.
            val albumHold = photoPlan.held.flatMap { h -> h.also.map { it to holdOf(h.id) } }.toMap()
            val photoScenes = candidates.map { c ->
                val pop = popById[c.id]
                PhotoScene(
                    c.id, pop?.fromFrame ?: outOf[c.k.coerceIn(0, movingFrames)], placeById[c.id], pop != null, c.id in photoPlan.leftOut,
                    albumHold[c.id] ?: holdOf(c.id), added = c.id in photoPlan.moved,
                )
            }
            val days = groups.mapIndexed { i, g ->
                ClipDay(g.date, dayLabels[i].text, g.fromIdx, g.toIdx, dayFrom[i], dayTo[i], realCum[g.toIdx] - realCum[g.fromIdx])
            }
            val ranges = ArrayList<LabelRange>()
            for (span in journey.spans) {
                val from = outOf[span.fromK.coerceIn(0, movingFrames)]
                val to = if (span.last) max(endCardStart, from) else outOf[span.toK.coerceIn(0, movingFrames)]
                if (to > from) ranges += LabelRange(from, to, span.label, ranges.size, span.key, span.stops)
            }
            // With music, place names come in on the beat (a day change already is a cut on one).
            val labelRanges = onBeats(ranges, beatFrames)
            val endCard = EndCard(
                title = input.title.ifBlank { "Trip" },
                dates = if (input.startDate != null && input.endDate != null) Dates.range(input.startDate, input.endDate) else null,
                stats = if (input.source == ClipSource.Planned) {
                    "${input.planned?.waypoints?.size ?: n} places · ${ClipText.km(countCum[n - 1] / 1000.0, locale)} approx."
                } else ClipText.stats(tripDays, countCum[n - 1] / 1000.0, locale),
                countries = ClipText.countries(input.stops.sortedBy { it.time }.mapNotNull { it.countryCode?.takeIf { c -> c.isNotBlank() } }, locale),
            )
            return ClipPlan(
                options = o,
                xs = px, ys = py, legVehicle = legV.toList(), realCum = realCum, days = days, stops = stopMarks,
                photos = pops, endCard = endCard, title = endCard.title, source = input.source, overview = overview,
                drawStart = drawStart, drawEnd = drawEnd, endCardStart = endCardStart, travelHours = travelHours,
                photoCandidates = onRoute.size, photoHolds = photoPlan.holds, photoScenes = photoScenes, labels = labelRanges,
                stills = stills, inserts = inserts, dayStarts = dayStarts.map { it.first }, beatFrames = beatFrames, cams = cams,
                headSeg = headSeg, headX = hx, headY = hy, headDist = hd, statsDist = sd, countedM = countCum[n - 1],
                bearings = bearings, facing = facing,
                routeView = routeView, fromSpace = fromSpace, openingEnd = openingEnd, cameraRuns = cameraRuns,
                pace = ClipTiming.drawSeconds(travelHours, 1) / (movingFrames.toDouble() / fps).coerceAtLeast(1e-6),
                tripDays = tripDays, skippedStops = skipped, startDate = input.startDate,
                dayMarks = journey.spans.mapNotNull { s -> s.date?.let { outOf[s.fromK.coerceIn(0, movingFrames)] to it } },
                stays = arranged.stays, photoRoom = photoPlan.room, pickedSeconds = photoPlan.pickedSeconds,
                tags = ClipTags.byPlace(labelRanges.map { it.key to it.stops }, input.planned?.waypoints.orEmpty()),
            )
        }

        /**
         * A place name over travel frames [fromK, toK) (the [last] one runs on until the end card); [arriveK] is where
         * the head reaches the place (a planned place's arrival, the last of a run of them; a day's end), where a
         * phone video after the place plays; [date] its day (a planned place without one in the trip's dates: null).
         * [card]: what its day card says (the day and the place; a stop's own title is its name, not the day's);
         * [stops]: the plan items it stands for.
         */
        inner class Span(
            val fromK: Int, val toK: Int, val label: ClipLabel, val key: String, val last: Boolean, val arriveK: Int = toK - 1,
            val date: LocalDate? = null, val card: ClipLabel = label, val stops: List<String> = emptyList(),
        )

        /** The days that can open with a card: the first place name of each new day, with its moving frame. */
        fun dayStarts(spans: List<Span>): List<Pair<DayCard, Int>> {
            val out = ArrayList<Pair<DayCard, Int>>()
            val seen = HashSet<LocalDate>()
            for (span in spans) {
                val date = span.date ?: continue
                // One card a day, at its first place (a plan that comes back to a day later gets none there).
                if (!seen.add(date)) continue
                val key = ClipDesign.dayCardKey(date)
                out += DayCard(key, date, span.card.day ?: Dates.dayMonth(date), span.card.place, span.key) to span.fromK
            }
            return out
        }

        /** A photo on the route: metres along it, when taken, and the travel frame where the head first reaches it. */
        inner class Candidate(val id: String, val dist: Double, val time: Long, val k: Int)

        /** What every layout of the journey starts from: the days and their names, the route's effective minutes and metres. */
        inner class Way(
            val groups: List<Group>, val dayLabels: List<ClipLabel>, val effCum: DoubleArray, val realCum: DoubleArray, val weights: DoubleArray,
        )

        /**
         * The journey laid out over [frames] moving frames: each day's frames ([dayFromK], [dayToK]), where the head is
         * in each frame (effective minutes [travelEff] and metres [travelDist] along the route) and the place names.
         */
        inner class Journey(
            val frames: Int, val dayFromK: IntArray, val dayToK: IntArray, val travelEff: DoubleArray, val travelDist: DoubleArray,
            val spans: List<Span>,
        ) {
            /** The key of the place name shown at moving frame [k]. */
            fun placeOf(k: Int): String? = spans.firstOrNull { k >= it.fromK && (k < it.toK || it.last) }?.key
        }

        fun layOut(way: Way, frames: Int): Journey {
            val groups = way.groups
            val effCum = way.effCum
            val realCum = way.realCum
            val legShares = if (input.source == ClipSource.Recorded) null else shareLegFrames(way.weights.toList(), frames, fps)
            val shares = if (legShares == null) shareFrames(groups.map { effCum[it.toIdx] - effCum[it.fromIdx] }, frames)
                else groups.map { group -> (group.fromIdx until group.toIdx).sumOf { legShares[it] } }.toIntArray()
            val dayFromK = IntArray(groups.size)
            val dayToK = IntArray(groups.size)
            var at = 0
            for (i in groups.indices) { dayFromK[i] = at; at += shares[i]; dayToK[i] = at }

            // Head position per moving frame.
            val travelEff = DoubleArray(frames)
            var d = 0
            var leg = 0
            var legFrom = 0
            var legTo = legShares?.firstOrNull() ?: 0
            for (k in 0 until frames) {
                while (d < groups.size - 1 && k >= dayToK[d]) d++
                travelEff[k] = if (legShares != null) {
                    while (leg < legShares.lastIndex && k >= legTo) {
                        legFrom = legTo
                        legTo += legShares[++leg]
                    }
                    // Sample inside the leg: sampling its exact end would select the NEXT vehicle in locate(). The last
                    // leg has none after it and ends on the last place (the close starts from there, without a jump).
                    val len = legShares[leg].coerceAtLeast(1)
                    val u = if (leg == legShares.lastIndex) (k - legFrom + 1.0) / len else (k - legFrom + 0.5) / len
                    effCum[leg] + (effCum[leg + 1] - effCum[leg]) * Ease.travel(u)
                } else {
                    val g = groups[d]
                    val len = (dayToK[d] - dayFromK[d]).coerceAtLeast(1)
                    val u = (k - dayFromK[d] + 1).toDouble() / len
                    effCum[g.fromIdx] + (effCum[g.toIdx] - effCum[g.fromIdx]) * Ease.travel(u)
                }
            }
            val travelDist = DoubleArray(frames) { k ->
                val (seg, t) = locate(effCum, travelEff[k])
                realCum[seg] + (realCum[seg + 1] - realCum[seg]) * t
            }

            // Place names over the moving frames, with the keys the scene editor uses (photo order goes by place).
            val spans = tidy(
                (if (input.source == ClipSource.Planned && legShares != null) placeSpans(legShares, frames) else emptyList())
                    .ifEmpty { daySpans(groups, way.dayLabels, dayFromK, dayToK) },
            )
            return Journey(frames, dayFromK, dayToK, travelEff, travelDist, spans)
        }

        /**
         * The journey laid out over [travelFrames] travel frames ([arrange]) and what it stops for after each moving
         * frame ([blocks], see [Hold]), with the close-ups' waits ([stillAt]) and the days that can open with a card.
         */
        inner class Arranged(
            val travelFrames: Int, val journey: Journey, val photos: PhotoPlan, val stillAt: Map<Int, Pair<Int, Double>>,
            val blocks: java.util.TreeMap<Int, MutableList<Hold>>, val dayStarts: List<Pair<DayCard, Int>>,
            val stays: Map<String, Double>,
        ) {
            /**
             * Frames the journey stands still for on top of its own: photo pauses and the still map around them, the
             * time at stops, phone videos, day cards ([HoldKind.adds]).
             */
            val stopFrames: Int get() = blocks.values.sumOf { b -> b.filter { it.kind.adds }.sumOf { it.frames } }
        }

        /**
         * The journey over [travelFrames] travel frames with what it stops for: the photos over it and their pauses, the
         * close-ups (the journey laid out again over the frames they leave), then after each moving frame a close-up's
         * wait with the photo pauses in its middle, the photo pauses, the phone videos after a place (where the head
         * reaches it) and the day cards (right before the first place name of each day, after the last day's videos).
         */
        fun arrange(
            way: Way, travelFrames: Int, onRoute: List<Triple<String, Double, Long>>, baseS: Double, overview: Camera,
            px: DoubleArray, py: DoubleArray, times: DoubleArray, realCum: DoubleArray, outro: Int,
        ): Arranged {
            var journey = layOut(way, travelFrames)
            var photoPlan = photosOver(journey, onRoute, baseS, ::holdOf)
            // Close-ups: chosen on this first timeline, then the journey is laid out again over the frames they leave.
            val chosenStills = if (!o.closeUp.on && o.cameraEdits.closeUp.values.none { it.on }) emptyList() else
                chooseStills(way, journey, photoPlan, onRoute, overview, px, py, times, realCum, travelFrames, outro)
            val carved = chosenStills.sumOf { it.carved }
            if (carved > 0) {
                journey = layOut(way, travelFrames - carved)
                photoPlan = photosOver(journey, onRoute, baseS, ::holdOf)
            }
            val movingFrames = journey.frames
            // Each close-up waits at the moving frame that reaches its place (two at one frame wait as one).
            val stillAt = HashMap<Int, Pair<Int, Double>>()
            for (s in chosenStills) {
                val k = firstFrameAt(journey.travelDist, s.dist, movingFrames - 1)
                val was = stillAt[k]
                stillAt[k] = if (was == null) s.carved to s.span else (was.first + s.carved) to min(was.second, s.span)
            }
            val blocks = java.util.TreeMap<Int, MutableList<Hold>>()
            fun add(afterK: Int, item: Hold) {
                if (item.frames > 0) blocks.getOrPut(afterK.coerceIn(-1, movingFrames - 1)) { ArrayList() } += item
            }
            for ((k, wait) in stillAt) {
                val before = wait.first / 2
                add(k, Hold(HoldKind.StillIn, before))
                add(k, Hold(HoldKind.StillOut, wait.first - before))
            }
            for (h in photoPlan.held) add(h.k, Hold(HoldKind.Photo, h.frames, h.id, also = h.also))
            // The time at each stop (owner, 2026-11-12: "a single event in a day last too little"): the journey stands
            // still where it reaches the place, its own "Time here" or Auto from how long its items last.
            val hours = input.planned?.waypoints.orEmpty().associate { it.itemId to it.hours }
            val stays = StopStays.plan(journey.spans.associate { it.key to it.stops }, hours, o.stays, baseS)
            for (span in journey.spans) stays[span.key]?.let { add(span.arriveK, Hold(HoldKind.Stay, r(it))) }
            for (v in o.videos.take(ClipVideo.MAX_VIDEOS)) {
                val span = journey.spans.firstOrNull { it.key == v.sceneKey } ?: continue
                add(span.arriveK, Hold(HoldKind.Video, r(v.seconds).coerceAtLeast(1), v.id, v.sceneKey))
            }
            val dayStarts = dayStarts(journey.spans)
            for ((card, fromK) in dayStarts) {
                if (o.dayCards.shows(card.key)) add(fromK - 1, Hold(HoldKind.DayCard, r(DAY_CARD_S), key = card.key, card = card))
            }
            for (items in blocks.values) items.sortBy { it.kind.ordinal }
            // The still map before the first photo of a stop and after its last (ClipOptions.photoGapSeconds): the
            // journey stops, then the photo comes; it goes, then the journey moves on.
            val gap = r(gapS)
            if (gap > 0) for (items in blocks.values) {
                val first = items.indexOfFirst { it.kind == HoldKind.Photo }
                if (first < 0) continue
                items.add(items.indexOfLast { it.kind == HoldKind.Photo } + 1, Hold(HoldKind.Gap, gap))
                items.add(first, Hold(HoldKind.Gap, gap))
            }
            return Arranged(travelFrames, journey, photoPlan, stillAt, blocks, dayStarts, stays)
        }

        /** Seconds of still map before and after each photo pause ([ClipOptions.photoGapSeconds]). */
        val gapS: Double = if (o.photos) o.photoGapSeconds.coerceIn(0.0, ClipDesign.MAX_GAP_S) else 0.0

        /** What a photo pause of [pauseS] seconds costs the clip: the pause and the still map around it (none without a pause). */
        fun withGaps(pauseS: Double): Double = if (pauseS > 0.0) pauseS + 2 * gapS else 0.0

        /** The photos Auto shows ([autoPauses]); filled by [photosOver]. */
        val autoShown = HashSet<String>()

        /** The places' own photos ([ClipEdits.picks]) in this clip; filled by [photosOver]. */
        val pickedNow = HashSet<String>()

        /**
         * Seconds the journey pauses for photo [id]: its own pause from the scene editor, else the style's pause (with
         * Auto photos only for the photos it shows, [autoShown], or asked for), within [ClipDesign.MAX_HOLD_S]. 0: the
         * photo pops in over the moving route, or with Auto isn't shown.
         */
        fun holdOf(id: String): Double {
            if (!o.photos) return 0.0
            val own = o.photoEdits.holds[id]
            val holdS = o.photoHoldSeconds.coerceAtLeast(0.0)
            val style = if (!o.photoAuto || id in autoShown || id in o.photoEdits.included || id in pickedNow) holdS else 0.0
            return (own ?: style).coerceIn(0.0, max(holdS, ClipDesign.MAX_HOLD_S))
        }

        /**
         * The photos Auto shows ([PhotoMode.Auto]; owner, 2026-11-09: "pause 3s the video the image appears and
         * disappears when there is movement"): one per place (photos at the same place name within [AUTO_GROUP_S] of
         * journey of each other are one place; the middle one shows), spread evenly over the route, as many as their
         * pauses of [ClipOptions.photoHoldSeconds] (and the still map around each, [withGaps]) fit in [HELD_SHARE] of a
         * clip of [clipS] seconds after the traveller's own stops ([otherS]: phone videos and day cards come first), at
         * most one per 4 s ([ClipTiming.holdCap]), after the photos asked for ([asked]) and the places' own photos
         * ([pickedS] seconds in [pickedPauses] pauses; those places get none from Auto). With the Album layout the other
         * photos of each place join its pause ([photosOver]).
         */
        fun autoPauses(
            pool: List<Candidate>, placeById: Map<String, String?>, clipS: Double, asked: Int, otherS: Double, pickedS: Double = 0.0,
            pickedPauses: Int = 0,
        ): Set<String> {
            val holdS = o.photoHoldSeconds
            if (holdS <= 0.0) return emptySet()
            val places = placeRuns(pool, placeById)
            val long = if (pickedPauses > 0) 0 else oneLongPause(clipS, otherS)
            val most = min(ClipTiming.holdCap(clipS) - pickedPauses, max(((clipS * HELD_SHARE - otherS - pickedS) / withGaps(holdS)).toInt(), long)) - asked
            return pickEvenly(places.map { it[it.size / 2] }, most).map { it.id }.toSet()
        }

        /**
         * 1 when a single pause (with its still map) longer than [HELD_SHARE] allows still leaves the journey
         * [MIN_JOURNEY_SHARE] of a clip of [clipS] seconds after [otherS] (the Photo pause slider's long pauses: the
         * clip shows at least one photo), else 0.
         */
        fun oneLongPause(clipS: Double, otherS: Double): Int =
            if (o.photoHoldSeconds > 0.0 && withGaps(o.photoHoldSeconds) + otherS <= clipS * (1 - MIN_JOURNEY_SHARE)) 1 else 0

        /** [pool] (in route order) as places: photos at the same place name within [AUTO_GROUP_S] of journey of each other. */
        fun placeRuns(pool: List<Candidate>, placeById: Map<String, String?>): List<List<Candidate>> {
            val places = ArrayList<MutableList<Candidate>>()
            for (c in pool) {
                val g = places.lastOrNull()
                val last = g?.lastOrNull()
                if (g != null && last != null && placeById[c.id] == placeById[last.id] && c.k - last.k <= r(AUTO_GROUP_S)) g += c
                else places += mutableListOf(c)
            }
            return places
        }

        /**
         * Whether the clip opens (and closes) from space: a route more than [SPACE_FROM_M] across, or one that crosses a
         * border (stops in two countries or more). Otherwise it opens over the region around the route.
         */
        fun fromSpace(px: DoubleArray, py: DoubleArray): Boolean {
            val r = bounds(px, py)
            val across = Geo.distanceM(Mercator.lat(r.y0), Mercator.lng(r.x0), Mercator.lat(r.y1), Mercator.lng(r.x1))
            val countries = input.stops.mapNotNull { it.countryCode?.trim()?.uppercase()?.takeIf { c -> c.isNotEmpty() } }.distinct().size
            return across > SPACE_FROM_M || countries >= 2
        }

        /**
         * Where the clip opens and closes ([ClipPlan.overview]), from the whole route's view [route]: from space on the
         * globe ([spaceView]) or, on a flat map, [SPACE_SPAN] times the route's view (kept on the world's map); otherwise
         * the region, [REGION_SPAN] times the route's view around it.
         */
        fun openingView(route: Camera, fromSpace: Boolean, px: DoubleArray, py: DoubleArray, realCum: DoubleArray): Camera = when {
            fromSpace && globe -> spaceView(px, py, realCum)
            globe -> Camera(route.cx, route.cy, min(route.spanX * REGION_SPAN, spaceView(px, py, realCum).spanX))
            else -> onWorld(route, if (fromSpace) SPACE_SPAN else REGION_SPAN)
        }

        /**
         * [route]'s view [factor] times wider, kept on the flat world's map (Web Mercator's square: no wider than
         * [WORLD_FILL] of it, slid back onto it), but never narrower than [route] itself.
         */
        fun onWorld(route: Camera, factor: Double): Camera {
            val most = WORLD_FILL * min(1.0, aspect)
            val span = max(route.spanX, min(route.spanX * factor, most))
            if (span > most) return Camera(route.cx, route.cy, span)
            val hw = span / 2
            val hh = span / aspect / 2
            return Camera(route.cx.coerceIn(hw, 1 - hw), route.cy.coerceIn(hh, 1 - hh), span)
        }

        /**
         * The photos on the route over journey [j]: where the head first reaches each, in route order, the ones not
         * left out ([pool]) with their pauses and the still map around them ([holds]), and the [held] ones: id, the
         * moving frame that reaches it, its pause in frames and the photos on its album page ([HeldPhoto]). The places'
         * own photos ([ClipEdits.picks]): each picked photo's place ([pickedAt]; [moved] when the traveller brought it
         * from another place or none), the photos a place's list leaves out or that were left out by hand ([leftOut]),
         * the seconds the clip has for them ([room]) and what each place's take ([pickedSeconds]). [order]: the photo
         * order per place, a place's own list first.
         */
        inner class PhotoPlan(
            val candidates: List<Candidate>, val placeById: Map<String, String?>, val pool: List<Candidate>, val holds: List<PhotoHold>,
            val held: List<HeldPhoto>, val pickedAt: Map<String, String> = emptyMap(), val moved: Set<String> = emptySet(),
            val leftOut: Set<String> = emptySet(), val room: Double = 0.0, val pickedSeconds: Map<String, Double> = emptyMap(),
            val order: Map<String, List<String>> = emptyMap(),
        )

        /** Every photo of the trip on this phone, by id (a place's list may bring one that sits nowhere on the route). */
        val photoById: Map<String, ClipPhoto> by lazy { input.photos.associateBy { it.id } }

        fun photosOver(j: Journey, onRoute: List<Triple<String, Double, Long>>, baseS: Double, holdOf: (String) -> Double): PhotoPlan {
            val edits = o.photoEdits
            val natural = onRoute.map { (id, dist, time) -> Candidate(id, dist, time, firstFrameAt(j.travelDist, dist, j.frames - 1)) }
            val home = natural.associate { it.id to j.placeOf(it.k) }
            // The places' own photos (the "Media here" panel): a place in this clip shows its list, together where the
            // journey reaches its first photo (else where it arrives), in the list's order; a photo of another place,
            // or of none, moves there. A photo in two lists stays in the first.
            val spanByKey = j.spans.associateBy { it.key }
            // A list whose photos are all gone (deleted, or a journal replaced) no longer keeps the place's others out.
            val picks = edits.picks.filter { (key, ids) -> key in spanByKey && (ids.isEmpty() || ids.any { it in photoById }) }
            val pickedAt = LinkedHashMap<String, String>()
            for ((key, ids) in picks) for (id in ids) if (id !in pickedAt && id in photoById) pickedAt[id] = key
            val moved = pickedAt.filter { (id, key) -> home[id] != key }.keys
            val anchor = picks.keys.associateWith { key ->
                val span = spanByKey.getValue(key)
                natural.filter { home[it.id] == key && it.id !in moved }.minOfOrNull { it.k }
                    ?: span.arriveK.coerceIn(span.fromK, max(span.fromK, span.toK - 1)).coerceIn(0, max(0, j.frames - 1))
            }
            fun at(id: String, time: Long, key: String): Candidate {
                val k = anchor.getValue(key)
                return Candidate(id, j.travelDist[k], time, k)
            }
            val fromNowhere = pickedAt.filterKeys { id -> id !in home }.map { (id, key) -> at(id, photoById[id]?.time ?: 0L, key) }
            val candidates = (natural.map { c -> pickedAt[c.id]?.let { at(c.id, c.time, it) } ?: c } + fromNowhere)
                .sortedWith(compareBy({ it.k }, { it.time }))
            val placeById = candidates.associate { it.id to (pickedAt[it.id] ?: home[it.id]) }
            // Left out: by hand, or not on its place's own list.
            val leftOut = candidates.filter { c -> c.id !in pickedAt && (placeById[c.id] in picks || c.id in edits.excluded) }.mapTo(HashSet()) { it.id }
            val pool = candidates.filter { it.id !in leftOut }
            pickedNow.clear()
            pickedNow += pickedAt.keys
            // The phone videos and day cards come first, then the places' own photos, then Auto's.
            val videoS = o.videos.take(ClipVideo.MAX_VIDEOS).filter { v -> j.spans.any { it.key == v.sceneKey } }.sumOf { it.seconds }
            val cardS = dayStarts(j.spans).count { (card, _) -> o.dayCards.shows(card.key) } * DAY_CARD_S
            val otherS = videoS + cardS
            // A set length gives the pauses their share of it; one long Auto pause may take more (oneLongPause). The
            // places' own photos have the same share, less what the videos and day cards take, as Auto's.
            val room = if (!lengthSet) ClipTiming.MAX_SECONDS - baseS
                else max(baseS * HELD_SHARE, if (o.photoAuto && oneLongPause(baseS, otherS) == 1) withGaps(o.photoHoldSeconds) else 0.0)
            val long = if (o.photoHoldSeconds > 0.0 && oneLongPause(baseS, otherS) == 1) withGaps(o.photoHoldSeconds) else 0.0
            val pickRoom = if (!lengthSet) (room - otherS).coerceAtLeast(0.0) else max(baseS * HELD_SHARE - otherS, long).coerceAtLeast(0.0)

            // Each place's own photos along the route, page by page, as far as they fit; past that nothing more.
            val album = o.photoLayout == PhotoLayout.Album
            val pickedHeld = ArrayList<HeldPhoto>()
            val pickedHolds = ArrayList<PhotoHold>()
            val pickedSeconds = LinkedHashMap<String, Double>()
            var used = 0.0
            var full = false
            for (key in picks.keys.sortedBy { anchor.getValue(it) }) {
                val paused = picks.getValue(key).filter { pickedAt[it] == key && holdOf(it) > 0.0 }
                val holdsHere = paused.map(holdOf)
                val n = if (full) 0 else PhotoPicks.fitting(holdsHere, gapS, o.photoLayout, pickRoom - used)
                if (n < paused.size) full = true
                val taken = PhotoPicks.seconds(holdsHere.take(n), gapS, o.photoLayout)
                used += taken
                pickedSeconds[key] = taken
                val pages = if (album) paused.take(n).chunked(PhotoLayout.ALBUM_MAX) else paused.take(n).map { listOf(it) }
                pages.forEachIndexed { i, page ->
                    pickedHeld += HeldPhoto(page.first(), anchor.getValue(key), r(holdOf(page.first())), page.drop(1))
                    // The speed chips count the place's still map once, with its first pause.
                    pickedHolds += PhotoHold(holdOf(page.first()) + if (i == 0) 2 * gapS else 0.0, forced = true)
                }
            }

            // The other places: the photos asked for, then Auto's in what is left.
            val rest = pool.filter { it.id !in pickedAt }
            if (o.photoAuto) {
                autoShown.clear()
                autoShown += autoPauses(rest, placeById, baseS, rest.count { it.id in edits.included }, otherS, used, pickedHeld.size)
            }
            // Each pause counts with the still map before and after it (the speed chips count the same).
            val restHolds = rest.map { PhotoHold(withGaps(holdOf(it.id)), it.id in edits.included || (o.photoAuto && holdOf(it.id) > 0.0)) }
            // Photo pauses: the journey stops where each held photo was taken, for its own pause. The ones asked for
            // come first, then others spread evenly (ClipTiming.chooseHeld, as the speed chips count them). The
            // traveller's order within a place swaps photos between slots, each keeping its pause.
            val order = edits.order + picks
            val heldSlots = ClipTiming.chooseHeld(restHolds, baseS, room - used).map { rest[it] }
            val heldIds = ClipOrder.arrange(heldSlots.map { it.id }, heldSlots.map { placeById[it.id] }, order)
            val restHeld = heldSlots.mapIndexed { i, slot -> HeldPhoto(heldIds[i], slot.k, r(holdOf(heldIds[i]))) }
            val held = (pickedHeld + if (album) albums(restHeld, rest, placeById) else restHeld).sortedBy { it.k }
            return PhotoPlan(
                candidates, placeById, pool, pickedHolds + restHolds, held, pickedAt, moved.toSet(), leftOut, pickRoom, pickedSeconds, order,
            )
        }

        /**
         * Album pages ([PhotoLayout.Album]; owner, 2026-11-09: "maybe I want like full screen or 2 pictures, see layout in
         * photo album"): each pause shows up to [PhotoLayout.ALBUM_MAX] photos of its place at once, in the same time: the
         * paused photo and others taken there (its run of photos, as Auto finds places), spread over the run, in the
         * order along the route with the traveller's order within the place. A photo with its own pause, or paused
         * already, keeps it; one place's photos never fill two pages.
         */
        fun albums(held: List<HeldPhoto>, pool: List<Candidate>, placeById: Map<String, String?>): List<HeldPhoto> {
            // Without pauses for every photo (On), the others pop in over the moving map: none join a page.
            if (!o.photoAuto && o.photoHoldSeconds <= 0.0) return held
            val runs = placeRuns(pool, placeById)
            val taken = held.mapTo(HashSet()) { it.id }
            return held.map { h ->
                val run = runs.firstOrNull { r -> r.any { it.id == h.id } } ?: return@map h
                val free = run.filter { it.id !in taken && it.id !in o.photoEdits.holds }
                val more = pickEvenly(free, PhotoLayout.ALBUM_MAX - 1).mapTo(HashSet()) { it.id }
                if (more.isEmpty()) return@map h
                taken += more
                val natural = run.map { it.id }.filter { it == h.id || it in more }
                val ids = ClipOrder.merge(natural, placeById[h.id]?.let { o.photoEdits.order[it] })
                HeldPhoto(ids.first(), h.k, h.frames, ids.drop(1))
            }
        }

        /** The camera's zoom where the place name [key] shows: its own (scene editor), else the clip's. */
        fun zoomOf(key: String?): ClipZoom = key?.let { o.cameraEdits.zoom[it] } ?: o.zoom

        /**
         * The follow camera over head positions [hx], [hy] (on path segments [seg]) with each frame's zoom [zoomAt]:
         * flights held as jumps, "whole route" stretches held in full, the look window and the closest view per
         * frame, and the close-ups [stills].
         */
        fun follow(
            hx: DoubleArray, hy: DoubleArray, seg: IntArray, zoomAt: Array<ClipZoom>, drawStart: Int, drawEnd: Int,
            overview: Camera, stills: List<FollowCamera.Still>, px: DoubleArray, py: DoubleArray,
        ): FollowCamera {
            val n = hx.size
            val jumps = jumps(px, py, seg, drawStart, drawEnd).toMutableList()
            val route = bounds(px, py)
            var g = 0
            while (g < n) {
                if (zoomAt[g] != ClipZoom.Route) { g++; continue }
                val from = g
                while (g < n && zoomAt[g] == ClipZoom.Route) g++
                jumps += FollowCamera.Jump(from, g, route, whole = true)
            }
            val look = if (zoomAt.all { it == ClipZoom.Normal }) null else DoubleArray(n) { zoomAt[it].look.takeIf { l -> l.isFinite() } ?: 1.0 }
            val floors = DoubleArray(n) { zoomAt[it].floorM.takeIf { m -> m.isFinite() } ?: MIN_VIEW_M }
            return FollowCamera(
                hx, hy, fps, aspect, overview, drawStart, jumps, { f, y -> minSpan(y, floors[f.coerceIn(0, n - 1)]) }, look, stills,
                routeSpan = fitSpan(route),
                maxSpan = if (globe) overview.spanX else Double.POSITIVE_INFINITY,
            )
        }

        /**
         * The steady camera ([CameraMode.Steady]) over head positions [hx], [hy] (on path segments [seg]) with each
         * frame's zoom [zoomAt], the place name shown [placeAt] (a place's own zoom goes by it) and the day [dayAt]: its
         * runs ([steadyRuns]), the close-ups [stills], and as [SteadyCamera] takes them the [waits] and [hidden] frames.
         */
        fun steady(
            hx: DoubleArray, hy: DoubleArray, seg: IntArray, zoomAt: Array<ClipZoom>, placeAt: Array<String?>, dayAt: (Int) -> Any?,
            drawStart: Int, drawEnd: Int, overview: Camera, stills: List<FollowCamera.Still>, px: DoubleArray, py: DoubleArray,
            waits: BooleanArray? = null, hidden: List<IntRange> = emptyList(),
        ): SteadyCamera {
            val runs = steadyRuns(
                // Each day on one vehicle says how high it would like the camera; calmRuns keeps few of those heights
                // (owner, 2026-11-08, on a height per day and vehicle: "many zoom in zoom out … tiring and confusing").
                hx, hy, drawStart, drawEnd, { f -> dayAt(f) to legV.getOrNull(seg[f]) }, zoomAt,
                { f -> placeAt[f]?.takeIf { it in o.cameraEdits.zoom } }, overview, bounds(px, py),
                lift = { f -> pace(seg[f], px, py) == 2 },
            )
            return SteadyCamera(hx, hy, fps, aspect, overview, runs, drawStart until drawEnd, stills, waits, hidden, chase = o.camera == CameraMode.Chase)
        }

        /** The journey's camera on its own timeline, by the clip's [CameraMode] (see [steady] and [follow]). */
        fun journeyCamera(
            hx: DoubleArray, hy: DoubleArray, seg: IntArray, zoomAt: Array<ClipZoom>, placeAt: Array<String?>, dayAt: (Int) -> Any?,
            drawStart: Int, drawEnd: Int, overview: Camera, px: DoubleArray, py: DoubleArray,
        ): ClipCamera = if (o.camera == CameraMode.Follow) follow(hx, hy, seg, zoomAt, drawStart, drawEnd, overview, emptyList(), px, py)
            else steady(hx, hy, seg, zoomAt, placeAt, dayAt, drawStart, drawEnd, overview, emptyList(), px, py)

        /**
         * How path segment [s] moves, for the steady camera's runs: 0 on foot (walking, cycling, a ferry), 1 on wheels
         * (car, train, bus), 2 in the air (a flight, or a hop longer than [LIFT_LEG_KM]: it lifts the camera once).
         */
        fun pace(s: Int, px: DoubleArray, py: DoubleArray): Int {
            val v = legV.getOrNull(s)
            if (v == Vehicle.Plane) return 2
            if (s >= 0 && s + 1 < px.size) {
                val lat = Mercator.lat((py[s] + py[s + 1]) / 2)
                val km = hypot(px[s + 1] - px[s], py[s + 1] - py[s]) * EARTH_KM * cos(Math.toRadians(lat))
                if (km > LIFT_LEG_KM) return 2
            }
            return if (v == Vehicle.Walk || v == Vehicle.Bike || v == Vehicle.Ferry) 0 else 1
        }

        /** The day a frame belongs to for the steady camera's runs: a planned place's own day, else the leg's. */
        fun dayKey(seg: Int, place: String?, spanDate: Map<String, LocalDate?>): Any? =
            if (input.source == ClipSource.Planned) place?.let { spanDate[it] } else legDay.getOrNull(seg)

        /**
         * The steady camera's runs over frames [from, to): the stretches where [key] (the day and the vehicle) holds, each
         * at its own width ([runWidth]) at the clip's zoom, made into few calm heights ([calmRuns]; [lift]: frames in the
         * air). Then a place's own zoom
         * ([ownAt]: its key, when the scene editor gave it one) is carved out at its own height: its stretch, at least
         * [ZOOM_MIN_S] around its middle. A "whole route" run looks at the whole [route] from its middle.
         */
        fun steadyRuns(
            hx: DoubleArray, hy: DoubleArray, from: Int, to: Int, key: (Int) -> Any?, zoomAt: Array<ClipZoom>, ownAt: (Int) -> String?,
            overview: Camera, route: WorldRect, lift: (Int) -> Boolean = { false },
        ): List<CameraRun> {
            if (to <= from) return emptyList()
            val routeSpan = min(fitSpan(route), overview.spanX)
            class Run(val a: Int, val b: Int, val span: Double)
            fun run(a: Int, b: Int) = Run(a, b, runWidth(hx, hy, a, b, o.zoom, routeSpan, own = false))
            // What each stretch would like on its own: a day on one vehicle at its own width ([runWidth]).
            val fine = ArrayList<Run>()
            var start = from
            for (f in from + 1 until to) if (key(f) != key(f - 1)) { fine += run(start, f); start = f }
            fine += run(start, to)
            // The Follow camera: the height How close asks, however fast; a stretch in the air lifts as it would.
            val runs = if (o.camera == CameraMode.Chase) {
                fine.map { rn -> if ((rn.a until rn.b).any(lift)) rn else Run(rn.a, rn.b, chaseWidth(hy, rn.a, rn.b, o.zoom, routeSpan)) }
            } else {
                calmRuns(fine.map { (it.a until it.b) to it.span }, hx, hy, from, to, lift, routeSpan).map { (a, b, span) -> Run(a, b, span) }
            }
            // A place's own zoom: its stretches, each at least ZOOM_MIN_S long around its middle, one after another.
            class Own(val a: Int, val b: Int, val zoom: ClipZoom)
            val own = ArrayList<Own>()
            var f = from
            while (f < to) {
                val k = ownAt(f)
                if (k == null) { f++; continue }
                val first = f
                while (f < to && ownAt(f) == k) f++
                var a = first
                var b = f
                val shortest = min(r(ZOOM_MIN_S), to - from)
                if (b - a < shortest) {
                    a = ((first + f) / 2 - shortest / 2).coerceIn(from, to - shortest)
                    b = a + shortest
                }
                a = max(a, own.lastOrNull()?.b ?: from)
                if (b > a) own += Own(a, b, zoomAt[first])
            }
            val ownOf = arrayOfNulls<Own>(to - from)
            for (s in own) for (g in s.a until s.b) ownOf[g - from] = s
            val runOf = IntArray(to - from)
            for ((i, rn) in runs.withIndex()) for (g in rn.a until rn.b) runOf[g - from] = i
            val out = ArrayList<CameraRun>()
            var g = from
            while (g < to) {
                val s = ownOf[g - from]
                val ri = runOf[g - from]
                var e = g + 1
                while (e < to && ownOf[e - from] === s && (s != null || runOf[e - from] == ri)) e++
                val zoom = s?.zoom ?: o.zoom
                out += when {
                    zoom == ClipZoom.Route -> CameraRun(g, e, routeSpan, s != null, route.cx, route.cy)
                    s != null -> CameraRun(g, e, ownWidth(hx, hy, s.a, s.b, zoom, runs[runOf[(s.a + s.b) / 2 - from]].span, routeSpan), true)
                    else -> CameraRun(g, e, runs[ri].span)
                }
                g = e
            }
            return out
        }

        /**
         * The steady camera's calm heights over frames [from, to) (owner, 2026-11-08: "it does many zoom in zoom out,
         * it's tiring and confusing"): from the heights the [fine] stretches would like on their own (frames and width),
         * the fewest that still serve them, chosen at once for the whole journey. Each change of height costs
         * [CALM_CHANGE_S] seconds of a stretch shown one step (e, about 2.7 times) off its own height (squared, so a
         * stretch twice that far off counts four times); a height holds at least [RUN_MIN_S] (at most [RUN_MIN_SHARE] of
         * a short journey), except in the air (a flight, a long hop: [lift]), which may lift for [RUN_SEEN_S]. A held
         * height is the time-weighted middle of what its stretches want, never so close that the head moves more than
         * the zoom's step allowance in a frame, nor wider than the whole route. Changes fall where a day or a vehicle
         * changes. Returns (from, to, width) per height.
         */
        fun calmRuns(
            fine: List<Pair<IntRange, Double>>, hx: DoubleArray, hy: DoubleArray, from: Int, to: Int, lift: (Int) -> Boolean,
            routeSpan: Double,
        ): List<Triple<Int, Int, Double>> {
            val n = to - from
            if (n <= 0) return emptyList()
            val wide = max(1.0, aspect)
            val allow = RUN_STEP_MAX * (o.zoom.look.takeIf { it.isFinite() }?.pow(-RUN_STEP_ZOOM) ?: 1.0)
            val want = DoubleArray(n)
            for ((range, span) in fine) for (g in range) want[g - from] = ln(span)
            // The least height for each frame: the head never moves more than the allowance of the view in a frame.
            val least = DoubleArray(n) { Double.NEGATIVE_INFINITY }
            for (g in from until to - 1) {
                val step = hypot(hx[g + 1] - hx[g], hy[g + 1] - hy[g])
                if (step > 0) {
                    val l = ln(step / allow * wide)
                    least[g - from] = max(least[g - from], l)
                    least[g + 1 - from] = max(least[g + 1 - from], l)
                }
            }
            val s1 = DoubleArray(n + 1)
            val s2 = DoubleArray(n + 1)
            val air = IntArray(n + 1)
            for (g in 0 until n) {
                s1[g + 1] = s1[g] + want[g]
                s2[g + 1] = s2[g] + want[g] * want[g]
                air[g + 1] = air[g] + if (lift(from + g)) 1 else 0
            }
            val cap = ln(routeSpan)
            val cuts = (listOf(from) + fine.drop(1).map { it.first.first } + to).distinct().sorted()
            val k = cuts.size
            val shortest = min(r(RUN_MIN_S), (n * RUN_MIN_SHARE).roundToInt()).coerceAtLeast(1)
            val seen = min(r(RUN_SEEN_S), (n * RUN_SEEN_SHARE).roundToInt()).coerceAtLeast(1)
            val change = CALM_CHANGE_S * fps
            fun height(i: Int, j: Int): Double {
                val a = cuts[i] - from
                val b = cuts[j] - from
                var floor = Double.NEGATIVE_INFINITY
                for (g in a until b) floor = max(floor, least[g])
                return min(max((s1[b] - s1[a]) / (b - a), floor), cap)
            }
            fun cost(i: Int, j: Int, h: Double): Double {
                val a = cuts[i] - from
                val b = cuts[j] - from
                return (b - a) * h * h - 2 * h * (s1[b] - s1[a]) + (s2[b] - s2[a])
            }
            fun holds(i: Int, j: Int): Boolean {
                val a = cuts[i] - from
                val b = cuts[j] - from
                return b - a >= shortest || (air[b] - air[a] == b - a && b - a >= seen) || (i == 0 && j == k - 1)
            }
            val best = DoubleArray(k) { Double.POSITIVE_INFINITY }
            val back = IntArray(k) { -1 }
            best[0] = 0.0
            for (j in 1 until k) for (i in 0 until j) {
                if (best[i] == Double.POSITIVE_INFINITY || !holds(i, j)) continue
                val c = best[i] + cost(i, j, height(i, j)) + if (i > 0) change else 0.0
                if (c < best[j]) { best[j] = c; back[j] = i }
            }
            if (back[k - 1] < 0) return listOf(Triple(from, to, exp(height(0, k - 1))))
            val out = ArrayList<Triple<Int, Int, Double>>()
            var j = k - 1
            while (j > 0) {
                val i = back[j]
                out += Triple(cuts[i], cuts[j], exp(height(i, j)))
                j = i
            }
            return out.reversed()
        }

        /**
         * A place's own zoom over head frames [a, b): as many steps closer (or wider) than the clip's zoom as the scene
         * editor says, applied to the width of the run it sits in ([runSpan]): the ratio of the zooms' looks, never
         * closer than its own floor nor wider than the whole route. From a "whole route" clip, its own run's width.
         */
        fun ownWidth(hx: DoubleArray, hy: DoubleArray, a: Int, b: Int, zoom: ClipZoom, runSpan: Double, routeSpan: Double): Double {
            if (!o.zoom.look.isFinite()) return runWidth(hx, hy, a, b, zoom, routeSpan, own = true)
            val floor = min(minSpan(hy[(a + b) / 2], zoom.floorM), routeSpan)
            return max(floor, min(runSpan * zoom.look / o.zoom.look, routeSpan))
        }

        /**
         * One steady run's width (world units) over head frames [a, b) at [zoom]: the frame's short side shows about
         * [RUN_LOOK_S] of its travel at its average pace (times the zoom's [ClipZoom.look]), at most the run itself with
         * its margins (times the same look, so each zoom step stays closer than the last); and however close the zoom,
         * the head never moves more than [RUN_STEP_MAX] of the short side in a frame (the centre can then keep up),
         * except at a place's own zoom ([own]: the traveller's explicit choice). Never closer than the zoom's floor
         * ([minSpan]) nor wider than the whole route ([routeSpan], the "whole route" zoom's own width). From the run's
         * route, not from the speed frame by frame: a run keeps one width however its pace changes.
         */
        /**
         * The Follow camera's width over frames [a, b) ([CameraMode.Chase]): [zoom]'s ground ([ClipZoom.followM]) at the
         * stretch's latitude, never closer than the map's pictures allow, nor wider than the whole route.
         */
        fun chaseWidth(hy: DoubleArray, a: Int, b: Int, zoom: ClipZoom, routeSpan: Double): Double {
            if (zoom == ClipZoom.Route || b <= a) return routeSpan
            var y = 0.0
            for (f in a until b) y += hy[f]
            return min(minSpan(y / (b - a), zoom.followM), routeSpan)
        }

        fun runWidth(hx: DoubleArray, hy: DoubleArray, a: Int, b: Int, zoom: ClipZoom, routeSpan: Double, own: Boolean): Double {
            if (zoom == ClipZoom.Route) return routeSpan
            var rect = WorldRect(hx[a], hy[a], hx[a], hy[a])
            var arc = 0.0
            var moving = 0
            var fastest = 0.0
            for (f in a until b) {
                rect = rect.union(hx[f], hy[f])
                if (f + 1 < b) {
                    val step = hypot(hx[f + 1] - hx[f], hy[f + 1] - hy[f])
                    if (step > 0) { arc += step; moving++; fastest = max(fastest, step) }
                }
            }
            val floor = min(minSpan(rect.cy, zoom.floorM), routeSpan)
            if (moving == 0) return floor
            // Widths are frame widths; the travel is measured against the short side (a wide frame is wider).
            val wide = max(1.0, aspect)
            val paced = min(arc / moving * fps * RUN_LOOK_S * zoom.look * wide, fitSpan(rect) * zoom.look)
            val safe = if (own) 0.0 else fastest / (RUN_STEP_MAX * zoom.look.pow(-RUN_STEP_ZOOM)) * wide
            return max(floor, min(max(paced, safe), routeSpan))
        }

        /**
         * Place names whose change falls between beats come in on the nearest one (within half a beat, and only where
         * one name follows another straight away: after a day card or a video it already starts on a beat).
         */
        fun onBeats(ranges: List<LabelRange>, beats: IntArray): List<LabelRange> {
            if (ranges.size < 2 || beats.size < 2) return ranges
            val half = (beats.last() - beats.first()).toDouble() / (beats.size - 1) / 2
            val from = IntArray(ranges.size) { ranges[it].from }
            val to = IntArray(ranges.size) { ranges[it].to }
            for (i in 1 until ranges.size) {
                if (to[i - 1] != from[i]) continue
                val b = beats.minBy { abs(it - from[i]) }
                if (abs(b - from[i]) > half || b <= from[i - 1] || b >= to[i]) continue
                to[i - 1] = b
                from[i] = b
            }
            return ranges.mapIndexed { i, l -> LabelRange(from[i], to[i], l.label, l.index, l.key, l.stops) }
        }

        /** A close-up chosen on the first layout: its place ([dist] metres along the route), journey frames it takes, width. */
        inner class PlannedStill(val dist: Double, val carved: Int, val span: Double)

        /**
         * The close-ups ([CloseUp]): at the stops on the route and the photo pauses, by each place name's own choice
         * or the clip's; places within [STILL_MERGE_S] of each other wait as one (on the photo pause when there is
         * one). Each lasts long enough to move in from where the camera would be (the opening's dive and the end's
         * pull-back included) at the capped zoom speed, hold [CLOSE_UP_HOLD_S] and move back out; what a photo pause
         * doesn't cover comes from the journey's frames ([PlannedStill.carved]), so the clip keeps its length. At most
         * [CLOSE_UP_SHARE] of the journey and [MAX_CLOSE_UPS] close-ups: the ones a place asks for first, then photo
         * pauses, then stops, spread evenly. A shorter journey moves the head faster and the camera wider, so the
         * lengths are worked out again for the share the first choice takes.
         */
        fun chooseStills(
            way: Way, j: Journey, photos: PhotoPlan, onRoute: List<Triple<String, Double, Long>>, overview: Camera,
            px: DoubleArray, py: DoubleArray, times: DoubleArray, realCum: DoubleArray, travelFrames: Int, outro: Int,
        ): List<PlannedStill> {
            val frames = j.frames
            class Spot(val dist: Double, val k: Int, val setting: CloseUp, val forced: Boolean, val heldFrames: Int)
            val spots = ArrayList<Spot>()
            fun add(dist: Double, k: Int, heldFrames: Int) {
                val own = j.placeOf(k)?.let { o.cameraEdits.closeUp[it] }
                val setting = own ?: o.closeUp
                if (setting.on) spots += Spot(dist, k, setting, own != null, heldFrames)
            }
            val distOf = photos.candidates.associate { it.id to it.dist }
            val heldAt = LinkedHashMap<Int, Pair<Double, Int>>()
            for ((id, k, pause) in photos.held) {
                val dist = distOf[id] ?: continue
                // The still map before and after the stop's photos waits too.
                heldAt[k] = (heldAt[k]?.first ?: dist) to ((heldAt[k]?.second ?: 2 * r(gapS)) + pause)
            }
            for ((k, v) in heldAt) add(v.first, k, v.second)
            for (s in input.stops) {
                val dist = placeOnRoute(s.time, s.lat, s.lng, 3 * HOUR, 3_000.0, px, py, times, realCum) ?: continue
                add(dist, firstFrameAt(j.travelDist, dist, frames - 1), 0)
            }
            if (spots.isEmpty()) return emptyList()
            spots.sortWith(compareBy({ it.k }, { -it.heldFrames }))
            val merged = ArrayList<Spot>()
            for (s in spots) {
                val last = merged.lastOrNull()
                if (last == null || s.k - last.k > r(STILL_MERGE_S)) { merged += s; continue }
                val keep = if (s.heldFrames > last.heldFrames) s else last
                val closer = if (s.setting.floorM < last.setting.floorM) s.setting else last.setting
                merged[merged.lastIndex] = Spot(keep.dist, keep.k, closer, s.forced || last.forced, keep.heldFrames)
            }

            // Where the camera would be without close-ups on this layout.
            val hxK = DoubleArray(frames)
            val hyK = DoubleArray(frames)
            val segK = IntArray(frames)
            for (k in 0 until frames) {
                val (seg, t) = locate(way.effCum, j.travelEff[k])
                segK[k] = seg
                val (x, y) = pointOn(px, py, seg, t)
                hxK[k] = x
                hyK[k] = y
            }
            val placeK = Array(frames) { j.placeOf(it) }
            val spanDate = j.spans.associate { it.key to it.date }
            val camera = journeyCamera(
                hxK, hyK, segK, Array(frames) { zoomOf(placeK[it]) }, placeK, { k -> dayKey(segK[k], placeK[k], spanDate) }, 0, frames,
                overview, px, py,
            )
            val rate = camera.rate
            val lnO = ln(overview.spanX)
            val around = r(FollowCamera.HOLD_S)
            val pullBack = min(r(3.0), (outro * 0.6).toInt())

            class Cost(val spot: Spot, val frames: Int, val span: Double) {
                val carved: Int get() = max(0, this.frames - spot.heldFrames)
            }
            fun costs(wider: Double): List<Cost> = merged.map { s ->
                val span = minSpan(hyK[s.k], s.setting.floorM)
                val close = ln(span)
                var level = Double.NEGATIVE_INFINITY
                for (g in max(0, s.k - around)..min(frames - 1, s.k + around)) level = max(level, camera.followLn[g])
                level = min(level + wider, lnO)
                // The follow camera dives and pulls back at the zoom speed limit; the steady camera's own eased moves
                // start from the close view itself.
                val steady = o.camera != CameraMode.Follow
                val before = if (steady) level else max(level, lnO - rate * (s.k + 1))
                val after = if (steady) level else max(level, lnO - rate * (frames - 1 - s.k + pullBack))
                val n = ceil(max(0.0, before - close) / rate).toInt() + ceil(max(0.0, after - close) / rate).toInt() +
                    r(CLOSE_UP_HOLD_S) + r(CLOSE_UP_EASE_S)
                Cost(s, max(n, r(CLOSE_UP_MIN_S)), span)
            }
            fun choose(costs: List<Cost>): List<Cost> {
                val budget = (travelFrames * CLOSE_UP_SHARE).toInt()
                var chosen = costs.filter { it.spot.forced }
                while (chosen.isNotEmpty() && (chosen.sumOf { it.carved } > budget || chosen.size > MAX_CLOSE_UPS)) chosen = chosen.dropLast(1)
                val rest = costs.filter { !it.spot.forced }
                for (group in listOf(rest.filter { it.spot.heldFrames > 0 }, rest.filter { it.spot.heldFrames == 0 })) {
                    var count = min(group.size, MAX_CLOSE_UPS - chosen.size)
                    while (count > 0) {
                        val picked = pickEvenly(group, count)
                        if (chosen.sumOf { it.carved } + picked.sumOf { it.carved } <= budget) { chosen = chosen + picked; break }
                        count--
                    }
                }
                return chosen.sortedBy { it.spot.k }
            }
            var chosen = choose(costs(0.0))
            val share = chosen.sumOf { it.carved }.toDouble() / travelFrames
            if (share > 0) chosen = choose(costs(ln(1 / (1 - share))))
            return chosen.map { PlannedStill(it.spot.dist, it.carved, it.span) }
        }

        /**
         * A planned route's place names: each place from halfway along the leg that reaches it to halfway along the
         * leg that leaves it (the first from the start, the last until the end card), "Day N" above it when the
         * plan gives the place a day within the trip. Places in a row with the same name and day show once, keyed
         * by the first one's plan item (so the key holds whatever the speed). With the stops' titles on
         * ([ClipOptions.stopTitles]; owner, 2026-11-09: "text should be able take the sub items title also") each stop
         * within a place is its own name: its title big ("Lisbon Cathedral"), the day and the place small over it ("Day
         * 2 · Lisbon"); a travel item, or one named as its place, shows the place as before.
         */
        fun placeSpans(legShares: IntArray, travelFrames: Int): List<Span> {
            val waypoints = input.planned?.waypoints.orEmpty()
            if (waypoints.size != legShares.size + 1) return emptyList()
            val edges = IntArray(waypoints.size + 1)
            val reach = IntArray(waypoints.size)
            var k = 0
            for (i in legShares.indices) {
                edges[i + 1] = (k + legShares[i] / 2).coerceIn(0, travelFrames)
                k += legShares[i]
                reach[i + 1] = k
            }
            edges[waypoints.size] = travelFrames
            val places = waypoints.map { ClipLabel(plannedDay(it.day), placeName(it.place ?: it.title)) }
            val labels = waypoints.mapIndexed { n, w -> stopTitle(w)?.let { ClipLabel(places[n].text.ifEmpty { null }, it) } ?: places[n] }
            val out = ArrayList<Span>()
            var i = 0
            while (i < waypoints.size) {
                var j = i
                while (j + 1 < waypoints.size && labels[j + 1] == labels[i]) j++
                out += Span(
                    edges[i], edges[j + 1], labels[i], ClipDesign.labelKey("item:${waypoints[i].itemId}"), last = j == waypoints.lastIndex,
                    // Inside the place's own stretch, so a video after it plays under its name.
                    arriveK = reach[j].coerceIn(edges[i], max(edges[i], edges[j + 1] - 1)).coerceIn(0, (travelFrames - 1).coerceAtLeast(0)),
                    date = waypoints[i].day?.takeIf { places[i].day != null },
                    card = places[i], stops = waypoints.subList(i, j + 1).map { it.itemId },
                )
                i = j + 1
            }
            return out
        }

        /**
         * A stop's own title for its place name when the stops' titles show ([ClipOptions.stopTitles]): shortened (what
         * follows "W day 1: ", the first of a long "A and B", at most 28 characters); none for a travel item ("Fly to
         * Lisbon" arrives in Lisbon) or a stop named as its place.
         */
        fun stopTitle(w: PlannedWaypoint): String? {
            if (!o.stopTitles || w.travel) return null
            val place = w.place?.let(::placeName) ?: return null
            var t = w.title.trim()
            t = t.substringAfter(": ", t).trim()
            if (t.length > 28) t = t.substringBefore(" and ").trim()
            return placeName(t)?.takeUnless { it.equals(place, ignoreCase = true) }
        }

        /** A recording's or saved stops' place names: one per day ("Day 4 · Naxos"), keyed by its date. */
        fun daySpans(groups: List<Group>, labels: List<ClipLabel>, fromK: IntArray, toK: IntArray): List<Span> =
            groups.indices.map { i ->
                Span(fromK[i], toK[i], labels[i], ClipDesign.labelKey("day:${groups[i].date}"), last = i == groups.lastIndex, date = groups[i].date)
            }
                .filter { it.label.text.isNotEmpty() }

        /**
         * Spans without frames left out (the last one always runs into the close), neighbours with the same name joined
         * (keeping the first key), and a key met again made unique ("…#2").
         */
        fun tidy(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            val seen = HashMap<String, Int>()
            for (s in spans) {
                if ((s.toK <= s.fromK && !s.last) || s.label.text.isEmpty()) continue
                val prev = out.lastOrNull()
                if (prev != null && prev.label == s.label && prev.toK == s.fromK) {
                    out[out.lastIndex] = Span(prev.fromK, s.toK, prev.label, prev.key, s.last, s.arriveK, prev.date, prev.card, prev.stops + s.stops)
                    continue
                }
                val n = (seen[s.key] ?: 0) + 1
                seen[s.key] = n
                out += if (n == 1) s else Span(s.fromK, s.toK, s.label, "${s.key}#$n", s.last, s.arriveK, s.date, s.card, s.stops)
            }
            return out
        }

        /** "Day 3" for a planned place's day within the trip's dates, else nothing (undated ideas, no trip dates). */
        fun plannedDay(day: LocalDate?): String? {
            val start = input.startDate ?: return null
            if (day == null) return null
            val number = (day.toEpochDay() - start.toEpochDay() + 1).toInt()
            val last = input.endDate?.let { (it.toEpochDay() - start.toEpochDay() + 1).toInt() } ?: Int.MAX_VALUE
            return if (number in 1..last) ClipText.dayName(number) else null
        }

        /** A planned place's name as written in the plan, shortened like the stops' place names. */
        fun placeName(title: String): String? {
            val t = title.trim()
            if (t.isEmpty()) return null
            return if (t.length > 28) t.take(27).trimEnd() + "…" else t
        }

        /** The tracked points, or the stops in order when there is no usable track. */
        fun routePoints(): List<GeoPoint>? {
            if (input.source == ClipSource.Planned) {
                val route = input.planned?.timeline(input.zone).orEmpty()
                return route.takeIf { it.size >= 2 && TrackShaping.lengthM(it) >= MIN_ROUTE_M }
            }
            if (input.source == ClipSource.Stops) {
                val route = input.stops.map { GeoPoint(it.time, it.lat, it.lng) }
                return route.takeIf { it.size >= 2 && TrackShaping.lengthM(it) >= MIN_ROUTE_M }
            }
            val track = TrackShaping.clean(input.points)
            if (track.size >= 2 && TrackShaping.lengthM(track) >= MIN_ROUTE_M) return TrackShaping.smooth(track)
            val fromStops = TrackShaping.clean(input.stops.map { GeoPoint(it.time, it.lat, it.lng) })
            if (fromStops.size >= 2 && TrackShaping.lengthM(fromStops) >= MIN_ROUTE_M) return fromStops
            return null
        }

        /**
         * Thins each stretch of one day and one vehicle on its own (so day and vehicle changes keep their points),
         * loosening the tolerance until the route has at most [MAX_POINTS] points. Returns the kept indices.
         */
        fun thin(pts: List<GeoPoint>, vehicles: List<Vehicle>, pDay: List<LocalDate>): List<Int> {
            val diag = Geo.distanceM(pts.minOf { it.lat }, pts.minOf { it.lng }, pts.maxOf { it.lat }, pts.maxOf { it.lng })
            var tol = max(3.0, diag / 3_000)
            while (true) {
                val keep = sortedSetOf(0, pts.size - 1)
                var start = 0
                for (k in 1..vehicles.size) {
                    val split = k == vehicles.size || vehicles[k] != vehicles[start] || pDay[k + 1] != pDay[start + 1]
                    if (!split) continue
                    val a = start
                    val b = k // the stretch is legs [start, k), points [a, b]
                    TrackShaping.simplify(pts.subList(a, b + 1), tol).forEach { keep += a + it }
                    start = k
                }
                if (keep.size <= MAX_POINTS || tol > diag) return keep.toList()
                tol *= 2
            }
        }

        fun buildPath(pts: List<GeoPoint>, vehicles: List<Vehicle>, counts: List<Boolean>, pDay: List<LocalDate>, kept: List<Int>, minutes: DoubleArray) {
            fun add(p: GeoPoint) {
                val x = Mercator.x(p.lng)
                // On the globe the route goes on past 180° (a Pacific crossing stays one short leg).
                xs += if (globe && xs.isNotEmpty()) ClipGlobe.near(x, xs.last()) else x
                ys += Mercator.y(p.lat)
                ts += p.time
            }
            add(pts[kept[0]])
            for (j in 0 until kept.size - 1) {
                val a = pts[kept[j]]
                val b = pts[kept[j + 1]]
                val v = vehicles[kept[j + 1] - 1]
                val counted = counts[kept[j + 1] - 1]
                val day = pDay[kept[j + 1]]
                val dist = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
                // A thinned leg takes the travel time of the legs it stands for (a rest inside it stays a rest).
                var time = 0.0
                for (i in kept[j] until kept[j + 1]) time += minutes[i]
                if (input.source == ClipSource.Recorded && v == Vehicle.Plane && dist >= ARC_MIN_M) {
                    if (globe) greatArc(a, b, dist, v, counted, day, time) else arc(a, b, dist, v, counted, day, time)
                } else {
                    add(b); legV += v; legCount += counted; legDay += day; legReal += dist; legMin += time
                }
            }
        }

        /** A flight drawn as a gentle arc bowing north (west when it flies due north or south). */
        fun arc(a: GeoPoint, b: GeoPoint, dist: Double, v: Vehicle, counted: Boolean, day: LocalDate, minutes: Double) {
            val ax = Mercator.x(a.lng)
            val ay = Mercator.y(a.lat)
            val bx = Mercator.x(b.lng)
            val by = Mercator.y(b.lat)
            var nx = -(by - ay)
            var ny = bx - ax
            if (ny > 0 || (ny == 0.0 && nx > 0)) { nx = -nx; ny = -ny }
            val qx = (ax + bx) / 2 + nx * 0.18
            val qy = (ay + by) / 2 + ny * 0.18
            val sx = DoubleArray(ARC_STEPS + 1)
            val sy = DoubleArray(ARC_STEPS + 1)
            for (s in 0..ARC_STEPS) {
                val t = s.toDouble() / ARC_STEPS
                val u = 1 - t
                sx[s] = u * u * ax + 2 * u * t * qx + t * t * bx
                sy[s] = u * u * ay + 2 * u * t * qy + t * t * by
            }
            val seg = DoubleArray(ARC_STEPS + 1)
            var length = 0.0
            for (s in 1..ARC_STEPS) { seg[s] = hypot(sx[s] - sx[s - 1], sy[s] - sy[s - 1]); length += seg[s] }
            for (s in 1..ARC_STEPS) {
                xs += sx[s]; ys += sy[s]
                ts += a.time + ((b.time - a.time) * s.toDouble() / ARC_STEPS).toLong()
                val share = if (length > 0) seg[s] / length else 1.0 / ARC_STEPS
                legV += v; legCount += counted; legDay += day; legReal += dist * share; legMin += minutes * share
            }
        }

        /**
         * A recorded flight on the globe: the great circle itself (what a plane flies; it looks right on a sphere), in
         * steps of [ClipGlobe.STEP_DEG] at most and never fewer than [ARC_STEPS].
         */
        fun greatArc(a: GeoPoint, b: GeoPoint, dist: Double, v: Vehicle, counted: Boolean, day: LocalDate, minutes: Double) {
            val ax = xs.last()
            val ay = ys.last()
            val bx = ClipGlobe.near(Mercator.x(b.lng), ax)
            val by = Mercator.y(b.lat)
            val deg = Math.toDegrees(ClipGlobe.angle(a.lat, a.lng, b.lat, b.lng))
            val steps = max(ARC_STEPS, ceil(deg / ClipGlobe.STEP_DEG).toInt())
            for (s in 1..steps) {
                val (x, y) = if (s == steps) bx to by else ClipGlobe.along(ax, ay, bx, by, s.toDouble() / steps)
                xs += x; ys += y
                ts += a.time + ((b.time - a.time) * s.toDouble() / steps).toLong()
                legV += v; legCount += counted; legDay += day; legReal += dist / steps; legMin += minutes / steps
            }
        }

        /**
         * Real travel minutes for each leg between [pts] (before thinning). Planned and saved-stop legs: the plan's
         * estimate for the leg's vehicle ([TravelTime]; a leg without one counts as by car). A recording: the time
         * between its points, except where the phone rested between them (slower than [STILL_KMH], or no time at
         * all), which counts as the short drive it stands for.
         */
        fun originalMinutes(pts: List<GeoPoint>, vehicles: List<Vehicle>): DoubleArray = DoubleArray(pts.size - 1) { i ->
            val dist = Geo.distanceM(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            val v = vehicles[i].takeUnless { it == Vehicle.Auto } ?: Vehicle.Car
            if (input.source != ClipSource.Recorded) return@DoubleArray TravelTime.minutes(v, dist) ?: 0.0
            val minutes = (pts[i + 1].time - pts[i].time) / 60_000.0
            val kmh = if (minutes > 0) dist / 1000.0 / (minutes / 60.0) else 0.0
            if (minutes <= 0 || kmh < STILL_KMH) TravelTime.minutes(Vehicle.Car, dist) ?: 0.0 else minutes
        }

        /**
         * Screen-time weights: planned legs each count [ClipTiming.effectiveMinutes] of their time; a recording's run
         * of one vehicle on one day counts that of its total, shared along its legs.
         */
        fun effectiveMinutes(minutes: DoubleArray): DoubleArray {
            if (input.source != ClipSource.Recorded) return DoubleArray(minutes.size) { ClipTiming.effectiveMinutes(minutes[it]) }
            val eff = DoubleArray(minutes.size)
            var start = 0
            for (k in 1..legV.size) {
                if (k < legV.size && legV[k] == legV[start] && legDay[k] == legDay[start]) continue
                var real = 0.0
                for (i in start until k) real += minutes[i]
                val factor = if (real > 0) ClipTiming.effectiveMinutes(real) / real else 1.0
                for (i in start until k) eff[i] = minutes[i] * factor
                start = k
            }
            return eff
        }

        inner class Group(val date: LocalDate, val fromIdx: Int, val toIdx: Int, val lengthM: Double)

        /** Days in route order (a leg belongs to the day it ends on); days under 20 m join a neighbour. */
        fun dayGroups(): List<Group> {
            val raw = ArrayList<Group>()
            var start = 0
            for (k in 1..legDay.size) {
                if (k < legDay.size && legDay[k] == legDay[start]) continue
                var len = 0.0
                for (i in start until k) len += legReal[i]
                raw += Group(legDay[start], start, k, len)
                start = k
            }
            val out = ArrayList<Group>()
            var carry: Int? = null
            for (g in raw) {
                if (g.lengthM < TINY_DAY_M) {
                    if (out.isNotEmpty()) {
                        val last = out.removeAt(out.size - 1)
                        out += Group(last.date, last.fromIdx, g.toIdx, last.lengthM + g.lengthM)
                    } else if (carry == null) carry = g.fromIdx
                    continue
                }
                out += if (carry != null) Group(g.date, carry, g.toIdx, g.lengthM) else g
                carry = null
            }
            if (out.isEmpty()) out += Group(raw.first().date, 0, legDay.size, legReal.sum())
            return out
        }

        /** "Day 4 · Naxos": the day's number (or date) and where it ends. A planned route has no days; see [placeRanges]. */
        fun dayLabel(g: Group, px: DoubleArray, py: DoubleArray): ClipLabel {
            val number = input.startDate?.let { start ->
                val n = (g.date.toEpochDay() - start.toEpochDay() + 1).toInt()
                val max = input.endDate?.let { (it.toEpochDay() - start.toEpochDay() + 1).toInt() } ?: Int.MAX_VALUE
                n.takeIf { it in 1..max }
            }
            // The place the day ends: the last stop reached that day, else the stop nearest the day's end.
            val endX = px[g.toIdx]
            val endY = py[g.toIdx]
            val stop = input.stops.filter { localDate(it.time) == g.date }.maxByOrNull { it.time }
                ?: input.stops.minByOrNull { metres(endX, endY, Mercator.x(it.lng), Mercator.y(it.lat)) }
                    ?.takeIf { metres(endX, endY, Mercator.x(it.lng), Mercator.y(it.lat)) <= 3_000 }
            val place = stop?.name?.let { ClipText.place(it) }
            if (input.source == ClipSource.Planned) return ClipLabel(null, place)
            return ClipLabel(number?.let { ClipText.dayName(it) } ?: Dates.dayMonth(g.date), place)
        }

        fun stopMarks(px: DoubleArray, py: DoubleArray, times: DoubleArray, realCum: DoubleArray, hd: DoubleArray, drawEnd: Int): List<StopMark> {
            val out = ArrayList<StopMark>()
            for (s in input.stops.sortedBy { it.time }) {
                val dist = placeOnRoute(s.time, s.lat, s.lng, 3 * HOUR, 3_000.0, px, py, times, realCum) ?: continue
                val (x, y) = at(realCum, px, py, dist, ::pointOn)
                val last = out.lastOrNull()
                if (last != null && abs(last.dist - dist) < 150 && metres(last.x, last.y, x, y) < 150) continue
                out += StopMark(x, y, dist, firstFrameAt(hd, dist, drawEnd), s.name)
            }
            return out
        }

        /**
         * Photos placed on the route: id, metres along it, and when taken. Photos off the route (more than 5 km from it
         * at their time) or without a place are left out, except with Auto photos ([ClipOptions.photoAuto]): those join
         * the stop nearest in time (a planned place by its day), else the route where the trip was at that time, else
         * the route nearest where they were taken. A photo with neither a time nor a place still has nowhere to go.
         */
        fun photosOnRoute(px: DoubleArray, py: DoubleArray, times: DoubleArray, realCum: DoubleArray): List<Triple<String, Double, Long>> {
            val stopsAt: List<Pair<Long, Double>> by lazy {
                val days = input.planned?.waypoints.orEmpty().associate { it.itemId to it.day }
                input.stops.mapNotNull { s ->
                    val time = if (input.source == ClipSource.Planned) days[s.id]?.atTime(12, 0)?.atZone(input.zone)?.toInstant()?.toEpochMilli() else s.time
                    val dist = placeOnRoute(s.time, s.lat, s.lng, 3 * HOUR, 3_000.0, px, py, times, realCum)
                    if (time == null || dist == null) null else time to dist
                }
            }
            fun joined(p: ClipPhoto): Double? {
                val t = p.time
                if (t != null) {
                    stopsAt.minByOrNull { abs(it.first - t) }?.let { return it.second }
                    if (input.source != ClipSource.Planned) {
                        val (i, f) = locate(times, t.toDouble().coerceIn(times.first(), times.last()))
                        return realCum[i] + (realCum[i + 1] - realCum[i]) * f
                    }
                }
                val lat = p.lat ?: return null
                val lng = p.lng ?: return null
                val sx = Mercator.x(lng)
                val sy = Mercator.y(lat)
                return realCum[px.indices.minBy { hypot(wrapped(px[it] - sx), py[it] - sy) }]
            }
            return input.photos.mapNotNull { p ->
                val dist = placeOnRoute(p.time, p.lat, p.lng, 12 * HOUR, 5_000.0, px, py, times, realCum)
                    ?: (if (o.photoAuto) joined(p) else null) ?: return@mapNotNull null
                Triple(p.id, dist, p.time ?: 0L)
            }
        }

        /**
         * Photo cards over the moving route (no pause): the ones asked for ([included]) and others spread evenly, at
         * most one per 2.5 s, each shown 1.6–3 s. Spaced, never over a paused photo or a full-frame insert ([busy], in
         * order), all before the end card; with music each pops in on a beat ([beats], output frames).
         */
        fun movingPops(
            moving: List<Candidate>, included: Set<String>, hd: DoubleArray, drawStart: Int, drawEnd: Int, endCardStart: Int,
            busy: List<IntRange>, beats: IntArray = IntArray(0),
        ): List<PhotoPop> {
            val wanted = moving.map { c -> Triple(c.id, firstFrameAt(hd, c.dist, drawEnd).coerceIn(drawStart, drawEnd - 1), c.time) }
                .sortedWith(compareBy({ it.second }, { it.third }))
            if (wanted.isEmpty()) return emptyList()
            val asked = wanted.filter { it.first in included }
            val others = wanted.filterNot { it.first in included }
            val picked = (asked + pickEvenly(others, (ClipTiming.photoCap(totalS) - asked.size).coerceAtLeast(0)))
                .sortedWith(compareBy({ it.second }, { it.third }))
            val hold = r((totalS * 0.11).coerceIn(1.6, 3.0))
            val spacing = (hold * 0.8).roundToInt()
            val period = if (beats.size >= 2) (beats.last() - beats.first()) / (beats.size - 1) else 0
            // The first beat at or after f, when one comes within a beat.
            fun onBeat(f: Int): Int {
                if (period <= 0) return f
                var lo = 0
                var hi = beats.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (beats[mid] < f) lo = mid + 1 else hi = mid
                }
                return if (lo < beats.size && beats[lo] - f <= period) beats[lo] else f
            }
            val out = ArrayList<PhotoPop>()
            var prev = Int.MIN_VALUE / 2
            for ((id, desired, _) in picked) {
                var start = onBeat(max(desired, prev + spacing))
                for (h in busy) if (start <= h.last && start + hold > h.first) start = onBeat(h.last + 1)
                if (start + hold > endCardStart) break
                out += PhotoPop(id, start, start + hold, out.size)
                prev = start
            }
            return out
        }

        /** Where on the route (metres along it) something happened: by its time, else by its place. */
        fun placeOnRoute(
            time: Long?, lat: Double?, lng: Double?, timeSlackMs: Long, nearM: Double,
            px: DoubleArray, py: DoubleArray, times: DoubleArray, realCum: DoubleArray,
        ): Double? {
            if (time != null && time >= times.first() - timeSlackMs && time <= times.last() + timeSlackMs) {
                val (i, t) = locate(times, time.toDouble())
                val d = realCum[i] + (realCum[i + 1] - realCum[i]) * t
                if (lat == null || lng == null) return d
                val (x, y) = at(realCum, px, py, d, ::pointOn)
                if (metres(x, y, Mercator.x(lng), Mercator.y(lat)) <= nearM) return d
            }
            if (lat == null || lng == null) return null
            val sx = Mercator.x(lng)
            val sy = Mercator.y(lat)
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in px.indices) {
                val d = hypot(wrapped(px[i] - sx), py[i] - sy)
                if (d < bestD) { bestD = d; best = i }
            }
            return if (best >= 0 && metres(px[best], py[best], sx, sy) <= nearM) realCum[best] else null
        }

        fun fit(rect: WorldRect) = Camera(rect.cx, rect.cy, fitSpan(rect))

        /** Width of a view that shows [rect] with the spare room around it. */
        fun fitSpan(rect: WorldRect): Double =
            max(max(rect.w / (1 - 2 * PAD_X), rect.h * aspect / (1 - 2 * PAD_Y)), minSpan(rect.cy))

        /**
         * The narrowest view (width in world units) at world row [y]: [floorM] of ground on the short side ([MIN_VIEW_M]
         * unless the zoom asks for closer or wider, see [ClipZoom.floorM]), and never closer than the map's sharpest
         * zoom ([MapStyle.maxZoom]: Earth's pictures stop sooner than the street maps). The map is drawn at one design
         * dp per map point, so the short side is [DESIGN_SHORT_DP] map points ([ZoomLimits.shortSpan]).
         */
        fun minSpan(y: Double, floorM: Double = MIN_VIEW_M): Double {
            val ground = floorM * Mercator.unitsPerMeter(y)
            val sharpest = ZoomLimits.shortSpan(o.map.maxZoom)
            return max(ground, sharpest) * max(1.0, aspect)
        }

        /**
         * Stretches where the camera holds both ends in view instead of following: flights (a recorded plane run or a
         * plane leg over [JUMP_PLANE_M]) and, on planned or saved-stop routes, any straight connection over
         * [JUMP_STRAIGHT_M]. Recorded drives and rides are followed however long they are.
         */
        fun jumps(px: DoubleArray, py: DoubleArray, headSeg: IntArray, drawStart: Int, drawEnd: Int): List<FollowCamera.Jump> {
            val runs = ArrayList<IntRange>()
            var start = 0
            for (k in 1..legV.size) {
                val split = k == legV.size || input.source != ClipSource.Recorded || legV[k] != legV[start] || legDay[k] != legDay[start]
                if (!split) continue
                var len = 0.0
                for (i in start until k) len += legReal[i]
                val plane = legV[start] == Vehicle.Plane && len >= JUMP_PLANE_M
                val straight = input.source != ClipSource.Recorded && len >= JUMP_STRAIGHT_M
                if (plane || straight) runs += start until k
                start = k
            }
            if (runs.isEmpty()) return emptyList()
            val out = ArrayList<FollowCamera.Jump>()
            for (run in runs) {
                var from = -1
                var to = -1
                for (f in drawStart until drawEnd) if (headSeg[f] in run) { if (from < 0) from = f; to = f + 1 }
                if (from < 0) continue
                out += FollowCamera.Jump(from, to, bounds(px, py, run.first, run.last + 2))
            }
            return out
        }
    }
}

private const val HOUR = 3_600_000L

/**
 * What the journey stops for after a moving frame, in this order in one place: the first half of a close-up's wait,
 * the time at the stop ([StopStays]), the photo pauses (in the middle of it, the still map before the first and after the last: [Gap], put in after the
 * others are in order), its second half, the phone videos after the place, the next day's card.
 */
internal enum class HoldKind(val still: Boolean, val adds: Boolean = true) {
    StillIn(true, adds = false), Stay(true), Photo(true), StillOut(true, adds = false), Video(false), DayCard(false), Gap(true);

    // [adds]: the hold adds time on top of the journey's (a close-up's waits take theirs from it).
}

/**
 * One stop of [frames] frames: a held photo's or a video's [id], the place name [key] a video follows (a day card's
 * own key), the day card's text ([card]), the other photos on a held photo's album page ([also]).
 */
internal class Hold(
    val kind: HoldKind, var frames: Int, val id: String? = null, val key: String? = null, val card: DayCard? = null,
    val also: List<String> = emptyList(),
)

/** A photo the journey pauses for after moving frame [k], for [frames], with the other photos on its album page ([also]). */
internal data class HeldPhoto(val id: String, val k: Int, val frames: Int, val also: List<String> = emptyList())

/** The output frames laid out: each one's position on the journey ([src], moving frames; −1 before it), and what shows. */
internal class LaidFrames(
    val src: DoubleArray, val outOf: IntArray, val drawEnd: Int,
    val heldPops: List<PhotoPop>, val stills: List<FollowCamera.Still>, val inserts: List<Insert>,
)

/**
 * The output frames: [drawStart] frames of opening, then each of [moving] frames once with what it stops for right
 * after it ([blocks], by moving frame; −1 before the first), then [outro] frames of close. During a stop the head stands
 * at its moving frame. [stillAt] marks the close-ups (their wait covers the photo pauses in its middle). [runs] (one
 * per stretch of moving frames between stops, and one after the last) lets a stretch take more or fewer frames than
 * it has (cuts on the beat): its frames then spread evenly over it.
 */
internal fun layFrames(
    drawStart: Int, moving: Int, blocks: java.util.SortedMap<Int, MutableList<Hold>>, stillAt: Map<Int, Pair<Int, Double>>, outro: Int,
    runs: IntArray? = null,
): LaidFrames {
    val src = ArrayList<Double>(drawStart + moving + outro + blocks.values.sumOf { b -> b.sumOf { it.frames } })
    repeat(drawStart) { src += -1.0 }
    val outOf = IntArray(moving + 1)
    val held = ArrayList<PhotoPop>()
    val stills = ArrayList<FollowCamera.Still>()
    val inserts = ArrayList<Insert>()
    var prev = -1
    var run = 0
    fun stretch(to: Int) {
        val first = prev + 1
        val count = to - prev
        val frames = if (count <= 0) 0 else (runs?.getOrNull(run) ?: count).coerceAtLeast(1)
        run++
        val start = src.size
        val still = frames == 1 || to == first
        for (j in 0 until frames) {
            src += when {
                frames == count -> (first + j).toDouble()
                still -> to.toDouble()
                else -> first + j * (to - first).toDouble() / (frames - 1)
            }
        }
        // Each moving frame's first output frame at or after it.
        for (k in first..to) outOf[k] = start + when {
            frames == count -> k - first
            still -> 0
            else -> ceil((k - first) * (frames - 1).toDouble() / (to - first) - 1e-9).toInt()
        }
        prev = max(prev, to)
    }
    for ((afterK, items) in blocks) {
        stretch(afterK)
        val at = afterK.coerceAtLeast(0).toDouble()
        val start = src.size
        var stillEnd = start
        for (item in items) {
            val from = src.size
            repeat(item.frames) { src += at }
            when (item.kind) {
                HoldKind.Photo -> (listOf(requireNotNull(item.id)) + item.also).forEachIndexed { i, id ->
                    // An album page's photos share the pause.
                    held += PhotoPop(id, from, src.size, held.size, held = true, album = i, albumOf = 1 + item.also.size)
                }
                HoldKind.Video -> inserts += Insert(InsertKind.Video, requireNotNull(item.key), from, src.size, item.id)
                HoldKind.DayCard -> inserts += Insert(InsertKind.DayCard, requireNotNull(item.key), from, src.size, card = item.card)
                else -> Unit
            }
            if (item.kind.still) stillEnd = src.size
        }
        stillAt[afterK]?.let { if (stillEnd > start) stills += FollowCamera.Still(start, stillEnd, it.second) }
    }
    stretch(moving - 1)
    outOf[moving] = src.size
    val drawEnd = src.size
    repeat(outro) { src += moving.toDouble() }
    return LaidFrames(src.toDoubleArray(), outOf, drawEnd, held, stills, inserts)
}

/** How each kind of stop may change length on the beat: close-ups only longer (their zoom needs its time), videos only shorter. */
private fun beatRound(kind: HoldKind): BeatFit.Round = when (kind) {
    HoldKind.StillIn, HoldKind.StillOut -> BeatFit.Round.Up
    HoldKind.Video -> BeatFit.Round.Down
    HoldKind.Photo, HoldKind.DayCard, HoldKind.Gap, HoldKind.Stay -> BeatFit.Round.Nearest
}

/** [a] at fractional index [x] (linear between its neighbours, clamped to its ends). */
private fun lerpAt(a: DoubleArray, x: Double): Double {
    if (a.isEmpty()) return 0.0
    if (x <= 0) return a[0]
    val i = x.toInt()
    if (i >= a.size - 1) return a[a.size - 1]
    val t = x - i
    return if (t == 0.0) a[i] else a[i] + (a[i + 1] - a[i]) * t
}

/** Ground metres between two world points, the short way round (a Globe route runs on past 180°). */
private fun metres(x1: Double, y1: Double, x2: Double, y2: Double): Double =
    hypot(wrapped(x2 - x1), y2 - y1) / Mercator.unitsPerMeter((y1 + y2) / 2)

/** A difference of world columns, the short way round: within half a world. */
private fun wrapped(dx: Double): Double = dx - Math.rint(dx)

/** Segment index and fraction for a value on a non-decreasing cumulative array. */
internal fun locate(cum: DoubleArray, value: Double): Pair<Int, Double> {
    val n = cum.size
    if (n < 2) return 0 to 0.0
    if (value <= cum[0]) return 0 to 0.0
    if (value >= cum[n - 1]) return n - 2 to 1.0
    var lo = 0
    var hi = n - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (cum[mid] <= value) lo = mid else hi = mid
    }
    val span = cum[lo + 1] - cum[lo]
    return lo to if (span > 0) (value - cum[lo]) / span else 1.0
}

/** The point [dist] metres along the route, between its points as [between] says (straight, or the great circle). */
private fun at(
    realCum: DoubleArray, px: DoubleArray, py: DoubleArray, dist: Double,
    between: (DoubleArray, DoubleArray, Int, Double) -> Pair<Double, Double>,
): Pair<Double, Double> {
    val (i, t) = locate(realCum, dist)
    return between(px, py, i, t)
}

/** First frame whose head has reached [dist] metres; [fallback] when it never does. */
private fun firstFrameAt(headDist: DoubleArray, dist: Double, fallback: Int): Int {
    var lo = 0
    var hi = headDist.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (headDist[mid] >= dist - 1e-6) hi = mid else lo = mid + 1
    }
    return if (lo < headDist.size) lo else fallback
}

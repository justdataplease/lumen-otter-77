package com.justdataplease.boringtravel.clip

import kotlin.math.max
import kotlin.math.min

/** Where the place name sits on the video. */
enum class NamePlace(val id: String, val label: String) { Top("top", "Top"), Bottom("bottom", "Bottom"), Off("off", "Off") }

/** The opening over the whole route: the trip's title and dates, or nothing. */
enum class Opening(val id: String, val label: String) { Title("title", "Title"), None("none", "None") }

/** The face of the place name and the opening (all bundled fonts; "Theme" is the theme's own title face). */
enum class ClipFont(val id: String, val label: String) {
    Theme("theme", "Theme"), Sans("sans", "Sans"), Serif("serif", "Serif"), Hand("hand", "Hand"), Tall("tall", "Tall");

    /** Sizes × this, so each face has about the same presence (Caveat is small on its body). */
    val scale: Float get() = when (this) { Hand -> 1.25f; Tall -> 1.1f; else -> 1f }
}

/** What the text sits on: nothing (a soft shadow keeps it readable), a solid panel, or frosted glass. */
enum class TextBack(val id: String, val label: String) { None("none", "None"), Panel("panel", "Panel"), Glass("glass", "Glass") }

enum class PanelShape(val id: String, val label: String) { Rounded("rounded", "Rounded"), Pill("pill", "Pill"), Square("square", "Square") }

/** The text's colour scheme: the panel's colour, or the letters' colour without a panel. */
enum class TextColor(val id: String, val label: String) {
    Light("light", "Light"), Dark("dark", "Dark"), Theme("theme", "Theme"), Accent("accent", "Accent"),
}

enum class LineStyle(val id: String, val label: String) { Solid("solid", "Solid"), Dashed("dashed", "Dashed") }

/** Route line thickness in design dp (the frame's short side is 360 dp). */
enum class LineWeight(val id: String, val label: String, val dp: Float) {
    Thin("thin", "Thin", 3.5f), Medium("medium", "Medium", 5.5f), Bold("bold", "Bold", 8f),
}

enum class LineColor(val id: String, val label: String) {
    Theme("theme", "Theme"), White("white", "White"), Black("black", "Black"), Accent("accent", "Accent"),
}

/**
 * The photo rows the editor offered before the thumb pad ([ClipDesign.photoY]); older styles and edits are read with
 * them: Top, Middle and Bottom are the pad's 0, 0.5 and 1 ([Spots.rowY]). The default spot is still worked out by row
 * ([ClipLayout.cardCentre]).
 */
enum class PhotoRow(val id: String, val label: String) { Top("top", "Top"), Middle("middle", "Middle"), Bottom("bottom", "Bottom") }

/** The sides the editor offered before the thumb pad ([ClipDesign.photoX]); Both alternates left and right. */
enum class PhotoSide(val id: String, val label: String) {
    Both("both", "Both"), Left("left", "Left"), Centre("centre", "Centre"), Right("right", "Right"),
}

/**
 * Photos on or off ([Auto] or [Off], all the editor offers now: owner, 2026-11-09: "many many choices no need them
 * all"). [Auto], the default (owner, 2026-11-09: "pause 3s the video the image appears and disappears when there is
 * movement"): the journey stops while a photo shows ([ClipDesign.photoHoldS], the Photo pause slider) and the photo goes
 * when it moves on; one photo per place, spread over the route as far as the pauses fit ([ClipOptions.photoAuto]);
 * photos far off the route or without a place join the nearest stop by time. [On], [Pause2] and [Pause3] are read from
 * older styles as Auto ([ClipPrefs.decode]) and stay for the plan's own tests.
 */
enum class PhotoMode(val id: String, val label: String, val holdSeconds: Double) {
    Auto("auto", "Auto", 3.0),
    Off("off", "Off", 0.0), On("on", "On", 0.0), Pause2("pause2", "Pause 2 s", 2.0), Pause3("pause3", "Pause 3 s", 3.0);

    val photos: Boolean get() = this != Off
}

/** The S, M and L the editor offered for a photo before its size slider; older edits are read as [scale]. */
enum class PhotoSize(val id: String, val label: String, val scale: Float) {
    S("s", "S", 0.78f), M("m", "M", 1f), L("l", "L", 1.3f),
}

/**
 * The type sizes before the size slider ([ClipDesign.textScale]): older styles are read as [scale], and the looks use
 * them (Minimal's small type, Bold's big type).
 */
enum class TextSize(val id: String, val label: String, val scale: Float) {
    Small("small", "Small", 0.82f), Normal("normal", "Normal", 1f), Large("large", "Large", 1.25f),
}

/**
 * The border round a photo card. [Theme]: the app theme's print border (Me › Theme: white, or the card colour, some
 * with a deeper foot; the photo cropped square), as cards always had. [None]: the photo alone, rounded, with its shadow.
 */
enum class PhotoFrame(val id: String, val label: String) { Theme("theme", "Theme"), None("none", "None") }

/**
 * The thumb pad's sums (owner, 2026-11-09: "maybe have like a slider to change all over the screen, same for side,
 * like a playstation thumb"): a place name or a photo card is placed by two shares of the room it can move in
 * ([ClipLayout.roomX], [ClipLayout.roomY]): x 0 at the left … 1 at the right, y 0 at the top … 1 at the bottom, 0.5 the
 * middle whatever the item's size. Null is the default spot.
 */
object Spots {
    /** The text's size range on the sliders: the clip's type (× its normal size), or one scene's (× the clip's). */
    val TEXT_SCALE = 0.7f..1.7f
    /** What [ClipDesign.textScale] may hold: the slider's range over any look's own size. */
    val TEXT_SCALE_ALL = 0.5..2.1
    /** A photo card's size range on the slider. */
    val PHOTO_SCALE = 0.5f..1.5f
    /** The stats line's size range on its slider (× its small default). */
    val STATS_SCALE = 0.6f..2.5f
    /** The Photo pause slider's range for all photos, in seconds (one photo's own pause may also be 0: no pause). */
    const val HOLD_MIN_S = 1.0

    fun rowY(row: PhotoRow): Float = when (row) {
        PhotoRow.Top -> 0f
        PhotoRow.Middle -> 0.5f
        PhotoRow.Bottom -> 1f
    }

    /** Both (left and right in turn) has no single point: null. */
    fun sideX(side: PhotoSide): Float? = when (side) {
        PhotoSide.Left -> 0f
        PhotoSide.Centre -> 0.5f
        PhotoSide.Right -> 1f
        PhotoSide.Both -> null
    }

    /** A share as stored: within 0–1, rounded to 0.1 % so a finger's jitter doesn't make a new edit. */
    fun share(v: Float): Float = (kotlin.math.round(v.coerceIn(0f, 1f) * 1000f) / 1000f)

    /** A size as stored: within [range], to the whole percent. */
    fun scale(v: Float, range: ClosedFloatingPointRange<Float>): Float = kotlin.math.round(v.coerceIn(range) * 100f) / 100f

    /** A pause as stored: half seconds within 0–[max]. */
    fun hold(s: Double, max: Double = ClipDesign.MAX_HOLD_S): Double = (kotlin.math.round(s * 2) / 2).coerceIn(0.0, max)
}

/**
 * One number on the trip's stats line (owner, 2026-11-09: "show some statistics like km or days … select font size
 * and position on screen"), counting up as the route goes ([ClipPlan.statsAt]): the day the journey is on, the distance
 * it has come.
 */
enum class TripStat(val id: String, val label: String) {
    Day("day", "Day"), Distance("km", "Distance");

    companion object {
        /** What the stats line shows by default. */
        val DEFAULT: Set<TripStat> = setOf(Day, Distance)

        /** "day,km" as [ClipPrefs] keeps it; unknown ids are dropped. */
        fun read(raw: String?): Set<TripStat>? = raw?.let { s ->
            if (s == "none") emptySet() else s.split(',').mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }.toSet()
        }

        fun write(stats: Set<TripStat>): String = if (stats.isEmpty()) "none" else entries.filter { it in stats }.joinToString(",") { it.id }
    }
}

/**
 * What the stats line shows, as the editor offers it (owner, 2026-11-09: "select if i want day, day km, km only or
 * nothing at all").
 */
enum class StatsShow(val label: String, val stats: Set<TripStat>) {
    Both("Day · km", setOf(TripStat.Day, TripStat.Distance)), Day("Day", setOf(TripStat.Day)),
    Km("km", setOf(TripStat.Distance)), None("None", emptySet());

    companion object {
        fun of(stats: Set<TripStat>): StatsShow = entries.firstOrNull { it.stats == stats } ?: Both
    }
}

/** The colour of the pictures (map, photos, videos): as they are, or warmer and a little softer like film. */
enum class ClipTone(val id: String, val label: String) { Natural("natural", "Natural"), Warm("warm", "Warm") }

/**
 * How a photo the journey stops for shows (owner, 2026-11-09: "for images add a layout option maybe I want like full
 * screen or 2 pictures, see layout in photo album"): the card over the map (where its position and side put it), the
 * photo filling the frame, or an album page with up to [ALBUM_MAX] photos of the place at once, laid out like the
 * book's album pages ([com.justdataplease.boringtravel.album.AlbumLayouts]). A photo with no pause pops in as a card
 * whatever the layout.
 */
enum class PhotoLayout(val id: String, val label: String) {
    Card("card", "Card"), Full("full", "Full screen"), Album("album", "Album");

    companion object {
        /** The most photos one album pause shows. */
        const val ALBUM_MAX = 4

        fun of(id: String?): PhotoLayout? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Named looks (owner: "Looks and sizes"): each sets the text, line and finish choices together; the photos, the
 * camera and where the place name sits stay as they were, and any single option can still be changed after. Postcard
 * is the clip as it always looked.
 */
enum class ClipLook(val id: String, val label: String) {
    Postcard("postcard", "Postcard"),
    /** Letterbox bars, warm tone, light grain, serif type without panels, a thin white line. */
    Film("film", "Film"),
    /** No panels, small sans type, a thin line. */
    Minimal("minimal", "Minimal"),
    /** Big condensed type on square accent panels, a thick accent line. */
    Bold("bold", "Bold");

    /** [d] with this look's choices. */
    fun apply(d: ClipDesign): ClipDesign = when (this) {
        Postcard -> d.copy(
            font = ClipFont.Theme, back = TextBack.Panel, panel = PanelShape.Rounded, color = TextColor.Light,
            line = LineStyle.Solid, weight = LineWeight.Medium, lineColor = LineColor.Theme, textSize = TextSize.Normal,
            textScale = 1f, bars = false, tone = ClipTone.Natural, grain = false,
        )
        Film -> d.copy(
            font = ClipFont.Serif, back = TextBack.None, panel = PanelShape.Rounded, color = TextColor.Light,
            line = LineStyle.Solid, weight = LineWeight.Thin, lineColor = LineColor.White, textSize = TextSize.Normal,
            textScale = 1f, bars = true, tone = ClipTone.Warm, grain = true,
        )
        Minimal -> d.copy(
            font = ClipFont.Sans, back = TextBack.None, panel = PanelShape.Rounded, color = TextColor.Light,
            line = LineStyle.Solid, weight = LineWeight.Thin, lineColor = LineColor.Theme, textSize = TextSize.Small,
            textScale = 1f, bars = false, tone = ClipTone.Natural, grain = false,
        )
        Bold -> d.copy(
            font = ClipFont.Tall, back = TextBack.Panel, panel = PanelShape.Square, color = TextColor.Accent,
            line = LineStyle.Solid, weight = LineWeight.Bold, lineColor = LineColor.Accent, textSize = TextSize.Large,
            textScale = 1f, bars = false, tone = ClipTone.Natural, grain = false,
        )
    }

    companion object {
        fun of(id: String?): ClipLook? = entries.firstOrNull { it.id == id }

        /** The look [d] has, or null when a single option was changed after picking one ("custom"). */
        fun of(d: ClipDesign): ClipLook? = entries.firstOrNull { it.apply(d) == d }
    }
}

/**
 * One scene's changes on top of the global style (the scene editor fills these; null = the global or automatic
 * choice). Keys: [ClipDesign.photoKey] for a photo card, [ClipDesign.labelKey] for a place name, [ClipDesign.OPENING]
 * and [ClipDesign.END]. Each kind reads only its own fields.
 */
data class SceneOverride(
    /** Place name: Top or Bottom (Off reads as hidden). */
    val name: NamePlace? = null,
    /** Place name, opening or photo card: its middle as a share of the frame's height (the thumb pad, or dragged). */
    val y: Float? = null,
    /** Place name or opening: hidden (true), or shown even when the global style hides it (false). */
    val hidden: Boolean? = null,
    /** The place name's text, the opening's title, or the end card's closing line (blank = automatic). */
    val text: String? = null,
    /** Place name or day card: the small line over the name ("Day 4" by default); blank leaves it out. */
    val kicker: String? = null,
    /** Place name, day card or opening: a short note under the name ("Sunset at the miradouro"). */
    val note: String? = null,
    /** Place name or day card: the exact spot, smallest, under the note ("Rua Augusta 24, Baixa"). */
    val spot: String? = null,
    /** The place name's or the title's own font, background and colour. */
    val font: ClipFont? = null,
    val back: TextBack? = null,
    val color: TextColor? = null,
    /** Photo: preset row and side. */
    val photoRow: PhotoRow? = null,
    val photoSide: PhotoSide? = null,
    /** Place name, opening or photo card: its middle as a share of the frame's width (with [y]). */
    val x: Float? = null,
    /** Photo: in the clip (true), left out (false), or as the clip picks (null). */
    val show: Boolean? = null,
    /** Photo: seconds the journey pauses for it (0 = it shows over the moving route). */
    val hold: Double? = null,
    /** Photo: the S, M and L chips before the size slider (read as [scale] when that is unset). */
    val size: PhotoSize? = null,
    /** Photo: its size on the slider (× the shape's own card width; null: the clip's [ClipDesign.photoScale]). */
    val scale: Float? = null,
    /** Place name or opening: its type's size on the slider, × the clip's ([ClipDesign.typeScale]). */
    val textScale: Float? = null,
    /** Photo: degrees of tilt (null = the look's own lean). */
    val tilt: Float? = null,
    /** Photo: a short line under the card, in the place name's style. */
    val caption: String? = null,
    /** Opening: the trip's dates over the title. */
    val dates: Boolean? = null,
    /** Place name: the camera this many steps closer than the clip's zoom while it shows (negative: wider). */
    val zoom: Int? = null,
    /** Place name: the close-up at its stops (null: the clip's). */
    val closeUp: CloseUp? = null,
    /** Place name: seconds the journey stands still at its stop ("Time here"; null: Auto, [StopStays]). */
    val stay: Double? = null,
    /**
     * Tags picked by hand in the clip (1.10 and 1.11), kept so those designs still load but no longer shown: a place's
     * tags now come from its plan items (owner, 2026-11-12: "clip inherits tags from plan items"; [ClipPlan.tags]).
     */
    val tags: List<String>? = null,
    /** The place's tags not shown (owner, 2026-11-12: tags "can be disabled enabled"); null: as the clip ([ClipDesign.tags]). */
    val tagsHidden: Boolean? = null,
    /** The tag stickers' colour and size (null: the clip's, [ClipDesign.tagColor], [ClipDesign.tagScale]) and spot (shares; null: under the text). */
    val tagColor: TagColor? = null,
    val tagScale: Float? = null,
    val tagX: Float? = null,
    val tagY: Float? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = SceneOverride()
    }
}

/**
 * What the plan needs from the scene editor (photos left out or asked for, pauses, order); the rest is drawing only.
 * Ids are media ids; [order] is keyed by the place name's key ([ClipDesign.labelKey]).
 */
data class PhotoEdits(
    val excluded: Set<String> = emptySet(),
    val included: Set<String> = emptySet(),
    val holds: Map<String, Double> = emptyMap(),
    val order: Map<String, List<String>> = emptyMap(),
    /** Each place's own photos in order ([ClipEdits.picks]): what shows there, ahead of [excluded], [included] and [order]. */
    val picks: Map<String, List<String>> = emptyMap(),
)

/**
 * How the clip looks, chosen on the clip screen and remembered on this phone ([ClipPrefs]): the opening, the place
 * name and its type, panel and colours, the route line, where photo cards sit, and the camera's zoom and close-ups.
 * Only [photoMode], [camera], [zoom], [closeUp], their edits in [overrides] and [order], the [videos], the day cards
 * and the [sky] change the plan (they map to [ClipOptions.photos], [ClipOptions.photoHoldSeconds],
 * [ClipOptions.photoAuto], [ClipOptions.photoEdits], [ClipOptions.camera], [ClipOptions.zoom], [ClipOptions.closeUp],
 * [ClipOptions.cameraEdits], [ClipOptions.videos], [ClipOptions.dayCards] and [ClipOptions.sky]: the map pictures are
 * fetched for the ground the plan says is visible); the [voices] go to the sound; the rest is drawing only.
 */
data class ClipDesign(
    val opening: Opening = Opening.Title,
    val name: NamePlace = NamePlace.Top,
    val font: ClipFont = ClipFont.Theme,
    val back: TextBack = TextBack.Panel,
    val panel: PanelShape = PanelShape.Rounded,
    val color: TextColor = TextColor.Light,
    val line: LineStyle = LineStyle.Solid,
    val weight: LineWeight = LineWeight.Medium,
    val lineColor: LineColor = LineColor.Theme,
    val photoRow: PhotoRow = PhotoRow.Bottom,
    /** Cards at the bottom right by default (owner, 2026-11-09: "the image at the bottom of the screen … bottom right"). */
    val photoSide: PhotoSide = PhotoSide.Right,
    val photoMode: PhotoMode = PhotoMode.Auto,
    /** How the camera moves: one steady height per stretch (the default), or the older adaptive follow. */
    val camera: CameraMode = CameraMode.Steady,
    /** How close the camera gets (Normal: as it always was). */
    val zoom: ClipZoom = ClipZoom.Normal,
    /** The close, still view at stops (off unless chosen). */
    val closeUp: CloseUp = CloseUp.Off,
    /** How far the camera pulls back ([ZoomOut]; Auto as the clip always did). */
    val zoomOut: ZoomOut = ZoomOut.Auto,
    /** The type's size (a look's small or big type). */
    val textSize: TextSize = TextSize.Normal,
    /** Film: letterbox bars at the top and bottom ([ClipLayout.filmBar]); the texts keep clear of them. */
    val bars: Boolean = false,
    /** The pictures' colour (map, photos, videos). */
    val tone: ClipTone = ClipTone.Natural,
    /** Film: a light grain over the whole frame, moving every frame. */
    val grain: Boolean = false,
    /** A full-frame card ("Day 14", the place, the date) opening each day; each day can say otherwise ([dayCardKey]). */
    val dayCards: Boolean = false,
    /**
     * In 3D, a band of sky at the top ([ClipOptions.sky]); off unless switched on (owner, on 1.4.0's videos: "there
     * is a gradient glare below the top place sign, can you remove it by default?").
     */
    val sky: Boolean = false,
    /** Seconds of still map before and after each photo pause ([ClipOptions.photoGapSeconds]; 0 to [MAX_GAP_S]). */
    val photoGapS: Double = PHOTO_GAP_S,
    /** A planned route's stops within a place show their own titles ([ClipOptions.stopTitles]). */
    val stopTitles: Boolean = true,
    /**
     * How the photos the journey stops for show ([PhotoLayout]: the card, the whole frame, an album page); the whole
     * frame by default (owner, 2026-11-09: "by default keep the full screen").
     */
    val photoLayout: PhotoLayout = PhotoLayout.Full,
    /**
     * The Text size slider (owner, 2026-11-09: "also font size we need"): the type's size × the look's own
     * ([textSize]); 1 = as the look has it. A place name or the opening can have its own ([SceneOverride.textScale]).
     */
    val textScale: Float = 1f,
    /**
     * Every place name's middle on the thumb pad (owner, 2026-11-09: "position does not go to bottom … like a
     * playstation thumb"), as shares of the frame; null: at the top or the bottom ([name]).
     */
    val nameX: Float? = null,
    val nameY: Float? = null,
    /** The photo cards' middle on the thumb pad (shares of the frame); null: by [photoRow] and [photoSide] as before. */
    val photoX: Float? = null,
    val photoY: Float? = null,
    /**
     * The Photo pause slider (owner, 2026-11-09: "add a slider, this is how much time an image is paused"): seconds
     * the journey stops for each photo Auto shows ([ClipOptions.photoHoldSeconds]).
     */
    val photoHoldS: Double = PhotoMode.Auto.holdSeconds,
    /** The photo cards' size on the slider (× the shape's own card width); a photo can have its own ([SceneOverride.scale]). */
    val photoScale: Float = 1f,
    /** The border round a photo card: the theme's print border, or none ([PhotoFrame]). */
    val photoFrame: PhotoFrame = PhotoFrame.Theme,
    /**
     * The trip's stats line ([TripStat]: the day and the distance so far; none hides it), small in the bottom left
     * corner by default (owner, 2026-11-09: "by default keep them bottom left small", then circled to the very corner).
     */
    val stats: Set<TripStat> = TripStat.DEFAULT,
    /** Its type's size on its own slider (× its small default size; [Spots.STATS_SCALE]). */
    val statsScale: Float = 1f,
    /**
     * Its middle on the thumb pad, anywhere on the frame like a photo or a place name (owner, 2026-11-09: "change it
     * same as you have the image … full change in screen"), as shares of the frame; null: the bottom left corner.
     */
    val statsX: Float? = null,
    val statsY: Float? = null,
    /** Its own font, background and colour (owner: "again same if transparent etc"); no panel by default. */
    val statsFont: ClipFont = ClipFont.Theme,
    val statsBack: TextBack = TextBack.None,
    val statsColor: TextColor = TextColor.Light,
    /** Per-scene changes for this trip and route (the scene editor; kept by [ClipEditStore], not in [ClipPrefs]). */
    val overrides: Map<String, SceneOverride> = emptyMap(),
    /** Photo order per place name key (the scene editor). */
    val order: Map<String, List<String>> = emptyMap(),
    /** Each place's own photos, in order (the "Media here" panel; [ClipEdits.picks]). */
    val picks: Map<String, List<String>> = emptyMap(),
    /** Phone videos after place names (per trip and route, kept with the scene edits). */
    val videos: List<ClipVideo> = emptyList(),
    /** Voice lines, one per place name or day (per trip and route, kept with the scene edits). */
    val voices: List<VoiceLine> = emptyList(),
    /** This video's own traveller (per trip and route, kept with the scene edits); null: [travellerDefault]. */
    val traveller: Traveller? = null,
    /** Me › Your traveller: who walks the legs on foot unless this video has its own (not kept here). */
    val travellerDefault: Traveller = Traveller.DEFAULT,
    /** This video's own rides (per trip and route, kept with the scene edits); null: [ridesDefault]. */
    val rides: Rides? = null,
    /** Me › Your traveller & rides: how the vehicles look unless this video has its own (not kept here). */
    val ridesDefault: Rides = Rides.DEFAULT,
    /** Where the music starts in its track (per trip and route, kept with the scene edits); null: its strongest stretch. */
    val musicStartS: Double? = null,
    /** Plan items left out of a planned route (per trip and route, kept with the scene edits; [ClipEdits.skipped]). */
    val skipped: Set<String> = emptySet(),
    /**
     * The places' tags as stickers under their names (owner, 2026-11-12: "clip inherits tags from plan items"): on
     * unless switched off; a place can say otherwise ([SceneOverride.tagsHidden]).
     */
    val tags: Boolean = true,
    /** The tag stickers' colour and size (× their own) for the whole clip; a place can have its own. */
    val tagColor: TagColor = TagColor.DEFAULT,
    val tagScale: Float = 1f,
) {
    /** Who walks the legs on foot in this video. */
    val travellerShown: Traveller get() = traveller ?: travellerDefault

    /** How the vehicles look in this video. */
    val ridesShown: Rides get() = rides ?: ridesDefault

    fun scene(key: String?): SceneOverride = key?.let { overrides[it] } ?: SceneOverride.NONE

    fun photoRow(photoId: String): PhotoRow = overrides[photoKey(photoId)]?.photoRow ?: photoRow
    fun photoSide(photoId: String): PhotoSide = overrides[photoKey(photoId)]?.photoSide ?: photoSide

    /** Whether the place name [labelKey] shows: its own choice, else the global one (Off hides every name). */
    fun labelVisible(labelKey: String?): Boolean {
        val o = scene(labelKey)
        return when (o.hidden) {
            true -> false
            false -> true
            null -> o.name?.let { it != NamePlace.Off } ?: (name != NamePlace.Off)
        }
    }

    /** Top or Bottom for the place name [labelKey] (a shown name never sits "Off"). */
    fun labelPlace(labelKey: String?): NamePlace =
        scene(labelKey).name?.takeIf { it != NamePlace.Off } ?: name.takeIf { it != NamePlace.Off } ?: NamePlace.Top

    /**
     * The place name [labelKey] as shown: [auto] with its own name and its own small line over it ([SceneOverride.kicker]:
     * blank leaves it out), or null when hidden.
     */
    fun label(labelKey: String?, auto: ClipLabel): ClipLabel? {
        if (!labelVisible(labelKey)) return null
        val o = scene(labelKey)
        val ownText = o.text?.trim()?.takeIf { it.isNotEmpty() }
        val ownKicker = o.kicker?.trim()
        if (ownText == null && ownKicker == null) return auto
        // A name that is only "Day 4" stays the name when a small line is put over it.
        val name = ownText ?: auto.place ?: auto.day
        val autoKicker = if (ownText == null && auto.place == null) null else auto.day
        return ClipLabel(if (ownKicker == null) autoKicker else ownKicker.ifEmpty { null }, name)
    }

    /** The note under a scene's name ([SceneOverride.note]), or null. */
    fun note(key: String?): String? = scene(key).note?.trim()?.takeIf { it.isNotEmpty() }

    /** The exact spot under a scene's note ([SceneOverride.spot]), or null. */
    fun spot(key: String?): String? = scene(key).spot?.trim()?.takeIf { it.isNotEmpty() }

    /** The place name's own font, background and colour, else the global ones. */
    fun labelFont(labelKey: String?): ClipFont = scene(labelKey).font ?: font
    fun labelBack(labelKey: String?): TextBack = scene(labelKey).back ?: back
    fun labelColor(labelKey: String?): TextColor = scene(labelKey).color ?: color

    /** The type's size for scene [key] (a place name or the opening): the look's, × the slider, × its own. */
    fun typeScale(key: String? = null): Float = textSize.scale * textScale * (key?.let { scene(it).textScale } ?: 1f)

    /**
     * Where the place name or opening [key] has its middle (shares of the frame): its own spot, else every name's
     * ([nameX], [nameY]); null: at the top or the bottom. A name moved only up or down stays centred.
     */
    fun nameSpot(key: String?): Pair<Float, Float>? {
        val o = scene(key)
        val y = o.y ?: nameY ?: return null
        return (o.x ?: nameX ?: 0.5f) to y
    }

    /** Where photo [photoId]'s card has its middle (shares of the frame): its own spot, else every card's; null: by row and side. */
    fun photoSpot(photoId: String): Pair<Float, Float>? {
        val o = scene(photoKey(photoId))
        val x = o.x
        val y = o.y
        if (x != null && y != null) return x to y
        val gx = photoX
        val gy = photoY
        return if (gx != null && gy != null) gx to gy else null
    }

    /** Photo [photoId]'s card size: its own (the slider, else the older S/M/L), else the clip's. */
    fun photoScale(photoId: String): Float {
        val o = scene(photoKey(photoId))
        return o.scale ?: o.size?.scale ?: photoScale
    }

    /** Whether the stats line shows at all. */
    val statsShown: Boolean get() = stats.isNotEmpty()

    /** Where the stats line has its middle (shares of the frame), or null: the bottom left corner. */
    val statsSpot: Pair<Float, Float>? get() {
        val x = statsX
        val y = statsY
        return if (x != null && y != null) x to y else null
    }

    /** Seconds the journey stops for each photo: the Photo pause slider with Auto (the older fixed pauses otherwise). */
    val holdSeconds: Double get() =
        if (photoMode == PhotoMode.Auto) photoHoldS.coerceIn(Spots.HOLD_MIN_S, MAX_HOLD_S) else photoMode.holdSeconds

    /** The camera's zoom while the place name [labelKey] shows: the clip's, moved by the scene's own steps. */
    fun zoom(labelKey: String?): ClipZoom = scene(labelKey).zoom?.let { zoom.step(it) } ?: zoom

    /** The close-up at the stops of the place name [labelKey]: its own choice, else the clip's. */
    fun closeUp(labelKey: String?): CloseUp = scene(labelKey).closeUp ?: closeUp

    /** What the plan needs from the camera changes of the place names. */
    fun cameraEdits(): CameraEdits {
        val zooms = HashMap<String, ClipZoom>()
        val closeUps = HashMap<String, CloseUp>()
        for ((key, o) in overrides) {
            if (!key.startsWith(LABEL)) continue
            o.zoom?.let { zooms[key] = zoom.step(it) }
            o.closeUp?.let { closeUps[key] = it }
        }
        return CameraEdits(zooms, closeUps)
    }

    /** Each place name's own "Time here", by its key ([ClipOptions.stays]). */
    fun stays(): Map<String, Double> = buildMap { for ((key, o) in overrides) if (key.startsWith(LABEL)) o.stay?.let { put(key, it) } }

    /** Whether the opening shows: its own choice, else the global one. */
    val openingVisible: Boolean get() = scene(OPENING).hidden?.let { !it } ?: (opening != Opening.None)

    /** The per-trip edits on their own (what [ClipEditStore] keeps). */
    val edits: ClipEdits get() = ClipEdits(overrides, order, videos, voices, traveller, rides, musicStartS, skipped, picks)

    fun withEdits(edits: ClipEdits): ClipDesign = copy(
        overrides = edits.scenes, order = edits.order, videos = edits.videos, voices = edits.voices, traveller = edits.traveller,
        rides = edits.rides, musicStartS = edits.musicStartS, skipped = edits.skipped, picks = edits.picks,
    )

    /**
     * The global choices only (designs with equal globals draw alike outside their edited scenes, the traveller and
     * the rides aside: see [travellerShown], [ridesShown]).
     */
    val globals: ClipDesign get() = copy(
        overrides = emptyMap(), order = emptyMap(), videos = emptyList(), voices = emptyList(), traveller = null,
        travellerDefault = Traveller.DEFAULT, rides = null, ridesDefault = Rides.DEFAULT, musicStartS = null,
        skipped = emptySet(), picks = emptyMap(),
    )

    /** Whether the day card [key] ([dayCardKey]) shows: its own choice, else the global one. */
    fun dayCardShown(key: String): Boolean = scene(key).hidden?.let { !it } ?: dayCards

    /** The day cards the plan puts in: every day when on, less the days hidden; with it off, the days asked for. */
    fun dayCardChoice(): DayCardChoice {
        val shown = HashSet<String>()
        val hidden = HashSet<String>()
        for ((key, o) in overrides) {
            if (!key.startsWith(DAY_CARD)) continue
            when (o.hidden) {
                true -> hidden += key
                false -> shown += key
                null -> Unit
            }
        }
        return DayCardChoice(dayCards, shown, hidden)
    }

    /** The voice line of the scene [key], if one was recorded. */
    fun voice(key: String): VoiceLine? = voices.firstOrNull { it.sceneKey == key }

    /** The named look these choices make, or null when one was changed after picking it. */
    val look: ClipLook? get() = ClipLook.of(this)

    /** What the plan needs from the per-scene edits. */
    fun photoEdits(): PhotoEdits {
        val excluded = HashSet<String>()
        val included = HashSet<String>()
        val holds = HashMap<String, Double>()
        for ((key, o) in overrides) {
            val id = photoId(key) ?: continue
            when (o.show) {
                true -> included += id
                false -> excluded += id
                null -> Unit
            }
            o.hold?.let { holds[id] = it.coerceIn(0.0, MAX_HOLD_S) }
        }
        return PhotoEdits(excluded, included, holds, order.filterValues { it.isNotEmpty() }, picks)
    }

    /**
     * Seconds the journey pauses for photo [id] when it shows (0: it shows over the moving map): its own pause, else
     * the clip's ([holdSeconds]), as the plan counts it.
     */
    fun photoPause(id: String): Double {
        if (!photoMode.photos) return 0.0
        val clip = holdSeconds
        return (scene(photoKey(id)).hold ?: clip).coerceIn(0.0, max(clip, MAX_HOLD_S))
    }

    /** Whether the place [key]'s tags show: its own switch, else the clip's. */
    fun tagsShown(key: String): Boolean = scene(key).tagsHidden?.let { !it } ?: tags

    /** The tag stickers' colour and size at the place [key]: its own, else the clip's. */
    fun tagColor(key: String): TagColor = scene(key).tagColor ?: tagColor
    fun tagScale(key: String): Float = scene(key).tagScale ?: tagScale

    /**
     * The plan options this design asks for (photos on or off, how long the journey pauses on each, the camera, its
     * zoom and close-ups, the 3D sky, the edits).
     */
    fun applyTo(options: ClipOptions): ClipOptions = options.copy(
        photos = photoMode.photos, photoHoldSeconds = holdSeconds, photoAuto = photoMode == PhotoMode.Auto,
        photoEdits = photoEdits(), camera = camera, zoom = zoom, closeUp = closeUp, zoomOut = zoomOut, cameraEdits = cameraEdits(),
        videos = videos.take(ClipVideo.MAX_VIDEOS).map { it.slot }, dayCards = dayCardChoice(),
        photoGapSeconds = photoGapS.coerceIn(0.0, MAX_GAP_S), stopTitles = stopTitles, skipped = skipped, photoLayout = photoLayout,
        sky = sky, stays = stays(),
    )

    companion object {
        const val PHOTO = "photo:"
        const val LABEL = "label:"
        const val OPENING = "opening"
        const val END = "end"
        /** A day card's key: "daycard:2027-10-04" ([SceneOverride.hidden] and [SceneOverride.text] apply). */
        const val DAY_CARD = "daycard:"
        /** The longest photo pause the editor's sliders offer. */
        const val MAX_HOLD_S = 8.0
        /** The most zoom steps a scene may move from the clip's (the whole range either way). */
        const val MAX_ZOOM_STEPS = 4
        /** The still map before and after a photo pause by default, and the most the editor's slider offers. */
        const val PHOTO_GAP_S = 0.5
        const val MAX_GAP_S = 2.0

        fun photoKey(id: String) = "$PHOTO$id"
        fun labelKey(id: String) = "$LABEL$id"
        fun dayCardKey(date: java.time.LocalDate) = "$DAY_CARD$date"
        fun photoId(key: String): String? = key.takeIf { it.startsWith(PHOTO) }?.removePrefix(PHOTO)?.takeIf { it.isNotEmpty() }
    }
}

/** The route line's sizes in pixels for a frame whose design unit is [u] px (see [ClipDesign.weight]). */
data class LineSpec(
    val width: Float,
    /** The light (or dark) edge under the line. */
    val casing: Float,
    /** On and off lengths of a dashed line (round caps add half a width at each end), or null for solid. */
    val dash: FloatArray?,
    /** The part still to come: dots of this size and spacing. */
    val futureWidth: Float,
    val futureGap: Float,
) {
    companion object {
        fun of(weight: LineWeight, style: LineStyle, u: Float): LineSpec {
            val w = weight.dp * u
            return LineSpec(
                width = w,
                casing = w + max(2.4f * u, w * 0.55f),
                dash = if (style == LineStyle.Dashed) floatArrayOf(1.6f * w, 2.2f * w) else null,
                futureWidth = max(2.8f * u, w * 0.5f),
                futureGap = max(7.5f * u, w * 1.6f),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is LineSpec && width == other.width && casing == other.casing &&
        (dash?.contentEquals(other.dash) ?: (other.dash == null)) && futureWidth == other.futureWidth && futureGap == other.futureGap

    override fun hashCode(): Int = ((width.hashCode() * 31 + casing.hashCode()) * 31 + (dash?.contentHashCode() ?: 0)) * 31 + futureWidth.hashCode()
}

/** A rectangle in frame pixels (plain, so the layout sums run on the JVM). */
data class FrameBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    fun intersects(o: FrameBox) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/**
 * Where the texts, photo cards and the watermark go in a [width] × [height] frame. [u] is the design unit (short side
 * / 360). A 9:16 story keeps clear of the top and bottom bands social apps cover with their own buttons; with Film's
 * letterbox [bars] everything keeps clear of the bars too.
 */
class ClipLayout(val width: Int, val height: Int, private val story: Boolean, bars: Boolean = false) {
    val u: Float = min(width, height) / 360f

    /** Each letterbox bar's height (0 without bars). */
    val bar: Float = if (bars) filmBar(width, height) else 0f

    /** Clear bands at the top and the bottom. */
    val safeTop: Float = max(if (story) max(18f * u, height * 0.09f) else 16f * u, if (bars) bar + 10f * u else 0f)
    /** A story's bottom fifth is under the app's caption and buttons (Reels, TikTok, Shorts). */
    val safeBottom: Float = max(if (story) max(18f * u, height * 0.20f) else 16f * u, if (bars) bar + 10f * u else 0f)
    val side: Float = 18f * u
    /** A story's right edge has the app's like and share buttons: cards and the mark keep clear of them. */
    val sideRight: Float = if (story) max(side, width * 0.12f) else side
    private val gap = 10f * u

    /** The top of a text block [blockH] high at [place] (Off puts the opening at the top). */
    fun blockTop(place: NamePlace, blockH: Float): Float = when (place) {
        NamePlace.Bottom -> height - safeBottom - blockH
        else -> safeTop
    }

    /** The widest a text block may be. */
    val blockMaxWidth: Float get() = width - 2 * side

    /** Card width for a frame of this shape; a card the journey pauses for is shown a little larger. */
    fun cardWidth(shape: ClipShape, held: Boolean, centred: Boolean): Float {
        val base = when (shape) {
            ClipShape.Story -> 0.46f
            ClipShape.Portrait -> 0.42f
            ClipShape.Square -> 0.38f
            ClipShape.Wide -> 0.26f
        }
        return width * base * (if (held) 1.15f else 1f) * (if (centred) 1.12f else 1f)
    }

    /**
     * The centre of a photo card [cardW] × [cardH] at [row] and [side] (Both: even slots left, odd right), kept clear
     * of a place name block [nameH] high at [name] when they share a row, and inside the frame.
     */
    fun cardCentre(row: PhotoRow, side: PhotoSide, slot: Int, cardW: Float, cardH: Float, name: NamePlace, nameH: Float): Pair<Float, Float> {
        val top = if (name == NamePlace.Top) safeTop + nameH + gap else safeTop
        val bottom = if (name == NamePlace.Bottom) height - safeBottom - nameH - gap else height - safeBottom
        return cardCentreWithin(row, side, slot, cardW, cardH, top, bottom)
    }

    /**
     * The same, kept clear of a place name drawn in [nameBox] (anywhere: the scene editor can move it): a name in the
     * upper half keeps cards below it, one in the lower half keeps them above.
     */
    fun cardCentre(row: PhotoRow, side: PhotoSide, slot: Int, cardW: Float, cardH: Float, nameBox: FrameBox?): Pair<Float, Float> {
        var top = safeTop
        var bottom = height - safeBottom
        if (nameBox != null) {
            if (nameBox.centerY < height / 2f) top = max(top, nameBox.bottom + gap) else bottom = min(bottom, nameBox.top - gap)
        }
        return cardCentreWithin(row, side, slot, cardW, cardH, top, bottom)
    }

    private fun cardCentreWithin(row: PhotoRow, side: PhotoSide, slot: Int, cardW: Float, cardH: Float, top: Float, bottom: Float): Pair<Float, Float> {
        val resolved = if (side == PhotoSide.Both) (if (slot % 2 == 0) PhotoSide.Left else PhotoSide.Right) else side
        // Room for the tilt and the pop-in overshoot.
        val padX = cardW * 0.06f
        val padY = cardH * 0.06f
        val x = when (resolved) {
            PhotoSide.Left -> this.side + padX + cardW / 2
            PhotoSide.Right -> width - sideRight - padX - cardW / 2
            else -> width / 2f
        }
        val minY = top + padY + cardH / 2
        val maxY = bottom - padY - cardH / 2
        val y = when (row) {
            PhotoRow.Top -> minY
            PhotoRow.Bottom -> maxY
            PhotoRow.Middle -> height / 2f
        }
        // A card taller than the room left sits in the middle of it.
        val cy = if (minY <= maxY) y.coerceIn(minY, maxY) else (minY + maxY) / 2
        return x to cy
    }

    /**
     * The clear edge a hand-placed card or text keeps (owner, 2026-11-09: "position does not go to bottom"): a little
     * room at every side of the frame, and Film's bars; not the bands a story keeps for the apps' buttons, which only
     * the default spots keep clear of.
     */
    private val edge: Float = max(8f * u, if (bars) bar + 6f * u else 0f)

    /** Where a card's middle may go when placed by hand (the thumb pad, a drag): anywhere in the frame, as shares of width and height. */
    fun cardRangeX(cardW: Float): ClosedFloatingPointRange<Float> =
        shares(8f * u + cardW * 0.56f, width - 8f * u - cardW * 0.56f, width.toFloat())

    fun cardRangeY(cardH: Float): ClosedFloatingPointRange<Float> =
        shares(edge + cardH * 0.56f, height - edge - cardH * 0.56f, height.toFloat())

    /** A card's centre in pixels for a hand-placed middle at [x], [y] (shares), kept inside [cardRangeX] and [cardRangeY]. */
    fun cardAt(x: Float, y: Float, cardW: Float, cardH: Float): Pair<Float, Float> =
        x.coerceIn(cardRangeX(cardW)) * width to y.coerceIn(cardRangeY(cardH)) * height

    /** Where a text block [blockH] high may have its middle when placed by hand: anywhere in the frame, as a share of the height. */
    fun blockRangeY(blockH: Float): ClosedFloatingPointRange<Float> =
        shares(edge + blockH / 2, height - edge - blockH / 2, height.toFloat())

    /** Where a text block [blockW] wide may have its middle: anywhere across the frame, as a share of the width. */
    fun blockRangeX(blockW: Float): ClosedFloatingPointRange<Float> =
        shares(8f * u + blockW / 2, width - 8f * u - blockW / 2, width.toFloat())

    /** The top of a text block [blockH] high whose middle is at [y] (a share of the height), inside the frame. */
    fun blockTopAt(y: Float, blockH: Float): Float = y.coerceIn(blockRangeY(blockH)) * height - blockH / 2

    /** The left of a text block [blockW] wide whose middle is at [x] (a share of the width), inside the frame. */
    fun blockLeftAt(x: Float, blockW: Float): Float = x.coerceIn(blockRangeX(blockW)) * width - blockW / 2

    private fun shares(lo: Float, hi: Float, total: Float): ClosedFloatingPointRange<Float> =
        if (lo <= hi) (lo / total)..(hi / total) else ((lo + hi) / 2 / total).let { it..it }

    /**
     * The stats line's box, [w] × [h]: its middle at [spot] (shares, kept inside the frame) when placed on the pad;
     * else in the bottom left corner of the frame itself (owner, 2026-11-09: "needed to be at the bottom bottom",
     * circled to the corner), clear of Film's bar.
     */
    fun statsBox(spot: Pair<Float, Float>?, w: Float, h: Float): FrameBox {
        val left = spot?.let { blockLeftAt(it.first, w) } ?: (10f * u)
        val top = spot?.let { blockTopAt(it.second, h) } ?: (height - max(10f * u, edge) - h)
        return FrameBox(left, top, left + w, top + h)
    }

    /** Where the stats line's middle sits by default, as shares of the frame (the thumb pad's Auto dot). */
    fun statsAuto(w: Float, h: Float): Pair<Float, Float> {
        val b = statsBox(null, w, h)
        return b.centerX / width to b.centerY / height
    }

    /**
     * A card's middle height [y] moved clear of [box] (the stats line) when a [cardW] × [cardH] card at ([x], [y])
     * would cover it: above a box in the lower half, below one in the upper half; as it was otherwise.
     */
    fun clearOf(box: FrameBox?, x: Float, y: Float, cardW: Float, cardH: Float): Float {
        if (box == null) return y
        val card = FrameBox(x - cardW / 2, y - cardH / 2, x + cardW / 2, y + cardH / 2)
        if (!card.intersects(box)) return y
        return if (box.centerY > height / 2f) min(y, box.top - gap - cardH * 0.56f) else max(y, box.bottom + gap + cardH * 0.56f)
    }

    companion object {
        /**
         * Film's letterbox bar for a [width] × [height] frame: a wide frame is cut to a 2.39:1 picture (cinema scope);
         * upright and square frames get thinner bands (8 % of a 9:16 frame's height, 9 % of a square's), which stay
         * inside a story's own clear bands.
         */
        fun filmBar(width: Int, height: Int): Float {
            val aspect = width.toFloat() / height
            return if (aspect >= 1.2f) max(0f, (height - width / 2.39f) / 2f) else height * (0.06f + 0.03f * aspect)
        }
    }
}

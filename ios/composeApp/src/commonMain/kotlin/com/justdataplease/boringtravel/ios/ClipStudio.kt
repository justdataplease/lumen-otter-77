package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.CameraMode
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipEditsJson
import com.justdataplease.boringtravel.clip.ClipExport
import com.justdataplease.boringtravel.clip.ClipGlobe
import com.justdataplease.boringtravel.clip.ClipInput
import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipLook
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipPhoto
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipSources
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.EditHistory
import com.justdataplease.boringtravel.clip.ExportBar
import com.justdataplease.boringtravel.clip.ExportStage
import com.justdataplease.boringtravel.clip.GlobeBase
import com.justdataplease.boringtravel.clip.InsertKind
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStylePrefs
import com.justdataplease.boringtravel.clip.MapTint
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.PlannedReplay
import com.justdataplease.boringtravel.clip.PreparedVideoFrames
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.TripVideo
import com.justdataplease.boringtravel.clip.VehicleSprites
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.VoiceRecorder
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicPrefs
import com.justdataplease.boringtravel.compat.Context
import com.justdataplease.boringtravel.compat.SystemClock
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.clip.PreviewMath
import com.justdataplease.boringtravel.ui.clip.musicUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the trip video's screen needs of the phone: its settings files (the video's remembered choices, as Android's
 * ClipPrefs and MusicPrefs keep them), the app's files (the notes' photos and videos, the clip's edits, videos and
 * voice lines, as Android keeps them under its files), the network for the map's style, the map's pictures, and the
 * phone's sound, microphone and videos ([device], round 19). Pro isn't sold here: [isPro] opens every length, as on
 * Android before Pro, and [noMark] is false, so every clip ends with the mark. Since round 20 also the export's: the
 * phone's time in the background ([background]), the trip's Media where a finished clip is kept ([saved]), and where
 * "Make default" puts a video's traveller and rides ([defaults]: Me › Your traveller & rides).
 */
class ClipKit(
    val prefs: Context,
    val files: MediaFiles,
    val web: Web,
    val snapshots: MapSnapshots,
    val device: ClipDevice = ClipDevice.SILENT,
    val isPro: Boolean = true,
    val noMark: Boolean = false,
    val background: BackgroundWork = NoBackgroundWork,
    val saved: SavedClips? = null,
    val defaults: TravellerDefaults = TravellerDefaults.NONE,
    /** Google Drive for Save to Drive and Send Drive link (since round 23); null where Google isn't set up. */
    val drive: com.justdataplease.boringtravel.google.DriveApi? = null,
)

/**
 * The trip video's state for trip [tripId]: the Android app's ui/clip/ClipViewModel.kt written again (its Java and
 * Android calls are this app's: the trip store, the files, the phone's [ClipDevice]), with the same members, names and
 * rules, so the editor's screens read in from Android (ui/clip/ClipTools.kt and the rest, where "ClipViewModel" is this
 * class) work on it as they are. The route sources the trip can make, the chosen one's plan of every frame, its map's
 * pictures, the photos, the look's style, the remembered choices, and the editor's edits: every change one step of
 * [history] (undo and redo), the scene edits kept for the trip and route ([ClipEditStore], Android's form), the clip's
 * choices for the next clip (the settings files). Phone videos and voice lines are copied in and recorded here
 * ([ClipMedia], [VoiceRecorder]), the videos' pictures prepared for the preview ([videoFrames]). It works in a scope of
 * its own under [scope]; [close] stops it and frees the pictures it holds. The export (round 20) is Android's: [make]
 * with its progress, then sharing ([phone]: a copy in Photos; [drive]: Google Drive, not connected on iPhone yet); no
 * edit is taken while it runs, and a travel change made meanwhile waits for it.
 */
class ClipStudio(
    private val book: TripBook,
    private val tripId: String,
    private val kit: ClipKit,
    scope: CoroutineScope,
    /** Me › Your traveller & rides (round 20): a restored backup's, else the default. */
    traveller: Traveller = Traveller.DEFAULT,
    rides: Rides = Rides.DEFAULT,
    /** The trip itself was changed here (a leg's travel, the Route tool): the app's other screens read it again. */
    private val onTripChanged: () -> Unit = {},
) {
    sealed interface Load {
        data object Loading : Load
        /** No recorded, planned, or saved-stop source has a usable route yet. */
        data object NoRoute : Load
        data object Failed : Load
        data object Ready : Load
    }

    enum class MapStatus { Loading, Ready, Plain }

    /** The microphone: idle, recording a voice line for [sceneKey] (since [startedAt], SystemClock's ms), or why it failed. */
    sealed interface Voice {
        data object Idle : Voice
        data class Recording(val sceneKey: String, val startedAt: Long) : Voice
        data class Failed(val reason: String) : Voice
    }

    /** Adding a phone video (copying it in can take a moment for a long one), or why it failed. */
    sealed interface MediaWork {
        data object Idle : MediaWork
        data object Adding : MediaWork
        data class Failed(val reason: String) : MediaWork
    }

    /** The export (Android's ClipViewModel.Make). */
    sealed interface Make {
        data object Idle : Make

        /** [progress]: the whole export's, 0 to ExportProgress.LAST; [stage]: what it is doing. */
        data class Working(val progress: Float, val stage: ExportStage = ExportStage.Picture) : Make

        /** The MP4 at [file] (under the app's files); [musicFailed]: the chosen music couldn't be added, so it is silent. */
        data class Done(val file: String, val musicFailed: Boolean = false) : Make
        data object Failed : Make
    }

    /** Google Drive (Android's ClipViewModel.Drive): the made video saved in BoringTravel › Exports, then its link. */
    sealed interface Drive {
        data object Idle : Drive
        data object Saving : Drive
        data object NotConnected : Drive

        /** In Drive at [url]; [link]: the link anyone can open, once asked for, [linking] while it is made. */
        data class Saved(
            val url: String, val link: String? = null, val linking: Boolean = false, val linkFailed: Boolean = false,
            val file: com.justdataplease.boringtravel.google.DriveFile? = null,
        ) : Drive
        data object Failed : Drive
    }

    /** A copy of the made video in the phone's photo library (Android: the gallery's Movies › BoringTravel). */
    sealed interface Phone {
        data object Idle : Phone
        data object Saving : Phone
        data object Saved : Phone
        data object Failed : Phone
    }

    val isPro: Boolean get() = kit.isPro
    val noMark: Boolean get() = kit.noMark

    /** Everything this studio starts, stopped together by [close]. */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()
    private val _sources = MutableStateFlow<List<ClipSource>>(emptyList())
    val sources: StateFlow<List<ClipSource>> = _sources.asStateFlow()
    private val _source = MutableStateFlow<ClipSource?>(null)
    val source: StateFlow<ClipSource?> = _source.asStateFlow()
    private val _planned = MutableStateFlow<PlannedReplay?>(null)
    val planned: StateFlow<PlannedReplay?> = _planned.asStateFlow()
    private val _replanning = MutableStateFlow(false)
    val replanning: StateFlow<Boolean> = _replanning.asStateFlow()

    /** Me › Your traveller: who walks unless this video has its own ([ClipDesign.travellerDefault]). */
    private var meTraveller = traveller

    /** Me › Your traveller & rides: how the vehicles look unless this video has its own ([ClipDesign.ridesDefault]). */
    private var meRides = rides

    // The style, the map look and the speed are remembered from the last clip (the defaults the first time, and once
    // more when the defaults change: ClipPrefs.newDefaults).
    private val _design = MutableStateFlow(
        ClipPrefs.run { newDefaults(kit.prefs); design(kit.prefs) }.copy(travellerDefault = traveller, ridesDefault = rides),
    )

    /** How the clip looks (texts, line, photo places) with this route's scene edits. */
    val design: StateFlow<ClipDesign> = _design.asStateFlow()

    // Cuts on the beat: on unless turned off, and only with a track.
    private val _onBeat = MutableStateFlow(MusicPrefs.onBeat(kit.prefs))

    /** Whether scene changes go on the music's beats (when a track is chosen; [ClipOptions.beats]). */
    val onBeat: StateFlow<Boolean> = _onBeat.asStateFlow()

    private val _options = MutableStateFlow(
        _design.value.applyTo(
            ClipOptions(
                speed = ClipPrefs.speed(kit.prefs), length = ClipPrefs.length(kit.prefs), shape = ClipPrefs.shape(kit.prefs),
                view = ClipPrefs.view(kit.prefs), map = MapStylePrefs.load(kit.prefs, MapStylePrefs.CLIP),
                music = MusicPrefs.load(kit.prefs),
            ),
        ).let { it.copy(beats = MusicCatalog.clipBeats(it.music, _onBeat.value, MusicCatalog.startFor(it.music, null))) },
    )
    val options: StateFlow<ClipOptions> = _options.asStateFlow()
    private val _plan = MutableStateFlow<ClipPlan?>(null)
    val plan: StateFlow<ClipPlan?> = _plan.asStateFlow()
    private val _base = MutableStateFlow(BaseMap.Plain)
    val base: StateFlow<BaseMap> = _base.asStateFlow()
    private val _mapStatus = MutableStateFlow(MapStatus.Loading)
    val mapStatus: StateFlow<MapStatus> = _mapStatus.asStateFlow()

    /** How much of the map's pictures are in while it loads (0–1): the preview waits for them before playing. */
    private val _mapProgress = MutableStateFlow(0f)
    val mapProgress: StateFlow<Float> = _mapProgress.asStateFlow()
    private val _photos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val photos: StateFlow<Map<String, Bitmap>> = _photos.asStateFlow()

    /** Whether photos are still being read for the plan (the export waits for them, round 20). */
    private val _photosLoading = MutableStateFlow(false)
    val photosLoading: StateFlow<Boolean> = _photosLoading.asStateFlow()
    private val _vehicleSprites = MutableStateFlow<Map<Vehicle, Bitmap>>(emptyMap())

    /** The paper vehicles' pictures in this video's rides (the Route tool's Travel, round 20). */
    val vehicleSprites: StateFlow<Map<Vehicle, Bitmap>> = _vehicleSprites.asStateFlow()
    private val _style = MutableStateFlow<ClipStyle?>(null)
    val style: StateFlow<ClipStyle?> = _style.asStateFlow()

    /**
     * What the editor's undo and redo step through: this route's scene edits and the clip's own choices (the style,
     * speed, shape, camera, map, music, cuts on the beat and a recording's vehicle). The choices are also remembered
     * for the next clip; the edits are kept for this trip and route ([ClipEditStore]).
     */
    data class Step(
        val edits: ClipEdits,
        /** The global style only ([ClipDesign.globals]). */
        val style: ClipDesign,
        val speed: Int,
        /** How long the clip runs ([ClipLength]); null: the trip's travel time at [speed] ("Pace"). */
        val length: ClipLength?,
        val shape: ClipShape,
        val view: ClipView,
        val map: MapStyle,
        val music: String?,
        val onBeat: Boolean,
        val vehicle: Vehicle,
    )

    // This trip's edits per route source (ClipEditStore); the current source's, with the choices, under undo and redo.
    private var savedEdits: Map<ClipSource, ClipEdits> = emptyMap()
    private val _history = MutableStateFlow(EditHistory(currentStep(ClipEdits())))
    val history: StateFlow<EditHistory<Step>> = _history.asStateFlow()

    private fun currentStep(edits: ClipEdits): Step {
        val o = _options.value
        return Step(edits, _design.value.globals, o.speed, o.length, o.shape, o.view, o.map, o.music, _onBeat.value, o.vehicle)
    }

    /**
     * The clip as the tracks editor draws it ([ClipTimeline]: the scenes back to back with the phone videos between
     * them, photos, close-ups), for the current plan and style; null while there is no plan.
     */
    val timeline: StateFlow<ClipTimeline?> = combine(_plan, _design) { p, d -> p?.let { ClipTimeline.of(it, d) } }
        .flowOn(Dispatchers.Default).stateIn(work, SharingStarted.Eagerly, null)

    private val _mediaWork = MutableStateFlow<MediaWork>(ClipStudio.MediaWork.Idle)
    val mediaWork: StateFlow<MediaWork> = _mediaWork.asStateFlow()

    /** The phone videos' pictures for the preview, prepared after each plan ([ClipVideoFrames.NONE] until then). */
    private val _videoFrames = MutableStateFlow(ClipVideoFrames.NONE)
    val videoFrames: StateFlow<ClipVideoFrames> = _videoFrames.asStateFlow()
    private var preparedVideos: Map<String, PreparedVideoFrames.Prepared> = emptyMap()
    private var videoJob: Job? = null
    private var videoJobKeys: Map<String, String>? = null

    private val _voice = MutableStateFlow<Voice>(Voice.Idle)
    val voice: StateFlow<Voice> = _voice.asStateFlow()
    private val recorder = VoiceRecorder(kit.files, tripId, kit.device.microphone)
    private var voiceTimer: Job? = null

    private val _make = MutableStateFlow<Make>(Make.Idle)
    val make: StateFlow<Make> = _make.asStateFlow()
    private val _drive = MutableStateFlow<Drive>(Drive.Idle)
    val drive: StateFlow<Drive> = _drive.asStateFlow()
    private val _phone = MutableStateFlow<Phone>(Phone.Idle)
    val phone: StateFlow<Phone> = _phone.asStateFlow()
    private var makeJob: Job? = null
    private var makeGeneration = 0L
    private var deferredChoices: Trip? = null

    /**
     * Whether the app is on screen ([setOnScreen], from the screen's lifecycle): an iPhone's video encoder works only
     * then, so the export pauses while it isn't and goes on when it is back.
     */
    private val onScreen = MutableStateFlow(true)
    private val exporter = ClipExporter(kit.files, kit.device.writer, kit.device.decoder, kit.device.videos, onScreen, kit.background)

    private val _legSaveFailed = MutableStateFlow(false)

    /** The last travel choice for a leg couldn't be saved (the Route panel says to try again). */
    val legSaveFailed: StateFlow<Boolean> = _legSaveFailed.asStateFlow()

    private var inputs: Map<ClipSource, ClipInput> = emptyMap()
    private var input: ClipInput? = null

    /** The trip's photos and videos on this phone: media id → its file under the app's files. */
    private var photoFiles: Map<String, String> = emptyMap()

    /** The trip's journal videos on this phone (the Add media sheet's video tiles). */
    private var journalVideos: List<TripVideo> = emptyList()
    private var dark = false
    private var palette: PaperVehicle3D.Palette? = null
    private var planJob: Job? = null
    private var mapJob: Job? = null
    private var photoJob: Job? = null
    private var loadJob: Job? = null
    private var spriteJob: Job? = null
    private var planGeneration = 0L

    /** What the base map in hand was fetched for: the plan's geometry, the theme's darkness, Globe's street map colours. */
    private data class MapKey(val geometry: Int, val dark: Boolean, val tint: MapTint?)
    private var mapKey: MapKey? = null
    private var mapRoute: Int? = null
    private var closed = false

    init {
        loadRoute()
    }

    fun retryLoad() = loadRoute()

    private fun loadRoute() {
        loadJob?.cancel()
        _load.value = Load.Loading
        loadJob = work.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val trip = book.trip(tripId) ?: return@withContext emptyMap()
                    val rows = book.snapshot
                    val media = rows.media.filter { it.tripId == tripId && it.deletedAt == null }
                    // Only photos and videos on this phone: the journey must never pause for a card that can't be drawn.
                    val here = media.filter { (kit.files.size(it.localPath) ?: 0L) > 0L }
                    photoFiles = here.associate { it.id to it.localPath }
                    journalVideos = here.filter { it.isVideo }.map { TripVideo(it.id, it.takenAt, it.mime) }
                    val points = rows.trackPoints.filter { it.tripId == tripId }
                    val stops = rows.stops.filter { it.tripId == tripId }
                    ClipSources.available(trip, points, stops, book.items(tripId), media)
                        .mapValues { (s, i) -> withCountries(s, i.copy(photos = i.photos.filter { it.id in photoFiles })) }
                }
                savedEdits = ClipEditStore.load(kit.files, tripId)
                // Videos and voice lines no saved edit uses any more (removed last time) leave the phone.
                ClipMedia.sweep(kit.files, tripId, savedEdits.values.flatMap { e -> e.videos.map { it.file } + e.voices.map { it.file } }.toSet())
                inputs = loaded
                _sources.value = loaded.keys.toList()
                val preferred = _source.value?.takeIf { it in loaded } ?: loaded.keys.firstOrNull()
                _source.value = null
                if (preferred == null) _load.value = Load.NoRoute else setSource(preferred)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _load.value = Load.Failed
            }
        }
    }

    /** A planned route's places get their country (plan items carry none), from the bundled outlines. */
    private suspend fun withCountries(source: ClipSource, input: ClipInput): ClipInput {
        if (source != ClipSource.Planned || input.stops.all { it.countryCode != null }) return input
        val lookup = CountryOutlines.load() ?: return input
        return input.copy(stops = input.stops.map { s -> if (s.countryCode != null) s else s.copy(countryCode = lookup.codeAt(s.lat, s.lng)) })
    }

    /** The route the clip follows (Trip plan, Recorded journey or Saved stops), with that route's own edits. */
    fun setSource(source: ClipSource) {
        if (_make.value is Make.Working || source == _source.value) return
        val next = inputs[source] ?: return
        input = next
        _source.value = source
        _planned.value = next.planned
        resetExport()
        _plan.value = null
        _load.value = Load.Loading
        // This route's own scene edits (a fresh undo history per route).
        val edits = savedEdits[source] ?: ClipEdits()
        _design.value = _design.value.withEdits(edits)
        _options.value = _design.value.applyTo(_options.value).let {
            it.copy(beats = MusicCatalog.clipBeats(it.music, _onBeat.value, MusicCatalog.startFor(it.music, edits.musicStartS)))
        }
        _history.value = EditHistory(currentStep(edits))
        replan()
    }

    /**
     * A scene change ([ClipEdits]), kept for this trip and route. Edits with the same [merge] key in a row (typing a
     * name) undo as one. Photo changes (in or out, pauses, order), videos, day cards and a place's zoom or close-up
     * re-plan; the rest redraws.
     */
    fun editScenes(merge: String? = null, change: (ClipEdits) -> ClipEdits) = commit(merge) { it.copy(edits = change(it.edits)) }

    /** One step back through the edits and choices (see [Step]). */
    fun undo() = _history.value.takeIf { it.canUndo }?.let { applyStep(it.undo()) }
    fun redo() = _history.value.takeIf { it.canRedo }?.let { applyStep(it.redo()) }

    /** Every change goes through here: one undo step (or joined to the last with the same [merge] key). */
    private fun commit(merge: String? = null, change: (Step) -> Step) {
        if (_make.value is Make.Working) return
        val h = _history.value
        val next = change(h.present)
        if (next != h.present) applyStep(h.edit(next, merge))
    }

    /** Makes [h]'s present the clip's: kept (edits for the trip, choices for the next clip), redrawn or re-planned. */
    private fun applyStep(h: EditHistory<Step>) {
        if (_make.value is Make.Working) return
        val source = _source.value ?: return
        val before = _history.value.present
        val s = h.present
        _history.value = h
        if (s == before) return
        val prefs = kit.prefs
        if (s.edits != before.edits) {
            savedEdits = savedEdits + (source to s.edits)
            ClipEditStore.saveLater(kit.files, tripId, savedEdits)
        }
        if (s.style != before.style) ClipPrefs.saveDesign(prefs, s.style)
        if (s.speed != before.speed) ClipPrefs.saveSpeed(prefs, s.speed)
        if (s.length != before.length) ClipPrefs.saveLength(prefs, s.length)
        if (s.shape != before.shape) ClipPrefs.saveShape(prefs, s.shape)
        if (s.view != before.view) ClipPrefs.saveView(prefs, s.view)
        if (s.map != before.map) MapStylePrefs.save(prefs, MapStylePrefs.CLIP, s.map)
        if (s.music != before.music) MusicPrefs.save(prefs, s.music)
        if (s.onBeat != before.onBeat) MusicPrefs.saveOnBeat(prefs, s.onBeat)
        _onBeat.value = s.onBeat
        val ridesBefore = _design.value.ridesShown
        val design = s.style.withEdits(s.edits).copy(travellerDefault = meTraveller, ridesDefault = meRides)
        _design.value = design
        if (design.ridesShown != ridesBefore) loadSprites()
        resetExport()
        update {
            design.applyTo(
                it.copy(
                    speed = s.speed, length = s.length, shape = s.shape, view = s.view, map = s.map, music = s.music, vehicle = s.vehicle,
                    beats = MusicCatalog.clipBeats(s.music, s.onBeat, MusicCatalog.startFor(s.music, s.edits.musicStartS)),
                ),
            )
        }
    }

    // --- Phone videos ---------------------------------------------------------------------------------------------

    /**
     * Adds the video [picked] from the phone's library (videos only) after the place name [sceneKey] (a
     * [com.justdataplease.boringtravel.clip.LabelRange.key]): moved into the trip's clip media, its first 5 s chosen,
     * sound off. [mediaWork] shows the copy and any failure; the clip re-plans with it (one undo step).
     */
    fun addVideo(picked: PickedMedia, sceneKey: String) {
        if (!addWith { ClipMedia.importVideo(kit.files, kit.device.videos, tripId, picked, sceneKey) }) kit.files.delete(picked.path)
    }

    /**
     * Adds the trip's journal video [mediaId] after the place name [sceneKey], as a picked one is added: a copy in the
     * clip's media that remembers where it came from.
     */
    fun addJournalVideo(mediaId: String, sceneKey: String) {
        val v = journalVideos.firstOrNull { it.id == mediaId }
        val path = photoFiles[mediaId]
        if (v == null || path == null) {
            _mediaWork.value = ClipStudio.MediaWork.Failed("Couldn't add the video.")
            return
        }
        addWith { ClipMedia.importJournalVideo(kit.files, kit.device.videos, tripId, path, v.mime, mediaId, sceneKey) }
    }

    /** Every journal video of the trip on this phone, oldest first (the Add media sheet). */
    fun tripVideos(): List<TripVideo> = journalVideos.sortedWith(compareBy({ it.time == null }, { it.time }))

    /** One video in at a time, within [ClipVideo.MAX_VIDEOS]; [mediaWork] shows the copy and why it failed. False: not started. */
    private fun addWith(import: suspend () -> ClipVideo): Boolean {
        if (_make.value is Make.Working || _mediaWork.value == ClipStudio.MediaWork.Adding) return false
        if (_design.value.videos.size >= ClipVideo.MAX_VIDEOS) {
            _mediaWork.value = ClipStudio.MediaWork.Failed("A clip takes up to ${ClipVideo.MAX_VIDEOS} videos.")
            return false
        }
        _mediaWork.value = ClipStudio.MediaWork.Adding
        work.launch {
            _mediaWork.value = try {
                val video = import()
                editScenes { it.withVideo(video) }
                ClipStudio.MediaWork.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClipMedia.AddFailed) {
                ClipStudio.MediaWork.Failed(e.message ?: "Couldn't add the video.")
            } catch (_: Exception) {
                ClipStudio.MediaWork.Failed("Couldn't add the video.")
            }
        }
        return true
    }

    /** The part of video [id] that plays: from [startMs], [lengthMs] long (kept within the video and 1–10 s). */
    fun trimVideo(id: String, startMs: Long, lengthMs: Long) = editScenes("trim:$id") { e -> e.changeVideo(id) { it.trimmed(startMs, lengthMs) } }

    /** Keep video [id]'s own sound quietly under the music (off by default). */
    fun setVideoSound(id: String, on: Boolean) = editScenes { e -> e.changeVideo(id) { it.copy(sound = on) } }

    /** Play video [id] after another place name. */
    fun moveVideo(id: String, sceneKey: String) = editScenes { e -> e.changeVideo(id) { it.copy(sceneKey = sceneKey) } }

    /** Takes video [id] out of the clip (its file stays until the trip's edits no longer use it; undo brings it back). */
    fun removeVideo(id: String) = editScenes { it.withoutVideo(id) }

    /** Clears a failed add's note. */
    fun dismissMediaNote() {
        if (_mediaWork.value is ClipStudio.MediaWork.Failed) _mediaWork.value = ClipStudio.MediaWork.Idle
    }

    // --- Voice lines and day cards ----------------------------------------------------------------------------------

    /** Whether the app may use the microphone; the screen asks for it itself before [startVoice]. */
    fun hasMicPermission(): Boolean = kit.device.microphone.access == MicAccess.Allowed

    /**
     * Starts recording a voice line for the scene [sceneKey] (a place name's key: it plays from that scene's start,
     * the music ducked under it). Needs the microphone ([hasMicPermission]); stops by itself after 20 s. [voice] shows it.
     */
    fun startVoice(sceneKey: String) {
        if (_make.value is Make.Working || _voice.value is Voice.Recording) return
        if (!hasMicPermission()) {
            _voice.value = Voice.Failed("Allow the microphone to record a voice line.")
            return
        }
        if (!recorder.start()) {
            _voice.value = Voice.Failed("The microphone couldn't start.")
            return
        }
        _voice.value = Voice.Recording(sceneKey, SystemClock.elapsedRealtime())
        voiceTimer?.cancel()
        voiceTimer = work.launch {
            delay(VoiceLine.MAX_MS)
            stopVoice()
        }
    }

    /** Stops and keeps the line for its scene (in place of the one it had; one undo step). Too short: dropped. */
    fun stopVoice() {
        val rec = _voice.value as? Voice.Recording ?: return
        voiceTimer?.cancel()
        voiceTimer = null
        val kept = recorder.stop()
        if (kept == null) {
            _voice.value = Voice.Failed("Too short. Hold on a little longer.")
            return
        }
        _voice.value = Voice.Idle
        editScenes { it.withVoice(VoiceLine(rec.sceneKey, kept.first, kept.second)) }
    }

    /** Stops and throws the line away. */
    fun cancelVoice() {
        voiceTimer?.cancel()
        voiceTimer = null
        recorder.cancel()
        _voice.value = Voice.Idle
    }

    /** Takes the scene's voice line out (its file stays until no saved edit uses it; undo brings it back). */
    fun removeVoice(sceneKey: String) = editScenes { it.withoutVoice(sceneKey) }

    fun dismissVoiceNote() {
        if (_voice.value is Voice.Failed) _voice.value = Voice.Idle
    }

    /**
     * Leaves the plan items [ids] out of this planned video, or puts them back: the route runs straight on to the next
     * stop. One undo step, kept with the trip's edits.
     */
    fun skipStops(ids: Collection<String>, skip: Boolean) = editScenes { it.skipping(ids, skip) }

    /** Every photo of the trip on this phone, oldest first (the "Media here" panel's Add media). */
    fun tripPhotos(): List<ClipPhoto> = input?.photos.orEmpty().sortedWith(compareBy({ it.time == null }, { it.time }))

    /** Day cards on every day, or off (remembered like the rest of the style; each day can still say otherwise). */
    fun setDayCards(on: Boolean) = setDesign { it.copy(dayCards = on) }

    /** Day [dayKey]'s card ([com.justdataplease.boringtravel.clip.DayCard.key]) shown or not, whatever the clip's choice (kept with the trip's edits). */
    fun setDayCard(dayKey: String, shown: Boolean) {
        val global = _design.value.dayCards
        editScenes { e -> e.change(dayKey) { it.copy(hidden = (!shown).takeIf { shown != global }) } }
    }

    /** Day [dayKey]'s card's own line in place of the place name (blank: the place name again). */
    fun setDayCardText(dayKey: String, text: String) =
        editScenes("daytext:$dayKey") { e -> e.change(dayKey) { it.copy(text = text.trim().take(60).ifEmpty { null }) } }

    /** The voice lines' files (the export lays them over the music, each from its scene's start: round 20). */
    fun voiceFile(v: VoiceLine): String = ClipMedia.voiceFile(tripId, v)

    /**
     * The preview's pictures of the videos [plan] plays, at the prepared preview's size; videos already prepared with
     * the same file, trim and size are kept. Published once all are in.
     */
    private fun prepareVideos(plan: ClipPlan) {
        val playing = plan.inserts.filter { it.kind == InsertKind.Video }.mapNotNull { it.id }.toSet()
        val videos = _design.value.videos.filter { it.id in playing }
        val (w, h) = PreviewMath.size(plan.options.shape, PreviewMath.FULL_SHORT)
        val keys = videos.associate { it.id to "${it.file}|${it.startMs}|${it.lengthMs}|${w}x$h" }
        val kept = preparedVideos.filter { (id, p) -> keys[id] == p.key }
        if (kept.keys == keys.keys) {
            if (kept.size != preparedVideos.size || _videoFrames.value === ClipVideoFrames.NONE && kept.isNotEmpty()) {
                preparedVideos = kept
                _videoFrames.value = PreparedVideoFrames(kept)
            }
            return
        }
        // Already preparing exactly these (the clip re-planned for something else): let it finish.
        if (videoJob?.isActive == true && videoJobKeys == keys) return
        videoJob?.cancel()
        videoJobKeys = keys
        val sources = ClipMedia.sources(tripId, videos)
        val phone = kit.device.videos
        videoJob = work.launch {
            val done = HashMap(kept)
            for (v in videos) {
                if (v.id in done) continue
                val source = sources[v.id] ?: continue
                val prepared = withContext(Dispatchers.Default) {
                    try {
                        PreparedVideoFrames.prepare(source, w, h, keys.getValue(v.id), phone) { isActive }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } ?: continue
                done[v.id] = prepared
            }
            preparedVideos = done
            _videoFrames.value = PreparedVideoFrames(done)
        }
    }

    /** The look's style for drawing (made by the screen, which knows the current look). */
    fun useStyle(next: ClipStyle) {
        val changedDark = next.look.dark != dark || _style.value == null
        // Globe's street map comes in the look's own land and sea: another light look fetches it again.
        val changedTint = _style.value?.let(::tintOf) != tintOf(next)
        dark = next.look.dark
        _style.value = next
        if (changedDark || changedTint) _plan.value?.let { fetchMap(it) }
    }

    /** The theme's colours for the paper vehicles' pictures ([vehicleSprites]), from the screen. */
    fun usePalette(next: PaperVehicle3D.Palette) {
        if (next == palette) return
        palette = next
        loadSprites()
    }

    private fun loadSprites() {
        val p = palette ?: return
        val rides = _design.value.ridesShown
        spriteJob?.cancel()
        spriteJob = work.launch {
            try {
                _vehicleSprites.value = VehicleSprites.load(p, rides)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The pictures there are stay.
            }
        }
    }

    /**
     * The leg of a planned route that arrives at the place name [labelKey] (its first plan item), or null: other routes
     * have no legs to choose travel for.
     */
    fun legInto(labelKey: String): RouteLeg? {
        val prefix = ClipDesign.LABEL + "item:"
        if (!labelKey.startsWith(prefix) || _source.value != ClipSource.Planned) return null
        val id = labelKey.removePrefix(prefix)
        return inputs[ClipSource.Planned]?.routeLegs?.firstOrNull { it.key.toId == id }
    }

    /** How [leg] travels (null: the plan's default), saved on the trip like the plan's own choice; the clip re-plans. */
    fun setLegVehicle(leg: RouteLeg, vehicle: Vehicle?) = saveLeg { book.setLegVehicle(tripId, leg.key, vehicle) }

    /** Whether [leg] counts in the trip's km (null: the default), saved on the trip like the plan's own switch. */
    fun setLegKm(leg: RouteLeg, count: Boolean?) = saveLeg { book.setLegKm(tripId, leg.key, count) }

    private fun saveLeg(save: () -> Trip?) {
        try {
            val trip = save() ?: book.trip(tripId)
            _legSaveFailed.value = false
            onTripChanged()
            trip?.let(::applySavedChoices)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _legSaveFailed.value = true
        }
    }

    /**
     * The trip's travel choices changed: every route's legs take them, and the clip re-plans when its own changed. An
     * export works on the plan it started with: a change made meanwhile waits for it to end.
     */
    private fun applySavedChoices(trip: Trip) {
        if (_make.value is Make.Working) {
            deferredChoices = trip
            return
        }
        inputs = inputs.mapValues { (_, value) -> value.copy(routeLegs = RouteLegs.withChoices(trip, value.routeLegs)) }
        val selected = _source.value ?: return
        val next = inputs[selected] ?: return
        val changed = input?.routeLegs != next.routeLegs
        input = next
        if (changed) {
            resetExport()
            replan()
        }
    }

    private fun applyDeferredChoices() {
        val saved = deferredChoices ?: return
        deferredChoices = null
        applySavedChoices(saved)
    }

    /**
     * The trip was changed elsewhere (a leg's travel set on the plan while this video was open: Android observes the
     * trip's travel choices): the clip takes them again, and re-plans when they changed.
     */
    fun tripChanged() {
        if (inputs.isEmpty()) return
        book.trip(tripId)?.takeIf { it.deletedAt == null }?.let(::applySavedChoices)
    }

    /** A recorded journey's vehicle (Auto: guessed by speed per stretch). */
    fun setVehicle(v: Vehicle) {
        if (_source.value == ClipSource.Recorded) commit { it.copy(vehicle = v) }
    }

    /** Pace: the trip's travel time at ×[speed] (×1, ×2, ×5 or ×10; no set length). */
    fun setSpeed(speed: Int) {
        if (speed in ClipTiming.SPEEDS) commit { it.copy(speed = speed, length = null) }
    }

    /** How long the clip runs; null: Pace. */
    fun setLength(length: ClipLength?) = commit { it.copy(length = length) }

    /** Calm (one height per stretch; the default) or Follow (the camera breathes with the speed). */
    fun setCamera(mode: CameraMode) = setDesign { it.copy(camera = mode) }

    /** 9:16 · 4:5 · 1:1 · 16:9 ([ClipShape]), remembered for the next clip: the camera, the texts and the export follow it. */
    fun setShape(s: ClipShape) = commit { it.copy(shape = s) }

    /** A named look ([ClipLook]): the text, line and finish choices together (remembered like any style change). */
    fun setLook(look: ClipLook) = setDesign { look.apply(it) }

    fun setView(view: ClipView) = commit { it.copy(view = view) }

    fun setMapStyle(style: MapStyle) = commit { it.copy(map = style) }

    /**
     * A style change, remembered for the next clip. The photo mode (photo pauses change the timing), the zoom and the
     * close-ups re-plan; the rest redraws the preview.
     */
    fun setDesign(change: (ClipDesign) -> ClipDesign) {
        val next = change(_design.value)
        commit { it.copy(style = next.globals, edits = next.edits) }
    }

    /** This video's traveller (kept with this trip's edits; one undo step); null, or the same as Me's, follows Me. */
    fun setTraveller(t: Traveller?) = setDesign { it.copy(traveller = t?.takeIf { v -> v != meTraveller }) }

    /** This video's traveller becomes Me › Your traveller, the default for every video. */
    fun makeTravellerDefault() {
        if (_make.value is Make.Working) return
        val t = _design.value.travellerShown
        useDefaultTraveller(t)
        kit.defaults.saveTraveller(t)
        setTraveller(null)
    }

    /** This video's rides (kept with this trip's edits; one undo step); null, or the same as Me's, follows Me. */
    fun setRides(r: Rides?) = setDesign { it.copy(rides = r?.takeIf { v -> v != meRides }) }

    /** This video's rides become Me's, the default for every video. */
    fun makeRidesDefault() {
        if (_make.value is Make.Working) return
        val r = _design.value.ridesShown
        useDefaultRides(r)
        kit.defaults.saveRides(r)
        setRides(null)
    }

    /** Me › Your traveller changed (or this video's was made the default): the clips follow it. */
    fun useDefaultTraveller(t: Traveller) {
        if (t == meTraveller && _design.value.travellerDefault == t) return
        meTraveller = t
        _design.value = _design.value.copy(travellerDefault = t)
    }

    /** Me › Your traveller & rides' rides changed: the clips follow them. */
    fun useDefaultRides(r: Rides) {
        if (r == meRides && _design.value.ridesDefault == r) return
        val before = _design.value.ridesShown
        meRides = r
        _design.value = _design.value.copy(ridesDefault = r)
        if (_design.value.ridesShown != before) loadSprites()
    }

    /** The track under the video, remembered for the next clip; null = silent. */
    fun setMusic(trackId: String?) = commit { it.copy(music = trackId, edits = it.edits.copy(musicStartS = null)) }

    /** Where the music starts in its track, in seconds (null: its strongest stretch); snapped to a bar when it plays. */
    fun setMusicStart(seconds: Double?) = commit { it.copy(edits = it.edits.copy(musicStartS = seconds)) }

    /** Where this clip's music starts in its track now, on a bar (0 without music): the editor's music and the export's. */
    val musicStartS: Double get() = MusicCatalog.startFor(_options.value.music, _design.value.musicStartS)

    /**
     * Cuts on the beat on or off (remembered; on by default): with a track, day changes, photo pauses, close-ups,
     * videos and day cards start on its beats and last whole beats; the clip's length stays within a beat.
     */
    fun setOnBeat(on: Boolean) = commit { it.copy(onBeat = on) }

    private fun update(change: (ClipOptions) -> ClipOptions) {
        if (_make.value is Make.Working) return
        val next = change(_options.value)
        if (next == _options.value) return
        _options.value = next
        resetExport()
        replan()
    }

    /** An edit after an export: back to "Create video" (the made file stays in the trip's Media). */
    private fun resetExport() {
        if (_make.value is Make.Working) return
        _make.value = Make.Idle
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
    }

    private fun replan() {
        val source = input ?: return
        val opts = _options.value
        val generation = ++planGeneration
        _replanning.value = true
        planJob?.cancel()
        planJob = work.launch {
            try {
                var made = withContext(Dispatchers.Default) { ClipPlan.build(source, opts) }
                if (made == null) {
                    _load.value = Load.NoRoute
                    return@launch
                }
                // A free clip stays within a minute (Pro on sale only): a long trip moves to the slowest speed that does.
                val free = if (opts.length != null) opts.speed else ClipTiming.freeSpeed(made.travelHours, opts.speed, isPro, made.photoHolds, made.addedSeconds)
                if (free != opts.speed) {
                    val faster = opts.copy(speed = free)
                    made = withContext(Dispatchers.Default) { ClipPlan.build(source, faster) } ?: made
                    _options.value = faster
                    // The step takes the speed the clip runs at (not a step of its own), so later changes keep it.
                    _history.value = _history.value.let { h -> h.copy(present = h.present.copy(speed = free)) }
                }
                _plan.value = made
                _load.value = Load.Ready
                fetchMap(made)
                loadPhotos(made)
                prepareVideos(made)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _load.value = Load.Failed
            } finally {
                if (generation == planGeneration) _replanning.value = false
            }
        }
    }

    /** One base map per route, camera, shape, map look and theme; other option changes reuse it. */
    private fun fetchMap(plan: ClipPlan) {
        val s = _style.value ?: return
        val tint = if (plan.globe) tintOf(s) else null
        val key = MapKey(plan.geometryKey, dark, tint)
        if (key == mapKey) return
        mapKey = key
        mapJob?.cancel()
        // Same route, look and theme (only the camera's pace changed): the map in hand still fits, so it stays on screen
        // until the new pictures are all in.
        val route = routeKey(plan, tint)
        val keep = route == mapRoute && _base.value.hasMap
        mapRoute = route
        if (!keep) _base.value = BaseMap.Plain
        _mapStatus.value = MapStatus.Loading
        _mapProgress.value = 0f
        val isDark = dark
        mapJob = work.launch {
            // Globe: the globe itself is bundled and there at once; its street map comes in for the close views.
            val globe = if (plan.globe) globeFor(plan) else null
            if (globe != null && !keep) _base.value = BaseMap(emptyList(), MapStyle.Globe, globe)
            val specs = withContext(Dispatchers.Default) { plan.layerSpecs() }
            val result = ClipMaps.fetch(
                kit.snapshots, kit.web, specs, isDark, plan.options.map, tint,
                onLayer = { partial -> if (!keep) _base.value = partial.withGlobe(globe) },
                onProgress = { _mapProgress.value = it },
            ).withGlobe(globe)
            if (result.hasMap || !keep) _base.value = result
            // A globe without the street map it needed (offline) still makes a clip, with the note to retry.
            val streetMissing = globe != null && specs.isNotEmpty() && _base.value.layers.isEmpty()
            _mapStatus.value = if (_base.value.hasMap && !streetMissing) MapStatus.Ready else MapStatus.Plain
        }
    }

    /** The route's ground (not its camera): a map fetched for it fits any plan with the same key. */
    private fun routeKey(plan: ClipPlan, tint: MapTint?): Int = listOf(
        plan.xs.contentHashCode(), plan.ys.contentHashCode(), plan.options.shape, plan.options.view, plan.options.sky, plan.options.map,
        plan.source, dark, tint,
    ).hashCode()

    /** Globe's street map colours: the look's land, sea and ink (as the trip map recolours its map). */
    private fun tintOf(style: ClipStyle): MapTint = MapTint.of(style.land, style.sea, style.ink)

    /**
     * The globe under a Globe clip: the Map tab's shapes, the countries the route goes through (first reached first,
     * [ClipGlobe.countries]) filled in the look's highlighter, with their flags.
     */
    private suspend fun globeFor(plan: ClipPlan): GlobeBase? {
        val geometry = CountryOutlines.geometry() ?: return null
        val countries = CountryOutlines.load()
        return withContext(Dispatchers.Default) {
            val codes = if (countries == null) emptyList() else ClipGlobe.countries(plan) { lat, lng -> countries.codeAt(lat, lng) }
            val pins = codes.take(ClipGlobe.MAX_FLAGS).mapNotNull { code ->
                CountryInfo.labelPoint(code)?.let { (lat, lng) -> GlobePin(code, lat, lng, CountryInfo.name(code)) }
            }
            GlobeBase(geometry, codes, pins)
        }
    }

    fun retryMap() {
        if (_make.value is Make.Working) return
        mapKey = null
        mapRoute = null
        _plan.value?.let(::fetchMap)
    }

    private fun loadPhotos(plan: ClipPlan) {
        val missing = plan.photos.map { it.id }.filter { it !in _photos.value && it in photoFiles }.distinct()
        if (missing.isEmpty()) return
        _photosLoading.value = true
        photoJob = work.launch {
            try {
                val more = withContext(Dispatchers.Default) {
                    missing.mapNotNull { id -> kit.files.read(photoFiles.getValue(id))?.let(ClipExport::decode)?.let { id to it } }.toMap()
                }
                if (closed) more.values.forEach { it.recycle() } else _photos.value = _photos.value + more
            } finally {
                _photosLoading.value = false
            }
        }
    }

    // --- The export and sharing (round 20) -----------------------------------------------------------------------

    /**
     * Whether the app is on screen (the screen's lifecycle: resumed or not). An export pauses while it isn't, at the end
     * of the frame it is on, and goes on from the next one when it is back (an iPhone's video encoder works only then).
     */
    fun setOnScreen(on: Boolean) {
        onScreen.value = on
    }

    /**
     * Makes the MP4 (Android's makeClip): the plan as it is now, its map, photos and style, the phone videos it plays
     * with their own sound where asked, the chosen music and the voice lines; [make] shows the progress, then Done (kept
     * in the trip's Media too) or Failed. Not while the map is still coming in or the clip re-plans; nothing else
     * changes while it runs.
     */
    fun makeClip() {
        if (_make.value is Make.Working || !_base.value.hasMap || _replanning.value) return
        val plan = _plan.value ?: return
        if (!isPro && ClipTiming.needsPro(plan.seconds)) return
        val generation = ++makeGeneration
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
        _make.value = Make.Working(0f)
        makeJob = work.launch {
            try {
                val style = _style.filterNotNull().first()
                mapJob?.join()
                check(_base.value.hasMap) { "A geographic map is required for this clip" }
                photoJob?.join()
                val design = _design.value
                val playing = plan.inserts.mapNotNull { it.id }.toSet()
                val videos = ClipMedia.sources(tripId, design.videos.filter { it.id in playing })
                val track = MusicCatalog.byId(plan.options.music)
                val job = ExportJob(
                    plan, _base.value, style, _photos.value, noMark, design, videos, track, track?.let(::musicUri),
                    ClipMusic.startUs(track, MusicCatalog.startFor(plan.options.music, design.musicStartS)),
                    videoSounds(plan) + voiceSounds(plan), ClipExporter.outputPath(plan.title),
                )
                // One bar over the frames, the sound and the last writes, on a whole percent; never 100 % before Done.
                val bar = ExportBar(job.hasSound) { p, stage -> if (generation == makeGeneration) _make.value = Make.Working(p, stage) }
                val made = exporter.make(job, bar)
                // Every finished clip is kept for the trip's Media tab (a copy in clips/, with its picture).
                if (generation == makeGeneration) kit.saved?.keep(tripId, plan.title, made.path, made.durationMs, made.width, made.height, made.thumb)
                if (generation == makeGeneration) _make.value = Make.Done(made.path, musicFailed = made.musicFailed)
            } catch (e: CancellationException) {
                if (generation == makeGeneration) _make.value = Make.Idle
                throw e
            } catch (_: Exception) {
                if (generation == makeGeneration) _make.value = Make.Failed
            } finally {
                if (generation == makeGeneration) applyDeferredChoices()
            }
        }
    }

    /** Stops a running export: nothing is kept, the last made clip stays (Android's cancelClip). */
    fun cancelClip() {
        makeGeneration++
        makeJob?.cancel()
        _make.value = Make.Idle
        applyDeferredChoices()
    }

    /** The phone videos' own sounds the export keeps (quietly under the music, a little louder without it). */
    private fun videoSounds(plan: ClipPlan): List<ClipMusic.Sound> {
        val byId = _design.value.videos.associateBy { it.id }
        val gain = if (plan.options.music != null) ClipMusic.VIDEO_GAIN_UNDER_MUSIC else ClipMusic.VIDEO_GAIN_ALONE
        return plan.inserts.filter { it.kind == InsertKind.Video }.mapNotNull { insert ->
            val v = byId[insert.id]?.takeIf { it.sound } ?: return@mapNotNull null
            ClipMusic.Sound(
                location = kit.files.absolute(ClipMedia.videoFile(tripId, v)),
                atUs = insert.fromFrame * 1_000_000L / plan.fps,
                fromUs = v.startMs * 1000,
                lengthUs = insert.frames * 1_000_000L / plan.fps,
                gain = gain,
            )
        }
    }

    /** The voice lines the export lays over the music, each from its scene's start, cut at the clip's end. */
    private fun voiceSounds(plan: ClipPlan): List<ClipMusic.Sound> = _design.value.voices.mapNotNull { v ->
        val from = plan.sceneStart(v.sceneKey) ?: return@mapNotNull null
        val atUs = from * 1_000_000L / plan.fps
        val lengthUs = minOf(v.durationMs * 1000, (plan.frameCount * 1_000_000L / plan.fps) - atUs)
        if (lengthUs <= 0) null else ClipMusic.voice(kit.files.absolute(ClipMedia.voiceFile(tripId, v)), atUs, lengthUs)
    }

    /** Keeps a copy of the made video in the phone's photo library (iPhone: Photos, asking for add-only access then). */
    fun saveToPhone() {
        val done = _make.value as? Make.Done ?: return
        if (_phone.value == Phone.Saving) return
        _phone.value = Phone.Saving
        kit.device.share.saveToPhotos(done.file) { ok -> if (_make.value == done) _phone.value = if (ok) Phone.Saved else Phone.Failed }
    }

    /**
     * Saves the made video to Google Drive, in BoringTravel › Exports (Android's saveToDrive); "Google Drive isn't
     * connected." with Connect Drive when it isn't (or this build has no Google).
     */
    fun saveToDrive() {
        val done = _make.value as? Make.Done ?: return
        if (_drive.value == Drive.Saving) return
        _drive.value = Drive.Saving
        work.launch {
            _drive.value = try {
                val drive = kit.drive
                if (drive == null || !drive.isConnected()) {
                    Drive.NotConnected
                } else {
                    val folder = drive.folder(com.justdataplease.boringtravel.google.DriveApi.EXPORTS)
                    val file = drive.upload(done.file, done.file.substringAfterLast('/'), "video/mp4", folder)
                    Drive.Saved(drive.openUrl(file), file = file)
                }
            } catch (e: com.justdataplease.boringtravel.google.DriveNotConnectedException) {
                Drive.NotConnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Drive.Failed
            }
        }
    }

    /** Makes the saved video's Drive link one anyone can open (the screen then offers it to send). */
    fun shareDriveLink() {
        val saved = _drive.value as? Drive.Saved ?: return
        val file = saved.file ?: return
        if (saved.link != null || saved.linking) return
        _drive.value = saved.copy(linking = true, linkFailed = false)
        work.launch {
            val now = try {
                saved.copy(link = kit.drive?.shareLink(file) ?: throw com.justdataplease.boringtravel.google.DriveNotConnectedException(), linking = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saved.copy(linking = false, linkFailed = true)
            }
            if (_drive.value is Drive.Saved) _drive.value = now
        }
    }

    /**
     * The screen left: the work stops (a recording is thrown away, as Android's view-model does when it goes) and the
     * photos' pictures are freed, a moment later (the map's pictures are the shared cache's, freed when it lets them
     * go; the vehicles' are VehicleSprites' cache's).
     */
    fun close() {
        closed = true
        recorder.cancel()
        work.cancel()
        val held = _photos.value
        _photos.value = emptyMap()
        // A preview's frame may still be drawing with them on another thread: freed once it can't be.
        if (held.isNotEmpty()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                delay(2_000)
                held.values.forEach { it.recycle() }
            }
        }
    }
}

/**
 * The scene editor's edits on this phone (the Android app's clip/ClipEditStore.kt): clip-edits/<trip id>.json under the
 * app's files, one part per route source, in Android's own form ([ClipEditsJson]), so the edits read the same on both
 * phones and a backup carries them (round 20). Written whole, in the background, one file after another in the order
 * asked (so the newest wins, as Android's), with the files' own atomic write.
 */
object ClipEditStore {
    private val writes = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + writes)

    fun path(tripId: String): String = "clip-edits/" + tripId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + ".json"

    /** Every route source's edits for [tripId] (empty when there are none or the file can't be read). */
    fun load(files: MediaFiles, tripId: String): Map<ClipSource, ClipEdits> {
        val text = runCatching { files.read(path(tripId))?.decodeToString() }.getOrNull()
        return ClipEditsJson.decode(text).mapNotNull { (id, edits) -> ClipSource.entries.firstOrNull { it.name == id }?.let { it to edits } }.toMap()
    }

    /** Keeps [all] for [tripId] now; nothing left, the file goes. */
    fun save(files: MediaFiles, tripId: String, all: Map<ClipSource, ClipEdits>) {
        val nonEmpty = all.filterValues { !it.isEmpty }
        if (nonEmpty.isEmpty()) files.delete(path(tripId)) else files.write(path(tripId), ClipEditsJson.encode(nonEmpty.mapKeys { it.key.name }).encodeToByteArray())
    }

    /** Keeps [all] for [tripId] in the background (a later call is written after an earlier one). */
    fun saveLater(files: MediaFiles, tripId: String, all: Map<ClipSource, ClipEdits>) {
        scope.launch { runCatching { save(files, tripId, all) } }
    }

    /** Waits until every write asked for so far is done (tests, and before a backup reads the files). */
    suspend fun flush() = withContext(writes) {}
}

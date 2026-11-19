package com.justdataplease.boringtravel.ui.clip

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.BaseMaps
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.tracking.TrackProcessor
import com.justdataplease.boringtravel.geo.Countries
import com.justdataplease.boringtravel.clip.CameraMode
import com.justdataplease.boringtravel.clip.ClipLength
import com.justdataplease.boringtravel.clip.ClipEditStore
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.EditHistory
import com.justdataplease.boringtravel.clip.ClipExport
import com.justdataplease.boringtravel.clip.ExportBar
import com.justdataplease.boringtravel.clip.ExportProgress
import com.justdataplease.boringtravel.clip.ExportStage
import com.justdataplease.boringtravel.clip.ClipInput
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipLook
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.clip.DayCard
import com.justdataplease.boringtravel.clip.InsertKind
import com.justdataplease.boringtravel.clip.TripVideo
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.clip.VoiceRecorder
import com.justdataplease.boringtravel.clip.PreparedVideoFrames
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipSources
import com.justdataplease.boringtravel.clip.ClipGlobe
import com.justdataplease.boringtravel.clip.GlobeBase
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapTint
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.clip.MapStylePrefs
import com.justdataplease.boringtravel.clip.PlannedReplay
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.VehicleSprites
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicPrefs
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveFile
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.model.Vehicle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Trip clip screen's state: the trip read once, the plan rebuilt whenever an option changes, the base map
 * fetched once per route and look, photos decoded once, and the MP4 job with progress and cancel.
 */
class ClipViewModel(private val graph: AppGraph, private val tripId: String) : ViewModel() {

    sealed interface Load {
        data object Loading : Load
        /** No recorded, planned, or saved-stop source has a usable route yet. */
        data object NoRoute : Load
        data object Failed : Load
        data object Ready : Load
    }

    enum class MapStatus { Loading, Ready, Plain }

    sealed interface Make {
        data object Idle : Make
        /** [progress]: the whole export's, 0 to [ExportProgress.LAST] ([ExportProgress.overall]); [stage]: what it is doing. */
        data class Working(val progress: Float, val stage: ExportStage = ExportStage.Picture) : Make
        /** [musicFailed]: the chosen music couldn't be added, so the video is silent (music hook, `clip/music`). */
        data class Done(val file: File, val musicFailed: Boolean = false) : Make
        data object Failed : Make
    }

    /** The microphone: idle, recording a voice line for [sceneKey] (since [startedAt], elapsed realtime ms), or why it failed. */
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

    sealed interface Drive {
        data object Idle : Drive
        data object Saving : Drive
        data object NotConnected : Drive
        /**
         * In Drive at [url]; [link]: the link anyone can open, once asked for ([shareDriveLink]; owner, 2026-11-09:
         * "send full video or google drive link?"), [linking] while it is made.
         */
        data class Saved(
            val url: String, val file: DriveFile? = null, val link: String? = null, val linking: Boolean = false, val linkFailed: Boolean = false,
        ) : Drive
        data object Failed : Drive
    }

    /** A copy of the made video in the phone's gallery (Movies › BoringTravel). */
    sealed interface Phone {
        data object Idle : Phone
        data object Saving : Phone
        data object Saved : Phone
        data object Failed : Phone
    }

    /**
     * Pro clips may run longer than a minute (billing, Wave 3K; debug builds can "Pretend Pro"); free clips go up to
     * [ClipTiming.FREE_MAX_SECONDS], as the 60 s length did.
     */
    val isPro: Boolean get() = graph.billing.state.value.isPro

    /**
     * What the renderer gets as its `isPro`: true leaves out the BoringTravel mark (the end card's
     * plane). Only Pro while it is for sale ([ProBilling.State.markOff]): the free launch keeps the mark on exported
     * clips and in the preview, so the preview shows what will be shared.
     */
    val noMark: Boolean get() = graph.billing.state.value.markOff

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
    // The style, the map look and the speed are remembered from the last clip (the defaults the first time, and once
    // more when the defaults change: ClipPrefs.newDefaults).
    private val _design = MutableStateFlow(ClipPrefs.run { newDefaults(graph.app); design(graph.app) })
    /** How the clip looks (texts, line, photo places); only its photo mode changes the plan. */
    val design: StateFlow<ClipDesign> = _design.asStateFlow()
    // Cuts on the beat (owner: "Cuts on the beat"): on unless turned off, and only with a track.
    private val _onBeat = MutableStateFlow(MusicPrefs.onBeat(graph.app))
    /** Whether scene changes go on the music's beats (when a track is chosen; [ClipOptions.beats]). */
    val onBeat: StateFlow<Boolean> = _onBeat.asStateFlow()
    private val _options = MutableStateFlow(_design.value.applyTo(ClipOptions(
        speed = ClipPrefs.speed(graph.app), length = ClipPrefs.length(graph.app), shape = ClipPrefs.shape(graph.app),
        view = ClipPrefs.view(graph.app), map = MapStylePrefs.load(graph.app, MapStylePrefs.CLIP),
        music = MusicPrefs.load(graph.app), // Music hook (clip/music): the last track, None the first time.
    ).let { it.copy(beats = MusicCatalog.clipBeats(it.music, _onBeat.value, MusicCatalog.startFor(it.music, null))) }))
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
    private val _vehicleSprites = MutableStateFlow<Map<Vehicle, Bitmap>>(emptyMap())
    val vehicleSprites: StateFlow<Map<Vehicle, Bitmap>> = _vehicleSprites.asStateFlow()
    private val _make = MutableStateFlow<Make>(Make.Idle)
    val make: StateFlow<Make> = _make.asStateFlow()
    private val _drive = MutableStateFlow<Drive>(Drive.Idle)
    val drive: StateFlow<Drive> = _drive.asStateFlow()
    private val _phone = MutableStateFlow<Phone>(Phone.Idle)
    val phone: StateFlow<Phone> = _phone.asStateFlow()
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
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This trip's and route's phone videos, in the order added ([ClipVideo]: after which place, trim, sound). */
    val videos: StateFlow<List<ClipVideo>> = _design.map { it.videos }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _mediaWork = MutableStateFlow<MediaWork>(MediaWork.Idle)
    val mediaWork: StateFlow<MediaWork> = _mediaWork.asStateFlow()
    /** The phone videos' pictures for the preview, prepared after each plan ([ClipVideoFrames.NONE] until then). */
    private val _videoFrames = MutableStateFlow<ClipVideoFrames>(ClipVideoFrames.NONE)
    val videoFrames: StateFlow<ClipVideoFrames> = _videoFrames.asStateFlow()
    private var preparedVideos: Map<String, PreparedVideoFrames.Prepared> = emptyMap()
    private var videoJob: Job? = null
    private var videoJobKeys: Map<String, String>? = null

    /** This trip's and route's voice lines, one per scene ([VoiceLine]: which scene, its file and length). */
    val voices: StateFlow<List<VoiceLine>> = _design.map { it.voices }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _voice = MutableStateFlow<Voice>(Voice.Idle)
    val voice: StateFlow<Voice> = _voice.asStateFlow()
    private val recorder = VoiceRecorder(graph.app, tripId)
    private var voiceTimer: Job? = null

    /**
     * Every day of the clip that can open with a day card ([DayCard]: its key, "Day 14", place, date, the place name it
     * opens), shown or not; [ClipDesign.dayCardShown] says which show.
     */
    val days: StateFlow<List<DayCard>> = _plan.map { it?.dayStarts.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var input: ClipInput? = null
    private var inputs: Map<ClipSource, ClipInput> = emptyMap()
    private var photoFiles: Map<String, File> = emptyMap()
    /** The trip's journal videos on this phone (the Add media sheet's video tiles). */
    private var journalVideos: List<TripVideo> = emptyList()
    private var dark = false
    private var planJob: Job? = null
    private var mapJob: Job? = null
    /** What the base map in hand was fetched for: the plan's geometry, the theme's darkness, Globe's street map colours. */
    private data class MapKey(val geometry: Int, val dark: Boolean, val tint: MapTint?)
    private var mapKey: MapKey? = null
    private var mapRoute: Int? = null
    private var photoJob: Job? = null
    private var makeJob: Job? = null
    private var loadJob: Job? = null
    private var choiceObservationJob: Job? = null
    private var deferredChoices: Trip? = null
    private var latestChoicesAt = Long.MIN_VALUE
    private var planGeneration = 0L
    private val artworkJob: Job
    @Volatile private var makeGeneration = 0L
    /** Me › Your traveller: who walks unless this video has its own ([ClipDesign.travellerDefault]). */
    private var meTraveller = Traveller.DEFAULT
    /** Me › Your traveller & rides: how the vehicles look unless this video has its own ([ClipDesign.ridesDefault]). */
    private var meRides = Rides.DEFAULT

    init {
        artworkJob = viewModelScope.launch {
            try {
                _vehicleSprites.value = VehicleSprites.load(graph.app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cached 3D models also provide picker thumbnails if packaged art cannot be decoded.
                Log.w("TripClip", "Unable to decode vehicle artwork", e)
            }
        }
        loadRoute()
        // Me › Your traveller changed (or this video's was made the default): the clips follow it.
        viewModelScope.launch { graph.settings.traveller.collect { useDefaultTraveller(it) } }
        viewModelScope.launch { graph.settings.rides.collect { useDefaultRides(it) } }
        // The leg list's pictures show this video's rides (the first load above has Me's).
        viewModelScope.launch {
            artworkJob.join()
            _design.map { it.ridesShown }.distinctUntilChanged().drop(1).collectLatest { rides ->
                try {
                    _vehicleSprites.value = VehicleSprites.load(graph.app, rides)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The pictures there are stay.
                }
            }
        }
    }

    fun retryLoad() = loadRoute()

    private fun useDefaultTraveller(t: Traveller) {
        if (t == meTraveller && _design.value.travellerDefault == t) return
        meTraveller = t
        _design.value = _design.value.copy(travellerDefault = t)
    }

    private fun useDefaultRides(r: Rides) {
        if (r == meRides && _design.value.ridesDefault == r) return
        meRides = r
        _design.value = _design.value.copy(ridesDefault = r)
    }

    private fun loadRoute() {
        loadJob?.cancel()
        choiceObservationJob?.cancel()
        EditorPerf.edit("open")
        _load.value = Load.Loading
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val trip = graph.trips.get(tripId)?.takeIf { it.deletedAt == null } ?: return@withContext emptyMap()
                    val media = graph.media.observeForTrip(tripId).first()
                    photoFiles = media.associate { it.id to graph.media.file(it) }.filterValues { it.isFile }
                    journalVideos = media.filter { it.isVideo && it.id in photoFiles }.map { TripVideo(it.id, it.takenAt, it.mime) }
                    // Only photos on this phone: the journey must never pause for a card that can't be drawn.
                    ClipSources.available(trip, graph.tracks.forTrip(tripId), graph.tracks.stops(tripId), graph.plan.items(tripId), media)
                        .mapValues { (source, input) -> withCountries(source, input.copy(photos = input.photos.filter { it.id in photoFiles })) }
                }
                savedEdits = ClipEditStore.load(graph.app, tripId)
                // The first preview already shows the traveller from Me.
                useDefaultTraveller(graph.settings.traveller.first())
                useDefaultRides(graph.settings.rides.first())
                // Videos and voice lines no saved edit uses any more (removed last time) leave the phone.
                ClipMedia.sweep(graph.app, tripId, savedEdits.values.flatMap { e -> e.videos.map { it.file } + e.voices.map { it.file } }.toSet())
                artworkJob.join()
                inputs = loaded
                _sources.value = loaded.keys.toList()
                val preferred = _source.value?.takeIf { it in loaded } ?: loaded.keys.firstOrNull()
                _source.value = null
                if (preferred == null) _load.value = Load.NoRoute else setSource(preferred)
                choiceObservationJob = viewModelScope.launch {
                    graph.trips.observeTrip(tripId).filterNotNull().distinctUntilChangedBy { it.travelModes }.collect { saved ->
                        if (saved.deletedAt == null) applySavedChoices(saved)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _load.value = Load.Failed
            }
        }
    }

    fun setSource(source: ClipSource) {
        if (_make.value is Make.Working || source == _source.value) return
        val next = inputs[source] ?: return
        input = next
        _source.value = source
        _planned.value = next.planned
        _make.value = Make.Idle
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
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
        EditorPerf.edit(changed(before, s))
        val app = graph.app
        if (s.edits != before.edits) {
            savedEdits = savedEdits + (source to s.edits)
            ClipEditStore.save(app, tripId, savedEdits)
        }
        if (s.style != before.style) ClipPrefs.saveDesign(app, s.style)
        if (s.speed != before.speed) ClipPrefs.saveSpeed(app, s.speed)
        if (s.length != before.length) ClipPrefs.saveLength(app, s.length)
        if (s.shape != before.shape) ClipPrefs.saveShape(app, s.shape)
        if (s.view != before.view) ClipPrefs.saveView(app, s.view)
        if (s.map != before.map) MapStylePrefs.save(app, MapStylePrefs.CLIP, s.map)
        if (s.music != before.music) MusicPrefs.save(app, s.music)
        if (s.onBeat != before.onBeat) MusicPrefs.saveOnBeat(app, s.onBeat)
        _onBeat.value = s.onBeat
        val design = s.style.withEdits(s.edits).copy(travellerDefault = meTraveller, ridesDefault = meRides)
        _design.value = design
        _make.value = Make.Idle
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
        update {
            design.applyTo(
                it.copy(
                    speed = s.speed, length = s.length, shape = s.shape, view = s.view, map = s.map, music = s.music, vehicle = s.vehicle,
                    beats = MusicCatalog.clipBeats(s.music, s.onBeat, MusicCatalog.startFor(s.music, s.edits.musicStartS)),
                ),
            )
        }
    }

    /** What a step changed, for the editor's timings ([EditorPerf]). */
    private fun changed(a: Step, b: Step): String = listOfNotNull(
        "edits".takeIf { a.edits != b.edits }, "style".takeIf { a.style != b.style }, "speed".takeIf { a.speed != b.speed },
        "length".takeIf { a.length != b.length }, "shape".takeIf { a.shape != b.shape }, "view".takeIf { a.view != b.view },
        "map".takeIf { a.map != b.map }, "music".takeIf { a.music != b.music }, "beat".takeIf { a.onBeat != b.onBeat },
        "vehicle".takeIf { a.vehicle != b.vehicle },
    ).joinToString("+")

    // --- Phone videos (owner: "Your phone videos") ---------------------------------------------------------------

    /**
     * Adds the video at [uri] (from the Photo Picker, videos only) after the place name [sceneKey] (a
     * [com.justdataplease.boringtravel.clip.LabelRange.key]): copied into the trip's clip media, its first 5 s
     * chosen, sound off. [mediaWork] shows the copy and any failure; the clip re-plans with it (one undo step).
     */
    fun addVideo(uri: Uri, sceneKey: String) = addWith { ClipMedia.importVideo(graph.app, tripId, uri, sceneKey) }

    /**
     * Adds the trip's journal video [mediaId] after the place name [sceneKey], as a picked one is added (owner,
     * 2026-11-14: "in clips videos are added as photos"): a copy in the clip's media that remembers where it came from.
     */
    fun addJournalVideo(mediaId: String, sceneKey: String) {
        val v = journalVideos.firstOrNull { it.id == mediaId }
        val file = photoFiles[mediaId]
        if (v == null || file == null) {
            _mediaWork.value = MediaWork.Failed("Couldn't add the video.")
            return
        }
        addWith { ClipMedia.importJournalVideo(graph.app, tripId, file, v.mime, mediaId, sceneKey) }
    }

    /** Every journal video of the trip on this phone, oldest first (the Add media sheet). */
    fun tripVideos(): List<TripVideo> = journalVideos.sortedWith(compareBy({ it.time == null }, { it.time }))

    /** One video in at a time, within [ClipVideo.MAX_VIDEOS]; [mediaWork] shows the copy and why it failed. */
    private fun addWith(import: suspend () -> ClipVideo) {
        if (_make.value is Make.Working || _mediaWork.value == MediaWork.Adding) return
        if (_design.value.videos.size >= ClipVideo.MAX_VIDEOS) {
            _mediaWork.value = MediaWork.Failed("A clip takes up to ${ClipVideo.MAX_VIDEOS} videos.")
            return
        }
        _mediaWork.value = MediaWork.Adding
        viewModelScope.launch {
            _mediaWork.value = try {
                val video = import()
                editScenes { it.withVideo(video) }
                MediaWork.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClipMedia.AddFailed) {
                MediaWork.Failed(e.message ?: "Couldn't add the video.")
            } catch (e: Exception) {
                Log.w("TripClip", "Couldn't add a video", e)
                MediaWork.Failed("Couldn't add the video.")
            }
        }
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
        if (_mediaWork.value is MediaWork.Failed) _mediaWork.value = MediaWork.Idle
    }

    // --- Voice lines and day cards (owner: "Voice-over and day cards") ----------------------------------------------

    /** Whether the app may use the microphone; the screen asks for RECORD_AUDIO itself before [startVoice]. */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(graph.app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts recording a voice line for the scene [sceneKey] (a place name's key: it plays from that scene's start,
     * the music ducked under it). Needs RECORD_AUDIO ([hasMicPermission]); stops by itself after 20 s. [voice] shows it.
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
        _voice.value = Voice.Recording(sceneKey, android.os.SystemClock.elapsedRealtime())
        voiceTimer?.cancel()
        voiceTimer = viewModelScope.launch {
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
     * Leaves the plan items [ids] out of this planned video, or puts them back (owner, 2026-11-09: "add ability to
     * deactivate a stop from the map next to let go, and go directly to the next"): the route runs straight on to the
     * next stop. One undo step, kept with the trip's edits.
     */
    fun skipStops(ids: Collection<String>, skip: Boolean) = editScenes { it.skipping(ids, skip) }

    /** Every photo of the trip on this phone, oldest first (the "Media here" panel's Add media). */
    fun tripPhotos(): List<com.justdataplease.boringtravel.clip.ClipPhoto> =
        input?.photos.orEmpty().sortedWith(compareBy({ it.time == null }, { it.time }))

    /** Day cards on every day, or off (remembered like the rest of the style; each day can still say otherwise). */
    fun setDayCards(on: Boolean) = setDesign { it.copy(dayCards = on) }

    /** Day [dayKey]'s card ([DayCard.key]) shown or not, whatever the clip's choice (kept with the trip's edits). */
    fun setDayCard(dayKey: String, shown: Boolean) {
        val global = _design.value.dayCards
        editScenes { e -> e.change(dayKey) { it.copy(hidden = (!shown).takeIf { shown != global }) } }
    }

    /** Day [dayKey]'s card's own line in place of the place name (blank: the place name again). */
    fun setDayCardText(dayKey: String, text: String) =
        editScenes("daytext:$dayKey") { e -> e.change(dayKey) { it.copy(text = text.trim().take(60).ifEmpty { null }) } }

    /** The voice lines the export lays over the music, each from its scene's start, cut at the clip's end. */
    private fun voiceSounds(plan: ClipPlan): List<ClipMusic.Sound> = _design.value.voices.mapNotNull { v ->
        val from = plan.sceneStart(v.sceneKey) ?: return@mapNotNull null
        val atUs = from * 1_000_000L / plan.fps
        val lengthUs = minOf(v.durationMs * 1000, (plan.frameCount * 1_000_000L / plan.fps) - atUs)
        if (lengthUs <= 0) null else ClipMusic.voice(ClipMedia.voiceFile(graph.app, tripId, v), atUs, lengthUs)
    }

    override fun onCleared() {
        recorder.cancel()
        super.onCleared()
    }

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
        val sources = ClipMedia.sources(graph.app, tripId, videos)
        videoJob = viewModelScope.launch {
            val done = HashMap(kept)
            for (v in videos) {
                if (v.id in done) continue
                val source = sources[v.id] ?: continue
                val prepared = withContext(Dispatchers.Default) {
                    try {
                        PreparedVideoFrames.prepare(source, w, h, keys.getValue(v.id)) { ensureActive() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("TripClip", "Couldn't prepare a video for the preview", e)
                        null
                    }
                } ?: continue
                done[v.id] = prepared
            }
            preparedVideos = done
            _videoFrames.value = PreparedVideoFrames(done)
        }
    }

    /** The phone videos' own sounds the export keeps (quietly under the music, a little louder without it). */
    private fun videoSounds(plan: ClipPlan): List<ClipMusic.Sound> {
        val byId = _design.value.videos.associateBy { it.id }
        val gain = if (plan.options.music != null) ClipMusic.VIDEO_GAIN_UNDER_MUSIC else ClipMusic.VIDEO_GAIN_ALONE
        return plan.inserts.filter { it.kind == InsertKind.Video }.mapNotNull { insert ->
            val v = byId[insert.id]?.takeIf { it.sound } ?: return@mapNotNull null
            ClipMusic.Sound(
                file = ClipMedia.videoFile(graph.app, tripId, v),
                atUs = insert.fromFrame * 1_000_000L / plan.fps,
                fromUs = v.startMs * 1000,
                lengthUs = insert.frames * 1_000_000L / plan.fps,
                gain = gain,
            )
        }
    }

    /** The look's style for drawing (made on the main thread by the screen, which knows the current look). */
    fun setStyle(style: ClipStyle) {
        val changedDark = style.look.dark != dark || _style.value == null
        // Globe's street map comes in the look's own land and sea: another light look fetches it again.
        val changedTint = _style.value?.let(::tintOf) != tintOf(style)
        dark = style.look.dark
        _style.value = style
        if (changedDark || changedTint) _plan.value?.let { fetchMap(it) }
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

    /**
     * How [leg] travels (null: the plan's default), saved on the trip like the plan's own choice; the clip re-plans when
     * the trip's travel changes (owner, 2026-11-08: the travel is chosen in the editor, not on the trip's map).
     */
    fun setLegVehicle(leg: RouteLeg, vehicle: Vehicle?) {
        viewModelScope.launch {
            try {
                graph.trips.setLegVehicle(tripId, leg.key, vehicle)
                _legSaveFailed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TripClip", "Couldn't save the leg's travel", e)
                _legSaveFailed.value = true
            }
        }
    }

    /** Whether [leg] counts in the trip's km (null: the default), saved on the trip like the plan's own switch. */
    fun setLegKm(leg: RouteLeg, count: Boolean?) {
        viewModelScope.launch {
            try {
                graph.trips.setLegKm(tripId, leg.key, count)
                _legSaveFailed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TripClip", "Couldn't save whether the leg counts in km", e)
                _legSaveFailed.value = true
            }
        }
    }

    private val _legSaveFailed = MutableStateFlow(false)
    /** The last travel choice for a leg couldn't be saved (the Route panel says to try again). */
    val legSaveFailed: StateFlow<Boolean> = _legSaveFailed.asStateFlow()

    /** The country lookup (bundled outlines), read once when a planned route needs it. */
    private val countries: Countries? by lazy {
        runCatching { graph.app.assets.open(TrackProcessor.COUNTRIES_ASSET).bufferedReader().use { Countries.parse(it.readText()) } }.getOrNull()
    }

    /**
     * A planned route's places get their country (plan items carry none), so the clip knows a trip that crosses a
     * border (it opens from space then) and names the countries on the end card.
     */
    private fun withCountries(source: ClipSource, input: ClipInput): ClipInput {
        if (source != ClipSource.Planned || input.stops.all { it.countryCode != null }) return input
        val lookup = countries ?: return input
        return input.copy(stops = input.stops.map { s -> if (s.countryCode != null) s else s.copy(countryCode = lookup.codeAt(s.lat, s.lng)) })
    }

    /** A recorded journey's vehicle (Auto: guessed by speed per stretch). */
    fun setVehicle(v: Vehicle) {
        if (_source.value == ClipSource.Recorded) commit { it.copy(vehicle = v) }
    }
    /**
     * Pace: the trip's travel time at ×[speed] (×1, ×2, ×5 or ×10; no set length). A free clip longer than a minute is
     * Pro (the screen opens the offer instead).
     */
    fun setSpeed(speed: Int) {
        if (speed in ClipTiming.SPEEDS) commit { it.copy(speed = speed, length = null) }
    }

    /** How long the clip runs (owner, 2026-11-08: "make sure speeds are well defined"); null: Pace. */
    fun setLength(length: ClipLength?) = commit { it.copy(length = length) }

    /** Steady (one height per stretch, Strava-like; the default) or Follow (the camera breathes with the speed). */
    fun setCamera(mode: CameraMode) = setDesign { it.copy(camera = mode) }
    /** 9:16 · 4:5 · 1:1 · 16:9 ([ClipShape]), remembered for the next clip: the camera, the texts and the export follow it. */
    fun setShape(s: ClipShape) = commit { it.copy(shape = s) }

    /**
     * A named look ([ClipLook]): sets the text, line and finish choices together (remembered like any style change);
     * single options can still be changed after with [setDesign]. [ClipDesign.look] says which look the style has.
     */
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

    /**
     * This video's traveller (kept with this trip's edits; one undo step); null, or the same as Me › Your traveller,
     * follows Me.
     */
    fun setTraveller(t: Traveller?) = setDesign { it.copy(traveller = t?.takeIf { v -> v != meTraveller }) }

    /** This video's traveller becomes Me › Your traveller, the default for every video. */
    fun makeTravellerDefault() {
        if (_make.value is Make.Working) return
        val t = _design.value.travellerShown
        useDefaultTraveller(t)
        viewModelScope.launch { graph.settings.setTraveller(t) }
        setTraveller(null)
    }

    /**
     * This video's rides, every vehicle's model and colours (kept with this trip's edits; one undo step); null, or the
     * same as Me › Your traveller & rides, follows Me.
     */
    fun setRides(r: Rides?) = setDesign { it.copy(rides = r?.takeIf { v -> v != meRides }) }

    /** This video's rides become Me's, the default for every video. */
    fun makeRidesDefault() {
        if (_make.value is Make.Working) return
        val r = _design.value.ridesShown
        useDefaultRides(r)
        viewModelScope.launch { graph.settings.setRides(r) }
        setRides(null)
    }

    /** Music hook (`clip/music`): the track under the video, remembered for the next clip; null = silent. */
    fun setMusic(trackId: String?) = commit { it.copy(music = trackId, edits = it.edits.copy(musicStartS = null)) }

    /** Where the music starts in its track, in seconds (null: its strongest stretch); snapped to a bar when it plays. */
    fun setMusicStart(seconds: Double?) = commit { it.copy(edits = it.edits.copy(musicStartS = seconds)) }

    /** Where this clip's music starts in its track now, on a bar (0 without music). */
    val musicStartS: Double get() = MusicCatalog.startFor(_options.value.music, _design.value.musicStartS)

    /**
     * Cuts on the beat on or off (remembered; on by default): with a track, day changes, photo pauses, close-ups,
     * videos and day cards start on its beats and last whole beats ([com.justdataplease.boringtravel.clip.BeatFit]);
     * the clip's length stays within a beat. Re-plans the clip.
     */
    fun setOnBeat(on: Boolean) = commit { it.copy(onBeat = on) }

    private fun applySavedChoices(trip: Trip) {
        // Repository choice writes have strictly increasing revisions, including rapid same-ms taps.
        if (trip.updatedAt < latestChoicesAt) return
        // An encoder uses one immutable reviewed plan; external edits take effect after it finishes.
        if (_make.value is Make.Working) {
            if (trip.updatedAt >= (deferredChoices?.updatedAt ?: Long.MIN_VALUE)) deferredChoices = trip
            return
        }
        latestChoicesAt = trip.updatedAt
        inputs = inputs.mapValues { (_, value) -> value.copy(routeLegs = RouteLegs.withChoices(trip, value.routeLegs)) }
        val selectedSource = _source.value ?: return
        val next = inputs[selectedSource] ?: return
        val changed = input?.routeLegs != next.routeLegs
        input = next
        if (changed) {
            _make.value = Make.Idle
            _drive.value = Drive.Idle
            _phone.value = Phone.Idle
            replan()
        }
    }

    private fun applyDeferredChoices() {
        val saved = deferredChoices ?: return
        deferredChoices = null
        applySavedChoices(saved)
    }

    private fun update(change: (ClipOptions) -> ClipOptions) {
        if (_make.value is Make.Working) return
        val next = change(_options.value)
        if (next == _options.value) return
        _options.value = next
        _make.value = Make.Idle
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
        replan()
    }

    private fun replan() {
        val source = input ?: return
        val opts = _options.value
        val generation = ++planGeneration
        _replanning.value = true
        planJob?.cancel()
        planJob = viewModelScope.launch {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                var plan = withContext(Dispatchers.Default) { ClipPlan.build(source, opts) }
                EditorPerf.log("plan built in ${android.os.SystemClock.elapsedRealtime() - t0} ms (${plan?.frameCount} frames)")
                if (plan == null) {
                    _load.value = Load.NoRoute
                    return@launch
                }
                // A free clip stays within a minute: a long trip at ×1 (or ×2) moves to the slowest speed that does.
                // Each photo's own pause counts (scene editor), as on the speed chips. A set length is already within it.
                val free = if (opts.length != null) opts.speed else ClipTiming.freeSpeed(plan.travelHours, opts.speed, isPro, plan.photoHolds, plan.addedSeconds)
                if (free != opts.speed) {
                    val faster = opts.copy(speed = free)
                    plan = withContext(Dispatchers.Default) { ClipPlan.build(source, faster) } ?: plan
                    _options.value = faster
                    // The step takes the speed the clip runs at (not a step of its own), so later changes keep it.
                    _history.value = _history.value.let { h -> h.copy(present = h.present.copy(speed = free)) }
                }
                _plan.value = plan
                _load.value = Load.Ready
                fetchMap(plan)
                loadPhotos(plan)
                prepareVideos(plan)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _load.value = Load.Failed
            } finally {
                if (generation == planGeneration) _replanning.value = false
            }
        }
    }

    /** One base map per route, camera, shape, map look and theme; other option changes (photos) reuse it. */
    private fun fetchMap(plan: ClipPlan) {
        val style = _style.value ?: return
        val tint = if (plan.globe) tintOf(style) else null
        val key = MapKey(plan.geometryKey, dark, tint)
        if (key == mapKey) return
        mapKey = key
        mapJob?.cancel()
        // Same route, look and theme (only the camera's pace changed, e.g. a photo pause in the scene editor): the
        // map in hand still fits, so it stays on screen until the new pictures are all in.
        val route = routeKey(plan, tint)
        val keep = route == mapRoute && _base.value.hasMap
        mapRoute = route
        if (!keep) _base.value = BaseMap.Plain
        _mapStatus.value = MapStatus.Loading
        _mapProgress.value = 0f
        val isDark = dark
        mapJob = viewModelScope.launch {
            // Globe: the globe itself is bundled and there at once; its street map comes in for the close views.
            val globe = if (plan.globe) globeFor(plan) else null
            if (globe != null && !keep) _base.value = BaseMap(emptyList(), MapStyle.Globe, globe)
            // Every picture the clip needs, the close-ups' and a closer zoom's included, comes in before the preview
            // plays (it waits while this loads) and before the export starts.
            val specs = withContext(Dispatchers.Default) { plan.layerSpecs() }
            EditorPerf.log("map: ${specs.size} pictures to fetch")
            val result = BaseMaps.fetch(
                graph.app, specs, isDark, plan.options.map, tint,
                onLayer = { partial -> if (!keep) _base.value = partial.withGlobe(globe) },
                onProgress = { _mapProgress.value = it },
            ).withGlobe(globe)
            if (result.hasMap || !keep) _base.value = result
            // A globe without the street map it needed (offline) still makes a clip, with the note to retry.
            val streetMissing = globe != null && specs.isNotEmpty() && _base.value.layers.isEmpty()
            _mapStatus.value = if (_base.value.hasMap && !streetMissing) MapStatus.Ready else MapStatus.Plain
            EditorPerf.log("map ready")
        }
    }

    /** The route's ground (not its camera): a map fetched for it fits any plan with the same key. */
    private fun routeKey(plan: ClipPlan, tint: MapTint?): Int = java.util.Objects.hash(
        plan.xs.contentHashCode(), plan.ys.contentHashCode(), plan.options.shape, plan.options.view, plan.options.sky, plan.options.map,
        plan.source, dark, tint,
    )

    /** Globe's street map colours: the look's land, sea and ink (as the trip map recolours its map). */
    private fun tintOf(style: ClipStyle): MapTint = MapTint.of(style.land, style.sea, style.ink)

    /**
     * The globe under a Globe clip: the Map tab's shapes (built once for the app), the countries the route goes
     * through (first reached first, [ClipGlobe.countries]) filled in the look's highlighter, with their flags.
     */
    private suspend fun globeFor(plan: ClipPlan): GlobeBase? {
        val geometry = graph.world.geometry() ?: return null
        val countries = graph.world.countries()
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
        val missing = plan.photos.map { it.id }.filter { it !in _photos.value && it in photoFiles }
        if (missing.isEmpty()) return
        photoJob = viewModelScope.launch {
            val more = ClipExport.loadPhotos(missing.associateWith { photoFiles.getValue(it) })
            _photos.value = _photos.value + more
        }
    }

    fun makeClip() {
        if (_make.value is Make.Working || !_base.value.hasMap || _replanning.value) return
        val plan = _plan.value ?: return
        if (!isPro && ClipTiming.needsPro(plan.seconds)) return
        val generation = ++makeGeneration
        _drive.value = Drive.Idle
        _phone.value = Phone.Idle
        _make.value = Make.Working(0f)
        makeJob = viewModelScope.launch {
            try {
                val style = _style.filterNotNull().first()
                mapJob?.join()
                check(_base.value.hasMap) { "A geographic map is required for this clip" }
                photoJob?.join()
                val out = ClipExport.outputFile(graph.app, plan.title)
                val design = _design.value
                val playing = plan.inserts.mapNotNull { it.id }.toSet()
                val videos = ClipMedia.sources(graph.app, tripId, design.videos.filter { it.id in playing })
                // Music hook (clip/music): the chosen track with the videos' kept sounds and the voice lines.
                val sounds = videoSounds(plan) + voiceSounds(plan)
                val track = MusicCatalog.byId(plan.options.music)
                val hasSound = track != null || sounds.isNotEmpty()
                // One bar over the frames, the sound and the last writes, on a whole percent; never 100 % before Done.
                val bar = ExportBar(hasSound) { p, stage -> if (generation == makeGeneration) _make.value = Make.Working(p, stage) }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var framesAt = startedAt
                var madeAt = startedAt
                val (file, withMusic) = coroutineScope {
                    // The sound needs only the clip's length, so it is made while the frames render (owner, on 1.4.0:
                    // "during export it lags at 100% for a bit"). A child: cancelling the export stops it too.
                    val sound = if (!hasSound) null else async(Dispatchers.Default) {
                        runCatching {
                            ClipMusic.prepare(
                                graph.app, plan.frameCount * 1_000_000L / plan.fps, track,
                                ClipMusic.startUs(track, MusicCatalog.startFor(plan.options.music, design.musicStartS)), sounds, bar::sound,
                            )
                        }
                    }
                    val file = ClipExport.make(plan, _base.value, style, _photos.value, noMark, design, out, videos) { p ->
                        if (p >= 1f) framesAt = android.os.SystemClock.elapsedRealtime()
                        bar.frames(p)
                    }
                    madeAt = android.os.SystemClock.elapsedRealtime()
                    bar.framesDone()
                    // The sound goes under the finished video; if it couldn't be made or added, the video stays silent.
                    val withMusic = if (sound == null) true else {
                        val made = sound.await()
                        val audio = made.getOrElse { e ->
                            if (e is CancellationException) throw e
                            Log.w("TripClip", "Couldn't make the sound", e)
                            null
                        }
                        when {
                            made.isFailure -> false
                            audio == null -> true
                            else -> ClipMusic.attach(file, audio, bar::sound)
                        }
                    }
                    file to withMusic
                }
                Log.i("TripClip", "export ms: frames=${framesAt - startedAt}, finish=${madeAt - framesAt}, sound=${android.os.SystemClock.elapsedRealtime() - madeAt}")
                // bt-nav hook: keep every finished clip for the trip's Media tab (a copy in files/clips, data/SavedClips).
                if (generation == makeGeneration) com.justdataplease.boringtravel.data.SavedClips.of(graph.app).keep(tripId, plan.title, file)
                if (generation == makeGeneration) _make.value = Make.Done(file, musicFailed = !withMusic)
            } catch (e: CancellationException) {
                if (generation == makeGeneration) _make.value = Make.Idle
                throw e
            } catch (e: Exception) {
                if (generation == makeGeneration) _make.value = Make.Failed
            } finally {
                if (generation == makeGeneration) applyDeferredChoices()
            }
        }
    }

    fun cancelClip() {
        makeGeneration++
        makeJob?.cancel()
        _make.value = Make.Idle
        applyDeferredChoices()
    }

    fun saveToDrive() {
        val done = _make.value as? Make.Done ?: return
        if (_drive.value == Drive.Saving) return
        _drive.value = Drive.Saving
        viewModelScope.launch {
            _drive.value = try {
                val drive = graph.drive
                if (!drive.isConnected()) {
                    Drive.NotConnected
                } else {
                    val folder = drive.folder(DriveApi.EXPORTS)
                    val file = drive.upload(done.file, done.file.name, "video/mp4", folder)
                    Drive.Saved(drive.openUrl(file), file)
                }
            } catch (e: DriveNotConnectedException) {
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
        viewModelScope.launch {
            val now = try {
                saved.copy(link = graph.drive.shareLink(file), linking = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saved.copy(linking = false, linkFailed = true)
            }
            if (_drive.value is Drive.Saved) _drive.value = now
        }
    }

    /** Keeps a copy of the made video in the phone's gallery (Movies › BoringTravel). */
    fun saveToPhone() {
        val done = _make.value as? Make.Done ?: return
        if (_phone.value == Phone.Saving) return
        _phone.value = Phone.Saving
        viewModelScope.launch {
            _phone.value = try {
                withContext(Dispatchers.IO) { Sharing.saveVideo(graph.app, done.file) }
                Phone.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Phone.Failed
            }
        }
    }
}

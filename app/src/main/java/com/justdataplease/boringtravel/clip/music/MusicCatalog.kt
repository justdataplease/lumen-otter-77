package com.justdataplease.boringtravel.clip.music

import com.justdataplease.boringtravel.clip.BeatGrid
import com.justdataplease.boringtravel.clip.ClipBeats

/**
 * One background track for the trip clip, bundled in `assets/music/`: joyful Latin, African and tropical grooves. All
 * eight are CC0 (public domain), recorded with their sources in `research/boringtravel/music.md`: AAC-LC .m4a, 96 kbps,
 * 44.1 kHz stereo, about −16 LUFS, at most two minutes (longer originals end with a 3 s fade).
 */
data class MusicTrack(
    /** Stable id, saved in the clip prefs; also the asset's file name. */
    val id: String,
    val name: String,
    val mood: String,
    /** Path inside the APK's assets. */
    val asset: String,
    /** Length of the bundled file. */
    val durationMs: Int,
    /** Its beat grid (measured by tools/music_beats.py), for cuts on the beat. */
    val beats: BeatGrid,
)

object MusicCatalog {
    const val FOLDER = "music"

    /**
     * The beat grids come from the bundled files (tools/music_beats.py, 2026-11-08): tempo from the onset envelope's
     * repeats, phase on the kick, held within ±16 ms at the start, middle and end of every track, and the same
     * within ~12 ms by an independent spectral-flux check. Afro Groove and Lost in Brazil repeat strongly every three
     * sixteenths (140 and 147 BPM); their beat is 105 and 110.
     */
    val tracks: List<MusicTrack> = listOf(
        track("latin_groove", "Latin Groove", "Latin", 120_000, bpm = 106.01, firstBeatMs = 180),
        track("afro_groove", "Afro Groove", "Afrobeat", 109_801, bpm = 105.00, firstBeatMs = 4),
        track("que_chilero", "Que Chilero", "Latin jazz", 120_000, bpm = 112.01, firstBeatMs = 91),
        track("mirihi", "Mirihi", "Tropical house", 120_000, bpm = 100.00, firstBeatMs = 564),
        track("lost_in_brazil", "Lost in Brazil", "Latin pop", 120_000, bpm = 110.00, firstBeatMs = 8),
        track("offbeat", "Offbeat", "Reggae", 120_000, bpm = 78.98, firstBeatMs = 20),
        track("caldera", "Caldera", "Dancehall", 120_000, bpm = 120.00, firstBeatMs = 8),
        track("sun_village", "Sun Village", "World", 120_000, bpm = 99.99, firstBeatMs = 301),
    )

    /** The track with [id]; null for None, or an id this version doesn't have (such as a track an older version offered). */
    fun byId(id: String?): MusicTrack? = id?.let { wanted -> tracks.firstOrNull { it.id == wanted } }

    /**
     * The beats a clip with track [id] played from [startS] cuts on ([ClipBeats]), or null when there is no music or
     * [onBeat] is off.
     */
    fun clipBeats(id: String?, onBeat: Boolean, startS: Double = 0.0): ClipBeats? =
        if (!onBeat) null else byId(id)?.let { ClipBeats(it.beats, it.durationMs / 1000.0, startOf(it, startS)) }

    /** Beats in a bar: starts snap to the first beat of one, so the song comes in on its downbeat. */
    const val BAR = 4

    /** The latest a track may start: [LAST_START_S] before its end (a start later than that is no song at all). */
    const val LAST_START_S = 12.0

    /** [s] seconds into [track], kept where the track can still play from (0 when it has no room). */
    fun startOf(track: MusicTrack, s: Double): Double {
        val last = (track.durationMs / 1000.0 - LAST_START_S).coerceAtLeast(0.0)
        return if (s.isFinite()) s.coerceIn(0.0, last) else 0.0
    }

    /**
     * Where a clip with track [id] starts in it: [chosen] seconds (the editor's "Starts at"), else the track's
     * strongest stretch ([MusicShapes.hookS]), on a bar; 0 without a track.
     */
    fun startFor(id: String?, chosen: Double?): Double {
        val track = byId(id) ?: return 0.0
        return onBar(track, chosen ?: MusicShapes.hookS(track.id))
    }

    /** The bar's first beat nearest [s] seconds into [track] (0 for the very start), within [startOf]. */
    fun onBar(track: MusicTrack, s: Double): Double {
        val bar = track.beats.periodS * BAR
        val first = track.beats.firstBeatS
        val wanted = startOf(track, s)
        if (bar <= 0 || wanted < first + bar / 2) return 0.0
        val k = Math.round((wanted - first) / bar)
        var at = first + k * bar
        val last = startOf(track, Double.MAX_VALUE)
        while (at > last && at - bar >= 0) at -= bar
        return at.coerceAtLeast(0.0)
    }

    private fun track(id: String, name: String, mood: String, durationMs: Int, bpm: Double, firstBeatMs: Int) =
        MusicTrack(id, name, mood, "$FOLDER/$id.m4a", durationMs, BeatGrid(bpm, firstBeatMs / 1000.0))
}

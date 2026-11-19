package com.justdataplease.boringtravel.clip.music

import android.content.Context
import androidx.core.content.edit

/**
 * The trip clip's last music choice, kept on this phone like the speed and the map look: [DEFAULT] the first time
 * (owner, 2026-11-08: defaults made to be shared; a silent clip is rarely watched), None once picked. And whether scene
 * changes go on the beat (on unless turned off; only with a track).
 */
object MusicPrefs {
    private const val FILE = "clip_music"
    private const val KEY = "track"
    private const val ON_BEAT = "on_beat"
    /** Stored for an explicit None, so "nothing saved yet" and "picked None" read the same way. */
    internal const val NONE = "none"

    fun load(context: Context): String? = prefs(context).getString(KEY, null)?.let(::decode) ?: DEFAULT

    /** The track a first clip gets. */
    const val DEFAULT = "latin_groove"

    /** Forgets the music choice, so the next clip starts on [DEFAULT] ([com.justdataplease.boringtravel.clip.ClipPrefs.newDefaults]). */
    fun forget(context: Context) {
        prefs(context).edit { remove(KEY) }
    }

    fun save(context: Context, trackId: String?) {
        prefs(context).edit().putString(KEY, encode(trackId)).apply()
    }

    /** Cuts on the beat: on unless turned off. */
    fun onBeat(context: Context): Boolean = prefs(context).getBoolean(ON_BEAT, true)

    fun saveOnBeat(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ON_BEAT, on).apply()
    }

    /** What is written for [trackId]: its id, or [NONE]. */
    internal fun encode(trackId: String?): String = MusicCatalog.byId(trackId)?.id ?: NONE

    /** The track id read back, or null (None) for [NONE], nothing saved, or a track this version no longer has. */
    internal fun decode(stored: String?): String? = MusicCatalog.byId(stored)?.id

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.engine.TripFile
import com.justdataplease.boringtravel.io.IOException

/*
 * Where the iPhone keeps its trips (round 28). Android keeps them in its Room database (files/../databases/boringtravel.db:
 * trips, plan items, notes, photos' and videos' rows, route points, stops, countries marked by hand, books) and the files
 * beside it under files/ (media/, clips/, clip-edits/, clip-media/, clip-voice/). The iPhone keeps the same rows as the
 * engine's one text (TripBook over a [TripFile]: the backup's data.json, every row of those eight tables and what a
 * backup carries with them) and the same files under Application Support/BoringTravel. Until round 28 that text was one
 * value in the app's UserDefaults, which iOS treats as invalid past 4 MB (CFPreferences' limit, "Attempting to store >=
 * 4194304 bytes"), a size a route recorded in detail for a few weeks reaches, and which reads as empty before the
 * phone's first unlock after a restart. Since round 28 it is a file of its own beside the photos, written whole and
 * atomically; the text in UserDefaults moves there on the first start.
 */

/** The trips' store couldn't be read though it is there (iPhone: its files are locked until the first unlock). */
class TripStoreLockedException : IOException("The trips can't be read yet.")

/**
 * The engine's text as the file [path] under [files] (the app's files: Application Support/BoringTravel on iPhone), each
 * write a whole new file put in the old one's place (a write the phone cut off leaves the last whole one).
 *
 * The first read of a phone that kept its trips in [legacy] (UserDefaults, before round 28) moves them here: the file is
 * written, read back, and only when it reads back the same is [forgetLegacy] called; until then the legacy text stays
 * and the next start tries again. A file that is there but can't be read is never taken as "no trips"
 * ([TripStoreLockedException]): the app then runs on a book in memory (AppCore), and nothing it writes can replace them.
 * A write that doesn't reach the disk (the phone full) throws, as Android's database does.
 */
class FileTripFile(
    private val files: MediaFiles,
    private val legacy: TripFile? = null,
    private val forgetLegacy: () -> Unit = {},
    private val path: String = PATH,
) : TripFile {
    override fun read(): String? {
        files.read(path)?.let { return it.decodeToString() }
        if (files.size(path) != null) throw TripStoreLockedException()
        val old = legacy?.read() ?: return null
        val bytes = old.encodeToByteArray()
        if (files.write(path, bytes) && files.read(path)?.contentEquals(bytes) == true) forgetLegacy()
        return old
    }

    override fun write(text: String) {
        if (!files.write(path, text.encodeToByteArray())) throw IOException("The trips couldn't be saved. Make room on the phone and try again.")
    }

    companion object {
        /** The store's file under the app's files. */
        const val PATH = "store/trips.json"
    }
}

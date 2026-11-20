package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.together.ShareLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Sharing into the app (round 27; Android: MainActivity's receiveShare and receivePictures, AppNav's pendingPlace and
 * pendingImport, and ui/share, read in at build time). On iPhone the share sheet runs the app's Share Extension
 * (iosApp/BoringTravelShare, Swift). Apple gives a share extension no way to open its app, and the app's screens can't
 * run inside it, so the extension only writes what was shared into the App Group's folder, as it came (the text and its
 * links, the page's title, the pictures and a file as files), and says so. The app takes it the next time it is on
 * screen ([ShareInbox]: at the start, back in front, and when the scene is active again) and decides here, by Android's
 * own rules ([ShareIntake]), where it goes: Add to plan (Android's ShareToPlanSheet on [ShareGraph]), the planner (a file,
 * a Google Doc's link, a longer text, pictures), the Join sheet, or "Nothing to add: share a link". Each waits for Welcome
 * and is kept with the screens ([AppRestore]), as Android's saved instance state keeps it.
 */

/** What the share extension wrote for one share: Shares/<name>.json, its files under Shares/<name>/ (names only here). */
@Serializable
data class ShareManifest(
    val v: Int = 1,
    /** The text and links shared, one after the other (a link the text already holds isn't written twice). */
    val text: String? = null,
    /** The shared item's own title (a browser's page title: Android's EXTRA_SUBJECT). */
    val subject: String? = null,
    /** The pictures, as files in the share's folder, in the order shared. */
    val pictures: List<String> = emptyList(),
    /** A shared file (Android's EXTRA_STREAM of a text/plain share), at most [ShareInbox.FILE_BYTES] of it. */
    val file: String? = null,
)

/** Where something shared into the app goes (the Android app's MainActivity: pendingJoin, pendingImport, pendingPlace). */
sealed interface ShareArrival {
    /** A shared trip's link: the Join sheet. */
    data class Join(val link: ShareLink) : ShareArrival

    /** For the planner: pictures, a file, a Google Doc's link or a longer text. */
    data class Import(val share: SharedItinerary) : ShareArrival

    /** A link for Add to plan, or [ShareRoute.NoLink] ("Nothing to add: share a link"). */
    data class Place(val route: ShareRoute) : ShareArrival
}

/** The Android app's MainActivity.receiveShare and receivePictures, line by line, on what the extension wrote. */
object ShareIntake {
    /**
     * Pictures go to the planner (at most [PictureReading.MAX_PICTURES]; Android's receivePictures, which comes first and
     * leaves any words beside them); a trip's join link to the Join sheet; then [SharedLinks.route]: a link in a short
     * text to Add to plan, a file, a Google Doc's link or a longer text to the planner, a few words without a link to
     * "Nothing to add". The text is cut one character past [ItineraryImport.MAX_CHARS], so the planner still says it is
     * too long instead of losing part of it. Null: nothing to do (nothing shared).
     */
    fun of(text: String?, subject: String?, pictures: List<String>, file: String?): ShareArrival? {
        if (pictures.isNotEmpty()) return ShareArrival.Import(SharedItinerary(pictures = pictures.take(PictureReading.MAX_PICTURES)))
        val t = text?.take(ItineraryImport.MAX_CHARS + 1)
        ShareLink.find(t)?.let { return ShareArrival.Join(it) }
        return when (val route = SharedLinks.route(t, subject, hasFile = file != null)) {
            null -> null
            ShareRoute.Itinerary -> ShareArrival.Import(SharedItinerary(t, file))
            else -> ShareArrival.Place(route)
        }
    }
}

/**
 * The shares the extension left in the App Group's folder ([group]: the folder itself on iPhone, a folder in memory in the
 * tests; null without an App Group, as in the Simulator's unsigned build). [take] reads each one, oldest first, decides
 * where it goes ([ShareIntake]) and moves the files it needs under the app's own [files] ("picked/…", where the planner's
 * pictures are), then removes it from the folder: a share is taken once. [planted]: shares to take as if the extension
 * had written them (the Simulator tour, which has no App Group).
 */
class ShareInbox(private val group: MediaFiles?, private val files: MediaFiles, planted: List<ShareManifest> = emptyList()) {
    private var plantedLeft = planted

    /** Goes up each time the phone says the app is active again (Swift's scenePhase): the app frame looks then. */
    var asked by mutableIntStateOf(0)
        private set

    fun ask() {
        asked++
    }

    /** Every share waiting, oldest first, as where each goes. Each is gone from the folder once read. */
    fun take(): List<ShareArrival> {
        val out = ArrayList<ShareArrival>()
        plantedLeft.forEachIndexed { i, m -> arrival(m, null, "planted-$i")?.let(out::add) }
        plantedLeft = emptyList()
        val g = group ?: return out
        val names = g.list(FOLDER).filter { it.endsWith(".json") }.sorted()
        for (name in names) {
            val id = name.removeSuffix(".json")
            val manifest = g.read("$FOLDER/$name")?.let { bytes ->
                runCatching { json.decodeFromString(ShareManifest.serializer(), bytes.decodeToString()) }.getOrNull()
            }
            // Only a name the extension makes ("<ms>-<uuid>"): nothing outside the share's own folder is ever read.
            if (manifest != null && SAFE_NAME.matches(id)) arrival(manifest, g, id)?.let(out::add)
            g.delete("$FOLDER/$name")
            if (SAFE_NAME.matches(id)) g.delete("$FOLDER/$id")
        }
        return out
    }

    /** Where [m] goes, with the files it needs moved under the app's own; the rest of its folder is left to be removed. */
    private fun arrival(m: ShareManifest, g: MediaFiles?, id: String): ShareArrival? {
        val pictures = m.pictures.filter(::safeFile)
        val file = m.file?.takeIf(::safeFile)
        val decided = ShareIntake.of(m.text, m.subject, pictures, file)
        if (decided !is ShareArrival.Import) return decided
        val share = decided.share
        var n = 0
        // A file of the share, under the app's files: "picked/shared-<share>-<n>.<ext>". A file that can't be brought
        // keeps its place in the list for the document (the planner then says it couldn't be opened); a picture is left out.
        fun bring(name: String): Pair<String, Boolean> {
            val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "bin"
            val path = "picked/shared-$id-${++n}.$ext"
            val bytes = g?.read("$FOLDER/$id/$name") ?: return path to false
            return path to files.write(path, bytes)
        }
        if (share.pictures.isNotEmpty()) {
            val moved = share.pictures.map(::bring).filter { it.second }.map { it.first }
            return if (moved.isEmpty()) null else ShareArrival.Import(share.copy(pictures = moved))
        }
        val uri = share.uri?.let { bring(it).first }
        return ShareArrival.Import(share.copy(uri = uri))
    }

    private fun safeFile(name: String): Boolean = SAFE_FILE.matches(name)

    companion object {
        /** The folder in the App Group's container the extension writes into (the widgets' files sit beside it). */
        const val FOLDER = "Shares"

        /** At most this much of a shared file is kept: one byte past Android's limit, so the planner says it is too long. */
        const val FILE_BYTES = ItineraryImport.MAX_BYTES + 1

        private val SAFE_NAME = Regex("[A-Za-z0-9-]{1,80}")
        private val SAFE_FILE = Regex("[A-Za-z0-9_-]{1,60}(\\.[A-Za-z0-9]{1,8})?")
        private val json = Json { ignoreUnknownKeys = true }

        /** [m] as the extension writes it (the tests write a share as the extension would). */
        fun manifestText(m: ShareManifest): String = json.encodeToString(ShareManifest.serializer(), m)
    }
}

/** The shares from the phone's share extension; null where a screen is drawn on its own. */
val LocalShareInbox = staticCompositionLocalOf<ShareInbox?> { null }

/*
 * Add to plan's AppGraph: Android's ShareToPlanSheet (read in) calls graph.trips, graph.plan, graph.links, graph.scope;
 * here they are the trip store as it changes ([revision]: the screens' count of changes), the item saved as the item
 * sheet saves it (the engine's saveItems, Android's PlanRepository.save), a short Maps link's place filled after saving
 * (Android's fillFromLink), the link previews' look at a Google Maps place or list, and the app frame's own coroutines
 * (an Add goes on after the sheet closes). today() is the frame's day (the real one in the app, the previews' own in tests).
 */
class ShareGraph(
    book: TripBook,
    web: Web,
    /** The app frame's own coroutines (the main thread), as Android's graph.scope. */
    val scope: CoroutineScope,
    revision: () -> Int,
    changed: () -> Unit,
    private val day: () -> LocalDate,
) {
    val trips = ShareGraphTrips(book, revision)
    val plan = ShareGraphPlan(book, revision, changed)
    val links = ShareGraphLinks(web)

    fun today(): LocalDate = day()
}

/** The trips as Add to plan reads them: all of them (deleted ones too, as Android's observeAll), as they change. */
class ShareGraphTrips(private val book: TripBook, private val revision: () -> Int) {
    fun observeEvery(): Flow<List<Trip>> = snapshotFlow {
        revision()
        book.snapshot.trips
    }
}

/** A trip's plan as Add to plan reads and writes it (Android's PlanRepository). */
class ShareGraphPlan(private val book: TripBook, private val revision: () -> Int, private val changed: () -> Unit) {
    fun observeItems(tripId: String): Flow<List<PlanItem>> = snapshotFlow {
        revision()
        book.items(tripId)
    }

    /** Saved as the item sheet saves (tidied, stamped); Android's PlanRepository.save. */
    suspend fun save(item: PlanItem) {
        if (book.saveItem(item) != null) changed()
    }

    /**
     * Item [id] gets the place behind its Google Maps link [url] (a short link answers only after saving), if it still has
     * that link and no place of its own by then (Android's PlanRepository.fillFromLink).
     */
    suspend fun fillFromLink(id: String, url: String, place: MapsPlace) {
        val current = book.item(id)?.takeIf { it.mapsUrl == url } ?: return
        val filled = LinkPlaces.fillLater(current, url, place)
        if (filled != current && book.saveItem(filled) != null) changed()
    }
}

/** What Add to plan asks of a link: a Google Maps place behind it, or the list of places it shares. */
class ShareGraphLinks(private val web: Web) {
    suspend fun mapsPlace(url: String, near: LatLng?): MapsPlace? = web.links.mapsPlace(url, near)

    suspend fun mapsList(url: String): MapsList? = web.links.mapsList(url)
}

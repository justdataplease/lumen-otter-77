package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.site.ExistingTrip
import com.justdataplease.boringtravel.site.SiteImportState
import com.justdataplease.boringtravel.site.SiteImporter
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import java.io.File

/*
 * Copy from a link's pictures: a blog read and sorted by the AI, the reading and the sorting under way, one post's
 * link, the AI silent, the dates without AI, into a trip you have, places only, the copy under way and done, a blog
 * copied before, a private Tumblr, a Google Maps list and its places added. On the made-up blog's recorded pages
 * ([SiteSamples]) and the built-in planner's recorded sorting, never the network or a model.
 */

/**
 * Copy from a link on its own: the app's importer ([siteImporter]) on [book], the blog's recorded web behind [net]
 * (the built-in planner answering from it when [ai]), no pauses between pages or map questions.
 */
class SiteCase(
    val book: TripBook = TripBook(MemoryTripFile()),
    val net: PlannerNet = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.SORTED)),
    ai: Boolean = true,
    val files: MemoryMediaFiles = MemoryMediaFiles(),
    val settings: AppSettings = AppSettings.ready(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val web = Recorded.web(net)
    val kit = PlannerKit(book, settings, web, files, PlannerPhone(firebase = if (ai) RecordedAi.FIREBASE else FirebaseSetup.Config("", "", "")), PREVIEW_VERSION)
    val importer: SiteImporter = siteImporter(scope, book, files, web, kit, setup = SiteCopySetup(pause = {}, mapGapMs = 0))

    /** Waits (at most 20 s) until the copy is in a state [test] takes; that state. */
    fun until(test: (SiteImportState) -> Boolean): SiteImportState {
        val until = System.currentTimeMillis() + 20_000
        while (!test(importer.state.value) && System.currentTimeMillis() < until) Thread.sleep(10)
        return importer.state.value
    }

    /** Reads [address] and waits for its proposal (or its failure). */
    fun read(address: String, into: String? = null): SiteImportState {
        importer.read(address, into)
        return until { it is SiteImportState.Ready || it is SiteImportState.Failed }
    }

    /** Copies what is ticked and waits until it is done. */
    fun copy(): SiteImportState {
        importer.copy()
        return until { it is SiteImportState.Done }
    }
}

private const val SITE_WIDTH = 1320
private const val SITE_HEIGHT = 2868
private const val SITE_DENSITY = 3f
private val SITE_FRAME = PaddingValues(top = 62.dp, bottom = 34.dp)

fun siteCopy(out: File) {
    fun drawn(name: String, look: Look, importer: SiteImporter, book: TripBook, web: Web, height: Int = SITE_HEIGHT, open: Int? = null) {
        File(out, "$name.png").writeBytes(
            render(SITE_WIDTH, height, SITE_DENSITY, frames = 12) {
                CompositionLocalProvider(LocalWeb provides web) {
                    BoringTravelTheme(look) {
                        Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(SITE_FRAME)) {
                            CopySiteScreen(importer, book, 0, onBack = {}, onOpenTrip = { _, _ -> }, onDone = {}, openAtStart = open)
                        }
                    }
                }
            },
        )
    }

    fun drawn(name: String, look: Look, case: SiteCase, height: Int = SITE_HEIGHT, open: Int? = null) =
        drawn(name, look, case.importer, case.book, case.web, height, open)

    /** A traveller with a trip of their own, so "Add to" offers it. */
    fun withTrip(): TripBook = TripBook(MemoryTripFile()).also { it.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26)) }

    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val id = look.id
        // The blog read and sorted by the AI: two trips, their places, the switches, where it goes.
        val blog = SiteCase(withTrip())
        blog.read(SiteSamples.HOME)
        drawn("copy-blog-ready-$id", look, blog)
        // The first trip unfolded: its notes with their days and pictures.
        drawn("copy-blog-notes-$id", look, blog, open = 0)
        // Copied: what went where.
        blog.copy()
        drawn("copy-blog-done-$id", look, blog)
        // The same blog again: nothing new.
        blog.read(SiteSamples.HOME)
        drawn("copy-blog-again-$id", look, blog)
    }

    // Reading, with posts found so far (a reading that waits after twelve posts).
    val reading = SiteCase()
    val held = SiteImporter(
        reading.scope,
        reader = { _, _, onProgress, _ ->
            onProgress(12)
            awaitCancellation()
        },
        store = BookSiteStore(reading.book, reading.files, reading.net, "", { _, _ -> emptyList() }, null),
        ai = { null },
    )
    held.read("https://${SiteSamples.HOST}")
    val until = System.currentTimeMillis() + 20_000
    while ((held.state.value as? SiteImportState.Reading)?.found != 12 && System.currentTimeMillis() < until) Thread.sleep(10)
    drawn("copy-blog-reading", Look.PostcardPop, held, reading.book, reading.web)
    held.cancel()

    // Sorting: the AI asked and not answered yet.
    val sorting = SiteCase(net = PlannerNet(SiteSamples.net()).hold(RecordedAi::isBuiltIn))
    sorting.importer.read(SiteSamples.HOME)
    sorting.until { it is SiteImportState.Reading && it.organising }
    drawn("copy-blog-sorting", Look.PostcardPop, sorting)
    sorting.importer.cancel()

    // A link to one post: only that post, the whole blog one tap away.
    val one = SiteCase(net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.ONE_SORTED)))
    one.read(SiteSamples.ONE_POST)
    drawn("copy-blog-post", Look.PostcardPop, one)

    // The AI answered with nothing it could use: the dates decided, and Ask the AI again.
    val silent = SiteCase(net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText("I can't help with that.")))
    silent.read(SiteSamples.HOME)
    drawn("copy-blog-silent", Look.PostcardPop, silent)

    // No AI set up: the posts grouped by their dates.
    val dates = SiteCase(ai = false)
    dates.read(SiteSamples.HOME)
    drawn("copy-blog-dates", Look.Trail, dates)

    // Into a trip of yours, and the places only.
    val into = SiteCase(withTrip())
    into.read(SiteSamples.HOME)
    val naxos = into.book.trips(SAMPLE_TODAY).single()
    into.importer.setInto(ExistingTrip(naxos.id, naxos.title, naxos.startDate, naxos.endDate, emptyList()))
    drawn("copy-blog-into", Look.PostcardPop, into)
    val placesOnly = SiteCase()
    placesOnly.read(SiteSamples.HOME)
    placesOnly.importer.setWithNotes(false)
    drawn("copy-blog-places-only", Look.PostcardPop, placesOnly)

    // Copying: the pictures don't arrive yet.
    val copying = SiteCase(net = PlannerNet(SiteSamples.net(pictures = emptyMap())).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.SORTED)).hold { "/wp-content/uploads/" in it })
    copying.read(SiteSamples.HOME)
    copying.importer.copy()
    copying.until { it is SiteImportState.Copying }
    drawn("copy-blog-copying", Look.Trail, copying)
    copying.importer.cancel()

    // A private Tumblr blog: said under the address.
    val failed = SiteCase()
    failed.read("privateblog.tumblr.com")
    drawn("copy-link-failed", Look.PostcardPop, failed)

    // A Google Maps list: its places, then added.
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val list = SiteCase(withTrip())
        list.read(Recorded.LIST_SHORT)
        drawn("copy-link-list-${look.id}", look, list)
        list.copy()
        drawn("copy-link-list-done-${look.id}", look, list)
    }
}

package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.ItineraryImportException
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.ios.AppRestore
import com.justdataplease.boringtravel.ios.AppStack
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.PLAN_IDEAS
import com.justdataplease.boringtravel.ios.Place
import com.justdataplease.boringtravel.ios.ShareArrival
import com.justdataplease.boringtravel.ios.ShareInbox
import com.justdataplease.boringtravel.ios.ShareIntake
import com.justdataplease.boringtravel.ios.ShareManifest
import com.justdataplease.boringtravel.ios.ShareRoute
import com.justdataplease.boringtravel.ios.SharedLink
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing into the app (round 27) on both platforms: where a share goes (Android's MainActivity.receiveShare and
 * receivePictures, over Android's SharedLinks), the share extension's folder read once and oldest first with its files
 * brought under the app's own, what waits kept with the window (AppRestore), and a shared document's bytes read by
 * Android's rules, where Kotlin/Native's text decoding and regular expressions could differ from the JVM's.
 */
class ShareInSmokeTest {
    private val maps = "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z"

    @Test fun eachShareGoesWhereAndroidSendsIt() {
        // A link with a few words: Add to plan, named by the words (the Maps app's place name).
        val place = assertIs<ShareArrival.Place>(ShareIntake.of("Yasaka Shrine\n$maps", null, emptyList(), null)).route
        val link = assertIs<ShareRoute.Place>(place).link
        assertEquals("Yasaka Shrine", link.name)
        assertTrue(link.url.startsWith("https://www.google.com/maps/place/Yasaka"))
        // A browser's page: its title as the name.
        assertEquals("Hotel Granvia", (ShareIntake.of("https://www.granviakyoto.com/", "Hotel Granvia", emptyList(), null) as ShareArrival.Place).route.let { (it as ShareRoute.Place).link.name })
        // A few words without a link: nothing to add.
        assertEquals(ShareArrival.Place(ShareRoute.NoLink), ShareIntake.of("see you in Seville", null, emptyList(), null))
        // Nothing shared: nothing happens.
        assertNull(ShareIntake.of(null, null, emptyList(), null))
        // Five lines with a link, a Google Doc, a file: the planner, as they are.
        val trip = (1..5).joinToString("\n") { "Day $it in Lisbon" } + " $maps"
        assertEquals(ShareArrival.Import(SharedItinerary(trip, null)), ShareIntake.of(trip, null, emptyList(), null))
        val doc = "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp/edit"
        assertEquals(ShareArrival.Import(SharedItinerary(doc, null)), ShareIntake.of(doc, null, emptyList(), null))
        assertEquals(ShareArrival.Import(SharedItinerary("notes", "document.txt")), ShareIntake.of("notes", null, emptyList(), "document.txt"))
        // Pictures first, words beside them left, 10 at most.
        val pictures = (1..12).map { "$it.jpg" }
        assertEquals(ShareArrival.Import(SharedItinerary(pictures = pictures.take(10))), ShareIntake.of(maps, null, pictures, null))
        // A trip's join link, alone or in a friend's message: Join.
        val invite = ShareLink("trip-000000000000000000001", "drvMaria-file-1", "member-1", "Maria", "Spain 2027")
        assertEquals(ShareArrival.Join(invite), ShareIntake.of("Join my trip “Spain 2027”:\n${invite.url}", null, emptyList(), null))
        assertEquals(ShareArrival.Join(invite), ShareIntake.of(invite.url.replace(ShareLink.WEB, ShareLink.APP), null, emptyList(), null))
        // Text past 30,000 characters is cut one past the limit, so the planner can still say it is too long.
        val long = "x".repeat(ItineraryImport.MAX_CHARS + 50)
        val cut = assertIs<ShareArrival.Import>(ShareIntake.of(long, null, emptyList(), null)).share.text!!
        assertEquals(ItineraryImport.MAX_CHARS + 1, cut.length)
    }

    private fun write(group: MemoryMediaFiles, name: String, manifest: ShareManifest, files: Map<String, ByteArray> = emptyMap()) {
        files.forEach { (file, bytes) -> group.write("${ShareInbox.FOLDER}/$name/$file", bytes) }
        group.write("${ShareInbox.FOLDER}/$name.json", ShareInbox.manifestText(manifest).encodeToByteArray())
    }

    @Test fun theFolderIsReadOnceOldestFirstAndItsFilesBroughtIn() {
        val group = MemoryMediaFiles()
        val files = MemoryMediaFiles()
        val photo = byteArrayOf(1, 2, 3, 4)
        write(group, "1795000000002-b", ShareManifest(pictures = listOf("1.heic", "2.jpg")), mapOf("1.heic" to photo, "2.jpg" to photo))
        write(group, "1795000000001-a", ShareManifest(text = "Yasaka Shrine\n$maps"))
        write(group, "1795000000003-c", ShareManifest(file = "document.txt"), mapOf("document.txt" to "Day 1".encodeToByteArray()))
        // Not the extension's: a broken file, a name that could leave the folder, a folder without its share file.
        group.write("${ShareInbox.FOLDER}/1795000000004-d.json", "{not json".encodeToByteArray())
        write(group, "1795000000005-e", ShareManifest(pictures = listOf("../../secret.jpg")))
        group.write("${ShareInbox.FOLDER}/1795000000006-f/1.jpg", photo)
        val inbox = ShareInbox(group, files)
        val arrivals = inbox.take()
        assertEquals(3, arrivals.size)
        assertIs<ShareArrival.Place>(arrivals[0])
        val pictures = assertIs<ShareArrival.Import>(arrivals[1]).share.pictures
        assertEquals(listOf("picked/shared-1795000000002-b-1.heic", "picked/shared-1795000000002-b-2.jpg"), pictures)
        pictures.forEach { assertContentEquals(photo, files.read(it)) }
        val document = assertIs<ShareArrival.Import>(arrivals[2]).share.uri!!
        assertEquals("Day 1", files.read(document)!!.decodeToString())
        // Every share file gone with its folder; the unfinished folder left for the extension; nothing taken twice.
        assertTrue(group.list(ShareInbox.FOLDER).isEmpty())
        assertEquals(listOf("1.jpg"), group.list("${ShareInbox.FOLDER}/1795000000006-f"))
        assertTrue(inbox.take().isEmpty())
        // Without an App Group nothing comes in; the tour's planted share comes in once.
        val planted = ShareInbox(null, files, listOf(ShareManifest(text = "Yasaka Shrine\n$maps")))
        assertIs<ShareArrival.Place>(planted.take().single())
        assertTrue(planted.take().isEmpty())
    }

    @Test fun whatWaitsIsKeptWithTheWindow() {
        val stack = AppStack(listOf(Place.Trips))
        val link = SharedLink(maps, "Yasaka Shrine")
        val text = AppRestore.encodeKey(stack, TripTab.Plan, PLAN_IDEAS, emptyMap(), place = ShareRoute.Place(link), imported = SharedItinerary("Day 1", "picked/a.txt", listOf("picked/b.jpg")))
        assertEquals(ShareRoute.Place(link), AppRestore.sharedPlaceOf(text))
        assertEquals(SharedItinerary("Day 1", "picked/a.txt", listOf("picked/b.jpg")), AppRestore.sharedImportOf(text))
        assertEquals(ShareRoute.NoLink, AppRestore.sharedPlaceOf(AppRestore.encodeKey(stack, TripTab.Plan, null, emptyMap(), place = ShareRoute.NoLink)))
        val plain = AppRestore.encode(stack, TripTab.Plan, localDate(2027, 6, 18), emptyMap())
        assertNull(AppRestore.sharedPlaceOf(plain))
        assertNull(AppRestore.sharedImportOf(plain))
        assertNull(AppRestore.sharedPlaceOf("not json"))
        // The plan's day asked for: a day, or the Ideas (Add to plan's "Ideas").
        val book = TripBook(MemoryTripFile())
        assertEquals(PLAN_IDEAS, AppRestore.decode(text, book)!!.tripDayKey)
        assertNull(AppRestore.decode(text, book)!!.tripDay)
        assertEquals(localDate(2027, 6, 18), AppRestore.decode(plain, book)!!.tripDay)
    }

    @Test fun aSharedDocumentIsReadByAndroidsRules() {
        assertEquals("Lisbon\nSintra", ItineraryImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Lisbon\r\nSintra".encodeToByteArray()))
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Ηράκλειο".flatMap { listOf((it.code and 0xFF).toByte(), (it.code shr 8).toByte()) }
        assertEquals("Ηράκλειο", ItineraryImport.decode(le))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Ηράκλειο".flatMap { listOf((it.code shr 8).toByte(), (it.code and 0xFF).toByte()) }
        assertEquals("Ηράκλειο", ItineraryImport.decode(be))
        fun fails(bytes: ByteArray): String? = assertFailsWith<ItineraryImportException> { ItineraryImport.decode(bytes) }.message
        assertEquals("Save the document as UTF-8 plain text (.txt), then open it again.", fails(byteArrayOf(0xC3.toByte(), 0x28)))
        // A lone half of a pair in UTF-16 isn't text either.
        assertEquals("Save the document as UTF-8 plain text (.txt), then open it again.", fails(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xD8.toByte())))
        assertEquals("This document has no text to import.", fails(" \n\t ".encodeToByteArray()))
        assertEquals("Choose a plain text (.txt) file. Export PDFs and Word documents to text first.", fails("%PDF-1.7\nobj".encodeToByteArray()))
        assertEquals(ItineraryImport.TOO_LARGE, fails(ByteArray(ItineraryImport.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertEquals(ItineraryImport.MAX_BYTES + 1, ShareInbox.FILE_BYTES)
    }
}

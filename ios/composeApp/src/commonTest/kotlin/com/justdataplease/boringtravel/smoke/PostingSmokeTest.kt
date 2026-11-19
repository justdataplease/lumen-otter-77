package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.community.CommunityLimits
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostDrafts
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostLink
import com.justdataplease.boringtravel.community.PostLinks
import com.justdataplease.boringtravel.community.PostPhotos
import com.justdataplease.boringtravel.community.PostRules
import com.justdataplease.boringtravel.community.PostSource
import com.justdataplease.boringtravel.community.PostSyncs
import com.justdataplease.boringtravel.community.UploadState
import com.justdataplease.boringtravel.compat.ZoneOffset
import com.justdataplease.boringtravel.compat.skeletonDate
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.Pictures
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.community.relative
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 25's posting on both phones (Kotlin/Native's regular expressions, text and dates differ from Java's in places):
 * the Drive folder's name ("[\\/\p{Cntrl}]+" cleaned out), a draft's rules (the cover, the pictures, the notes, the
 * days and km, the first month, why it can't post), a post's fingerprint (SHA-256 by compat's MessageDigest, hex as
 * Java writes it), the trip-to-post links kept under the app's files and read back, a photo made the feed's JPEG with
 * Skia, and the Community post sheet's "5 minutes ago" and dates.
 */
class PostingSmokeTest {
    private val start = localDate(2027, 3, 1)
    private val trip = Trip("t1", "Patagonia", startDate = start, endDate = start.plusDays(11), coverMediaId = "m2", createdAt = 1, updatedAt = 1)
    private val items = listOf(
        PlanItem("a", "t1", day = start, position = 1.0, title = "El Calafate", lat = -50.34, lng = -72.26, createdAt = 1, updatedAt = 1),
        PlanItem("b", "t1", day = start.plusDays(2), position = 1.0, title = "El Chaltén", lat = -49.33, lng = -72.89, createdAt = 1, updatedAt = 1),
        PlanItem("idea", "t1", title = "Bariloche", createdAt = 1, updatedAt = 1),
    )
    private val notes = listOf(
        Entry("e1", "t1", occurredAt = 10, zoneId = "UTC", title = "Glacier", plainText = "Ice that cracks.", createdAt = 1, updatedAt = 1),
        Entry("e2", "t1", occurredAt = 20, zoneId = "UTC", title = "Fitz Roy", plainText = "Clouds all day.", createdAt = 1, updatedAt = 1),
    )
    private val photos = listOf(
        Media("m1", "t1", "e1", "media/m1.jpg", takenAt = 11, createdAt = 11, updatedAt = 11),
        Media("m2", "t1", "e2", "media/m2.jpg", takenAt = 21, createdAt = 21, updatedAt = 21),
        Media("vid", "t1", null, "media/v.mp4", mime = "video/mp4", takenAt = 30, createdAt = 30, updatedAt = 30),
    )
    private val source = PostSource(trip, items, notes, photos, countries = listOf("AR"))

    @Test fun theDriveFoldersNameIsCleanedAsOnAndroid() {
        val nov11 = 1_794_398_400_000L // 2026-11-11T12:00Z
        assertEquals("Lisbon 2026-11-11", PostRules.folderName("Lisbon", nov11, ZoneOffset.UTC))
        assertEquals("a b 2026-11-11", PostRules.folderName("a/\\b", nov11, ZoneOffset.UTC))
        assertEquals("tab and bell 2026-11-11", PostRules.folderName("tab\tand\u0007bell", nov11, ZoneOffset.UTC))
        assertEquals("Ελλάδα 2026-11-11", PostRules.folderName("  Ελλάδα  ", nov11, ZoneOffset.UTC))
        assertEquals("Post 2026-11-11", PostRules.folderName("  ", nov11, ZoneOffset.UTC))
        assertEquals(80 + " 2026-11-11".length, PostRules.folderName("x".repeat(120), nov11, ZoneOffset.UTC).length)
    }

    @Test fun aDraftsRulesAsOnAndroid() {
        assertEquals(listOf("m1", "m2"), source.ownPhotos.map { it.id })
        val trip = PostDrafts.start(source, "trip")
        assertEquals("m2", trip.cover)
        assertEquals(listOf(null, "m1", "m2"), PostDrafts.coverChoices(source, trip))
        assertTrue(PostDrafts.needsDrive(source, trip))
        assertFalse(PostDrafts.needsDrive(source, PostDrafts.withCover(source, trip, null)))
        val withNotes = PostDrafts.withNotes(source, trip, true)
        assertEquals(listOf("m2", "m1"), PostDrafts.pictures(source, withNotes))
        assertEquals(12, PostDrafts.days(source))
        assertEquals("2027-03", PostDrafts.startMonth(source))
        // El Calafate to El Chaltén: about 120 km in a straight line (by car, so counted), the idea left out.
        assertTrue(PostDrafts.km(source) in 100..140)
        assertNull(PostDrafts.problem(source, trip))
        assertEquals("Give it a title.", PostDrafts.problem(source, trip.copy(title = " ")))
        assertEquals("Pick at least one photo.", PostDrafts.problem(source, PostDrafts.withKind(source, trip, PostKind.Album)))
        assertEquals("Make a clip of this trip first.", PostDrafts.problem(source, PostDrafts.withKind(source, trip, PostKind.Clip)))
        assertEquals("Demo trips can't be posted. Post a trip of your own.", PostDrafts.problem(source.copy(trip = this.trip.copy(id = "demo-t1")), trip))
        val plan = PostDrafts.plan("p1", source, withNotes) { null }
        assertEquals(listOf("m2", "m1"), plan.pictures)
        assertEquals(2, plan.trip?.notes?.size)
        assertEquals("The title is too long.", PostRules.problem(plan.copy(title = "T".repeat(CommunityLimits.TITLE + 1))))
    }

    @Test fun aFingerprintIsSha256InHexAndFollowsThePlan() {
        val plan = PostDrafts.plan("p1", source, PostDrafts.start(source, "trip")) { null }
        val print = PostSyncs.fingerprint(plan)
        assertEquals(32, print.length)
        assertTrue(print.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(print, PostSyncs.fingerprint(plan.copy(title = "Another title")))
        assertNotEquals(print, PostSyncs.fingerprint(plan.copy(km = plan.km + 1)))
        val link = PostSyncs.link("t1", Post("p1", "me", "trailnotes", PostKind.Trip, "Patagonia"), PostDrafts.start(source, "trip"), source, UploadState(), plan, at = 5)
        assertEquals(print, link.sent)
    }

    @Test fun linksAreKeptUnderTheAppsFilesAndReadBack() = runTest {
        val files = MemoryMediaFiles()
        val links = PostLinks(files)
        links.put(PostLink("t1", "p1", "Patagonia", files = mapOf("m1" to "drvFile00001")))
        links.put(PostLink("t2", "p2"))
        assertNotNull(files.read("community/post-links.json"))
        assertEquals("drvFile00001", PostLinks(files).of("t1")?.files?.get("m1"))
        links.removePost("p2")
        assertNull(PostLinks(files).of("t2"))
        assertEquals(emptyMap(), PostLinks.decode("{not json"))
        links.clear()
        assertNull(PostLinks(files).of("t1"))
    }

    @Test fun aPhotoBecomesTheFeedsJpegWithSkia() {
        val surface = Surface.makeRasterN32Premul(2400, 600)
        surface.canvas.clear(0xFF3366AA.toInt())
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        val jpeg = assertNotNull(PostPhotos.encode(png))
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        assertEquals(CommunityLimits.PHOTO_SIDE to 400, Pictures.size(jpeg))
        assertNull(PostPhotos.encode(ByteArray(16)))
    }

    @Test fun theSheetsLastUpdatedLine() {
        val now = 1_795_000_000_000L
        assertEquals("5 minutes ago", relative(now - 5 * 60_000L, now))
        assertEquals("1 minute ago", relative(now - 60_000L, now))
        assertEquals("3 hours ago", relative(now - 3 * 3_600_000L, now))
        assertEquals("Nov 12, 2026", skeletonDate("yMMMd", localDate(2026, 11, 12).toEpochDay(), "en-US"))
        assertEquals("12 Nov 2026", skeletonDate("yMMMd", localDate(2026, 11, 12).toEpochDay(), "en-GB"))
    }
}

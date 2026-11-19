package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.data.SavedClipIndex
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.data.repo.VideoTags
import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.data.settings.TripPhotoStyles
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.ios.AppRestore
import com.justdataplease.boringtravel.ios.AppStack
import com.justdataplease.boringtravel.ios.CountryZones
import com.justdataplease.boringtravel.ios.Deeplink
import com.justdataplease.boringtravel.ios.ItemNotes
import com.justdataplease.boringtravel.ios.MapCamera
import com.justdataplease.boringtravel.ios.MapFit
import com.justdataplease.boringtravel.ios.MapInsets
import com.justdataplease.boringtravel.ios.MapProjection
import com.justdataplease.boringtravel.ios.Place
import com.justdataplease.boringtravel.ios.TrackingNotes
import com.justdataplease.boringtravel.ios.copyProgress
import com.justdataplease.boringtravel.ios.videoClock
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.site.SiteImportState
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The second parity round of 19 Nov 2026, on both the desktop's JVM and the iPhone Simulator (Kotlin/Native's regular
 * expressions and number text differ from Java's in places): a video's date and ISO 6709 place tags, its limits and the
 * "40%" Android shows, the trip's photo defaults as Android writes them, a note written about a plan item, the turned
 * map's projection and fit, each country's time zones from the IANA table under ICU's names, a restored clip's picture
 * time, the player's clock, the recording card's words, Copy from a link's progress and a Write about an item kept as
 * text.
 */
class SecondParitySmokeTest {
    @Test
    fun aVideosTagsAndLimitsReadAsAndroidReadsThem() {
        assertEquals(1_794_478_530_000L, VideoTags.date("20261112T101530.000Z"))
        assertEquals(1_794_478_530_250L, VideoTags.date("20261112T101530.250Z"))
        assertEquals(1_794_478_530_000L, VideoTags.date(" 20261112T101530 "))
        assertNull(VideoTags.date("19040101T000000.000Z"))
        assertNull(VideoTags.date("20261332T101530.000Z"))
        assertEquals(37.7749 to -122.4194, VideoTags.location("+37.7749-122.4194/"))
        assertEquals(-33.8688 to 151.2093, VideoTags.location("-33.8688+151.2093+010.000/"))
        assertNull(VideoTags.location("+00.0000+000.0000/"))
        assertEquals(720, VideoLimits.shortSide(1080, 1920))
        assertNull(VideoLimits.shortSide(1280, 720))
        assertEquals(3_000_000, VideoLimits.bitrate(61_000))
        assertEquals(1_000_000, VideoLimits.bitrate(30 * 60_000L))
        assertEquals(40, VideoLimits.percent(0.4f))
        assertEquals(99, VideoLimits.percent(1f))
    }

    @Test
    fun aTripsPhotoDefaultsAreWrittenAsAndroidWritesThem() {
        val styles = TripPhotoStyles().with("t1", TripPhotoStyle(0.65f, PhotoFrame.Polaroid)).with("t2", TripPhotoStyle(0.45f, PhotoFrame.None))
        assertEquals("t1=0.65|polaroid;t2=0.45|none;*=0.45|none", styles.encode())
        assertEquals(styles, TripPhotoStyles.decode(styles.encode()))
        assertEquals(TripPhotoStyle(0.45f, PhotoFrame.None), styles.forForm(null))
        assertEquals("Medium", TripPhotoStyle(0.6f, PhotoFrame.Tape).size.label)
    }

    @Test
    fun aNoteAboutAPlanItemIsPlacedAtItAndDatedOnItsDay() {
        val day = localDate(2026, 11, 14)
        val item = PlanItem(
            "i", "t", day, 1.0, timeMinutes = 10 * 60, title = "Museo del Prado", placeName = "Madrid",
            lat = 40.4138, lng = -3.6921, createdAt = 1, updatedAt = 1,
        )
        val now = 1_795_000_000_000L // 2026-11-18, after the item's day
        val note = ItemNotes.newEntry("n", item, listOf(item), now, "UTC")
        assertEquals("Museo del Prado (Madrid)", note.placeName)
        assertEquals("t", note.tripId)
        // On its day at its time (10:00 UTC on 14 Nov 2026).
        assertEquals(1_794_650_400_000L, note.occurredAt)
        assertEquals(listOf("n"), ItemNotes.of(item, listOf(note)).map { it.id })
    }

    @Test
    fun aTurnedMapPutsPlacesWhereMapLibreDrawsThem() {
        val north = MapProjection(MapCamera(0.0, 0.0, 10.0), 400f, 800f)
        val east = MapProjection(MapCamera(0.0, 0.0, 10.0, bearing = 90.0), 400f, 800f)
        // A place east of the middle: to the right with north up, above it with east up.
        val p = north.at(0.0, 0.01)
        assertTrue(p.x > 200f && abs(p.y - 400f) < 0.5f)
        val q = east.at(0.0, 0.01)
        assertTrue(abs(q.x - 200f) < 0.5f && q.y < 400f)
        // A twist turns the map clockwise about the fingers: the place under them stays where it is.
        val under = east.at(0.005, 0.005)
        val turned = east.turned(30f, under.x, under.y)
        assertTrue(abs(turned.bearing - 60.0) < 1e-9)
        val after = MapProjection(turned, 400f, 800f).at(0.005, 0.005)
        assertTrue(abs(after.x - under.x) < 0.5f && abs(after.y - under.y) < 0.5f, "$under → $after")
        // A pan of 100 points to the right moves the content 100 points to the right, turned or not.
        val panned = MapProjection(east.panned(100f, 0f), 400f, 800f).at(0.005, 0.005)
        assertTrue(abs(panned.x - under.x - 100f) < 0.5f && abs(panned.y - under.y) < 0.5f, "$under → $panned")
        // A fit at a bearing still shows the whole box.
        val box = BBox(37.0, 25.0, 37.2, 25.4)
        val cam = MapFit.camera(box, 400f, 800f, MapInsets(), bearing = 45.0)
        val proj = MapProjection(cam, 400f, 800f)
        for ((lat, lng) in listOf(37.0 to 25.0, 37.0 to 25.4, 37.2 to 25.0, 37.2 to 25.4)) {
            val c = proj.at(lat, lng)
            assertTrue(c.x in -1f..401f && c.y in -1f..801f, "corner $lat,$lng at $c")
        }
    }

    @Test
    fun eachCountrysZonesAreICUsFromTheIanaTable() {
        val table = CountryZones.parse(
            "# comment\nGR\t+3758+02343\tEurope/Athens\nDK\t+5540+01235\tEurope/Copenhagen\nIN\t+2232+08822\tAsia/Kolkata\n" +
                "VN\t+1045+10640\tAsia/Ho_Chi_Minh\nUS\t+404251-0740023\tAmerica/New_York\tEastern (most areas)\nUS\t+340308-1181434\tAmerica/Los_Angeles\tPacific\n",
        )
        assertEquals(setOf("Europe/Athens"), table["GR"])
        assertEquals(setOf("Europe/Copenhagen"), table["DK"])
        assertEquals(setOf("Asia/Calcutta"), table["IN"])
        assertEquals(setOf("Asia/Saigon"), table["VN"])
        assertEquals(setOf("America/New_York", "America/Los_Angeles"), table["US"])
    }

    @Test
    fun smallWordsAndNumbersAreAndroids() {
        assertEquals(4_000L, SavedClipIndex.thumbAtMs(10_000))
        assertEquals("0:12", videoClock(12_400))
        assertEquals("1:05", videoClock(65_000))
        val card = TrackingNotes.recording(" Naxos ", TrackingLevel.Balanced)
        assertEquals("Recording your route · Balanced", card.title)
        assertEquals("Naxos", card.trip)
        assertEquals("Stop", card.stop)
        assertEquals(Deeplink.TRACKING, Deeplink.of("boringtravel://tracking"))
        assertEquals(0f, copyProgress(SiteImportState.Reading("a", 0)))
        assertEquals(0.55f, assertNotNull(copyProgress(SiteImportState.Copying("a", 1, 2, 0))), 1e-6f)
        assertNull(copyProgress(SiteImportState.Idle))
    }

    @Test
    fun aNoteAboutAPlanItemComesBackAfterThePhoneClosedTheApp() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Madrid")
        val stack = AppStack(listOf(Place.Trips, Place.Trip(trip.id), Place.Write(trip.id, planItemId = "item-1")))
        val text = AppRestore.encode(stack, TripTab.Plan, null, emptyMap())
        val back = assertNotNull(AppRestore.decode(text, book))
        assertEquals(Place.Write(trip.id, planItemId = "item-1"), back.stack.last())
        assertEquals(Place.Trips, back.stack.first())
    }
}

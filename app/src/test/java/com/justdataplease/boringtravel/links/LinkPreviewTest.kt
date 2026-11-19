package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.PlaceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LinkPreviewTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    @Test fun picturesAreKeptLongestFailuresShortest() {
        assertEquals(now + 30 * day, LinkPolicy.retryAt(LinkOutcome.Picture, now))
        assertEquals(now + 7 * day, LinkPolicy.retryAt(LinkOutcome.NoPicture, now))
        assertEquals(now + day, LinkPolicy.retryAt(LinkOutcome.Refused, now))
        assertEquals(now + 15 * minute, LinkPolicy.retryAt(LinkOutcome.Offline, now))
        val p = LinkPreview("https://a.example/", retryAt = now + 1)
        assertTrue(LinkPolicy.fresh(p, now))
        assertFalse(LinkPolicy.fresh(p, now + 1))
        assertFalse(LinkPolicy.fresh(null, now))
    }

    @Test fun aFailedLookKeepsWhatWasKnown() {
        val old = LinkPreview(
            "https://a.example/", imageUrl = "https://a.example/og.jpg", imageSource = LinkPreview.SOURCE_PAGE, title = "Taverna",
            faviconUrl = LinkUrls.favicon("a.example"), fetchedAt = now - 40 * day, retryAt = now - day,
        )
        val offline = LinkPreview("https://a.example/", fetchedAt = now, retryAt = now + 15 * minute, failed = true)
        val kept = LinkPolicy.merge(old, offline)
        assertEquals("https://a.example/og.jpg", kept.imageUrl)
        assertEquals("Taverna", kept.title)
        assertEquals(LinkUrls.favicon("a.example"), kept.faviconUrl)
        assertEquals(now + 15 * minute, kept.retryAt)
        assertTrue(kept.failed)
        // A look that worked replaces everything, a picture gone included.
        val fresh = LinkPreview("https://a.example/", title = "Taverna Lefteris", fetchedAt = now, retryAt = now + 7 * day)
        assertSame(fresh, LinkPolicy.merge(old, fresh))
        // Nothing known before: the failure itself is kept (so the link isn't asked again for a while).
        assertSame(offline, LinkPolicy.merge(null, offline))
    }

    @Test fun theFileRoundTripsAndKeepsTheNewest() {
        val a = LinkPreview("https://a.example/", imageUrl = "https://a.example/og.jpg", imageSource = "page", title = "A", siteName = "Site", fetchedAt = 1, retryAt = 2)
        val b = LinkPreview("https://maps.app.goo.gl/x", imageSource = "wikipedia", imageUrl = "https://thumb.wikimedia.org/x.jpg", lat = 35.0, lng = 135.7, fetchedAt = 3, retryAt = 4, failed = true)
        val text = LinkPreviewCodec.encode(listOf(a, b))
        assertFalse(text, text.contains("null"))
        assertEquals(mapOf(a.url to a, b.url to b), LinkPreviewCodec.decode(text))
        assertTrue(LinkPreviewCodec.decode(text).getValue(b.url).fromWikipedia)
        // Unknown fields from a later version are ignored; a damaged file is an empty cache.
        assertEquals("X", LinkPreviewCodec.decode("""[{"url":"https://x.example/","title":"X","future":1}]""").getValue("https://x.example/").title)
        assertTrue(LinkPreviewCodec.decode("{oops").isEmpty())
        assertTrue(LinkPreviewCodec.decode("").isEmpty())

        val many = (1..LinkPreviewCodec.MAX + 50).map { LinkPreview("https://s$it.example/", fetchedAt = it.toLong()) }
        val kept = LinkPreviewCodec.decode(LinkPreviewCodec.encode(many))
        assertEquals(LinkPreviewCodec.MAX, kept.size)
        assertTrue(kept.containsKey("https://s${LinkPreviewCodec.MAX + 50}.example/"))
        assertFalse(kept.containsKey("https://s1.example/"))
    }

    private fun item(lat: Double? = null, lng: Double? = null, place: String = "", day: LocalDate? = null, url: String? = null) =
        PlanItem("i", "t", day, title = "", placeName = place, lat = lat, lng = lng, createdAt = 1, updatedAt = 1, url = url)

    @Test fun aMapsLinkFillsOnlyAnItemWithoutAPlace() {
        val place = MapsPlace("Yasaka Shrine", 35.0036559, 135.7785534)
        val filled = LinkPlaces.fill(item(), place)
        assertEquals("Yasaka Shrine", filled.placeName)
        assertEquals(35.0036559, filled.lat!!, 0.0)
        assertEquals(135.7785534, filled.lng!!, 0.0)
        // The item's own name stays; a located item isn't moved; no pin, no change.
        assertEquals("Gion, Kyoto", LinkPlaces.fill(item(place = "Gion, Kyoto"), place).placeName)
        val located = item(34.9, 135.7, "Kiyomizu")
        assertSame(located, LinkPlaces.fill(located, place))
        assertSame(located, LinkPlaces.fill(located, null))
        val bare = item()
        assertSame(bare, LinkPlaces.fill(bare, MapsPlace("Eiffel Tower", null, null)))
        assertEquals("", LinkPlaces.fill(bare, MapsPlace(null, 51.5, -0.12)).placeName)
    }

    @Test fun aNameWithoutAPinIsLookedUpNearTheTrip() {
        fun r(name: String, lat: Double, lng: Double) = PlaceResult(name, name, lat, lng)
        val seattle = r("Starbucks, Seattle", 47.6, -122.3)
        val kyoto = r("Starbucks, Kyoto", 35.0, 135.77)
        assertEquals(seattle, LinkPlaces.choose(listOf(seattle, kyoto), null))
        assertEquals(kyoto, LinkPlaces.choose(listOf(seattle, kyoto), LatLng(34.69, 135.50)))
        assertNull(LinkPlaces.choose(listOf(seattle), LatLng(34.69, 135.50)))
        assertNull(LinkPlaces.choose(emptyList(), null))
    }

    @Test fun theTripsWhereaboutsComeFromTheDayElseTheTrip() {
        val d1 = LocalDate.of(2027, 4, 1)
        val d2 = d1.plusDays(1)
        val items = listOf(item(day = d1), item(34.69, 135.50, day = d1), item(35.0, 135.77, day = d2))
        assertEquals(LatLng(35.0, 135.77), LinkPlaces.near(items, d2))
        assertEquals(LatLng(34.69, 135.50), LinkPlaces.near(items, null))
        assertNull(LinkPlaces.near(listOf(item()), d1))
    }

    @Test fun withPlacePhotosOffNoWikipediaPictureShowsAndALookWithoutThemIsRedoneWhenOn() {
        val maps = LinkPreview(
            "https://maps.app.goo.gl/x", imageUrl = "https://upload.wikimedia.org/a/yasaka.jpg", imageSource = LinkPreview.SOURCE_WIKIPEDIA,
            title = "Yasaka Shrine", siteName = MapsLinks.SITE_NAME, lat = 35.0, lng = 135.7, retryAt = now + day,
            imageAuthor = "Jane Doe", imageLicense = "CC BY-SA 4.0", imageFile = "Yasaka_jinja.jpg",
        )
        val off = LinkPolicy.shown(maps, photosOn = false)
        assertNull(off.imageUrl)
        assertFalse(off.fromWikipedia)
        assertNull(off.credit)
        assertNull(off.imageLicense)
        assertEquals("Yasaka Shrine", off.title)
        assertSame(maps, LinkPolicy.shown(maps, photosOn = true))
        // A site's own picture is the traveller's link, not Wikipedia: it stays.
        val site = LinkPreview("https://a.example/", imageUrl = "https://a.example/og.jpg", imageSource = LinkPreview.SOURCE_PAGE)
        assertSame(site, LinkPolicy.shown(site, photosOn = false))

        // Looked at with photos off: fresh while they stay off, looked at again as soon as they are on.
        val lookedOff = maps.copy(imageUrl = null, imageSource = null, photosOff = true)
        assertTrue(LinkPolicy.fresh(lookedOff, now, photosOn = false))
        assertFalse(LinkPolicy.fresh(lookedOff, now, photosOn = true))
        assertTrue(LinkPolicy.fresh(maps, now, photosOn = true))
        // A failed look keeps the old picture but not the old "off": the back-off holds.
        val failedOn = LinkPreview(maps.url, fetchedAt = now, retryAt = now + 15 * minute, failed = true)
        val merged = LinkPolicy.merge(lookedOff, failedOn)
        assertFalse(merged.photosOff)
        assertTrue(LinkPolicy.fresh(merged, now, photosOn = true))
        // The flag is kept in the file only when set.
        assertFalse(LinkPreviewCodec.encode(listOf(maps)).contains("photosOff"))
        assertTrue(LinkPreviewCodec.decode(LinkPreviewCodec.encode(listOf(lookedOff))).getValue(maps.url).photosOff)
    }

    @Test fun aPlacesPhotoIsKeptUnderItsKeyWithTheSameBackOff() {
        val query = PlaceQuery(35.0036, 135.7785, listOf("Yasaka Shrine"))
        val photo = LinkPreview(
            query.key, imageUrl = "https://upload.wikimedia.org/a/yasaka.jpg", imageSource = "wikipedia", title = "Yasaka Shrine", retryAt = now + 30 * day,
            imageAuthor = "Jane Doe", imageLicense = "CC BY-SA 4.0", imageFile = "Yasaka_jinja.jpg",
        )
        val none = LinkPreview("geo:1.0000,2.0000", retryAt = LinkPolicy.retryAt(LinkOutcome.NoPicture, now))
        val back = LinkPreviewCodec.decode(LinkPreviewCodec.encode(listOf(photo, none)))
        assertEquals(photo, back[query.key])
        assertTrue(LinkPolicy.fresh(back[query.key], now))
        assertTrue(LinkPolicy.fresh(back["geo:1.0000,2.0000"], now + 6 * day))
        assertFalse(LinkPolicy.fresh(back["geo:1.0000,2.0000"], now + 7 * day))
    }

    @Test fun anItemKnownOnlyByItsLinkGetsThePlacesNameLater() {
        val maps = "https://maps.app.goo.gl/x"
        assertEquals(LinkPlaces.PLACEHOLDER_MAPS, LinkPlaces.placeholderTitle(maps))
        assertEquals("example.com", LinkPlaces.placeholderTitle("https://www.example.com/menu"))
        val saved = item(url = maps).copy(title = LinkPlaces.PLACEHOLDER_MAPS)
        val later = LinkPlaces.fillLater(saved, maps, MapsPlace("Yasaka Shrine, Gion", 35.0, 135.77))
        assertEquals("Yasaka Shrine", later.title)
        assertEquals("Yasaka Shrine, Gion", later.placeName)
        assertEquals(35.0, later.lat!!, 0.0)
        // A title of the traveller's own stays.
        assertEquals("Shrine at dusk", LinkPlaces.fillLater(saved.copy(title = "Shrine at dusk"), maps, MapsPlace("Yasaka Shrine", 35.0, 135.77)).title)
    }

    @Test fun aWikipediaPictureShowsOnlyWithItsCreditAndKeepsIt() {
        val photo = WikiPhoto(
            "https://upload.wikimedia.org/a/yasaka.jpg", "Yasaka Shrine", "Yasaka_jinja.jpg",
            WikiCredit("Jane Doe", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0", "Yasaka_jinja.jpg"),
        )
        val p = LinkPreview("geo:35.0036,135.7785|yasaka shrine", title = "Yasaka Shrine", retryAt = now + day).withWikiPhoto(photo)
        assertTrue(p.fromWikipedia)
        assertEquals("Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia", p.credit)
        assertEquals("https://en.wikipedia.org/wiki/File:Yasaka_jinja.jpg", p.creditUrl)
        assertFalse(p.uncredited)
        assertSame(p, LinkPolicy.shown(p, photosOn = true))
        assertTrue(LinkPolicy.fresh(p, now))
        // The credit goes into the file with the picture and comes back.
        assertEquals(p, LinkPreviewCodec.decode(LinkPreviewCodec.encode(listOf(p))).getValue(p.url))
        // A failed look keeps the old picture with its own credit.
        val failed = LinkPreview(p.url, fetchedAt = now, retryAt = now + 15 * minute, failed = true)
        val kept = LinkPolicy.merge(p, failed)
        assertEquals(p.imageUrl, kept.imageUrl)
        assertEquals(p.credit, kept.credit)

        // Kept by a version before credits: never shown, and looked at again at once.
        val old = LinkPreview(p.url, imageUrl = photo.imageUrl, imageSource = LinkPreview.SOURCE_WIKIPEDIA, title = "Yasaka Shrine", retryAt = now + 20 * day)
        assertTrue(old.uncredited)
        assertNull(old.credit)
        assertNull(LinkPolicy.shown(old, photosOn = true).imageUrl)
        assertEquals("Yasaka Shrine", LinkPolicy.shown(old, photosOn = true).title)
        assertFalse(LinkPolicy.fresh(old, now))
        // A site's own picture needs no credit line.
        val site = LinkPreview("https://a.example/", imageUrl = "https://a.example/og.jpg", imageSource = LinkPreview.SOURCE_PAGE, retryAt = now + day)
        assertNull(site.credit)
        assertFalse(site.uncredited)
        assertSame(site, LinkPolicy.shown(site, photosOn = true))
        // No photo: nothing to credit.
        assertNull(LinkPreview("geo:1,2", retryAt = now + day).withWikiPhoto(null).credit)
    }

    @Test fun everyRequestNamesTheAppAndNoneClaimsABrowser() {
        val ua = LinkFetcher.userAgent("1.0.0")
        assertEquals("BoringTravel/1.0.0 (Android; +https://justdataplease.com/apps/boringtravel/)", ua)
        val crawler = LinkFetcher.crawlerUserAgent("1.0.0")
        assertEquals("Mozilla/5.0 (compatible; BoringTravel/1.0.0; +https://justdataplease.com/apps/boringtravel/)", crawler)
        listOf(ua, crawler).forEach { agent ->
            listOf("Chrome", "Safari", "AppleWebKit", "Gecko", "Linux", "Mobile").forEach { assertFalse("$agent has $it", agent.contains(it)) }
        }
    }
}

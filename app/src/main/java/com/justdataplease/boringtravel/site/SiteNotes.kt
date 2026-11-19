package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.links.LinkFill
import com.justdataplease.boringtravel.links.MapsListPlace
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.Geo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * A post as a journal note, exactly as written: the post's title, its date (shown in the phone's time zone), its blocks
 * in order (pictures as plain full-width photos with their captions), and its tags as a last line ("#japan #kyoto").
 * Nothing is added that isn't in the post. Ids are made from addresses ([entryId], [pictureIds]), so copying the same
 * site again finds the notes it already made. Pure and unit tested.
 */
object SiteNotes {
    /** The note and its body, ready for JournalRepository.save. */
    data class Note(val entry: Entry, val doc: EntryDoc)

    /**
     * Where a note was written, as the organiser named it, with its point on the map when the search found one, and
     * then what the map says it is: its [address] line ("Fushimi Inari-taisha, Kyoto, Japan"), [kind] (a plan kind's
     * id) and [externalId] at the map's source.
     */
    data class Place(
        val name: String, val lat: Double? = null, val lng: Double? = null,
        val address: String? = null, val kind: String? = null, val externalId: String? = null,
    ) {
        val located: Boolean get() = lat != null && lng != null
    }

    /**
     * The plan item for [place] in trip [tripId] (owner, 15 Nov 2026: copying a site "should try to find the place and
     * create also trip items and associate with notes"): named as the notes there are placed, at the same point, so
     * the item shows those notes (ui/trip/ItemNotes: by name, or within 100 m). On [day], the first note's (null: an
     * idea without a day); [visited] ticks it (a note about a day gone by was written there). One per place and trip:
     * the same trip and name always give the same id ([placeItemId]).
     */
    fun placeItem(tripId: String, place: Place, day: LocalDate?, visited: Boolean, now: Long): PlanItem = PlanItem(
        id = placeItemId(tripId, place.name), tripId = tripId, day = day, kind = PlanKind.of(place.kind).id, title = place.name.trim(),
        placeName = place.address.orEmpty(), lat = place.lat, lng = place.lng, done = visited, source = ItemSource.Import.id,
        externalId = place.externalId, createdAt = now, updatedAt = now,
    )

    fun placeItemId(tripId: String, name: String): String =
        uuid("boringtravel-site-place:$tripId:" + name.trim().lowercase(Locale.ROOT))

    /**
     * The plan item for a place that came with its own pin and no note of the journal's (a Google Maps list): an idea
     * without a day, Food or Stay when its name says so, with the note written beside it in the list, its address and
     * its own Maps link. One per place and trip ([pinItemId]), so the same list copied again adds nothing twice.
     */
    fun pinItem(tripId: String, pin: MapsListPlace, now: Long): PlanItem = PlanItem(
        id = pinItemId(tripId, pin), tripId = tripId, day = null, kind = (LinkFill.obviousKind(pin.name) ?: PlanKind.Place).id,
        title = pin.name.trim(), note = pin.note.trim(), placeName = pin.address.trim(), lat = pin.lat, lng = pin.lng,
        source = ItemSource.Import.id, createdAt = now, updatedAt = now, mapsUrl = pin.mapsUrl,
    )

    /** The same trip, name and pin (to about a metre) always give the same id: two cafés of one name both get an item. */
    fun pinItemId(tripId: String, pin: MapsListPlace): String = uuid(
        "boringtravel-site-pin:$tripId:" + pin.name.trim().lowercase(Locale.ROOT) +
            "@" + String.format(Locale.ROOT, "%.5f,%.5f", pin.lat, pin.lng),
    )

    /**
     * The plan has [new]'s place already as [planned]: the same name, and the same spot (within [SAME_SPOT_M]) when
     * both have a pin. Two branches of one café a few streets apart are two places; a name without a pin is the place.
     */
    fun samePlace(planned: PlanItem, new: PlanItem): Boolean {
        if (!planned.title.trim().equals(new.title.trim(), ignoreCase = true)) return false
        val a = planned.lat?.let { lat -> planned.lng?.let { lat to it } } ?: return true
        val b = new.lat?.let { lat -> new.lng?.let { lat to it } } ?: return true
        return Geo.distanceM(a.first, a.second, b.first, b.second) <= SAME_SPOT_M
    }

    /** How near two pins of the same name are one place. */
    const val SAME_SPOT_M = 200.0

    /** The note's id: the same post address (and section of it, [SitePost.part]) always gives the same id. */
    fun entryId(post: SitePost): String =
        uuid("boringtravel-site-post:" + canonical(post.url) + (post.part?.let { "#part:$it" } ?: ""))

    /**
     * One photo id per picture of [post], in order: from the picture's address and the note's id, so the same picture
     * in two notes gets two photos; a picture shown twice in one post gets an id for each time.
     */
    fun pictureIds(post: SitePost, entryId: String): List<String> {
        val times = HashMap<String, Int>()
        return post.pictures.map { p ->
            val n = times.merge(p.url, 1, Int::plus)!!
            uuid("boringtravel-site-picture:$entryId:${p.url}" + if (n > 1) "#$n" else "")
        }
    }

    /**
     * The note for [post] in trip [tripId]. [kept] are the photo ids whose pictures were copied: the others are left out
     * of the note. An undated post is placed at [undatedAt] (epoch ms). [place] is where the organiser says it was
     * written (only the note's place: its words stay as they are).
     */
    fun note(
        post: SitePost, entryId: String, tripId: String, zone: ZoneId, undatedAt: Long, kept: Set<String>, now: Long,
        place: Place? = null,
    ): Note {
        val ids = pictureIds(post, entryId)
        var picture = 0
        val blocks = post.blocks.mapNotNull { b ->
            when (b) {
                is SiteBlock.Text -> Block.Paragraph(RichHtml.sanitize(b.html), b.list)
                is SiteBlock.Heading -> Block.Heading(b.text, b.level.coerceIn(2, 4))
                is SiteBlock.Quote -> Block.Quote(RichHtml.sanitize(b.html))
                is SiteBlock.Picture -> ids[picture++].takeIf { it in kept }?.let { Block.Photo(it, caption = b.caption) }
                SiteBlock.Divider -> Block.Divider
            }
        } + listOfNotNull(tagLine(post.tags))
        val doc = EntryDoc(blocks = blocks)
        val entry = Entry(
            id = entryId, tripId = tripId, occurredAt = post.at?.toEpochMilli() ?: undatedAt, zoneId = zone.id,
            title = post.title, body = doc.toJson(), plainText = DocRender.plainText(doc),
            placeName = place?.name.orEmpty(), lat = place?.lat, lng = place?.lng, createdAt = now, updatedAt = now,
        )
        return Note(entry, doc)
    }

    /** "#japan #kyoto", as the site shows its tags; null without tags. */
    fun tagLine(tags: List<String>): Block.Paragraph? {
        val line = tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().joinToString(" ") { "#$it" }
        return if (line.isEmpty()) null else Block.Paragraph(RichHtml.escape(line))
    }

    /**
     * One spelling per post address: https, lower-case host, no "www." difference, no fragment, no trailing slash. The
     * query stays (WordPress's `?p=123` addresses are posts).
     */
    fun canonical(url: String): String {
        val u = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val host = u.host.lowercase().removePrefix("www.")
        val path = u.encodedPath.trimEnd('/').ifEmpty { "/" }
        val query = u.encodedQuery?.let { "?$it" }.orEmpty()
        return "https://$host$path$query"
    }

    private fun uuid(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}

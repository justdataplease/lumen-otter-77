package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.art.DemoArt
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.SceneMath
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.tracking.StopSource
import com.justdataplease.boringtravel.ui.editor.PhotoDefaults
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Builds the example trips from short tables: Argentina's plan with its travel modes ([SampleArgentina]), and the
 * journal entries with their pictures (Argentina's real photos from the app's assets, [DemoPhotos]; Lisbon's drawn
 * ones, [DemoArt]) placed as Write places them, in layouts too, and the places stayed (Argentina, [SampleLisbon]).
 * The plans of Kyoto & Osaka and Lisbon & Porto come from the trip ideas (programs/ProgramTrips). Nothing is booked:
 * no prices, no weather.
 */
internal object SampleBuilder {
    /**
     * A plan item on day [day] of the trip (null = an idea). [via] = how you reach this place from the previous one;
     * [url] = the place's (or the service's) website, as a program item has it.
     */
    class Row(
        val day: Int?, val kind: String, val title: String, val note: String,
        val at: Pair<Double, Double>? = null, val via: Vehicle? = null, val optional: Boolean = false,
        val url: String? = null,
    )

    sealed interface Part
    class Text(val html: String) : Part
    class Check(val items: List<CheckItem>) : Part
    class Pic(
        val kind: SceneKind, val time: String, val at: Pair<Double, Double>? = null,
        val shape: SceneMath.Shape = SceneMath.Shape.Wide, val mirror: Boolean = false,
        val tilt: Float? = null, val width: Float? = null, val frame: PhotoFrame? = null, val caption: String = "",
    ) : Part

    /** A real photo ([DemoPhotos]) taken at [time], placed as Write places it unless it says otherwise. */
    class Snap(
        val photo: DemoPhotos.Photo, val time: String, val at: Pair<Double, Double>? = null,
        val tilt: Float? = null, val width: Float? = null, val frame: PhotoFrame? = null, val caption: String = "",
    ) : Part

    /** Real photos side by side in a layout (the usual one for their number), each in [frame]. */
    class Layout(val snaps: List<Snap>, val frame: PhotoFrame = PhotoFrame.None) : Part

    /** A journal entry on day [day], written at [time] (null: the trip's own rule, see [entries]). */
    class Day(val day: Int, val time: String?, val title: String, val place: String, val at: Pair<Double, Double>, val parts: List<Part>)

    /**
     * The trip and its plan from [start], [days] long. Items on days before [doneBefore] are ticked, except notes and
     * optional ones (what a traveller on that day would have done); the legs get the rows' travel modes.
     */
    fun plan(
        tripId: String, title: String, subtitle: String, start: LocalDate, days: Int, rows: List<Row>, t: Long, doneBefore: Int = 1,
    ): Pair<Trip, List<PlanItem>> {
        val trip = Trip(
            tripId, title, subtitle, start, start.plusDays((days - 1).toLong()),
            trackingLevel = "off", createdAt = t, updatedAt = t,
        )
        val positions = HashMap<Int?, Double>()
        val items = rows.map { r ->
            val pos = (positions[r.day] ?: 0.0) + 1.0
            positions[r.day] = pos
            LinkPlaces.sortLinks(PlanItem(
                id = newId(), tripId = tripId, day = r.day?.let { start.plusDays((it - 1).toLong()) }, position = pos,
                kind = r.kind, title = r.title, note = r.note, lat = r.at?.first, lng = r.at?.second,
                done = r.day != null && r.day < doneBefore && r.kind != "note" && !r.optional,
                createdAt = t, updatedAt = t, url = r.url,
            ))
        }
        // Flights, trains, boats and walks between the places, set the way the planner would.
        val viaById = rows.zip(items).mapNotNull { (r, item) -> r.via?.let { item.id to it } }.toMap()
        var modes: String? = null
        for (leg in RouteLegs.planned(trip, items)) {
            val via = viaById[leg.key.toId] ?: continue
            modes = TravelModes.set(modes, leg.key, via)
        }
        return trip.copy(travelModes = modes) to items
    }

    /** A set time on day [day] of a trip from [start], in [zone]. */
    fun at(start: LocalDate, zone: ZoneId, day: Int, time: String): Instant =
        start.plusDays((day - 1).toLong()).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    /** [time] on the day of [written] in [zone]; an empty time = 10 minutes before [written]. */
    fun at(written: Instant, zone: ZoneId, time: String): Instant =
        if (time.isEmpty()) written.minusSeconds(600) else written.atZone(zone).toLocalDate().atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    /**
     * The pictures of one trip: media rows written like imported photos, and where each file comes from (drawn:
     * [pictures]; a real photo in the app's assets: [photos]).
     */
    class Album(private val tripId: String, private val t: Long) {
        val media = ArrayList<Media>()
        val pictures = HashMap<String, DemoArt.Picture>()
        val photos = HashMap<String, DemoPhotos.Photo>()

        fun addPhoto(entryId: String?, photo: DemoPhotos.Photo, taken: Instant, place: Pair<Double, Double>): Media {
            val id = newId()
            photos[id] = photo
            return Media(
                id = id, tripId = tripId, entryId = entryId, localPath = DemoPhotos.localPath(photo, id), mime = "image/jpeg",
                width = photo.width, height = photo.height, takenAt = taken.toEpochMilli(), lat = place.first, lng = place.second,
                createdAt = t, updatedAt = t,
            ).also { media += it }
        }

        fun add(entryId: String?, kind: SceneKind, shape: SceneMath.Shape, mirror: Boolean, taken: Instant, place: Pair<Double, Double>): Media {
            val id = newId()
            val picture = DemoArt.Picture(kind, shape, mirror)
            val size = picture.size
            pictures[id] = picture
            return Media(
                id = id, tripId = tripId, entryId = entryId, localPath = DemoArt.localPath(picture, id), mime = "image/jpeg",
                width = size.width, height = size.height, takenAt = taken.toEpochMilli(), lat = place.first, lng = place.second,
                createdAt = t, updatedAt = t,
            ).also { media += it }
        }
    }

    /**
     * The journal entries of [days], each written at [written] with its pictures taken at [taken] (never after the
     * entry), placed as Write places photos unless a picture says otherwise. Weather is never filled in.
     */
    fun entries(
        tripId: String, zone: ZoneId, days: List<Day>, album: Album, t: Long,
        written: (Day) -> Instant, taken: (Day, Pic, Instant) -> Instant,
    ): List<Entry> {
        val defaults = PhotoDefaults.Page
        return days.map { d ->
            val id = newId()
            val at = written(d)
            fun snap(s: Snap) = album.addPhoto(id, s.photo, minOf(SampleBuilder.at(at, zone, s.time), at), s.at ?: d.at)
            val blocks = d.parts.map { part ->
                when (part) {
                    is Text -> Block.Paragraph(part.html)
                    is Check -> Block.Checklist(part.items)
                    is Pic -> {
                        val m = album.add(id, part.kind, part.shape, part.mirror, minOf(taken(d, part, at), at), part.at ?: d.at)
                        Block.Photo(
                            m.id, rotation = part.tilt ?: defaults.tiltFor(0), width = part.width ?: defaults.width,
                            frame = part.frame ?: defaults.frame, caption = part.caption,
                        )
                    }
                    is Snap -> Block.Photo(
                        snap(part).id, rotation = part.tilt ?: defaults.tiltFor(0), width = part.width ?: defaults.width,
                        frame = part.frame ?: defaults.frame, caption = part.caption,
                    )
                    is Layout -> Block.Album(PhotoAlbums.usual(part.snaps.size).id, part.snaps.map { snap(it).id }).withFrame(part.frame)
                }
            }
            val doc = EntryDoc(blocks = blocks)
            Entry(
                id = id, tripId = tripId, occurredAt = at.toEpochMilli(), zoneId = zone.id, title = d.title,
                body = doc.toJson(), plainText = DocRender.plainText(doc), placeName = d.place, lat = d.at.first, lng = d.at.second,
                weather = null, tempC = null, createdAt = t, updatedAt = t,
            )
        }
    }

    /** A place stayed, added by hand at the plan's point. No track between them. */
    fun stop(tripId: String, arrive: Instant, leave: Instant?, p: Pair<Double, Double>, name: String, country: String, t: Long) = Stop(
        id = newId(), tripId = tripId, arrivedAt = arrive.toEpochMilli(), leftAt = leave?.toEpochMilli(),
        lat = p.first, lng = p.second, name = name, countryCode = country, source = StopSource.MANUAL, createdAt = t, updatedAt = t,
    )
}

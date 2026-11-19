package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.art.DemoArt
import com.justdataplease.boringtravel.data.SampleBuilder.Check
import com.justdataplease.boringtravel.data.SampleBuilder.Day
import com.justdataplease.boringtravel.data.SampleBuilder.Layout
import com.justdataplease.boringtravel.data.SampleBuilder.Row
import com.justdataplease.boringtravel.data.SampleBuilder.Snap
import com.justdataplease.boringtravel.data.SampleBuilder.Text
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.model.Vehicle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The demo trip "Argentina & Patagonia": the owner's 31-day plan (Buenos Aires, Mendoza, Bariloche, El Chaltén,
 * El Calafate, Torres del Paine, Punta Arenas, Santiago), dated so that today is day [TODAY] in El Chaltén, with
 * journal entries for the days so far, real photos of the places (freely licensed, shipped in the app: [DemoPhotos])
 * and the places stayed.
 *
 * A planning draft: nothing is booked, so there are no booking times, prices or confirmations. Coordinates are
 * approximate points of real places, for the map and the planned route only; there is no recorded GPS track.
 * Weather is never filled in.
 */
object SampleArgentina {
    const val TRIP_ID = "demo-argentina"

    /** The first version: the same plan fixed at 1–31 March 2027, without entries (replaced once, if untouched). */
    const val LEGACY_TRIP_ID = "sample-argentina"
    val LEGACY_START: LocalDate = LocalDate.of(2027, 3, 1)

    const val DAYS = 31
    /** Today is this day of the trip: El Chaltén, the Laguna Capri walk. */
    const val TODAY = 14
    const val TITLE = "Argentina & Patagonia"
    /** Just the route: the Demo tag says it's an example. */
    const val SUBTITLE = "Buenos Aires → Patagonia → Santiago"
    /** The subtitle before the Demo tag (renamed when the demo is next re-anchored, see DemoTrips.reanchor). */
    const val OLD_SUBTITLE = "Example trip · Buenos Aires → Patagonia → Santiago"
    val ZONE: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")

    fun start(today: LocalDate): LocalDate = today.minusDays((TODAY - 1).toLong())

    data class Plan(val trip: Trip, val items: List<PlanItem>)

    /**
     * Everything the demo adds. Each media row's file is drawn ([pictures]) or copied from the app's assets ([photos]),
     * by media id.
     */
    data class Demo(
        val trip: Trip,
        val items: List<PlanItem>,
        val entries: List<Entry>,
        val media: List<Media>,
        val pictures: Map<String, DemoArt.Picture>,
        val stops: List<Stop>,
        val photos: Map<String, DemoPhotos.Photo> = emptyMap(),
    )

    // Day numbers are days of the trip. `via` = how you reach this place from the previous one on the route.
    private val rows = listOf(
        // 1 · Buenos Aires
        Row(1, "transport", "Fly to Buenos Aires", "Arrival flight to book", -34.8222 to -58.5358, url = "https://www.aeropuertosargentina.com/en/EZE"),
        Row(1, "stay", "Boutique hotel in Palermo Soho", "To book", -34.5886 to -58.4302, Vehicle.Car),
        Row(1, "food", "Parrilla dinner", "Easy first night: steak with Malbec"),
        Row(2, "food", "Slow morning at a historic café", ""),
        Row(2, "place", "Recoleta Cemetery", "And the neighbourhood around it", -34.5875 to -58.3934, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/recoleta-cemetery"),
        Row(2, "place", "Rosedal, Palermo", "The rose garden", -34.5718 to -58.4176, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/palermo-rose-garden"),
        Row(3, "place", "San Telmo", "Antiques and colonial streets", -34.6205 to -58.3714, url = "https://turismo.buenosaires.gob.ar/en/atractivo/san-telmo"),
        Row(3, "place", "La Boca", "Colourful streets, go in daytime", -34.6390 to -58.3626, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/caminito"),
        Row(3, "activity", "Tango show", "Evening · reservation to book"),
        // 2 · Mendoza wine country
        Row(4, "transport", "Fly to Mendoza", "Flight to book", -32.8317 to -68.7929, Vehicle.Plane, url = "https://www.aeropuertosargentina.com/en/MDZ"),
        Row(4, "stay", "Wine lodge in Valle de Uco", "≈ 1 h 30 transfer · vines and the Andes outside · to book", -33.62 to -69.20, Vehicle.Car),
        Row(5, "activity", "Winery visit and pairing lunch", "One winery, a long multi-course lunch, then the pool"),
        Row(6, "activity", "Vendimia: Carrusel parade", "Morning, in Mendoza city", -32.8894 to -68.8445, Vehicle.Car, url = "https://www.mendoza.gob.ar/vendimia/"),
        Row(6, "activity", "Vendimia: Acto Central", "Night show at the Teatro Griego · optional, tickets needed", -32.886 to -68.882, optional = true, url = "https://www.mendoza.gob.ar/vendimia/"),
        Row(7, "note", "Spa morning", "Optional: a drive toward the Aconcagua viewpoints"),
        // 3 · Bariloche and the Lake District
        Row(8, "transport", "Fly to Bariloche", "Direct flight to book", -41.1512 to -71.1578, Vehicle.Plane, url = "https://www.aeropuertosargentina.com/en/BRC"),
        Row(8, "stay", "Hotel by Lake Nahuel Huapi", "For example the Llao Llao · to book", -41.053 to -71.535, Vehicle.Car),
        Row(9, "activity", "Circuito Chico", "The scenic loop by the lakes", -41.056 to -71.513, url = "https://barilocheturismo.gob.ar/en/short-circuit"),
        Row(9, "place", "Cerro Campanario chairlift", "One of the best views over the lakes", -41.050 to -71.489, url = "https://cerrocampanario.com.ar/la-aerosilla/"),
        Row(10, "food", "Chocolate on Calle Mitre", "Then a lakeside afternoon", -41.1336 to -71.3057, Vehicle.Car),
        Row(11, "activity", "Isla Victoria and the Arrayanes Forest", "By boat from Puerto Pañuelo", -40.8456 to -71.6369, Vehicle.Ferry, url = "https://barilocheturismo.gob.ar/en/victoria-island-and-myrtle-woods"),
        Row(12, "note", "Rest day", "Pool, spa or the short Cerro Llao Llao trail (≈ 1 h)"),
        // 4 · El Chaltén and Mount Fitz Roy
        Row(13, "transport", "Fly to El Calafate", "Morning flight to book", -50.2803 to -72.0531, Vehicle.Plane, url = "https://www.aeropuertoelcalafate.com/en/"),
        Row(13, "transport", "Transfer to El Chaltén", "≈ 3 h · stay to book", -49.3315 to -72.8863, Vehicle.Car),
        Row(13, "place", "Mirador de los Cóndores", "Sunset walk · ≈ 1 h", -49.3373 to -72.8778, Vehicle.Walk, url = "https://elchalten.tur.ar/mirador-de-los-condores-y-aguilas/"),
        Row(14, "activity", "Laguna Capri and Mirador Fitz Roy", "≈ 8–10 km return, 3–5 h, steep first kilometre", -49.296 to -72.927, Vehicle.Walk, url = "https://elchalten.tur.ar/laguna-capri/"),
        Row(15, "place", "Chorrillo del Salto", "Rest day. Cafés, a craft brewery, or this flat waterfall walk (≈ 1–2 h)", -49.287 to -72.881, url = "https://elchalten.tur.ar/el-chorrillo-del-salto/"),
        Row(16, "activity", "Laguna de los Tres", "Optional, on the clearest day: ≈ 21–26 km, 8–10 h, very steep last hour. Easier: Laguna Torre (≈ 18 km)", -49.272 to -72.999, Vehicle.Walk, url = "https://elchalten.tur.ar/laguna-de-los-tres/"),
        // 5 · El Calafate and the Perito Moreno Glacier
        Row(17, "transport", "Back to El Calafate", "Quiet evening by Lago Argentino · stay to book", -50.3379 to -72.2648, Vehicle.Car),
        Row(18, "activity", "Perito Moreno Glacier", "The boardwalks facing the ice, plus the 1 h boat to the face or a mini-trek on the ice", -50.471 to -73.031, Vehicle.Car, url = "https://www.elcalafate.tur.ar/in-perito-moreno-glacier.htm"),
        // 6 · Torres del Paine and the W Trek
        Row(19, "transport", "Bus to Puerto Natales", "≈ 5–6 h with the border", -51.7300 to -72.5065, Vehicle.Car),
        Row(20, "transport", "Bus to Pudeto", "Then the catamaran across Lake Pehoé", -51.079 to -72.989, Vehicle.Car),
        Row(20, "transport", "Catamaran to Paine Grande", "", -51.0716 to -73.1155, Vehicle.Ferry),
        Row(20, "activity", "W day 1: Refugio Grey", "≈ 11 km", -50.997 to -73.209, Vehicle.Walk, url = "https://www.vertice.travel/alojamiento/refuge-camping-grey/"),
        Row(21, "activity", "W day 2: Glacier Grey, back to Paine Grande", "Viewpoints first · ≈ 11 km", -51.0716 to -73.1155, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        Row(22, "activity", "W day 3: French Valley", "On to Refugio Francés or Cuernos · ≈ 13–18 km", -51.043 to -73.040, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        Row(23, "activity", "W day 4: Refugio Chileno", "Along Lake Nordenskjöld · ≈ 15 km", -50.984 to -72.872, Vehicle.Walk, url = "https://lastorres.com/en/camping/camping-chileno/"),
        Row(24, "activity", "W day 5: Base of the Towers", "Sunrise · ≈ 2 h climb, then down", -50.943 to -72.967, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        Row(24, "transport", "Afternoon bus to Natales", "", -51.7300 to -72.5065, Vehicle.Car),
        Row(24, "stay", "Recovery days in Puerto Natales", "Sleep in, seafood on the waterfront, a massage, the shoreline walk · to book"),
        Row(25, "note", "Recovery day", "Seafood on the waterfront"),
        Row(26, "place", "Milodón Cave", "Optional", -51.5683 to -72.6167, Vehicle.Car, url = "https://www.conaf.cl/parque_nacionales/monumento-natural-cueva-del-milodon/"),
        // 7 · Punta Arenas
        Row(27, "transport", "Bus to Punta Arenas", "≈ 3 h · one night on the Strait of Magellan, to book", -53.1626 to -70.9081, Vehicle.Car),
        // 8 · Santiago
        Row(28, "transport", "Fly to Santiago", "Flight to book", -33.3930 to -70.7858, Vehicle.Plane, url = "https://www.aeropuertosantiagodechile.cl/en"),
        Row(28, "stay", "Hotel in Lastarria or Providencia", "Easy dinner · to book", -33.4372 to -70.6403, Vehicle.Car),
        Row(29, "place", "Cerro San Cristóbal", "Cable car up for the city and the Andes, then the Lastarria cafés", -33.4253 to -70.6331, url = "https://telefericosantiago.cl/?lang=en"),
        Row(30, "activity", "Day trip to Valparaíso", "Or a Maipo or Casablanca winery lunch", -33.0472 to -71.6127, Vehicle.Car, url = "https://chile.travel/en/destinations/valparaiso/"),
        Row(31, "transport", "Fly home", "Flight to book", -33.3930 to -70.7858, Vehicle.Car, url = "https://www.aeropuertosantiagodechile.cl/en"),
        // Not on a day yet.
        Row(null, "note", "Still to book", "Flights, stays, the tango night, Vendimia tickets, the W Trek refugios, meals and buses"),
        Row(null, "place", "Aconcagua viewpoints", "Optional drive from Mendoza", url = "https://sitios.mendoza.gob.ar/energiayambiente/aconcagua/"),
        Row(null, "place", "Laguna Torre", "The easier Fitz Roy-area hike (≈ 18 km)", url = "https://elchalten.tur.ar/laguna-torre/"),
    ) + listOfNotNull(creditsRow())

    /** The plan note that credits the demo photos whose licences ask for it ([DemoPhotos.CREDITS]). */
    const val CREDITS_TITLE = "Photo credits"

    private fun creditsRow(): Row? = DemoPhotos.CREDITS.takeIf { it.isNotEmpty() }?.let {
        Row(null, "note", CREDITS_TITLE, "Demo photos from Wikimedia Commons, shared under Creative Commons: " + it.joinToString(" · "))
    }

    /**
     * The plan starting on [start]. Items on days before [doneBefore] are ticked, except notes and optional ones
     * (what a traveller on that day would have done).
     */
    fun plan(start: LocalDate, t: Long, tripId: String = TRIP_ID, doneBefore: Int = 1): Plan {
        val (trip, items) = SampleBuilder.plan(tripId, TITLE, SUBTITLE, start, DAYS, rows, t, doneBefore)
        return Plan(trip, items)
    }

    /**
     * The website of the plan's own item that [item] still is (the same day of a trip starting [start], kind and title),
     * or null. Sample version 7 adds them to demos seeded before the plan had links (SampleTrips.addLinks).
     */
    fun link(item: PlanItem, start: LocalDate?): String? {
        val day = item.day?.let { d -> (d.toEpochDay() - (start ?: return null).toEpochDay() + 1).toInt() }
        return rows.firstOrNull { it.day == day && it.kind == item.kind && it.title == item.title }?.url
    }

    // ---- The journal so far ----

    // A Day with time null is the entry written today, at [todayEntryTime]. Short notes, as people write them on the go.
    private val palermo = -34.5886 to -58.4302
    private val chalten = -49.3315 to -72.8863

    private val days = listOf(
        Day(
            1, "23:20", "First night", "Palermo Soho, Buenos Aires", palermo, listOf(
                Text("Landed at 7. Taxi in along 9 de Julio, Obelisco all lit up."),
                Snap(DemoPhotos.OBELISCO, "21:40", -34.6037 to -58.3816),
                Text("Steak and Malbec round the corner at ten. We were still the first ones in."),
            ),
        ),
        Day(
            2, "19:10", "Recoleta", "Recoleta, Buenos Aires", -34.5875 to -58.3934, listOf(
                Text("<b>Medialunas and café con leche</b>, then a second coffee. Nobody here is in a hurry."),
                Layout(listOf(Snap(DemoPhotos.CAFE, "09:50"), Snap(DemoPhotos.RECOLETA, "11:30"), Snap(DemoPhotos.ROSEDAL, "16:20", -34.5718 to -58.4176))),
                Text("Recoleta Cemetery: marble streets, cats asleep on the steps. Roses in Palermo on the way back. Feet done by five."),
            ),
        ),
        Day(
            3, "23:45", "San Telmo, then tango", "San Telmo, Buenos Aires", -34.6205 to -58.3714, listOf(
                Text("Antiques in San Telmo all morning. Nearly bought an old soda siphon."),
                Layout(listOf(Snap(DemoPhotos.SAN_TELMO, "11:10"), Snap(DemoPhotos.CAMINITO, "15:20", -34.6390 to -58.3626)), PhotoFrame.Tape),
                Text("La Boca after lunch. The houses really are those colours."),
                Text("Tango show tonight. <i>They make it look easy.</i>"),
            ),
        ),
        Day(
            5, "18:00", "Lunch in Valle de Uco", "Valle de Uco, Mendoza", -33.62 to -69.20, listOf(
                Text("One winery, one very long lunch. Seven courses, seven glasses, the Andes in the window."),
                Snap(DemoPhotos.UCO, "13:40"),
                Text("Pool after. Nobody said much."),
            ),
        ),
        Day(
            6, "14:10", "Vendimia", "Mendoza", -32.8894 to -68.8445, listOf(
                Text("Carrusel parade this morning: floats, gauchos, grapes thrown into the crowd. Caught a bunch."),
                Snap(DemoPhotos.GRAPES, "11:15"),
                Text("Skipped the night show."),
            ),
        ),
        Day(
            8, "20:30", "Bariloche", "Lake Nahuel Huapi, Bariloche", -41.053 to -71.535, listOf(
                Text("Flew south. Cold and clear, and the lake is huge."),
                Snap(DemoPhotos.LLAO_LLAO, "18:40"),
            ),
        ),
        Day(
            9, "18:30", "Circuito Chico", "Cerro Campanario, Bariloche", -41.050 to -71.489, listOf(
                Text("Drove the Circuito Chico with too many stops."),
                Text("<b>Chairlift up Campanario.</b> Lakes and islands everywhere, and a lot of wind."),
                Snap(DemoPhotos.CAMPANARIO, "16:10"),
            ),
        ),
        Day(
            10, "17:20", "Chocolate on Mitre", "Calle Mitre, Bariloche", -41.1336 to -71.3057, listOf(
                Text("Mitre is one chocolate shop after another. Tried three, bought from the fourth."),
                Snap(DemoPhotos.CENTRO, "15:30"),
                Check(listOf(CheckItem("Dark chocolate for home", true), CheckItem("Something for work", true), CheckItem("Leave some for the bus"))),
            ),
        ),
        Day(
            11, "19:00", "Arrayanes by boat", "Bosque de Arrayanes", -40.8456 to -71.6369, listOf(
                Text("Boat from Puerto Pañuelo to Isla Victoria, then the Arrayanes forest."),
                Layout(listOf(Snap(DemoPhotos.BOAT, "10:40", -41.054 to -71.527), Snap(DemoPhotos.ARRAYANES, "13:20")), PhotoFrame.Polaroid),
                Text("The trees are cinnamon coloured and cold to touch. Windy on the way back, sat inside."),
            ),
        ),
        Day(
            13, "21:50", "To El Chaltén", "Mirador de los Cóndores, El Chaltén", -49.3373 to -72.8778, listOf(
                Text("Flight to Calafate, then three hours of bus with Fitz Roy getting bigger in the window."),
                Snap(DemoPhotos.CHALTEN, "15:40", chalten),
                Text("Up to the Mirador de los Cóndores for sunset. Orange for five minutes, then grey."),
                Snap(DemoPhotos.SUNSET, "20:30"),
                Text("For tomorrow:"),
                Check(listOf(CheckItem("Water", true), CheckItem("Snacks", true), CheckItem("Rain jacket in the bag"), CheckItem("Alarm"))),
            ),
        ),
        Day(
            TODAY, null, "Laguna Capri", "Laguna Capri, El Chaltén", -49.296 to -72.927, listOf(
                Text("First km is steep, then it's trees and easy."),
                Snap(DemoPhotos.CAPRI, "", tilt = -4f, width = .8f, frame = PhotoFrame.Polaroid, caption = "Laguna Capri"),
                Text("Wind dropped and Fitz Roy is in the water too. Sitting on a rock deciding about the mirador."),
            ),
        ),
    )

    /**
     * The first journal (drawn pictures, sample versions 2 to 7): each entry's title and its text as stored
     * ([Entry.plainText]). [newJournal] replaces it with this one while its words are still these: the daily move of
     * the demo restamps rows, so their stamps can't tell.
     */
    val FIRST_TEXTS: Map<String, String> = mapOf(
        "First night in Palermo" to "Long flight. The taxi came in along 9 de Julio, past the Obelisco all lit up.\n\n" +
            "Bags down, then round the corner for steak and a glass of Malbec. We sat down at ten and were still the first ones in.",
        "Recoleta" to "Slow start at an old café: medialunas and café con leche, then a second coffee because nobody was in a hurry.\n\n" +
            "Recoleta Cemetery after. Marble lanes like a small city, cats asleep on the steps.\n\nBack through the rose garden in Palermo. Feet done by five.",
        "San Telmo, then tango" to "Antique shops in San Telmo all morning. Nearly bought an old soda siphon.\n\n" +
            "La Boca after lunch. The houses really are those colours. Stayed on the main streets and left before dark.\n\nTango show tonight. They make it look easy.",
        "Lunch in Valle de Uco" to "One winery and one very long lunch. Course after course, each with its own glass, and the Andes in the window the whole time.\n\n" +
            "Pool after. Nobody said much.",
        "Vendimia parade" to "The Carrusel this morning: floats, gauchos on horseback, and fruit thrown into the crowd. Caught a bunch of grapes.\n\n" +
            "Skipped the night show. Early night instead.",
        "Circuito Chico" to "Drove the Circuito Chico with too many stops. Every bend is another lake.\n\n" +
            "Took the chairlift up Cerro Campanario. Lakes and islands in every direction, and a lot of wind.",
        "Chocolate on Mitre" to "Calle Mitre is one chocolate shop after another. Tried three, bought from the fourth.\n\n" +
            "[x] Dark chocolate for home\n[x] Something for work\n[ ] Leave some for the bus\n\nAfternoon by the lake doing nothing.",
        "Arrayanes by boat" to "Boat from Puerto Pañuelo to Isla Victoria, then on to the Arrayanes forest.\n\n" +
            "The trees are cinnamon-coloured and cool to the touch. A short boardwalk loop, quiet even with a boatload of us.\n\nWindy on the way back. Sat inside.",
        "To El Chaltén" to "Morning flight to El Calafate, then three hours on the bus with Fitz Roy getting bigger in the window.\n\n" +
            "Walked up to the Mirador de los Cóndores for sunset. The peaks went orange for a few minutes, then grey.\n\nFor tomorrow:\n\n" +
            "[x] Water\n[x] Snacks\n[ ] Rain jacket in the bag\n[ ] Alarm",
        "Laguna Capri" to "The first kilometre is steep, then the trail goes into the trees and eases off.\n\nLaguna Capri\n\n" +
            "When the wind drops, Fitz Roy is in the water too.\n\nSitting on a rock deciding whether to go on to the mirador.",
    )

    /** Nothing in the demo is later than this: 20 minutes before [now]. */
    fun latest(now: Instant): Instant = now.minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.MINUTES)

    /** The trip's cover photo: Fitz Roy. */
    val COVER: DemoPhotos.Photo get() = DemoPhotos.FITZ_ROY

    /** The journal ([media]: its photos) and the cover of a trip from [start], stamped [t]; [photos] covers both. */
    private class Journal(val entries: List<Entry>, val media: List<Media>, val photos: Map<String, DemoPhotos.Photo>, val cover: Media)

    private fun journal(start: LocalDate, now: Instant, t: Long): Journal {
        val album = SampleBuilder.Album(TRIP_ID, t)
        // A set time on a day of the trip, but never after [latest] (a phone far west of Argentina).
        fun at(day: Int, time: String) = minOf(SampleBuilder.at(start, ZONE, day, time), latest(now))
        val entries = SampleBuilder.entries(
            TRIP_ID, ZONE, days, album, t,
            written = { d -> d.time?.let { at(d.day, it) } ?: todayEntryTime(start.plusDays((d.day - 1).toLong()), now) },
            taken = { _, _, written -> written },
        )
        // The cover: Fitz Roy from the village, on arrival.
        val photos = album.media.toList()
        val cover = album.addPhoto(null, COVER, at(13, "17:10"), chalten)
        return Journal(entries, photos, album.photos, cover)
    }

    /** What [newJournal] changes: the trip (its cover), the old entries gone, the old pictures gone and the new rows. */
    data class Swap(
        val trip: Trip,
        val entries: List<Entry>,
        val gone: List<Media>,
        val added: List<Media>,
        val photos: Map<String, DemoPhotos.Photo>,
        /** The [CREDITS_TITLE] note, when the plan has none yet. */
        val items: List<PlanItem> = emptyList(),
    )

    /**
     * Sample version 8: the first journal (drawn pictures) becomes this one, with real photos. [entries] and [media] are
     * the demo trip's live rows. Only while nobody changed it: every entry with a title of the first journal still has
     * its words ([FIRST_TEXTS]), there is at least one, and they hold a drawn picture. Then those entries and their
     * pictures are soft-deleted at [t] and the new journal added on the trip's own dates; notes of the traveller's own
     * stay. A drawn cover (a "media/demo-" file) becomes the Fitz Roy
     * photo, whatever happened to the journal; then the plan ([items]: its live items) gets the [CREDITS_TITLE] note when
     * it has none. The new rows carry the trip's stamp when the trip is untouched, so it still reads as untouched; else
     * [t]. Null when there is nothing to do.
     */
    fun newJournal(trip: Trip, entries: List<Entry>, media: List<Media>, now: Instant, t: Long, items: List<PlanItem> = emptyList()): Swap? {
        if (trip.id != TRIP_ID || trip.deletedAt != null) return null
        val start = trip.startDate ?: return null
        val live = entries.filter { it.deletedAt == null && it.tripId == TRIP_ID && it.title in FIRST_TEXTS }
        val liveIds = live.mapTo(HashSet()) { it.id }
        val replace = live.isNotEmpty() && live.all { FIRST_TEXTS[it.title] == it.plainText } &&
            media.any { it.deletedAt == null && it.entryId in liveIds && it.localPath.startsWith("media/demo-") }
        val oldCover = media.firstOrNull { it.id == trip.coverMediaId && it.deletedAt == null }?.takeIf { it.localPath.startsWith("media/demo-") }
        if (!replace && oldCover == null) return null
        val untouched = trip.updatedAt == trip.createdAt
        val stamp = if (untouched) trip.createdAt else t
        val built = journal(start, now, stamp)
        val goneIds = if (replace) live.mapTo(HashSet()) { it.id } else emptySet()
        val gone = media.filter { it.deletedAt == null && (it.entryId in goneIds || it.id == oldCover?.id) }.map { it.copy(deletedAt = t, updatedAt = t) }
        val added = (if (replace) built.media else emptyList()) + listOfNotNull(if (oldCover != null) built.cover else null)
        val keep = added.mapTo(HashSet()) { it.id }
        val credits = creditsRow()?.takeIf { items.none { it.deletedAt == null && it.title == CREDITS_TITLE } }?.let { row ->
            val (_, planned) = SampleBuilder.plan(TRIP_ID, TITLE, SUBTITLE, start, DAYS, listOf(row), stamp)
            val last = items.filter { it.day == null && it.deletedAt == null }.maxOfOrNull { it.position } ?: 0.0
            planned.map { it.copy(position = last + 1.0) }
        }.orEmpty()
        return Swap(
            trip = if (oldCover == null) trip else trip.copy(coverMediaId = built.cover.id, updatedAt = if (untouched) trip.updatedAt else t),
            entries = if (replace) live.map { it.copy(deletedAt = t, updatedAt = t) } + built.entries else emptyList(),
            gone = gone,
            added = added,
            photos = built.photos.filterKeys { it in keep },
            items = credits,
        )
    }

    /** Today's entry: 20 minutes ago, or 12:40 on the trail when that was earlier. Never in the future. */
    fun todayEntryTime(day: LocalDate, now: Instant): Instant = minOf(latest(now), day.atTime(12, 40).atZone(ZONE).toInstant())

    /** The whole demo for an install whose date is [today]; [now] places today's entry, [t] stamps every row. */
    fun demo(today: LocalDate, now: Instant, t: Long): Demo {
        val start = start(today)
        val plan = plan(start, t, TRIP_ID, doneBefore = TODAY)
        val journal = journal(start, now, t)
        fun at(day: Int, time: String) = minOf(SampleBuilder.at(start, ZONE, day, time), latest(now))
        fun stop(arrive: Instant, leave: Instant?, p: Pair<Double, Double>, name: String) = SampleBuilder.stop(TRIP_ID, arrive, leave, p, name, "AR", t)
        // The places stayed, at the plan's points. No track between them.
        val stops = listOf(
            stop(at(1, "19:30"), at(4, "08:00"), palermo, "Palermo Soho, Buenos Aires"),
            stop(at(4, "14:30"), at(8, "08:00"), -33.62 to -69.20, "Valle de Uco, Mendoza"),
            stop(at(8, "15:00"), at(13, "05:30"), -41.053 to -71.535, "Lake Nahuel Huapi, Bariloche"),
            stop(at(13, "15:30"), null, chalten, "El Chaltén"),
        )
        return Demo(
            plan.trip.copy(coverMediaId = journal.cover.id), plan.items, journal.entries, journal.media + journal.cover,
            emptyMap(), stops, journal.photos,
        )
    }
}

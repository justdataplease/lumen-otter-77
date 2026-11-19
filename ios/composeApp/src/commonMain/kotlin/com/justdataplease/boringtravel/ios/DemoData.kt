package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.SceneMath
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.model.Vehicle

/*
 * The example trips' own words and pictures, as the Android app has them (data/SampleArgentina.kt, SampleLisbon.kt,
 * DemoPhotos.kt): Argentina's plan and journal, Lisbon's journal, and the demo's photos. Copied here word for word
 * because those files build their rows with java.time and Android's drawing; the plans of Kyoto & Osaka and Lisbon &
 * Porto are the engine's own trip ideas (ProgramTrips), as on Android. Keep them in step with the Android files.
 */

/**
 * The Argentina demo's real photos, freely licensed (CC0, public domain or CC BY / BY-SA with their [Photo.credit]),
 * read from this app's files ("files/demo/argentina/<name>.jpg", the Android app's assets, copied in by the build).
 */
object DemoPhotos {
    const val FOLDER = "demo/argentina"

    data class Photo(val name: String, val width: Int, val height: Int, val credit: String? = null) {
        /** The file in this app's resources. */
        val resource: String get() = "files/$FOLDER/$name.jpg"
    }

    val OBELISCO = Photo("obelisco-night", 853, 1280, credit = "Dennis G. Jarvis, CC BY-SA 2.0")
    val CAFE = Photo("cafe-medialunas", 1280, 964)
    val RECOLETA = Photo("recoleta-cemetery", 1280, 853)
    val ROSEDAL = Photo("rosedal", 1280, 960)
    val SAN_TELMO = Photo("san-telmo", 1280, 853)
    val CAMINITO = Photo("caminito", 1280, 960)
    val UCO = Photo("uco-vineyard", 1280, 794, credit = "David, CC BY 2.0")
    val GRAPES = Photo("mendoza-grapes", 1280, 960, credit = "Micah MacAllen, CC BY-SA 2.0")
    val LLAO_LLAO = Photo("llao-llao", 1280, 960, credit = "Alexrebolledo, CC BY-SA 3.0")
    val CAMPANARIO = Photo("campanario-view", 1280, 800, credit = "Hiroki Ogawa, CC BY 3.0")
    val CENTRO = Photo("bariloche-centro", 1280, 960, credit = "Phil Whitehouse, CC BY 2.0")
    val BOAT = Photo("puerto-panuelo-boat", 1280, 914, credit = "Wolfgang Fricke, CC BY 3.0")
    val ARRAYANES = Photo("arrayanes", 960, 1280)
    val CHALTEN = Photo("chalten-village", 1280, 960, credit = "Gagea, CC BY-SA 3.0")
    val SUNSET = Photo("condores-sunset", 1280, 746, credit = "Masa Sakano, CC BY-SA 2.0")
    val CAPRI = Photo("laguna-capri", 960, 1280, credit = "Gagea, CC BY-SA 3.0")
    val FITZ_ROY = Photo("fitz-roy-cover", 1280, 960)

    val ALL: List<Photo> = listOf(OBELISCO, CAFE, RECOLETA, ROSEDAL, SAN_TELMO, CAMINITO, UCO, GRAPES, LLAO_LLAO, CAMPANARIO, CENTRO, BOAT, ARRAYANES, CHALTEN, SUNSET, CAPRI, FITZ_ROY)

    /** The credits the licences ask for, one line each ("Author, CC BY 2.0"). */
    val CREDITS: List<String> = ALL.mapNotNull { it.credit }.distinct()

    /** Where a demo photo is kept once seeded: media/argentina-<name>-<id>.jpg (a real photo, never drawn). */
    fun localPath(photo: Photo, id: String): String = "media/argentina-${photo.name}-$id.jpg"
}

/** How the examples are written down: plan rows and journal days (the Android app's SampleBuilder). */
object SampleRows {
    /**
     * A plan item on day [day] of the trip (null = an idea). [via] = how you reach this place from the previous one;
     * [url] = the place's (or the service's) website.
     */
    class Row(
        val day: Int?, val kind: String, val title: String, val note: String,
        val at: Pair<Double, Double>? = null, val via: Vehicle? = null, val optional: Boolean = false,
        val url: String? = null,
    )

    sealed interface Part
    class Text(val html: String) : Part
    class Check(val items: List<CheckItem>) : Part

    /** A drawn picture taken at [time] ("HH:mm"), placed as Write places a photo unless it says otherwise. */
    class Pic(
        val kind: SceneKind, val time: String, val at: Pair<Double, Double>? = null,
        val shape: SceneMath.Shape = SceneMath.Shape.Wide, val mirror: Boolean = false,
    ) : Part

    /** A real photo ([DemoPhotos]) taken at [time] (empty: 10 minutes before the entry), placed as Write places it. */
    class Snap(
        val photo: DemoPhotos.Photo, val time: String, val at: Pair<Double, Double>? = null,
        val tilt: Float? = null, val width: Float? = null, val frame: PhotoFrame? = null, val caption: String = "",
    ) : Part

    /** Real photos side by side in a layout (the usual one for their number), each in [frame]. */
    class Layout(val snaps: List<Snap>, val frame: PhotoFrame = PhotoFrame.None) : Part

    /** A journal entry on day [day], written at [time] ("HH:mm"; null: the entry written today). */
    class Day(val day: Int, val time: String?, val title: String, val place: String, val at: Pair<Double, Double>, val parts: List<Part>)
}

/** The demo trip "Argentina & Patagonia" (the Android app's data/SampleArgentina.kt), happening now: today is day [TODAY]. */
object SampleArgentina {
    const val TRIP_ID = "demo-argentina"
    const val DAYS = 31

    /** Today is this day of the trip: El Chaltén, the Laguna Capri walk. */
    const val TODAY = 14
    const val TITLE = "Argentina & Patagonia"
    const val SUBTITLE = "Buenos Aires → Patagonia → Santiago"

    /** The subtitle before the Demo tag (renamed when the demo is next moved, see DemoTrips.reanchor). */
    const val OLD_SUBTITLE = "Example trip · Buenos Aires → Patagonia → Santiago"
    const val ZONE = "America/Argentina/Buenos_Aires"

    /** The plan note that credits the demo photos whose licences ask for it. */
    const val CREDITS_TITLE = "Photo credits"

    private fun row(day: Int?, kind: String, title: String, note: String, at: Pair<Double, Double>? = null, via: Vehicle? = null, optional: Boolean = false, url: String? = null) =
        SampleRows.Row(day, kind, title, note, at, via, optional, url)

    // Day numbers are days of the trip. `via` = how you reach this place from the previous one on the route.
    val rows: List<SampleRows.Row> = listOf(
        // 1 · Buenos Aires
        row(1, "transport", "Fly to Buenos Aires", "Arrival flight to book", -34.8222 to -58.5358, url = "https://www.aeropuertosargentina.com/en/EZE"),
        row(1, "stay", "Boutique hotel in Palermo Soho", "To book", -34.5886 to -58.4302, Vehicle.Car),
        row(1, "food", "Parrilla dinner", "Easy first night: steak with Malbec"),
        row(2, "food", "Slow morning at a historic café", ""),
        row(2, "place", "Recoleta Cemetery", "And the neighbourhood around it", -34.5875 to -58.3934, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/recoleta-cemetery"),
        row(2, "place", "Rosedal, Palermo", "The rose garden", -34.5718 to -58.4176, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/palermo-rose-garden"),
        row(3, "place", "San Telmo", "Antiques and colonial streets", -34.6205 to -58.3714, url = "https://turismo.buenosaires.gob.ar/en/atractivo/san-telmo"),
        row(3, "place", "La Boca", "Colourful streets, go in daytime", -34.6390 to -58.3626, url = "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/caminito"),
        row(3, "activity", "Tango show", "Evening · reservation to book"),
        // 2 · Mendoza wine country
        row(4, "transport", "Fly to Mendoza", "Flight to book", -32.8317 to -68.7929, Vehicle.Plane, url = "https://www.aeropuertosargentina.com/en/MDZ"),
        row(4, "stay", "Wine lodge in Valle de Uco", "≈ 1 h 30 transfer · vines and the Andes outside · to book", -33.62 to -69.20, Vehicle.Car),
        row(5, "activity", "Winery visit and pairing lunch", "One winery, a long multi-course lunch, then the pool"),
        row(6, "activity", "Vendimia: Carrusel parade", "Morning, in Mendoza city", -32.8894 to -68.8445, Vehicle.Car, url = "https://www.mendoza.gob.ar/vendimia/"),
        row(6, "activity", "Vendimia: Acto Central", "Night show at the Teatro Griego · optional, tickets needed", -32.886 to -68.882, optional = true, url = "https://www.mendoza.gob.ar/vendimia/"),
        row(7, "note", "Spa morning", "Optional: a drive toward the Aconcagua viewpoints"),
        // 3 · Bariloche and the Lake District
        row(8, "transport", "Fly to Bariloche", "Direct flight to book", -41.1512 to -71.1578, Vehicle.Plane, url = "https://www.aeropuertosargentina.com/en/BRC"),
        row(8, "stay", "Hotel by Lake Nahuel Huapi", "For example the Llao Llao · to book", -41.053 to -71.535, Vehicle.Car),
        row(9, "activity", "Circuito Chico", "The scenic loop by the lakes", -41.056 to -71.513, url = "https://barilocheturismo.gob.ar/en/short-circuit"),
        row(9, "place", "Cerro Campanario chairlift", "One of the best views over the lakes", -41.050 to -71.489, url = "https://cerrocampanario.com.ar/la-aerosilla/"),
        row(10, "food", "Chocolate on Calle Mitre", "Then a lakeside afternoon", -41.1336 to -71.3057, Vehicle.Car),
        row(11, "activity", "Isla Victoria and the Arrayanes Forest", "By boat from Puerto Pañuelo", -40.8456 to -71.6369, Vehicle.Ferry, url = "https://barilocheturismo.gob.ar/en/victoria-island-and-myrtle-woods"),
        row(12, "note", "Rest day", "Pool, spa or the short Cerro Llao Llao trail (≈ 1 h)"),
        // 4 · El Chaltén and Mount Fitz Roy
        row(13, "transport", "Fly to El Calafate", "Morning flight to book", -50.2803 to -72.0531, Vehicle.Plane, url = "https://www.aeropuertoelcalafate.com/en/"),
        row(13, "transport", "Transfer to El Chaltén", "≈ 3 h · stay to book", -49.3315 to -72.8863, Vehicle.Car),
        row(13, "place", "Mirador de los Cóndores", "Sunset walk · ≈ 1 h", -49.3373 to -72.8778, Vehicle.Walk, url = "https://elchalten.tur.ar/mirador-de-los-condores-y-aguilas/"),
        row(14, "activity", "Laguna Capri and Mirador Fitz Roy", "≈ 8–10 km return, 3–5 h, steep first kilometre", -49.296 to -72.927, Vehicle.Walk, url = "https://elchalten.tur.ar/laguna-capri/"),
        row(15, "place", "Chorrillo del Salto", "Rest day. Cafés, a craft brewery, or this flat waterfall walk (≈ 1–2 h)", -49.287 to -72.881, url = "https://elchalten.tur.ar/el-chorrillo-del-salto/"),
        row(16, "activity", "Laguna de los Tres", "Optional, on the clearest day: ≈ 21–26 km, 8–10 h, very steep last hour. Easier: Laguna Torre (≈ 18 km)", -49.272 to -72.999, Vehicle.Walk, url = "https://elchalten.tur.ar/laguna-de-los-tres/"),
        // 5 · El Calafate and the Perito Moreno Glacier
        row(17, "transport", "Back to El Calafate", "Quiet evening by Lago Argentino · stay to book", -50.3379 to -72.2648, Vehicle.Car),
        row(18, "activity", "Perito Moreno Glacier", "The boardwalks facing the ice, plus the 1 h boat to the face or a mini-trek on the ice", -50.471 to -73.031, Vehicle.Car, url = "https://www.elcalafate.tur.ar/in-perito-moreno-glacier.htm"),
        // 6 · Torres del Paine and the W Trek
        row(19, "transport", "Bus to Puerto Natales", "≈ 5–6 h with the border", -51.7300 to -72.5065, Vehicle.Car),
        row(20, "transport", "Bus to Pudeto", "Then the catamaran across Lake Pehoé", -51.079 to -72.989, Vehicle.Car),
        row(20, "transport", "Catamaran to Paine Grande", "", -51.0716 to -73.1155, Vehicle.Ferry),
        row(20, "activity", "W day 1: Refugio Grey", "≈ 11 km", -50.997 to -73.209, Vehicle.Walk, url = "https://www.vertice.travel/alojamiento/refuge-camping-grey/"),
        row(21, "activity", "W day 2: Glacier Grey, back to Paine Grande", "Viewpoints first · ≈ 11 km", -51.0716 to -73.1155, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        row(22, "activity", "W day 3: French Valley", "On to Refugio Francés or Cuernos · ≈ 13–18 km", -51.043 to -73.040, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        row(23, "activity", "W day 4: Refugio Chileno", "Along Lake Nordenskjöld · ≈ 15 km", -50.984 to -72.872, Vehicle.Walk, url = "https://lastorres.com/en/camping/camping-chileno/"),
        row(24, "activity", "W day 5: Base of the Towers", "Sunrise · ≈ 2 h climb, then down", -50.943 to -72.967, Vehicle.Walk, url = "https://www.conaf.cl/parque_nacionales/parque-nacional-torres-del-paine/"),
        row(24, "transport", "Afternoon bus to Natales", "", -51.7300 to -72.5065, Vehicle.Car),
        row(24, "stay", "Recovery days in Puerto Natales", "Sleep in, seafood on the waterfront, a massage, the shoreline walk · to book"),
        row(25, "note", "Recovery day", "Seafood on the waterfront"),
        row(26, "place", "Milodón Cave", "Optional", -51.5683 to -72.6167, Vehicle.Car, url = "https://www.conaf.cl/parque_nacionales/monumento-natural-cueva-del-milodon/"),
        // 7 · Punta Arenas
        row(27, "transport", "Bus to Punta Arenas", "≈ 3 h · one night on the Strait of Magellan, to book", -53.1626 to -70.9081, Vehicle.Car),
        // 8 · Santiago
        row(28, "transport", "Fly to Santiago", "Flight to book", -33.3930 to -70.7858, Vehicle.Plane, url = "https://www.aeropuertosantiagodechile.cl/en"),
        row(28, "stay", "Hotel in Lastarria or Providencia", "Easy dinner · to book", -33.4372 to -70.6403, Vehicle.Car),
        row(29, "place", "Cerro San Cristóbal", "Cable car up for the city and the Andes, then the Lastarria cafés", -33.4253 to -70.6331, url = "https://telefericosantiago.cl/?lang=en"),
        row(30, "activity", "Day trip to Valparaíso", "Or a Maipo or Casablanca winery lunch", -33.0472 to -71.6127, Vehicle.Car, url = "https://chile.travel/en/destinations/valparaiso/"),
        row(31, "transport", "Fly home", "Flight to book", -33.3930 to -70.7858, Vehicle.Car, url = "https://www.aeropuertosantiagodechile.cl/en"),
        // Not on a day yet.
        row(null, "note", "Still to book", "Flights, stays, the tango night, Vendimia tickets, the W Trek refugios, meals and buses"),
        row(null, "place", "Aconcagua viewpoints", "Optional drive from Mendoza", url = "https://sitios.mendoza.gob.ar/energiayambiente/aconcagua/"),
        row(null, "place", "Laguna Torre", "The easier Fitz Roy-area hike (≈ 18 km)", url = "https://elchalten.tur.ar/laguna-torre/"),
        row(null, "note", CREDITS_TITLE, "Demo photos from Wikimedia Commons, shared under Creative Commons: " + DemoPhotos.CREDITS.joinToString(" · ")),
    )

    val PALERMO = -34.5886 to -58.4302
    val CHALTEN = -49.3315 to -72.8863

    /** The journal so far. A day with time null is the entry written today. Short notes, as people write them on the go. */
    val days: List<SampleRows.Day> = listOf(
        SampleRows.Day(
            1, "23:20", "First night", "Palermo Soho, Buenos Aires", PALERMO, listOf(
                SampleRows.Text("Landed at 7. Taxi in along 9 de Julio, Obelisco all lit up."),
                SampleRows.Snap(DemoPhotos.OBELISCO, "21:40", -34.6037 to -58.3816),
                SampleRows.Text("Steak and Malbec round the corner at ten. We were still the first ones in."),
            ),
        ),
        SampleRows.Day(
            2, "19:10", "Recoleta", "Recoleta, Buenos Aires", -34.5875 to -58.3934, listOf(
                SampleRows.Text("<b>Medialunas and café con leche</b>, then a second coffee. Nobody here is in a hurry."),
                SampleRows.Layout(listOf(SampleRows.Snap(DemoPhotos.CAFE, "09:50"), SampleRows.Snap(DemoPhotos.RECOLETA, "11:30"), SampleRows.Snap(DemoPhotos.ROSEDAL, "16:20", -34.5718 to -58.4176))),
                SampleRows.Text("Recoleta Cemetery: marble streets, cats asleep on the steps. Roses in Palermo on the way back. Feet done by five."),
            ),
        ),
        SampleRows.Day(
            3, "23:45", "San Telmo, then tango", "San Telmo, Buenos Aires", -34.6205 to -58.3714, listOf(
                SampleRows.Text("Antiques in San Telmo all morning. Nearly bought an old soda siphon."),
                SampleRows.Layout(listOf(SampleRows.Snap(DemoPhotos.SAN_TELMO, "11:10"), SampleRows.Snap(DemoPhotos.CAMINITO, "15:20", -34.6390 to -58.3626)), PhotoFrame.Tape),
                SampleRows.Text("La Boca after lunch. The houses really are those colours."),
                SampleRows.Text("Tango show tonight. <i>They make it look easy.</i>"),
            ),
        ),
        SampleRows.Day(
            5, "18:00", "Lunch in Valle de Uco", "Valle de Uco, Mendoza", -33.62 to -69.20, listOf(
                SampleRows.Text("One winery, one very long lunch. Seven courses, seven glasses, the Andes in the window."),
                SampleRows.Snap(DemoPhotos.UCO, "13:40"),
                SampleRows.Text("Pool after. Nobody said much."),
            ),
        ),
        SampleRows.Day(
            6, "14:10", "Vendimia", "Mendoza", -32.8894 to -68.8445, listOf(
                SampleRows.Text("Carrusel parade this morning: floats, gauchos, grapes thrown into the crowd. Caught a bunch."),
                SampleRows.Snap(DemoPhotos.GRAPES, "11:15"),
                SampleRows.Text("Skipped the night show."),
            ),
        ),
        SampleRows.Day(
            8, "20:30", "Bariloche", "Lake Nahuel Huapi, Bariloche", -41.053 to -71.535, listOf(
                SampleRows.Text("Flew south. Cold and clear, and the lake is huge."),
                SampleRows.Snap(DemoPhotos.LLAO_LLAO, "18:40"),
            ),
        ),
        SampleRows.Day(
            9, "18:30", "Circuito Chico", "Cerro Campanario, Bariloche", -41.050 to -71.489, listOf(
                SampleRows.Text("Drove the Circuito Chico with too many stops."),
                SampleRows.Text("<b>Chairlift up Campanario.</b> Lakes and islands everywhere, and a lot of wind."),
                SampleRows.Snap(DemoPhotos.CAMPANARIO, "16:10"),
            ),
        ),
        SampleRows.Day(
            10, "17:20", "Chocolate on Mitre", "Calle Mitre, Bariloche", -41.1336 to -71.3057, listOf(
                SampleRows.Text("Mitre is one chocolate shop after another. Tried three, bought from the fourth."),
                SampleRows.Snap(DemoPhotos.CENTRO, "15:30"),
                SampleRows.Check(listOf(CheckItem("Dark chocolate for home", true), CheckItem("Something for work", true), CheckItem("Leave some for the bus"))),
            ),
        ),
        SampleRows.Day(
            11, "19:00", "Arrayanes by boat", "Bosque de Arrayanes", -40.8456 to -71.6369, listOf(
                SampleRows.Text("Boat from Puerto Pañuelo to Isla Victoria, then the Arrayanes forest."),
                SampleRows.Layout(listOf(SampleRows.Snap(DemoPhotos.BOAT, "10:40", -41.054 to -71.527), SampleRows.Snap(DemoPhotos.ARRAYANES, "13:20")), PhotoFrame.Polaroid),
                SampleRows.Text("The trees are cinnamon coloured and cold to touch. Windy on the way back, sat inside."),
            ),
        ),
        SampleRows.Day(
            13, "21:50", "To El Chaltén", "Mirador de los Cóndores, El Chaltén", -49.3373 to -72.8778, listOf(
                SampleRows.Text("Flight to Calafate, then three hours of bus with Fitz Roy getting bigger in the window."),
                SampleRows.Snap(DemoPhotos.CHALTEN, "15:40", CHALTEN),
                SampleRows.Text("Up to the Mirador de los Cóndores for sunset. Orange for five minutes, then grey."),
                SampleRows.Snap(DemoPhotos.SUNSET, "20:30"),
                SampleRows.Text("For tomorrow:"),
                SampleRows.Check(listOf(CheckItem("Water", true), CheckItem("Snacks", true), CheckItem("Rain jacket in the bag"), CheckItem("Alarm"))),
            ),
        ),
        SampleRows.Day(
            TODAY, null, "Laguna Capri", "Laguna Capri, El Chaltén", -49.296 to -72.927, listOf(
                SampleRows.Text("First km is steep, then it's trees and easy."),
                SampleRows.Snap(DemoPhotos.CAPRI, "", tilt = -4f, width = .8f, frame = PhotoFrame.Polaroid, caption = "Laguna Capri"),
                SampleRows.Text("Wind dropped and Fitz Roy is in the water too. Sitting on a rock deciding about the mirador."),
            ),
        ),
    )
}

/** The example trip "Lisbon & Porto" (the Android app's data/SampleLisbon.kt): its journal and stays beside the plan. */
object SampleLisbonJournal {
    const val ZONE = "Europe/Lisbon"
    val ALFAMA = 38.7115 to -9.1300
    val RIBEIRA = 41.1408 to -8.6131

    val days: List<SampleRows.Day> = listOf(
        SampleRows.Day(
            1, "21:30", "Alfama, first evening", "Alfama, Lisbon", ALFAMA, listOf(
                SampleRows.Text("Taxi from the airport, then up the hill on foot with the bags. Wrong turn twice."),
                SampleRows.Pic(SceneKind.City, "19:40", 38.7118 to -9.1301),
                SampleRows.Text("Santa Luzia at sunset: tiles, the river, a lot of people being quiet."),
            ),
        ),
        SampleRows.Day(
            2, "20:10", "Tram 28 and the castle", "São Jorge Castle, Lisbon", 38.7139 to -9.1335, listOf(
                SampleRows.Text("Tram 28 from Martim Moniz before nine. It squeals round corners with centimetres to spare."),
                SampleRows.Pic(SceneKind.City, "11:20", shape = SceneMath.Shape.Square, mirror = true),
                SampleRows.Text("Castle walls, peacocks, and the whole city below."),
            ),
        ),
        SampleRows.Day(
            3, "19:00", "Belém", "Belém, Lisbon", 38.6979 to -9.2068, listOf(
                SampleRows.Text("Tram 15 out to Belém. The monastery cloister is all carved stone."),
                SampleRows.Pic(SceneKind.Coast, "11:00", 38.6916 to -9.2160),
                SampleRows.Text("Pastéis de Belém still warm, <b>cinnamon and sugar</b> on top."),
                SampleRows.Text("LX Factory after, mostly for the bookshop."),
            ),
        ),
        SampleRows.Day(
            4, "21:00", "Sintra", "Sintra", 38.7963 to -9.3960, listOf(
                SampleRows.Text("Train from Rossio, then the bus up to Pena in the fog."),
                SampleRows.Pic(SceneKind.Village, "12:10", 38.7876 to -9.3906, SceneMath.Shape.Tall),
                SampleRows.Text("It cleared at Quinta da Regaleira. Walked down the spiral well."),
                SampleRows.Check(listOf(CheckItem("Pena", true), CheckItem("Regaleira", true), CheckItem("Moorish Castle"))),
            ),
        ),
        SampleRows.Day(
            5, "23:30", "Bairro Alto", "Bairro Alto, Lisbon", 38.7130 to -9.1440, listOf(
                SampleRows.Text("Long lunch at the market, a nap, then out late."),
                SampleRows.Pic(SceneKind.Night, "22:40"),
                SampleRows.Text("Fado drifting out of a doorway on the way home."),
            ),
        ),
        SampleRows.Day(
            6, "20:30", "North to Porto", "Ribeira, Porto", RIBEIRA, listOf(
                SampleRows.Text("Three hours on the train, mostly fields and pine."),
                SampleRows.Pic(SceneKind.City, "18:50", 41.1404 to -8.6117, mirror = true),
                SampleRows.Text("Ribeira in the evening: washing on the balconies, boats on the river."),
            ),
        ),
        SampleRows.Day(
            7, "19:30", "Lello and the cellars", "Vila Nova de Gaia", 41.1374 to -8.6123, listOf(
                SampleRows.Text("Livraria Lello at opening time. Worth the early start."),
                SampleRows.Text("Over the top of the bridge to Gaia, then port in a cool cellar."),
                SampleRows.Pic(SceneKind.Coast, "17:30", 41.1397 to -8.6094, SceneMath.Shape.Square, mirror = true),
            ),
        ),
        SampleRows.Day(
            8, "21:10", "Douro valley", "Pinhão, Douro", 41.1902 to -7.5450, listOf(
                SampleRows.Text("Train up the river, vineyards on terraces all the way."),
                SampleRows.Pic(SceneKind.Countryside, "13:00", 41.1950 to -7.5470),
                SampleRows.Text("A short walk above Pinhão, then the evening train back."),
            ),
        ),
    )
}

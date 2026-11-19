package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.art.decomposed
import com.justdataplease.boringtravel.art.oneDecimal
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PlanStep
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan's rules as this app writes them again (PlanDrop, the travel rows and their rough times, the countdowns, an
 * item as text, the drawn pictures' word rules), against the Android app's own test cases (app/src/test: PlanDropTest,
 * LegRowTest, PlanTravelRowsTest, TripCountdownTest, PlanItemTextTest), case for case, and against the Java calls the
 * Android code makes where this app writes plain Kotlin instead.
 */
class PlanRulesAgainstAndroidTest {
    private var locale: Locale = Locale.getDefault()

    @BeforeTest
    fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @AfterTest
    fun restore() = Locale.setDefault(locale)

    // ------------------------------------------------------------------ PlanDropTest

    private val sat = localDate(2027, 3, 13)
    private val sun = sat.plusDays(1)

    private fun item(id: String, pos: Double, time: Int? = null, day: LocalDate? = sat, endDays: Int? = null, endMinutes: Int? = null, kind: String = "place") =
        PlanItem(id, "t", day, pos, timeMinutes = time, kind = kind, title = id, createdAt = 0, updatedAt = 0, endDays = endDays, endMinutes = endMinutes)

    private val museum = item("museum", 1.0, 10 * 60)
    private val lunch = item("lunch", 2.0, 13 * 60)
    private val walk = item("walk", 3.0)
    private val dinner = item("dinner", 4.0, 20 * 60)

    @Test
    fun aDropKeepsOrFitsTheTime() {
        assertEquals(11 * 60, PlanDrop.fit(11 * 60, 10 * 60, 13 * 60))
        assertEquals(10 * 60, PlanDrop.fit(10 * 60, 10 * 60, 13 * 60))
        assertEquals(9 * 60, PlanDrop.fit(9 * 60, null, null))
        assertEquals(22 * 60, PlanDrop.fit(22 * 60, 20 * 60, null))
        assertEquals(8 * 60, PlanDrop.fit(8 * 60, null, 9 * 60))
        assertEquals(11 * 60 + 30, PlanDrop.fit(18 * 60, 10 * 60, 13 * 60))
        assertEquals(10 * 60 + 25, PlanDrop.fit(18 * 60, 10 * 60 + 10, 10 * 60 + 41))
        assertEquals(10 * 60, PlanDrop.fit(18 * 60, 10 * 60, 10 * 60 + 2))
        assertEquals(21 * 60, PlanDrop.fit(9 * 60, 20 * 60, null))
        assertEquals(8 * 60, PlanDrop.fit(12 * 60, null, 9 * 60))
        assertEquals(24 * 60 - 1, PlanDrop.fit(9 * 60, 23 * 60 + 30, null))
        assertEquals(0, PlanDrop.fit(9 * 60, null, 30))
    }

    @Test
    fun anEndMovesWithItsStartAndKeepsTheLength() {
        val show = item("show", 1.0, 19 * 60, endMinutes = 22 * 60)
        val earlier = PlanDrop.withTime(show, 17 * 60)
        assertEquals(17 * 60, earlier.timeMinutes)
        assertEquals(20 * 60, earlier.endMinutes)
        assertNull(earlier.endDays)
        val late = PlanDrop.withTime(show, 22 * 60 + 30)
        assertEquals(1, late.endDays)
        assertEquals(60 + 30, late.endMinutes)
        assertEquals(PlanSpan.minutes(show), PlanSpan.minutes(late))
        val hostal = item("hostal", 1.0, 15 * 60, endDays = 3, kind = "stay")
        assertEquals(3, PlanDrop.withTime(hostal, 12 * 60).endDays)
    }

    @Test
    fun dropsWithinADayAndOntoAnother() {
        val untimed = PlanDrop.drop(walk, sat, listOf(museum), listOf(lunch, dinner))
        assertEquals(1.5, untimed.single().position)
        assertEquals(listOf("museum", "walk", "lunch", "dinner"), PlanSpan.dayOrder(listOf(museum, lunch, dinner) + untimed).map { it.id })

        val moved = PlanDrop.drop(dinner, sat, listOf(museum), listOf(lunch, walk)).single()
        assertEquals(11 * 60 + 30, moved.timeMinutes)
        assertEquals(16 * 60 + 30, PlanDrop.drop(museum, sat, listOf(lunch, walk), listOf(dinner)).single().timeMinutes)

        val brunch = item("brunch", 1.0, 11 * 60, day = sun)
        val other = PlanDrop.drop(lunch, sun, listOf(brunch), emptyList()).single()
        assertEquals(sun, other.day)
        assertEquals(13 * 60, other.timeMinutes)
        assertEquals(2.0, other.position)
        assertEquals("Moved to Sun 14 · 13:00", PlanDrop.notice(lunch, other))

        val hostal = item("hostal", 1.0, 15 * 60, endDays = 2, endMinutes = 11 * 60, kind = "stay")
        val stay = PlanDrop.drop(hostal, sun, emptyList(), emptyList()).single()
        assertEquals(sun.plusDays(2), PlanSpan.endDate(stay))
        assertEquals("Moved to Sun 14 · 15:00", PlanDrop.notice(hostal, stay))

        assertEquals(1.0, PlanDrop.drop(walk, sun, emptyList(), emptyList()).single().position)
        val idea = PlanDrop.drop(walk, null, emptyList(), listOf(item("other", 5.0, day = null))).single()
        assertNull(idea.day)
        assertEquals(4.0, idea.position)
        assertEquals("Moved to Ideas", PlanDrop.notice(walk, idea))

        assertTrue(PlanDrop.drop(lunch, sat, listOf(museum), listOf(walk, dinner)).isEmpty())
        assertNull(PlanDrop.notice(walk, walk.copy(position = 9.0)))
    }

    @Test
    fun outOfStepAndCrowdedNeighboursAreRenumbered() {
        val a = item("a", 5.0)
        val b = item("b", 1.0)
        val c = item("c", 3.0)
        val saves = PlanDrop.drop(c, sat, listOf(a), listOf(b))
        assertEquals("c", saves.first().id)
        val all = (listOf(a, b, c).associateBy { it.id } + saves.associateBy { it.id }).values
        assertEquals(listOf("a", "c", "b"), all.sortedBy { it.position }.map { it.id })

        val x = item("x", 1.0)
        val y = item("y", 1.0 + 1e-7)
        val crowded = PlanDrop.drop(walk, sat, listOf(x), listOf(y)).associateBy { it.id }
        assertEquals(listOf(1.0, 2.0, 3.0), listOf(crowded["x"] ?: x, crowded.getValue("walk"), crowded["y"] ?: y).map { it.position })
    }

    private fun line(i: PlanItem, end: Boolean = false) = PlanListLogic.Row.Line(PlanLine(PlanStep(i, end), null))

    @Test
    fun theTargetIsTheDayUnderTheNearestHeader() {
        val hostal = item("hostal", 9.0, 15 * 60, endDays = 1, endMinutes = 11 * 60, kind = "stay")
        val brunch = item("brunch", 1.0, 12 * 60, day = sun)
        val rows = listOf(
            PlanListLogic.Row.DayHead(sat, 1), line(museum), line(lunch),
            PlanListLogic.Row.DayHead(sun, 2), line(hostal, end = true), line(walk), line(brunch),
            PlanListLogic.Row.Folded(sun.plusDays(1), sun.plusDays(3)),
            PlanListLogic.Row.IdeasHead(0), PlanListLogic.Row.IdeasEmpty,
        )
        val t = assertNotNull(PlanDrop.target(rows, "i-walk"))
        assertEquals(sun, t.day)
        assertEquals(emptyList(), t.before.map { it.id })
        assertEquals(listOf("brunch"), t.after.map { it.id })
        assertEquals(sun.plusDays(1), PlanDrop.target(rows.toMutableList().apply { add(7, removeAt(5)) }, "i-walk")?.day)
        assertNull(PlanDrop.target(rows.toMutableList().apply { add(8, removeAt(5)) }, "i-walk")?.day)
        val top = assertNotNull(PlanDrop.target(rows.toMutableList().apply { add(0, removeAt(5)) }, "i-walk"))
        assertEquals(sat, top.day)
        assertEquals(listOf("museum", "lunch"), top.after.map { it.id })
        assertNull(PlanDrop.target(rows, "i-nothing"))

        val ideas = listOf(item("x", 1.0, day = null), item("y", 2.0, day = null), item("z", 3.0, day = null))
        val i = assertNotNull(PlanDrop.target(ideas.map { line(it) }, "i-y"))
        assertNull(i.day)
        assertEquals(listOf("x"), i.before.map { it.id })
        assertEquals(listOf("z"), i.after.map { it.id })
        assertFalse(PlanDrop.isTarget(PlanListLogic.Row.DayEmpty(sat)))
        assertFalse(PlanDrop.isTarget(PlanListLogic.Row.IdeasEmpty))
        assertTrue(PlanDrop.isTarget(PlanListLogic.Row.DayHead(sat, 1)))
    }

    // ------------------------------------------------------------------ LegRowTest, PlanTravelRowsTest

    private val legDay = localDate(2027, 3, 13)
    private val legTrip = Trip("t", "Patagonia", startDate = legDay, endDate = legDay.plusDays(3), createdAt = 1, updatedAt = 1)
    private fun located(id: String, d: LocalDate?, pos: Double, lat: Double?, lng: Double?, trip: String = "t") =
        PlanItem(id, trip, d, pos, title = id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)
    private fun key(a: String, b: String) = RouteLegKey(RouteLegSource.Planned, a, b)

    @Test
    fun legRowsShowTheChosenVehicleAndARoughTime() {
        val airport = located("El Calafate", legDay, 1.0, -50.2803, -72.0531)
        val town = located("El Chaltén", legDay, 2.0, -49.3315, -72.8863)
        val mirador = located("Mirador", legDay, 3.0, -49.3373, -72.8778)
        val capri = located("Laguna Capri", legDay.plusDays(1), 1.0, -49.296, -72.927)
        var modes = TravelModes.set(null, key(airport.id, town.id), Vehicle.Car)
        modes = TravelModes.set(modes, key(town.id, mirador.id), Vehicle.Walk)
        val items = listOf(airport, town, mirador, capri)
        val rows = PlanTravelRows.incoming(legTrip.copy(travelModes = modes), items)
        val byId = items.associateBy { it.id }
        fun model(to: PlanItem): LegRowModel = rows.getValue(to.id).let { LegRowModel.of(it, byId[it.leg.key.fromId], to) }
        assertEquals("Car · ≈ 2 h 30", model(town).text)
        assertNull(model(town).fromLabel)
        assertEquals("Walk · ≈ 15 min", model(mirador).text)
        val next = model(capri)
        assertEquals(Vehicle.Car, next.vehicle)
        assertTrue(next.text.startsWith("Car · ≈ "), next.text)
        assertEquals("Mirador", next.fromLabel)
    }

    @Test
    fun roughTimesAsAndroidRoundsThem() {
        assertEquals(5, LegEstimate.rounded(1))
        assertEquals(15, LegEstimate.rounded(13))
        assertEquals(45, LegEstimate.rounded(47))
        assertEquals(60, LegEstimate.rounded(61))
        assertEquals(90, LegEstimate.rounded(95))
        assertEquals(180, LegEstimate.rounded(179))
        assertEquals(180, LegEstimate.rounded(205))
        assertEquals(240, LegEstimate.rounded(215))
        assertEquals("≈ 20 min", LegEstimate.short(19))
        assertEquals("≈ 1 h", LegEstimate.short(58 + 4))
        assertEquals("≈ 1 h 30", LegEstimate.short(88))
        assertEquals("≈ 4 h", LegEstimate.short(230))
        // 100 km: by car 100 × 1.35 / 65 h ≈ 125 min; by plane 105 / 700 h + 30 min ≈ 39 min; on foot ≈ 29 h.
        assertEquals(125, LegEstimate.minutes(Vehicle.Car, 100_000.0))
        assertEquals(39, LegEstimate.minutes(Vehicle.Plane, 100_000.0))
        assertEquals(1733, LegEstimate.minutes(Vehicle.Walk, 100_000.0))
        assertNull(LegEstimate.minutes(Vehicle.Auto, 100_000.0))
        assertNull(LegEstimate.minutes(Vehicle.Car, 0.0))
        assertNull(LegEstimate.minutes(Vehicle.Car, Double.NaN))
    }

    @Test
    fun theDefaultsAreCarUnlessTheNameTheDistanceOrOnePlaceSaySo() {
        assertEquals(Vehicle.Car, TravelDefault.guess("Recoleta Cemetery"))
        assertEquals(Vehicle.Plane, TravelDefault.guess("Fly to Mendoza"))
        assertEquals(Vehicle.Train, TravelDefault.guess("Shinkansen to Shin-Osaka"))
        assertEquals(Vehicle.Ferry, TravelDefault.guess("Ferry to Naxos"))
        assertEquals(Vehicle.Car, TravelDefault.guess("Flyover viewpoint"))
        // Buenos Aires to Mendoza is about 985 km: unchosen, it flies. Two sights of one town on one day: on foot.
        val ba = located("Recoleta Cemetery", legDay, 1.0, -34.5875, -58.3934)
        val mdz = located("Winery", legDay.plusDays(1), 1.0, -32.8894, -68.8445)
        val legs = RouteLegs.planned(legTrip, listOf(ba, mdz))
        assertEquals(Vehicle.Plane, legs.single().travel)
        assertFalse(legs.single().countsKm)
        val a = located("Cathedral", legDay, 1.0, 38.7100, -9.1336).copy(placeName = "Sé, Lisbon, Portugal")
        val b = located("Alfama lanes", legDay, 2.0, 38.7115, -9.1300).copy(placeName = "Alfama, Lisbon, Portugal")
        val walk = RouteLegs.planned(legTrip, listOf(a, b)).single()
        assertTrue(walk.samePlace)
        assertEquals(Vehicle.Walk, walk.travel)
        assertTrue(walk.countsKm)
        // The switch saves only what differs from the default.
        assertEquals(true, legKmChoice(Vehicle.Plane, true))
        assertNull(legKmChoice(Vehicle.Plane, false))
        assertNull(legKmChoice(Vehicle.Car, true))
        assertEquals(false, legKmChoice(Vehicle.Car, false))
    }

    @Test
    fun theDistanceReadsAsJavaWritesItInEnglish() {
        for (km in listOf(0.04, 0.3, 4.25, 9.94, 9.96, 10.0, 12.5, 999.4, 2370.0, 12_345.6, 1_234_567.0)) {
            val java = if (km < 10) String.format(Locale.ENGLISH, "%.1f km", km) else String.format(Locale.ENGLISH, "%,d km", Math.round(km))
            assertEquals(java, kmText(km), "$km")
        }
        assertEquals("2,370 km in the trip's total", legKmLine(2_370_000.0, true))
        assertEquals("2,370 km, left out of the trip's total", legKmLine(2_370_000.0, false))
        assertEquals("In the trip's total", legKmLine(Double.NaN, true))
        assertEquals("From Bariloche · ≈ 2 h", gettingThereLine("Bariloche", Vehicle.Car, 100_000.0))
    }

    @Test
    fun theTravelRowsFollowTheRoute() {
        val day = localDate(2028, 5, 10)
        val trip = Trip("trip", "Example", startDate = day, endDate = day.plusDays(2), createdAt = 1, updatedAt = 1)
        fun i(id: String, position: Double, date: LocalDate? = day, lat: Double? = 37.0, lng: Double? = 23.0) = located(id, date, position, lat, lng, "trip")
        val a = i("Harbour", 2.0)
        val note = i("Packing note", 1.0, day.plusDays(1), null, null)
        val b = i("Museum", 2.0, day.plusDays(1), 37.1, 23.1)
        val rows = PlanTravelRows.incoming(trip, listOf(b, note, a))
        assertEquals(setOf(b.id), rows.keys)
        val incoming = rows.getValue(b.id)
        assertEquals(key(a.id, b.id), incoming.leg.key)
        assertEquals(day, incoming.originDay)
        assertEquals("Harbour", incoming.fromLabel)
        assertEquals(Vehicle.Car, incoming.mode)

        // Unlocated, invalid, deleted and other trips' items get no travel rows.
        val x = i("a", 1.0)
        val same = i("same-place", 2.0, lat = 37.000001, lng = 23.000001)
        val y = i("b", 7.0, lat = 37.5)
        val r2 = PlanTravelRows.incoming(trip, listOf(x, same, i("missing", 3.0, lat = null, lng = null), i("invalid", 4.0, lat = Double.NaN), i("deleted", 5.0, lat = 37.3).copy(deletedAt = 4), i("foreign", 6.0, lat = 37.4).copy(tripId = "other"), y))
        assertEquals(setOf(y.id), r2.keys)

        // A reorder never moves an old choice onto the new leg, and the open chooser closes.
        val c = i("c", 3.0, lat = 37.2)
        val bb = i("bb", 2.0, lat = 37.1)
        val saved = trip.copy(travelModes = TravelModes.set(null, key(x.id, bb.id), Vehicle.Ferry))
        assertEquals(Vehicle.Ferry, PlanTravelRows.incoming(saved, listOf(x, bb, c)).getValue(bb.id).mode)
        val after = PlanTravelRows.incoming(saved, listOf(x, bb.copy(position = 4.0), c))
        assertEquals(key(c.id, bb.id), after.getValue(bb.id).leg.key)
        assertNull(PlanTravelRows.resolve(after, key(x.id, bb.id)))
    }

    // ------------------------------------------------------------------ TripCountdownTest

    @Test
    fun countdownsReadAsOnAndroid() {
        val today = localDate(2026, 11, 7)
        fun d(y: Int, m: Int, day: Int) = localDate(y, m, day)
        val argentina = Trip("arg", "Argentina & Patagonia", startDate = d(2026, 10, 25), endDate = d(2026, 11, 24), createdAt = 1, updatedAt = 1)
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Now, 17, "days left"), TripCountdown.of(argentina, today))
        assertEquals(14f / 31f, TripCountdown.progress(argentina.startDate, argentina.endDate, today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Next, 330, "days to go"), TripCountdown.of(d(2027, 10, 3), d(2027, 10, 14), today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Past, 6, "months ago"), TripCountdown.of(d(2026, 5, 9), d(2026, 5, 17), today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Now, 1, "day left"), TripCountdown.of(d(2026, 11, 1), d(2026, 11, 8), today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Now, null, "Last day"), TripCountdown.of(today, null, today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Now, null, "Last day"), TripCountdown.of(today, d(2026, 11, 1), today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Next, null, "Tomorrow"), TripCountdown.of(d(2026, 11, 8), d(2026, 11, 20), today))
        assertEquals(TripCountdown.Count(TripCountdown.Phase.Someday, null, "Add dates"), TripCountdown.of(null, null, today))
        fun ago(days: Long) = TripCountdown.ago(days).let { it.number to it.unit }
        assertEquals(null to "Yesterday", ago(1))
        assertEquals(13L to "days ago", ago(13))
        assertEquals(2L to "weeks ago", ago(14))
        assertEquals(8L to "weeks ago", ago(59))
        assertEquals(2L to "months ago", ago(60))
        assertEquals(11L to "months ago", ago(340))
        assertEquals(1L to "year ago", ago(500))
        assertEquals(2L to "years ago", ago(600))
        assertEquals(null to "Yesterday", ago(0))
        assertEquals(0.7f, TripCountdown.progress(d(2026, 11, 1), d(2026, 11, 10), today), 1e-6f)
        assertEquals("68 places planned", TripCountdown.countLine(68, past = false))
        assertEquals("1 place", TripCountdown.countLine(1, past = false, short = true))
        assertEquals("1 entry", TripCountdown.countLine(1, past = true, short = true))
        assertNull(TripCountdown.countLine(0, past = false))
    }

    // ------------------------------------------------------------------ PlanItemTextTest

    private val alhambra = PlanItem(
        id = "a", tripId = "t", day = sat, position = 1.0, timeMinutes = 10 * 60, endMinutes = 13 * 60,
        title = "Alhambra", placeName = "Calle Real de la Alhambra, Granada, Spain", lat = 37.176, lng = -3.588,
        url = "https://www.alhambra-patronato.es", note = "Tickets booked. Bring passports.", done = true,
        createdAt = 0, updatedAt = 0,
    )

    @Test
    fun anItemSharedAsTextReadsAsOnAndroid() {
        val text = PlanItemText.of(alhambra, PlanItemText.gettingThere(Vehicle.Train, "Madrid"))
        assertEquals(
            """
            Alhambra
            Sat 13 Mar 2027 · 10:00–13:00
            Calle Real de la Alhambra, Granada, Spain
            Website: https://www.alhambra-patronato.es
            Map: https://www.google.com/maps/search/?api=1&query=37.176,-3.588
            Getting there: train from Madrid
            Note: Tickets booked. Bring passports.
            """.trimIndent(),
            text,
        )
        val idea = PlanItem(id = "i", tripId = "t", day = null, title = "Hammam", createdAt = 0, updatedAt = 0)
        assertEquals("Hammam", PlanItemText.of(idea))
        assertEquals("Hammam\nSat 13 Mar 2027 · until 14:00", PlanItemText.of(idea.copy(day = sat, endMinutes = 14 * 60)))
        assertEquals("Granada\nMap: https://www.google.com/maps/search/?api=1&query=Granada", PlanItemText.of(idea.copy(title = "Granada", placeName = "Granada", note = "  ")))
        val hostal = alhambra.copy(title = "Hostal Alicante", kind = "stay", timeMinutes = 15 * 60, endDays = 3, endMinutes = 11 * 60, url = null, note = "", placeName = "", lat = null, lng = null)
        assertEquals("Hostal Alicante\nSat 13 Mar 2027 · 15:00\n3 nights, check-out Tue 16 Mar · 11:00", PlanItemText.of(hostal))
        val party = hostal.copy(title = "Party", kind = "activity", timeMinutes = 22 * 60, endDays = 1, endMinutes = 2 * 60)
        assertEquals("Party\nSat 13 Mar 2027 · 22:00\nUntil Sun 14 Mar · 02:00", PlanItemText.of(party))
        val maps = "https://www.google.com/maps/place/Alhambra/@37.176,-3.588,17z"
        val withMaps = PlanItemText.of(alhambra.copy(url = maps, note = ""))
        assertEquals("Map: $maps", withMaps.lines().first { it.startsWith("Map:") })
        assertFalse(withMaps.contains("Website:"))
        val short = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val named = alhambra.copy(url = null, lat = null, lng = null, placeName = "Abuela's patio")
        assertEquals("Map: $short", PlanItemText.of(named.copy(mapsUrl = short)).lines().single { it.startsWith("Map:") })
        assertEquals("Map: https://www.google.com/maps/search/?api=1&query=Abuela%27s+patio", PlanItemText.of(named).lines().single { it.startsWith("Map:") })
        assertEquals("https://www.google.com/maps/search/?api=1&query=0.0001,-180", PlanItemText.mapUrl(alhambra.copy(lat = 0.0001, lng = -180.0)))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Plaza+Nueva%2C+Granada", PlanItemText.mapUrl(alhambra.copy(lat = null, lng = null, placeName = "Plaza Nueva, Granada")))
        assertEquals("on foot from Hotel Ritz", PlanItemText.gettingThere(Vehicle.Walk, "Hotel Ritz"))
        assertEquals("#moorish #gardens", PlanItemText.of(alhambra.copy(tags = "moorish gardens")).lines().last())
    }

    @Test
    fun linksAndCoordinatesAreWrittenAsJavaWritesThem() {
        for (s in listOf("Abuela's patio", "Plaza Nueva, Granada", "Café de Flore", "Ιερά Οδός 12", "a*b_c-d.e ~!")) {
            assertEquals(URLEncoder.encode(s, "UTF-8"), UrlCodec.formEncode(s), s)
        }
        for (v in listOf(37.176, -3.588, 0.0001, -180.0, 12.3456789, -0.0000004, 51.5, 139.691706)) {
            val java = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
            assertEquals(java, PlanItemText.coordinate(v), "$v")
        }
        assertTrue(MapsLinks.isMaps("https://www.google.com/maps/place/Alhambra"))
        assertTrue(MapsLinks.isMaps("https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"))
        assertTrue(MapsLinks.isMaps("https://maps.google.co.uk/?q=x"))
        assertFalse(MapsLinks.isMaps("https://www.alhambra-patronato.es"))
        assertFalse(MapsLinks.isMaps("https://www.google.com/search?q=maps"))
    }

    // ------------------------------------------------------------------ the drawn pictures' word rules

    @Test
    fun theDrawnPicturesPickTheirKindAsOnAndroid() {
        // art/Scenes.kt is compiled here with Java's Normalizer and formatting rewritten: the same answers for Latin
        // letters and modern Greek (polytonic Greek is left as it is).
        for (s in listOf("Λίμνη Πλαστήρα", "Glaciar Perito Moreno", "Café São Jorge", "Αθήνα, Ελλάδα", "Côte d'Azur", "Ζάκυνθος", "Πήλιο", "plain words")) {
            val java = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            assertEquals(java, decomposed(s.lowercase()).replace(Regex("\\p{M}+"), ""), s)
        }
        for (v in listOf(0.0, 37.94, -3.55, 12.25, -0.04, 139.69, 51.45, -72.95)) {
            assertEquals(String.format(Locale.ROOT, "%.1f", v), oneDecimal(v), "$v")
        }
        assertEquals(SceneKind.Lake, ScenePick.kindOf("Λίμνη Πλαστήρα"))
        assertEquals(SceneKind.Glacier, ScenePick.kindOf("Glaciar Perito Moreno"))
        assertEquals(SceneKind.Mountain, ScenePick.forTrip("x", "Argentina & Patagonia"))
        assertEquals(SceneKind.City, ScenePick.forTrip("x", "Kyoto & Osaka"))
    }
}

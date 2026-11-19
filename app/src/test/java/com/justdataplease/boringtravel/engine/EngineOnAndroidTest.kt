package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.time.localDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * The shared engine (the :shared module, docs/shared-engine.md) seen from the Android app: what the iPhone's trip
 * store writes passes this app's backup reader, the two phones build the same trip from a trip idea, and the plan's
 * route is one route. The engine's own tests (shared/src/commonTest) cover the same from the other side.
 */
class EngineOnAndroidTest {
    private val start: LocalDate = LocalDate.of(2027, 4, 1)

    @Test
    fun `the engine's day is java time's own day here`() {
        // No conversion anywhere in the app: a row's day is still a java.time.LocalDate.
        val day: LocalDate = localDate(2027, 4, 1)
        assertEquals(start, day)
        assertSame(BackupJson, BackupCodec.json)
    }

    @Test
    fun `what the iPhone's trip store keeps opens as a backup on Android`() {
        var n = 0
        val file = MemoryTripFile()
        val book = TripBook(file, now = { 1_000L }, ids = { "id-${n++}" })
        val destination = ProgramLibrary.destinations.first()
        val trip = book.startFromIdea(destination, destination.default, Pace.Balanced, start)
        book.addItem(trip.id, "Evening walk", start, PlanKind.Activity, timeMinutes = 1170)
        book.nameDay(trip.id, start, "Temples and tea")
        book.writeNote(trip.id, "Arrived late.\n\nThe lanterns were lit.", at = 2_000L, zoneId = "Asia/Tokyo")

        // The kept text as data.json of a Drive backup, through this app's writer and reader (format, checksum, rows).
        val data = BackupJson.decodeFromString(BackupData.serializer(), file.read()!!)
        val zip = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(book.snapshot, "1.22.0", 3_000L), data, zip)
        val (manifest, read) = BackupCodec.read(ByteArrayInputStream(zip.toByteArray()))

        assertEquals(1, manifest.items.trips)
        assertEquals(1, manifest.items.entries)
        assertEquals(data, read)
        assertEquals(book.snapshot, read.toSnapshot())
        // And the same bytes Android would write for these rows.
        assertEquals(file.read(), BackupCodec.json.encodeToString(BackupData.serializer(), read))
    }

    @Test
    fun `both phones build the same trip from every trip idea`() {
        for (destination in ProgramLibrary.destinations) for (program in destination.programs) for (pace in Pace.entries) {
            var a = 0
            var b = 0
            val android = ProgramTrips.build(destination, program, pace, start, 7L, links = LinkPlaces::sortLinks, ids = { "id-${a++}" })
            // The iPhone's store passes no link rule yet: the bundled ideas must not need one.
            val book = TripBook(MemoryTripFile(), now = { 7L }, ids = { "id-${b++}" })
            val trip = book.startFromIdea(destination, program, pace, start)
            assertEquals("${program.id} at ${pace.id}", android.trip, trip)
            assertEquals("${program.id} at ${pace.id}", android.items.sortedBy { it.id }, book.snapshot.planItems.sortedBy { it.id })
        }
    }

    @Test
    fun `the plan's route is the same route everywhere`() {
        for (destination in ProgramLibrary.destinations) for (program in destination.programs) {
            val built = ProgramTrips.build(destination, program, Pace.Energetic, start, 7L, links = LinkPlaces::sortLinks)
            assertEquals(program.id, RouteLegs.planned(built.trip, built.items).map { it.key }, PlanRoute.legKeys(built.trip, built.items))
        }
    }
}

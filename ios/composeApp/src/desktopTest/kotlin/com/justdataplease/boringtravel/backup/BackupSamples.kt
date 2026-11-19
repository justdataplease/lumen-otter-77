package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import java.time.LocalDate

/*
 * The Android app's sample rows (its BackupCodecTest.kt, which also tests Java's zip streams and so isn't read in):
 * its BackupMergeTest, read in as it is, starts from them, and so do this app's own backup tests.
 */

/** Every field of every table gets a distinct value, so a swapped field in the mapping shows up. */
internal fun sampleSnapshot() = Snapshot(
    trips = listOf(
        Trip("t1", "Cyclades", "Paros, Naxos", LocalDate.of(2027, 6, 17), LocalDate.of(2027, 6, 26), "m1", "balanced", 101, 102,
            travelModes = """{"v":1,"legs":[{"source":"planned","from":"p1","to":"p2","vehicle":"ferry","future":"keep"}],"unknown":[1,2]}"""),
        Trip("t2", "Someday: Kyoto", "", null, null, null, null, 201, 202, deletedAt = 203),
    ),
    planItems = listOf(
        PlanItem(
            "p1", "t1", LocalDate.of(2027, 6, 18), 1.5, 450, "transport", "Ferry Paros → Naxos", "Gate B", "Parikia port", 37.08, 25.15,
            true, "ai", "draft-7", 301, 302, null, "https://www.blue-star.gr/routes?from=paros&to=naxos",
        ),
        PlanItem("p2", "t1", null, 0.0, null, "food", "Idea: «Lefteris»", createdAt = 311, updatedAt = 312, deletedAt = 313),
    ),
    entries = listOf(
        Entry(
            "e1", "t1", 1_812_345_678_000, "Europe/Athens", "Halki", """{"v":1,"blocks":[{"t":"p","html":"Forty \"minutes\" <b>late</b>"}]}""",
            "Forty minutes late", "Halki, Naxos", 37.0587, 25.4807, "sunny", 29.5, 401, 402, null,
        ),
        Entry("e2", null, 1_812_000_000_000, "UTC", createdAt = 411, updatedAt = 412, deletedAt = 413),
    ),
    media = listOf(
        Media("m1", "t1", "e1", "media/m1.jpg", "image/jpeg", 4032, 3024, 1_812_345_000_000, 37.05, 25.48, "drive-m1", "abc123", 501, 502, null),
        Media("m2", "t1", null, "media/m2.png", "image/png", createdAt = 511, updatedAt = 512, deletedAt = 513),
    ),
    trackPoints = listOf(
        TrackPoint(0, "t1", 1_812_345_000_000, 37.0, 25.1, 12.5f, 3.2f, 104.0, "detailed"),
        TrackPoint(0, null, 1_812_345_060_000, 37.001, 25.101),
    ),
    stops = listOf(
        Stop("s1", "t1", 1_812_300_000_000, 1_812_310_000_000, 37.05, 25.48, "Halki", "GR", "manual", 601, 602, null),
        Stop("s2", null, 1_812_400_000_000, null, 36.0, 24.0, createdAt = 611, updatedAt = 612, deletedAt = 613),
    ),
    countryMarks = listOf(CountryMark("manual:PT", "PT", CountryMark.SOURCE_MANUAL, 701, 702, null)),
    books = listOf(BookDraft("b1", "t1", "sq21", "postcard", "kraft", true, """{"v":1,"paper":"lines","pages":[{"id":"c","t":"cover","future":1}]}""", 801, 802, null)),
)

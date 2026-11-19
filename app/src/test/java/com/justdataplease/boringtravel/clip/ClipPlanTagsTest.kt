package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Tags in the clip come from the plan (owner, 2026-11-12: "exclude from clip; clip inherits tags from plan items"): a
 * place shows its plan items' tags, in plan order, each once; the clip and each place keep their switch, colour, size
 * and spot; tags picked in the clip by 1.10 and 1.11 still load but no longer show.
 */
class ClipPlanTagsTest {
    private val day = LocalDate.of(2031, 5, 9)
    private val trip = Trip("lx", "Lisbon", startDate = day, endDate = day.plusDays(2), createdAt = 1, updatedAt = 1)
    private fun item(
        id: String, title: String, lat: Double, lng: Double, d: Int, pos: Int, placeName: String = "", kind: PlanKind = PlanKind.Place,
        tags: String? = null,
    ) = PlanItem(id = id, tripId = trip.id, day = day.plusDays(d.toLong()), position = pos.toDouble(), title = title, placeName = placeName,
        kind = kind.id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1, tags = tags)

    private val items = listOf(
        item("fly", "Fly to Lisbon", 38.7742, -9.1342, 0, 0, kind = PlanKind.Transport),
        item("se", "Lisbon Cathedral", 38.7099, -9.1334, 0, 1, placeName = "Sé de Lisboa, Lisbon, Portugal", tags = "church views"),
        item("castle", "São Jorge Castle", 38.7139, -9.1335, 0, 2, placeName = "Castelo de São Jorge, Lisbon, Portugal", tags = "views castle"),
        item("tram", "Tram 28 and the lanes of Alfama with a long lunch", 38.7115, -9.1300, 0, 3, placeName = "Alfama, Lisbon, Portugal", tags = "food"),
        item("pena", "Pena Palace", 38.7876, -9.3906, 1, 0, placeName = "Palácio da Pena, Sintra, Portugal", tags = "palace views"),
        item("porto", "Train to Porto", 41.1496, -8.6109, 2, 0, kind = PlanKind.Transport),
    )
    private val lisbonZone = ZoneId.of("Europe/Lisbon")
    private fun planned(list: List<PlanItem> = items) =
        ClipSources.available(trip, emptyList(), emptyList(), list, emptyList(), lisbonZone).getValue(ClipSource.Planned)

    private fun build(input: ClipInput, options: ClipOptions) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))

    @Test fun aPlacesTagsAreItsPlanItemsInPlanOrder() {
        // Each stop its own name: each its own item's tags.
        val each = build(planned(), ClipOptions(stopTitles = true))
        assertEquals(listOf("church", "views"), each.tags["label:item:se"])
        assertEquals(listOf("views", "castle"), each.tags["label:item:castle"])
        assertEquals(listOf("palace", "views"), each.tags["label:item:pena"])
        // An item without tags: none.
        assertNull(each.tags["label:item:porto"])
        // One name for the place and day: its items' tags in plan order, each once.
        val one = build(planned(), ClipOptions(stopTitles = false))
        val lisbon = one.labels.first { it.stops.containsAll(listOf("se", "castle", "tram")) }
        assertEquals(listOf("church", "views", "castle", "food"), one.tags[lisbon.key])
        // Every key with tags is a place name in the clip.
        assertTrue(one.labels.map { it.key }.containsAll(one.tags.keys))
    }

    @Test fun aRecordingOrSavedStopsHaveNone() {
        val t0 = day.atTime(9, 0).atZone(lisbonZone).toInstant().toEpochMilli()
        val walk = ClipInput(
            "Walk", day, day, lisbonZone, (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 38.71 + m * 0.0004, -9.13 + m * 0.0003, 8f) },
            emptyList(), emptyList(),
        )
        assertTrue(build(walk, ClipOptions()).tags.isEmpty())
        assertTrue(ClipTags.byPlace(listOf("label:day:x" to emptyList()), emptyList()).isEmpty())
    }

    @Test fun atMostEightTagsAndTheSameSpotKeepsBothItems() {
        val waypoints = listOf(
            PlannedWaypoint("a", "A", 0.0, 0.0, null, tags = (1..6).map { "a$it" }),
            PlannedWaypoint("b", "B", 0.0, 0.0, null, tags = listOf("a1") + (1..6).map { "b$it" }),
        )
        val tags = ClipTags.byPlace(listOf("k" to listOf("a", "b")), waypoints).getValue("k")
        assertEquals(ClipTags.MAX, tags.size)
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5", "a6", "b1", "b2"), tags)
        // Two items at the same spot are one stop: its tags are both items'.
        val twice = items.take(2) + item("se2", "Cathedral cloister", 38.7099, -9.1334, 0, 2, tags = "cloister")
        val stop = PlannedReplay.build(trip, twice).waypoints.single { it.itemId == "se" }
        assertEquals(listOf("church", "views", "cloister"), stop.tags)
    }

    @Test fun tagsShowUnlessSwitchedOffForTheClipOrThePlace() {
        val d = ClipDesign()
        assertTrue(d.tagsShown("k"))
        assertFalse(d.copy(tags = false).tagsShown("k"))
        // A place's own switch wins either way.
        val off = d.withEdits(ClipEdits(mapOf("k" to SceneOverride(tagsHidden = true))))
        assertFalse(off.tagsShown("k"))
        assertTrue(off.tagsShown("other"))
        val on = d.copy(tags = false).withEdits(ClipEdits(mapOf("k" to SceneOverride(tagsHidden = false))))
        assertTrue(on.tagsShown("k"))
        // Colour and size: the place's own, else the clip's.
        val look = d.copy(tagColor = TagColor.Sea, tagScale = 1.4f).withEdits(ClipEdits(mapOf("k" to SceneOverride(tagColor = TagColor.Pink))))
        assertEquals(TagColor.Pink, look.tagColor("k"))
        assertEquals(TagColor.Sea, look.tagColor("other"))
        assertEquals(1.4f, look.tagScale("k"), 1e-6f)
        // The clip's choices are remembered; a style from before them shows the tags in the stickers' own look.
        val kept = ClipPrefs.decode(ClipPrefs.encode(d.copy(tags = false, tagColor = TagColor.Coral, tagScale = 1.4f)))
        assertFalse(kept.tags)
        assertEquals(TagColor.Coral, kept.tagColor)
        assertEquals(1.4f, kept.tagScale, 1e-6f)
        val older = ClipPrefs.decode("photos=auto;camera=steady;tagScale=9")
        assertTrue(older.tags)
        assertEquals(TagColor.DEFAULT, older.tagColor)
        assertEquals(1f, older.tagScale, 0f)
    }

    @Test fun tagsPickedInTheClipBeforeStillLoadButAreNotShown() {
        val key = "label:item:pena"
        val saved = """{"v":1,"sources":{"Planned":{"scenes":{"$key":{"tags":["sushi"],"tagColor":"coral"}}}}}"""
        val edits = ClipEditsJson.decode(saved).getValue("Planned")
        // Kept as they were, and written back.
        assertEquals(listOf("sushi"), edits.scene(key).tags)
        assertTrue(ClipEditsJson.encode(mapOf("Planned" to edits)).contains("sushi"))
        // The place shows its plan item's tags, not those.
        val design = ClipDesign().withEdits(edits)
        val p = build(planned(), design.applyTo(ClipOptions(stopTitles = true)))
        assertEquals(listOf("palace", "views"), p.tags[key])
        assertEquals(TagColor.Coral, design.tagColor(key))
    }
}

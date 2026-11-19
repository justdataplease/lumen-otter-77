package com.justdataplease.boringtravel.tags

import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipEditsJson
import com.justdataplease.boringtravel.clip.ClipTags
import com.justdataplease.boringtravel.clip.TagColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The owner's tag tree (2026-11-12) and the scenes' tags. */
class TagTreeTest {
    private val tree = TagTree.parse(File("src/main/assets/tags/tags.json").readText())

    @Test fun theOwnersListIsThereWholeAndEachTagOnce() {
        assertEquals(9, tree.categories.size)
        assertEquals("Seasons & Events", tree.categories.first().name)
        assertEquals("Sports & Wellness", tree.categories.last().name)
        assertEquals(111, tree.categories.sumOf { it.subs.size })
        assertEquals(650, tree.all.size)
        assertEquals(tree.all.size, tree.all.map { it.tag }.toSet().size)
        assertEquals("Food & Drink › Italian", tree.all.single { it.tag == "pizza" }.path)
        assertTrue(tree.all.all { it.tag.matches(Regex("[a-z0-9]+")) })
    }

    @Test fun typingSuggestsTheTagThenOnesStartingWithItThenOnesWithItInside() {
        assertEquals("sushi", tree.suggest("sushi").first().tag)
        val sus = tree.suggest("#Sus").map { it.tag }
        assertTrue(sus.containsAll(listOf("sushi", "sustainabletravel")))
        // A subcategory's name brings its tags.
        val italian = tree.suggest("italian").map { it.tag }
        assertTrue(italian.containsAll(listOf("pizza", "pasta")))
        // Taken ones are left out; at most the limit.
        assertTrue("hiking" !in tree.suggest("hik", taken = listOf("hiking")).map { it.tag })
        assertEquals(3, tree.suggest("a", limit = 3).size)
        assertEquals(emptyList<TagHit>(), tree.suggest("  # "))
    }

    @Test fun tagsAreKeptPlain() {
        assertEquals("roadtrip", TagTree.normalize(" #Road Trip! "))
        assertNull(TagTree.normalize("#"))
        assertEquals(listOf("a", "b"), ClipTags.clean(listOf("#a", "A", "b", "")))
        assertEquals(ClipTags.MAX, ClipTags.clean((1..20).map { "t$it" }).size)
    }

    @Test fun aScenesTagsColourSizeAndSpotAreKept() {
        val key = "label:item:x"
        val edits = ClipEdits().change(key) { it.copy(tags = listOf("sushi", "nightlife"), tagColor = TagColor.Coral, tagScale = 1.4f, tagX = 0.3f, tagY = 0.7f) }
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("planned" to edits))).getValue("planned").scene(key)
        assertEquals(listOf("sushi", "nightlife"), back.tags)
        assertEquals(TagColor.Coral, back.tagColor)
        assertEquals(1.4f, back.tagScale!!, 1e-6f)
        assertEquals(0.3f, back.tagX!!, 1e-6f)
        assertEquals(0.7f, back.tagY!!, 1e-6f)
        // Odd values are ignored, tags cleaned.
        val bad = ClipEditsJson.decode("""{"v":1,"sources":{"planned":{"scenes":{"$key":{"tags":["#A b","",7],"tagScale":9,"tagColor":"nope","tagX":2}}}}}""")
            .getValue("planned").scene(key)
        assertEquals(listOf("ab"), bad.tags)
        assertNull(bad.tagScale); assertNull(bad.tagColor); assertNull(bad.tagX)
        assertEquals(listOf("sushi", "nightlife"), ClipDesign(overrides = mapOf(key to back)).scene(key).tags)
        // Switched off: kept, not shown; on by default.
        assertNull(back.tagsHidden)
        val hidden = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("planned" to edits.change(key) { it.copy(tagsHidden = true) }))).getValue("planned").scene(key)
        assertEquals(true, hidden.tagsHidden)
        assertEquals(listOf("sushi", "nightlife"), hidden.tags)
    }
}

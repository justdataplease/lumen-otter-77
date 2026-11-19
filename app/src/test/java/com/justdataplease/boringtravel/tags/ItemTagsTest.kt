package com.justdataplease.boringtravel.tags

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemTagsTest {
    @Test fun storedTagsComeBackInOrderEachOnce() {
        assertEquals(listOf("pizza", "streetfood", "sunset"), ItemTags.parse("pizza streetfood sunset pizza"))
        assertEquals(emptyList<String>(), ItemTags.parse(null))
        assertEquals(emptyList<String>(), ItemTags.parse("   "))
    }

    @Test fun whatIsNotATagIsDropped() {
        assertEquals(listOf("pizza", "streetfood", "caf\u00e9"), ItemTags.parse("#Pizza,  street-food\n!!! Caf\u00e9"))
        assertEquals("pizza streetfood", ItemTags.tidy(" #Pizza   street-food "))
        assertNull(ItemTags.tidy("# ! ?"))
    }

    @Test fun noMoreThanTheMost() {
        val many = (1..20).map { "tag$it" }
        assertEquals(ItemTags.MAX, ItemTags.parse(many.joinToString(" ")).size)
        assertEquals(ItemTags.MAX, ItemTags.format(many)!!.split(' ').size)
        assertEquals(many.take(ItemTags.MAX), ItemTags.add(many.take(ItemTags.MAX), "onemore"))
    }

    @Test fun addingKeepsTheOrderAndSkipsWhatIsThere() {
        assertEquals(listOf("pizza", "sunset"), ItemTags.add(listOf("pizza"), "#Sunset"))
        assertEquals(listOf("pizza"), ItemTags.add(listOf("pizza"), "PIZZA"))
        assertEquals(listOf("pizza"), ItemTags.add(listOf("pizza"), "  "))
    }

    @Test fun anItemsTagsAndTheirLine() {
        val item = PlanItem(id = "p1", tripId = "t1", title = "Da Michele", createdAt = 1, updatedAt = 1, tags = "pizza streetfood")
        assertEquals(listOf("pizza", "streetfood"), ItemTags.of(item))
        assertEquals("#pizza #streetfood", ItemTags.line(ItemTags.of(item)))
        assertNull(ItemTags.format(emptyList()))
        assertEquals(emptyList<String>(), ItemTags.of(item.copy(tags = null)))
    }
}

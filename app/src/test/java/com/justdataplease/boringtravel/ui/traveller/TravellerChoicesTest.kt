package com.justdataplease.boringtravel.ui.traveller

import com.justdataplease.boringtravel.clip.BottomStyle
import com.justdataplease.boringtravel.clip.Cloth
import com.justdataplease.boringtravel.clip.FacialHair
import com.justdataplease.boringtravel.clip.HairStyle
import com.justdataplease.boringtravel.clip.Hat
import com.justdataplease.boringtravel.clip.Person
import com.justdataplease.boringtravel.clip.SkinTone
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows Me › Your traveller and the editor's Traveller tool show for a person. */
class TravellerChoicesTest {
    private fun keys(p: Person) = TravellerChoices.rows(p).map { it.key }

    @Test fun rowsThatDontApplyAreLeftOut() {
        assertEquals(
            listOf("skin", "hair", "face", "hairColour", "top", "topColour", "bottom", "bottomColour", "hat", "glasses", "bag"),
            keys(Person.MAN),
        )
        // A dress takes the top's colour: no bottom colour, and the top's row says so.
        val dress = TravellerChoices.rows(Person.WOMAN)
        assertFalse("bottomColour" in dress.map { it.key })
        assertEquals("Dress colour", dress.first { it.key == "topColour" }.label)
        // A hat brings its colour; bald with no facial hair has no hair colour.
        assertTrue("hatColour" in keys(Person.MAN.copy(hat = Hat.Beanie)))
        assertFalse("hairColour" in keys(Person.MAN.copy(hair = HairStyle.Bald, facialHair = FacialHair.None)))
        assertTrue("hairColour" in keys(Person.MAN.copy(hair = HairStyle.Bald, facialHair = FacialHair.Beard)))
    }

    @Test fun everyRowShowsItsChoiceAndPicksChangeOnlyThatOption() {
        val p = Person.MAN.copy(hat = Hat.Cap)
        for (row in TravellerChoices.rows(p)) {
            assertTrue(row.key, row.selected in row.choices.indices)
            assertEquals(row.key, row.swatches, row.choices.all { it.argb != null })
            for (i in row.choices.indices) {
                val q = row.pick(p, i)
                // Picking a row's own choice again changes nothing; the row then shows the pick.
                if (i == row.selected) assertEquals(p, q)
                val again = TravellerChoices.rows(q).firstOrNull { it.key == row.key }
                if (again != null) assertEquals(row.key, i, again.selected)
            }
            // Off the row: nothing.
            assertEquals(p, row.pick(p, -1))
            assertEquals(p, row.pick(p, row.choices.size))
        }
        val skin = TravellerChoices.rows(p).first { it.key == "skin" }
        assertEquals(SkinTone.entries.size, skin.choices.size)
        assertEquals(SkinTone.T8, skin.pick(p, SkinTone.entries.lastIndex).skin)
        val bottom = TravellerChoices.rows(p).first { it.key == "bottom" }
        assertEquals(BottomStyle.Skirt, bottom.pick(p, BottomStyle.Skirt.ordinal).bottom)
        val top = TravellerChoices.rows(p).first { it.key == "topColour" }
        assertEquals(Cloth.entries.map { it.argb }, top.choices.map { it.argb })
    }

    @Test fun aCoupleEditsTheOnePickedOthersTheOneShown() {
        val t = Traveller(Who.Couple)
        assertEquals(Who.Man, TravellerChoices.slot(t, Who.Man))
        assertEquals(Who.Woman, TravellerChoices.slot(t, Who.Woman))
        assertEquals(Who.Man, TravellerChoices.slot(t, Who.Couple))
        assertEquals(Who.Woman, TravellerChoices.slot(t.copy(who = Who.Woman), Who.Man))
        assertEquals(Who.Man, TravellerChoices.slot(t.copy(who = Who.Man), Who.Woman))
        assertEquals("Couple, hand in hand", TravellerChoices.summary(t))
    }
}

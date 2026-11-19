package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A post about several places as a note per place: cut where the places begin, nothing lost, nothing said twice. */
class SitePartsTest {
    private val at = Instant.parse("2019-05-04T10:00:00Z")

    private fun text(s: String) = SiteBlock.Text(s)
    private fun found(name: String, part: Int? = null, area: String? = "Lisbon, Portugal") = SiteParts.Found(SiteSpot(name, area), part)

    /** "Three days in Lisbon": words before the first place, three places under their headings, pictures, a divider. */
    private val lisbon = SitePost(
        "7", "https://blog.example.com/lisbon", "Three days in Lisbon", at,
        listOf(
            text("We spent three days in <b>Lisbon</b>."),                     // part 1
            SiteBlock.Picture("https://blog.example.com/a.jpg", "The river"),
            SiteBlock.Heading("Belém Tower", 2),                               // part 2
            text("Take tram 15."),                                             // part 3
            SiteBlock.Picture("https://blog.example.com/b.jpg"),
            SiteBlock.Divider,
            SiteBlock.Heading("Alfama", 2),                                    // part 4
            text("Get lost in the lanes."),                                    // part 5
            SiteBlock.Quote("Fado at night."),                                 // part 6
            text("LX Factory is for Sunday."),                                 // part 7
            text("Go for the market."),                                        // part 8
        ),
        tags = listOf("lisbon", "portugal"),
    )

    @Test fun thePartsAreTheBlocksWithWords() {
        assertEquals(listOf(0, 2, 3, 6, 7, 8, 9, 10), SiteParts.listed(lisbon))
        assertEquals("We spent three days in Lisbon.", SiteParts.words(lisbon.blocks[0]))
        assertNull(SiteParts.words(SiteBlock.Divider))
        assertNull(SiteParts.words(SiteBlock.Picture("https://blog.example.com/a.jpg", "A caption")))
        assertNull(SiteParts.words(text("  <br> ")))
        // A post of hundreds of paragraphs lists the first 120.
        val long = lisbon.copy(blocks = (1..300).map { text("Paragraph $it") })
        assertEquals(SiteParts.MAX_LISTED, SiteParts.listed(long).size)
    }

    @Test fun aNotePerPlaceWhereThePlacesBegin() {
        val notes = SiteParts.cut(lisbon, listOf(found("Lisbon", area = "Portugal"), found("Belém Tower", 2), found("Alfama", 4), found("LX Factory", 7)))
        assertEquals(listOf("Three days in Lisbon", "Belém Tower", "Alfama", "Three days in Lisbon"), notes.map { it.post.title })
        assertEquals(listOf("Lisbon", "Belém Tower", "Alfama", "LX Factory"), notes.map { it.pin?.name })
        assertEquals(listOf("Portugal", "Lisbon, Portugal", "Lisbon, Portugal", "Lisbon, Portugal"), notes.map { it.pin?.area })
        assertTrue(notes.all { it.also.isEmpty() })
        // The words before the first place are a note of their own; a heading that titles its note isn't said twice;
        // the divider between two places goes; pictures stay where they were.
        assertEquals(listOf(lisbon.blocks[0], lisbon.blocks[1]), notes[0].post.blocks)
        assertEquals(listOf(lisbon.blocks[3], lisbon.blocks[4]), notes[1].post.blocks)
        assertEquals(listOf(lisbon.blocks[7], lisbon.blocks[8]), notes[2].post.blocks)
        assertEquals(listOf(lisbon.blocks[9], lisbon.blocks[10]), notes[3].post.blocks)
        // In the post's order a minute apart; the tags close the last note.
        assertEquals(listOf(0L, 60L, 120L, 180L), notes.map { it.post.at!!.epochSecond - at.epochSecond })
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), listOf("lisbon", "portugal")), notes.map { it.post.tags })
        // The last note keeps the post's own id, so the post counts as copied once all its notes are; the others are
        // named after their place, the same on every copy.
        assertEquals(listOf("place:lisbon", "place:belém tower", "place:alfama", null), notes.map { it.post.part })
        assertEquals(SiteNotes.entryId(lisbon), SiteNotes.entryId(notes.last().post))
        assertEquals(4, notes.map { SiteNotes.entryId(it.post) }.toSet().size)
        assertEquals(4, notes.map { it.post.id }.toSet().size)
        assertEquals(notes, SiteParts.cut(lisbon, listOf(found("Lisbon", area = "Portugal"), found("Belém Tower", 2), found("Alfama", 4), found("LX Factory", 7))))
    }

    @Test fun everyWordAndPictureIsInExactlyOneNote() {
        val notes = SiteParts.cut(lisbon, listOf(found("Belém Tower", 2), found("Alfama", 4), found("LX Factory", 7)))
        val kept = notes.flatMap { n -> listOfNotNull(n.post.title.takeIf { it != lisbon.title }?.let { SiteBlock.Heading(it, 2) }) + n.post.blocks }
        assertEquals(lisbon.blocks.filter { it !is SiteBlock.Divider }, kept)
    }

    @Test fun aPostThatCannotBeSeparatedStaysOneNoteWithAllItsPlaces() {
        // No parts given, or only one place with a part: one note, placed at the first place.
        for (places in listOf(
            listOf(found("Belém Tower"), found("Alfama"), found("LX Factory")),
            listOf(found("Belém Tower", 2), found("Alfama"), found("LX Factory")),
            listOf(found("Belém Tower", 2), found("Alfama", 2), found("LX Factory", 99)),
        )) {
            val note = SiteParts.cut(lisbon, places).single()
            assertEquals(lisbon, note.post)
            assertEquals("Belém Tower", note.pin?.name)
            assertEquals(listOf("Alfama", "LX Factory"), note.also.map { it.name })
        }
        // No places at all: the post as it is.
        assertEquals(listOf(SiteParts.Part(lisbon)), SiteParts.cut(lisbon, emptyList()))
    }

    @Test fun placesThatBeginTogetherShareTheirNote() {
        val notes = SiteParts.cut(lisbon, listOf(found("Belém Tower", 2), found("Pastéis de Belém", 2), found("Alfama", 4)))
        assertEquals(listOf(null, "Belém Tower", "Alfama"), notes.map { it.pin?.name })
        assertEquals(listOf("Pastéis de Belém"), notes[1].also.map { it.name })
        // The words before the first place have no place of their own here.
        assertEquals("start", notes[0].post.part)
        assertTrue(notes[0].also.isEmpty())
    }

    @Test fun aPartThatIsNotThereGivesNoCut() {
        val notes = SiteParts.cut(lisbon, listOf(found("Belém Tower", 2), found("Alfama", 4), found("Nowhere", 0), found("Elsewhere", -3), found("Far", 500)))
        // The three without a real part, named nowhere in the post: with its first note.
        assertEquals(listOf("Nowhere", "Belém Tower", "Alfama"), notes.map { it.pin?.name })
        assertEquals(listOf("Elsewhere", "Far"), notes[0].also.map { it.name })
    }

    @Test fun aPlaceWithoutAStretchGoesWithTheNoteThatNamesIt() {
        // "LX Factory" and "the market" are named in Alfama's stretch here, "Lisbon" in the opening words, "tram 15"
        // under Belém Tower: each goes where it is written, and places a note only when the note has no place yet.
        val notes = SiteParts.cut(lisbon, listOf(found("lisbon"), found("Belém Tower", 2), found("Tram 15"), found("Alfama", 4), found("LX Factory"), found("the market")))
        assertEquals(listOf("lisbon", "Belém Tower", "Alfama"), notes.map { it.pin?.name })
        assertEquals(listOf(emptyList(), listOf("Tram 15"), listOf("LX Factory", "the market")), notes.map { n -> n.also.map { it.name } })
        // Closing words that name where they stayed are placed there, not the opening ones.
        val post = lisbon.copy(blocks = lisbon.blocks + listOf(SiteBlock.Heading("Where we stayed", 2), text("A flat near Graça.")))
        val stayed = SiteParts.cut(post, listOf(found("Graça"), found("Belém Tower", 2), found("Alfama", 4).copy(last = 8)))
        assertEquals(listOf(null, "Belém Tower", "Alfama", "Graça"), stayed.map { it.pin?.name })
        assertEquals("Where we stayed", stayed.last().post.title)
    }

    @Test fun withoutWordsBeforeTheFirstPlaceTheLoosePlacesGoWithIt() {
        val post = lisbon.copy(blocks = listOf(SiteBlock.Divider) + lisbon.blocks.drop(2))
        val notes = SiteParts.cut(post, listOf(found("Porto"), found("Belém Tower", 1), found("Alfama", 3)))
        assertEquals(listOf("Belém Tower", "Alfama"), notes.map { it.pin?.name })
        assertEquals(listOf("Porto"), notes[0].also.map { it.name })
        assertEquals(listOf("place:belém tower", null), notes.map { it.post.part })
    }

    @Test fun aPlaceCanBeginAtAParagraph() {
        // No heading there: the note keeps the post's title, and its paragraph.
        val notes = SiteParts.cut(lisbon, listOf(found("Alfama", 6), found("LX Factory", 7)))
        assertEquals(listOf("Three days in Lisbon", "Three days in Lisbon", "Three days in Lisbon"), notes.map { it.post.title })
        assertEquals(lisbon.blocks.subList(0, 8), notes[0].post.blocks)
        assertEquals(listOf(lisbon.blocks[8]), notes[1].post.blocks)
        assertEquals(listOf(lisbon.blocks[9], lisbon.blocks[10]), notes[2].post.blocks)
    }

    @Test fun whatFollowsAPlacesLastPartIsANoteOfItsOwn() {
        // "Getting around" after Belém Tower and the closing lines after LX Factory are about no place.
        val post = lisbon.copy(
            blocks = lisbon.blocks.subList(0, 5) + listOf(SiteBlock.Heading("Getting around", 2), text("Buy a day ticket.")) +   // parts 4, 5
                lisbon.blocks.subList(5, 9) +                                                                                   // divider, Alfama 6, 7, quote 8
                listOf(text("LX Factory is for Sunday."), SiteBlock.Picture("https://blog.example.com/c.jpg"), text("That was Lisbon. Next week: Porto.")), // 9, 10
        )
        val notes = SiteParts.cut(post, listOf(found("Belém Tower", 2).copy(last = 3), found("Alfama", 6).copy(last = 8), found("LX Factory", 9).copy(last = 9)))
        assertEquals(listOf(null, "Belém Tower", null, "Alfama", "LX Factory", null), notes.map { it.pin?.name })
        assertEquals(listOf("Three days in Lisbon", "Belém Tower", "Getting around", "Alfama", "Three days in Lisbon", "Three days in Lisbon"), notes.map { it.post.title })
        assertEquals(listOf("start", "place:belém tower", "rest", "place:alfama", "place:lx factory", null), notes.map { it.post.part })
        // A picture after a place's last words stays with the place; the closing lines are the post's last note.
        assertEquals(listOf(text("LX Factory is for Sunday."), SiteBlock.Picture("https://blog.example.com/c.jpg")), notes[4].post.blocks)
        assertEquals(listOf(text("That was Lisbon. Next week: Porto.")), notes[5].post.blocks)
        assertEquals(listOf(text("Buy a day ticket.")), notes[2].post.blocks)
        assertEquals(listOf("lisbon", "portugal"), notes[5].post.tags)
        val kept = notes.flatMap { n -> listOfNotNull(n.post.title.takeIf { it != post.title }?.let { SiteBlock.Heading(it, 2) }) + n.post.blocks }
        assertEquals(post.blocks.filter { it !is SiteBlock.Divider }, kept)
        // A last part before the place's own part, at the next place or past the post's end changes nothing.
        val plain = SiteParts.cut(lisbon, listOf(found("Belém Tower", 2), found("Alfama", 4), found("LX Factory", 7)))
        assertEquals(plain, SiteParts.cut(lisbon, listOf(found("Belém Tower", 2).copy(last = 1), found("Alfama", 4).copy(last = 6), found("LX Factory", 7).copy(last = 400))))
    }

    @Test fun aPlaceToldUnderAHeadingBeginsAtTheHeading() {
        // The model pointed at the paragraphs under the headings (parts 3 and 5): the headings still title their notes,
        // and none is left closing the note before or standing alone.
        val notes = SiteParts.cut(lisbon, listOf(found("Belém Tower", 3).copy(last = 3), found("Alfama", 5), found("LX Factory", 7)))
        assertEquals(SiteParts.cut(lisbon, listOf(found("Belém Tower", 2), found("Alfama", 4), found("LX Factory", 7))), notes)
        assertEquals(listOf("Three days in Lisbon", "Belém Tower", "Alfama", "Three days in Lisbon"), notes.map { it.post.title })
        // Two headings one above the other: the outer one titles the note, the inner one stays in it.
        val nested = lisbon.copy(blocks = listOf(text("Hello."), SiteBlock.Heading("Food", 2), SiteBlock.Heading("Time Out Market", 3), text("Busy."), SiteBlock.Heading("Alfama", 2), text("Lanes.")))
        val food = SiteParts.cut(nested, listOf(found("Time Out Market", 3), found("Alfama", 5)))
        assertEquals(listOf("Three days in Lisbon", "Food", "Alfama"), food.map { it.post.title })
        assertEquals(listOf(SiteBlock.Heading("Time Out Market", 3), text("Busy.")), food[1].post.blocks)
    }

    @Test fun aSectionOfADocumentKeepsItsOwnName() {
        // A Google Doc section ("day 3", SiteSections) about two places: its last note keeps the section's id.
        val section = lisbon.copy(part = "day 3")
        val notes = SiteParts.cut(section, listOf(found("Belém Tower", 2), found("Alfama", 4)))
        assertEquals(listOf("day 3/start", "day 3/place:belém tower", "day 3"), notes.map { it.post.part })
        assertEquals(SiteNotes.entryId(section), SiteNotes.entryId(notes.last().post))
    }

    @Test fun twoStretchesAtTheSamePlaceGetTwoNames() {
        val notes = SiteParts.cut(lisbon, listOf(SiteParts.Found(SiteSpot("Alfama"), 2), SiteParts.Found(SiteSpot("alfama "), 4), found("LX Factory", 7)))
        assertEquals(listOf("start", "place:alfama", "place:alfama#2", null), notes.map { it.post.part })
    }

    @Test fun anUndatedPostStaysUndated() {
        val notes = SiteParts.cut(lisbon.copy(at = null), listOf(found("Belém Tower", 2), found("Alfama", 4)))
        assertTrue(notes.all { it.post.at == null })
    }
}

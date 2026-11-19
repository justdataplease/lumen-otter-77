package com.justdataplease.boringtravel.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMailTest {
    private val trip = "3f1c2a9e-5b7d-4e21-9a0c-2d8f6b1e7a44"

    private fun shared(
        members: List<Member> = emptyList(),
        fileId: String? = "fileMe01",
        listed: Boolean = false,
        unreachable: List<String> = emptyList(),
        mailed: String? = null,
        mailedAt: Long = 0,
    ) = SharedTrip(trip, me = "me", fileId = fileId, members = members, listed = listed, unreachable = unreachable, mailed = mailed, mailedAt = mailedAt)

    @Test fun theBoxIsAFixedHashOfTheTripId() {
        // The rules tests (firebase/test/mailbox.js) and every app version must agree on it: it never changes.
        assertEquals("d63e872a66a40fe4c17488921b326a22653c620a1a0a09fdfa852da883cc30b7", TripMail.box(trip))
        assertNotEquals(TripMail.box(trip), TripMail.box("4f1c2a9e-5b7d-4e21-9a0c-2d8f6b1e7a44"))
        assertTrue(TripMail.box(trip)!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun aShortOrOddTripIdHasNoBox() {
        assertNull(TripMail.box("t"))
        assertNull(TripMail.box("demo-trip-1"))
        assertNull(TripMail.box("3f1c2a9e 5b7d 4e21 9a0c 2d8f6b1e7a44"))
        assertNull(TripMail.box(""))
    }

    @Test fun onlyEntriesTheAppWritesAreRead() {
        assertEquals(MailEntry("uidA", "m-1", "fileA1"), TripMail.parse("uidA", "m-1", "fileA1"))
        assertNull(TripMail.parse("uidA", null, "fileA1"))
        assertNull(TripMail.parse("uidA", "m-1", 12345678L))
        assertNull(TripMail.parse("uidA", "m 1", "fileA1"))
        assertNull(TripMail.parse("uidA", "m-1", "abc"))
        assertNull(TripMail.parse("uidA", "m-1", "file/A1"))
        assertNull(TripMail.parse("uid A", "m-1", "fileA1"))
    }

    @Test fun postedNamesTheAccountAndTheCopy() {
        assertTrue(TripMail.isPosted(shared(mailed = TripMail.posted("uidA", "fileMe01"))))
        // Posted for an older copy (its folder was deleted in Drive): not posted now.
        assertFalse(TripMail.isPosted(shared(mailed = TripMail.posted("uidA", "fileOld01"))))
        assertFalse(TripMail.isPosted(shared(mailed = null)))
        assertFalse(TripMail.isPosted(shared(fileId = null, mailed = TripMail.posted("uidA", "fileMe01"))))
    }

    @Test fun newcomersAreTheMembersThisPhoneDoesNotKnow() {
        val s = shared(members = listOf(Member("ana", "Ana", "fileAna1")))
        val entries = listOf(
            MailEntry("u0", "me", "fileMe01"),
            MailEntry("u1", "ana", "fileAna1"),
            MailEntry("u2", "bo", "fileBo01"),
        )
        assertEquals(listOf(MailEntry("u2", "bo", "fileBo01")), TripMail.newcomers(s, entries, emptySet()))
    }

    @Test fun aKnownMemberMovesOnlyWhenTheirCopyIsLost() {
        val entries = listOf(MailEntry("u1", "ana", "fileAna2"))
        // Ana's copy still reads: someone else's entry under her id doesn't move her.
        assertTrue(TripMail.newcomers(shared(members = listOf(Member("ana", "Ana", "fileAna1"))), entries, emptySet()).isEmpty())
        // Her copy is gone, she left (and joined again), or she never had one: the new address is read.
        assertEquals(entries, TripMail.newcomers(shared(members = listOf(Member("ana", "Ana", "fileAna1")), unreachable = listOf("ana")), entries, emptySet()))
        assertEquals(entries, TripMail.newcomers(shared(members = listOf(Member("ana", "Ana", "fileAna1", left = true))), entries, emptySet()))
        assertEquals(entries, TripMail.newcomers(shared(members = listOf(Member("ana", "Ana", null))), entries, emptySet()))
        // The same address again is nothing new.
        assertTrue(TripMail.newcomers(shared(members = listOf(Member("ana", "Ana", "fileAna2", left = true))), entries, emptySet()).isEmpty())
    }

    @Test fun refusedEntriesAndRepeatsAreLeftOut() {
        val bad = MailEntry("u2", "bo", "fileFake1")
        val good = MailEntry("u3", "bo", "fileBo001")
        val refused = setOf(TripMail.key(trip, bad))
        assertEquals(listOf(good), TripMail.newcomers(shared(), listOf(bad, good), refused))
        // One entry per member: the first.
        assertEquals(listOf(bad), TripMail.newcomers(shared(), listOf(bad, good), emptySet()))
    }

    @Test fun neverMoreNewMembersThanTheTripTakes() {
        val members = (1..9).map { Member("m$it", "M$it", "fileM000$it") }
        val entries = (1..5).map { MailEntry("u$it", "new$it", "fileNew0$it") } + MailEntry("u9", "m9", "fileM9new")
        val unreachable = listOf("m9")
        val picked = TripMail.newcomers(shared(members = members, unreachable = unreachable), entries, emptySet())
        // 12 people at most: me and 9 known leave room for 2 new; a known member at a new address always fits.
        assertEquals(listOf("m9", "new1", "new2"), picked.map { it.member })
    }

    @Test fun theLinkIsOfferedOnlyWhenTheMailboxCannotCarryIt() {
        val ana = Member("ana", "Ana", "fileAna1")
        val posted = TripMail.posted("uidMe", "fileMe01")
        val now = 1_000_000L
        // No copy yet (Drive first), read by the others already, or nobody else in the trip: nothing to send.
        assertFalse(TripMail.offerLink(shared(members = listOf(ana), fileId = null), null, now))
        assertFalse(TripMail.offerLink(shared(members = listOf(ana), listed = true), null, now))
        assertFalse(TripMail.offerLink(shared(), null, now))
        assertFalse(TripMail.offerLink(shared(members = listOf(ana.copy(left = true))), null, now))
        // Not in the mailbox (not signed in, Firestore failed): the link at once.
        assertTrue(TripMail.offerLink(shared(members = listOf(ana)), setOf("ana"), now))
        // In it, but none of the others reads it (an older app, or not signed in).
        assertTrue(TripMail.offerLink(shared(members = listOf(ana), mailed = posted, mailedAt = now), setOf("me"), now))
        // In it with Ana there too: wait for her app to pick it up…
        assertFalse(TripMail.offerLink(shared(members = listOf(ana), mailed = posted, mailedAt = now), setOf("me", "ana"), now + 60_000))
        assertFalse(TripMail.offerLink(shared(members = listOf(ana), mailed = posted, mailedAt = now), null, now + 60_000))
        // …but not for ever.
        assertTrue(TripMail.offerLink(shared(members = listOf(ana), mailed = posted, mailedAt = now), setOf("me", "ana"), now + TripMail.READ_WAIT_MS + 1))
    }
}

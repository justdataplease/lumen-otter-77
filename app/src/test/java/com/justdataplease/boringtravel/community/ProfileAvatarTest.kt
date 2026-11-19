package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The community picture's look, view and framing (owner, 2026-11-12: "select avatar and the viewpoint"). */
class ProfileAvatarTest {
    @Test fun theViewAndFramingTravelWithTheTraveller() {
        val pic = ProfileAvatar(Traveller(Who.Couple), heading = 200f, full = true)
        val raw = pic.encode()
        assertTrue(raw.length <= CommunityLimits.AVATAR)
        assertEquals(pic, ProfileAvatar.decodeOrNull(raw))
        // An older app still reads the traveller.
        assertEquals(Who.Couple, Traveller.decodeOrNull(raw)!!.who)
    }

    @Test fun anOlderPictureIsTheThreeQuarterHeadAndShoulders() {
        val old = ProfileAvatar.decodeOrNull(Traveller(Who.Woman).encode())!!
        assertEquals(ProfileAvatar.DEFAULT_HEADING, old.heading, 0f)
        assertFalse(old.full)
        assertNull(ProfileAvatar.decodeOrNull(null))
        assertNull(ProfileAvatar.decodeOrNull("not a traveller"))
        assertEquals(10f, ProfileAvatar.normal(-350f), 1e-4f)
        assertEquals(ProfileAvatar.DEFAULT_HEADING, ProfileAvatar.decodeOrNull(Traveller().encode() + ";view=abc")!!.heading, 0f)
    }
}

package com.justdataplease.boringtravel.clip.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPrefsTest {
    @Test fun everyTrackAndNoneRoundTrip() {
        MusicCatalog.tracks.forEach { assertEquals(it.id, MusicPrefs.decode(MusicPrefs.encode(it.id))) }
        assertEquals(MusicPrefs.NONE, MusicPrefs.encode(null))
        assertNull(MusicPrefs.decode(MusicPrefs.encode(null)))
    }

    @Test fun nothingSavedOrAnOldTrackReadsAsNone() {
        assertNull(MusicPrefs.decode(null))
        assertNull(MusicPrefs.decode(""))
        assertNull(MusicPrefs.decode("removed_track"))
        assertEquals(MusicPrefs.NONE, MusicPrefs.encode("removed_track"))
    }

    @Test fun theClipOptionsCarryTheChoice() {
        val options = com.justdataplease.boringtravel.clip.ClipOptions()
        assertNull(options.music)
        assertEquals("caldera", options.copy(music = MusicPrefs.decode("caldera")).music)
    }

    @Test fun aSavedTrackFromTheOldSetFallsBackToNone() {
        // A phone that saved one of the dropped tracks gets a silent clip (None), and saving it again stores None.
        MusicCatalogTest.DROPPED.forEach {
            assertNull(it, MusicPrefs.decode(it))
            assertEquals(it, MusicPrefs.NONE, MusicPrefs.encode(it))
            assertNull(it, com.justdataplease.boringtravel.clip.ClipOptions(music = MusicPrefs.decode(it)).music)
        }
        // Latin Groove stayed, so that choice survives.
        assertEquals("latin_groove", MusicPrefs.decode("latin_groove"))
    }
}

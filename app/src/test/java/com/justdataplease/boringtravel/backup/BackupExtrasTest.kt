package com.justdataplease.boringtravel.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** extras/ names: what a restore writes where, and what it never touches. */
class BackupExtrasTest {
    @Test fun eachKindMapsToItsPlaceUnderTheAppsFiles() {
        assertEquals("clip-edits/t1.json", BackupExtras.target("extras/clip-edits/t1.json"))
        assertEquals("clip-voice/t_1-a/0b7c.m4a", BackupExtras.target("extras/clip-voice/t_1-a/0b7c.m4a"))
        assertEquals("clip-media/t1/5e1f-22.mp4", BackupExtras.target("extras/clip-media/t1/5e1f-22.mp4"))
        assertEquals("extras/clip-edits/t1.json", BackupExtras.clipEdits("t1.json"))
        assertEquals("extras/clip-media/t1/a.mp4", BackupExtras.clipFile(BackupExtras.CLIP_MEDIA, "t1", "a.mp4"))
        assertTrue(BackupExtras.isText("extras/clip-edits/t1.json"))
        assertFalse(BackupExtras.isText("extras/clip-media/t1/a.mp4"))
        assertFalse(BackupExtras.isText("media/a.jpg"))
    }

    @Test fun oddNamesAndUnknownKindsGoNowhere() {
        listOf(
            "extras/clip-edits/../x.json", "extras/clip-edits/a/b.json", "extras/clip-edits/t1.txt", "extras/clip-edits/.json",
            "extras/clip-media/t1/..", "extras/clip-media/t1/.hidden", "extras/clip-media/t 1/a.mp4", "extras/clip-media/t1/a/b.mp4",
            "extras/clip-media/t1", "extras/clip-voice/t1/a..b.m4a", "extras/clip-music/t1/a.mp3", "extras/../media/a.jpg",
            "media/a.jpg", "clip-edits/t1.json", "extras/clip-media/t1/a\\b.mp4",
        ).forEach { assertNull(it, BackupExtras.target(it)) }
        assertNull(BackupExtras.clipEdits("bad name.json"))
        assertNull(BackupExtras.clipFile(BackupExtras.CLIP_VOICE, "t1", "../a.m4a"))
        assertNull(BackupExtras.clipFile("clip-music", "t1", "a.mp3"))
    }

    @Test fun eachKindHasItsBound() {
        assertEquals(BackupExtras.MAX_EDITS_BYTES, BackupArchive.limit("extras/clip-edits/t1.json"))
        assertEquals(BackupExtras.MAX_VOICE_BYTES, BackupArchive.limit("extras/clip-voice/t1/a.m4a"))
        assertEquals(BackupExtras.MAX_VIDEO_BYTES, BackupArchive.limit("extras/clip-media/t1/a.mp4"))
        assertEquals(BackupExtras.MAX_VIDEO_BYTES, BackupArchive.limit("extras/later-kind/a.bin"))
        assertEquals(BackupArchive.MAX_PHOTO_BYTES, BackupArchive.limit("media/a.jpg"))
        assertEquals(BackupCodec.MAX_DATA_BYTES, BackupArchive.limit("data.json"))
    }
}

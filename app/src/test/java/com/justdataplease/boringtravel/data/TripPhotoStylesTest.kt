package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.settings.TripPhotoStyle
import com.justdataplease.boringtravel.data.settings.TripPhotoStyles
import com.justdataplease.boringtravel.doc.PhotoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each trip's defaults for new photos in its notes (Edit trip › Photos in notes). */
class TripPhotoStylesTest {
    private val medium = TripPhotoStyle(0.65f, PhotoFrame.Polaroid)

    @Test
    fun `a trip without its own style keeps what notes always did`() {
        val styles = TripPhotoStyles().with("t1", medium)
        assertEquals(medium, styles.of("t1"))
        assertEquals(TripPhotoStyle.Default, styles.of("t2"))
        assertEquals(TripPhotoStyle.Default, styles.of(null))
        assertEquals(TripPhotoStyle(1f, PhotoFrame.Rounded), TripPhotoStyle.Default)
    }

    @Test
    fun `a new trip starts from the last choice`() {
        assertEquals(TripPhotoStyle.Default, TripPhotoStyles().forForm(null))
        val styles = TripPhotoStyles().with("t1", medium)
        assertEquals(medium, styles.forForm(null))
        assertEquals(medium, styles.forForm("t1"))
        assertEquals(TripPhotoStyle.Default, styles.forForm("t2"))
    }

    @Test
    fun `the text reads back`() {
        val styles = TripPhotoStyles().with("t1", medium).with("9f0c-ab", TripPhotoStyle(0.45f, PhotoFrame.None))
        val text = styles.encode()
        assertEquals("t1=0.65|polaroid;9f0c-ab=0.45|none;*=0.45|none", text)
        assertEquals(styles, TripPhotoStyles.decode(text))
    }

    @Test
    fun `damaged parts are skipped`() {
        val styles = TripPhotoStyles.decode("t1=0.65|polaroid;;junk;t2=9|tape;t3=0.85|glitter;=0.5|none;t4=0.85|tape")
        assertEquals(setOf("t1", "t4"), styles.byTrip.keys)
        assertNull(styles.last)
        assertEquals(TripPhotoStyles(), TripPhotoStyles.decode(null))
        assertEquals(TripPhotoStyles(), TripPhotoStyles.decode("x".repeat(TripPhotoStyles.MAX_CHARS + 1)))
    }

    @Test
    fun `the oldest go when there are too many`() {
        var styles = TripPhotoStyles()
        repeat(TripPhotoStyles.MAX + 5) { styles = styles.with("t$it", medium) }
        assertEquals(TripPhotoStyles.MAX, styles.byTrip.size)
        assertEquals(TripPhotoStyle.Default, styles.of("t0"))
        assertEquals(medium, styles.of("t${TripPhotoStyles.MAX + 4}"))
    }

    @Test
    fun `a width set by hand shows as the nearest size`() {
        assertEquals("Medium", TripPhotoStyle(0.6f, PhotoFrame.None).size.label)
        assertEquals("Full", TripPhotoStyle.Default.size.label)
        assertEquals(listOf("Small", "Medium", "Large", "Full"), TripPhotoStyle.SIZES.map { it.label })
    }
}

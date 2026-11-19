package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.ui.theme.Look
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The trip video's picture on the desktop: the demo's Argentina made into a clip as its screen makes it, frames drawn by
 * the Android app's own renderer on Skia, and what must be in them (the globe's colours from space, the route drawn, the
 * end card's mark in the theme's yellow). The frames are also written to build/clip-frames for a look.
 */
class ClipPictureOnDesktopTest {
    private val out = File("build/clip-frames").apply { mkdirs() }

    @Test fun theDemoClipDrawsEveryMoment() {
        val (book, kit) = demoBook()
        withStudio(book, kit, SampleArgentina.TRIP_ID, Look.PostcardPop) { studio ->
            assertEquals(ClipStudio.Load.Ready, studio.load.value)
            assertEquals(ClipSource.Planned, studio.source.value)
            val plan = requireNotNull(studio.plan.value)
            // A first clip: the defaults (9:16, the Globe), and the globe is there without the network.
            assertEquals(ClipShape.Story, plan.options.shape)
            assertEquals(MapStyle.Globe, plan.options.map)
            assertTrue(studio.base.value.hasMap)
            val style = requireNotNull(studio.style.value)
            ClipFrames(plan, studio.base.value, style, studio.photos.value, studio.noMark, studio.design.value, 540, 960).use { frames ->
                val moments = listOfNotNull(
                    "start" to 0, "opening" to ClipMoments.opening(plan), "midway" to ClipMoments.midway(plan),
                    ClipMoments.label(plan, 2)?.let { "label" to it }, ClipMoments.photo(plan)?.let { "photo" to it },
                    "end" to ClipMoments.end(plan),
                )
                val pictures = moments.associate { (name, f) ->
                    val bmp = frames.draw(f)
                    File(out, "demo-$name.png").writeBytes(requireNotNull(bmp.png()))
                    name to bmp.png()!!.size
                }
                assertEquals(moments.size, pictures.size)
                // The end card: the brand mark's tile in the theme's yellow, centred above the title.
                val end = frames.draw(ClipMoments.end(plan))
                val marks = (240..400 step 4).count { y -> end.getPixel(270, y) == (0xFF000000.toInt() or (style.mark.sky and 0xFFFFFF)) }
                assertTrue(marks > 5, "the mark's yellow shows on the end card ($marks rows)")
                val endMiddle = end.getPixel(270, 480)
                assertNotEquals(frames.draw(0).getPixel(270, 480), endMiddle)
            }
        }
    }

    /**
     * A frame is a pure function of the clip and its number (what the export, round 20, relies on): drawn again after
     * others, or by another renderer, it is the same picture, pixel for pixel. And how long a frame takes on this PC,
     * for the export's planning (printed).
     */
    @Test fun aFrameIsTheSameWheneverItIsDrawn() {
        val (book, kit) = demoBook()
        withStudio(book, kit, SampleArgentina.TRIP_ID, Look.Market) { studio ->
            val plan = requireNotNull(studio.plan.value)
            val style = requireNotNull(studio.style.value)
            val f = ClipMoments.label(plan, 3) ?: ClipMoments.midway(plan)
            fun pixels(frames: ClipFrames, frame: Int): IntArray {
                val bmp = frames.draw(frame)
                return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            }
            ClipFrames(plan, studio.base.value, style, studio.photos.value, studio.noMark, studio.design.value, 540, 960).use { a ->
                ClipFrames(plan, studio.base.value, style, studio.photos.value, studio.noMark, studio.design.value, 540, 960).use { b ->
                    val first = pixels(a, f)
                    pixels(a, 0)
                    pixels(a, ClipMoments.end(plan))
                    assertTrue(first.contentEquals(pixels(a, f)), "the same renderer draws the frame again the same")
                    assertTrue(first.contentEquals(pixels(b, f)), "another renderer draws it the same")
                }
            }
            for ((w, h) in listOf(540 to 960, 1080 to 1920)) {
                ClipFrames(plan, studio.base.value, style, studio.photos.value, studio.noMark, studio.design.value, w, h).use { frames ->
                    frames.draw(0)
                    val step = plan.frameCount / 40
                    val t0 = System.nanoTime()
                    var n = 0
                    for (frame in 0 until plan.frameCount step step) {
                        frames.draw(frame)
                        n++
                    }
                    println("CLIP-TIMING ${w}x$h: ${(System.nanoTime() - t0) / 1_000_000 / n} ms a frame over $n frames (${plan.frameCount} in the clip)")
                }
            }
        }
    }

    /** compat's Random is java.util.Random: the film grain's specks are where Android puts them. */
    @Test fun javaRandomIsJavas() {
        val ours = com.justdataplease.boringtravel.compat.JavaRandom(7L)
        val theirs = java.util.Random(7L)
        repeat(20_000) { assertEquals(theirs.nextGaussian(), ours.nextGaussian(), 1e-12) }
        val ints = com.justdataplease.boringtravel.compat.JavaRandom(-3L)
        val javaInts = java.util.Random(-3L)
        repeat(1_000) { assertEquals(javaInts.nextInt(), ints.nextInt()) }
    }
}

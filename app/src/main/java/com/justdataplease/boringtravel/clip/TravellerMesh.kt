package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.Builder
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.V
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The traveller as a low-poly paper figure, built like the paper vehicles (x right, y forward, z up; faces folded from
 * rings, rods and boxes) with each part in the person's own colour: skin, hair, top, bottom, hat. Shoes and the
 * backpack take the theme's paper. Arms and legs hang on bones that swing with the [WalkCycle]; a couple walk side by
 * side in mirrored step, their inner hands joined and swinging together. Pure: the same mesh in the preview, the
 * export and the tests.
 */
internal object TravellerMesh {
    /** Every figure is scaled by this sphere, so a person is as tall in a couple as alone, whatever they wear. */
    const val RADIUS = 1.25f

    /** A couple stand this far apart, centre to centre (close enough to hold hands). */
    const val COUPLE_GAP = 0.84f

    /** How far each limb swings (degrees) at full stride; joined hands swing less. */
    const val ARM_SWING = 22f
    const val HELD_SWING = 9f
    const val LEG_SWING = 24f

    /** Where each of [count] people stands across (x), left to right. */
    fun offsets(count: Int): FloatArray = if (count < 2) floatArrayOf(0f) else floatArrayOf(-COUPLE_GAP / 2, COUPLE_GAP / 2)

    fun build(t: Traveller): PaperMesh {
        val b = Builder()
        val people = t.people
        val xs = offsets(people.size)
        people.forEachIndexed { i, p ->
            val couple = people.size > 1
            // The second person walks in mirrored step, so the joined (inner) arms swing the same way.
            val gait = if (i == 0) 1f else -1f
            val holds = if (!couple) 0 else if (i == 0) 1 else -1
            Figure(b, p, xs[i], gait, holds).build()
        }
        return b.build(RADIUS)
    }

    // ---------------------------------------------------------------- colours

    internal fun mix(a: Int, b: Int, f: Float): Int = PaperVehicle3D.Palette.mix(a, b, f)

    private const val INK = 0xFF2A2421.toInt()

    /** The head is widest up to here; hair keeps its full width to this height. */
    private const val CROWN = 2.06f

    /** The head's widest ring: the hair has a seam here too, so each band of the head sorts behind the hair over it. */
    private const val TEMPLES = 1.97f

    /** Under a skirt or dress the legs swing from here, just inside the hem (at .63). */
    private const val HEM_PIVOT = .74f

    /**
     * Long hair's curtain from the bottom up: height, width, depth, its middle (y). Round the head down to the
     * shoulders, then narrower and clear of the back.
     */
    private val CURTAIN = arrayOf(
        floatArrayOf(1.42f, .32f, .20f, -.115f), floatArrayOf(1.52f, .37f, .25f, -.10f), floatArrayOf(1.62f, .40f, .31f, -.06f),
        floatArrayOf(1.71f, .425f, .415f, -.01f), floatArrayOf(1.80f, .42f, .40f, -.01f), floatArrayOf(1.90f, .425f, .405f, -.01f),
        floatArrayOf(2.0f, .42f, .40f, -.01f),
    )

    /** How much nearer details on a surface sort ([PaperFace.lift]). */
    private const val DECAL_LIFT = .11f

    /** The torso's sections from the waist up: height, width, depth. */
    private val TORSO = arrayOf(
        floatArrayOf(1.08f, .40f, .25f), floatArrayOf(1.32f, .42f, .26f), floatArrayOf(1.56f, .49f, .29f),
        floatArrayOf(1.64f, .47f, .28f), floatArrayOf(1.70f, .30f, .21f),
    )
    private const val WHITE = 0xFFF7F3EA.toInt()

    /** One person, built at [ox] across. [gait] mirrors the step; [holds] is the side whose hand is held (0: none). */
    private class Figure(val b: Builder, val p: Person, val ox: Float, val gait: Float, val holds: Int) {
        val skin = p.skin.argb
        val hair = p.hairColour.argb
        val top = p.topColour.argb
        val bottom = if (p.dress) top else p.bottomColour.argb
        val hatColour = p.hatColour.argb

        /** The hair's outer size at the temples (a hat goes over it). */
        val capW = when (p.hair) {
            HairStyle.Curly -> .47f
            HairStyle.Bald -> .37f
            HairStyle.Buzz -> .375f
            else -> .395f
        }

        fun build() {
            legs()
            hips()
            torso()
            arms()
            neckAndHead()
            hair()
            face()
            glasses()
            hat()
            extras()
        }

        // ------------------------------------------------------------ helpers

        private fun paint(argb: Int) { b.color = argb }

        /** A theme material (shoes, bag): no own colour. */
        private fun theme() { b.color = 0 }

        private fun v(x: Float, y: Float, z: Float) = V(ox + x, y, z)

        /**
         * An ellipse of [n] points [w] wide and [d] deep around (x, y), each at the height [z] gives for its angle
         * (0 is to the right, π/2 ahead). A whole one starts so it has a flat front and back.
         */
        private fun ring(x: Float, y: Float, w: Float, d: Float, n: Int = 10, from: Double = PI / 2 - PI / n, to: Double = from + 2 * PI, closed: Boolean = true, z: (Double) -> Float): List<V> {
            val count = if (closed) n else n + 1
            val step = (to - from) / n
            return (0 until count).map { i ->
                val t = from + i * step
                v(x + (cos(t) * w / 2).toFloat(), y + (sin(t) * d / 2).toFloat(), z(t))
            }
        }

        private fun flat(x: Float, y: Float, z: Float, w: Float, d: Float, n: Int = 10) = ring(x, y, w, d, n) { z }

        /**
         * Quads between consecutive rings (no bottom: the camera is always above), facing out from the axis at
         * ([axisX], [axisY]) across; a [top] lid on the last ring when it is closed.
         */
        private fun shell(rings: List<List<V>>, axisX: Float = 0f, axisY: Float = 0f, closed: Boolean = true, top: Boolean = true) {
            val n = rings.first().size
            val segments = if (closed) n else n - 1
            for (k in 0 until rings.size - 1) for (i in 0 until segments) {
                val j = (i + 1) % n
                val q = listOf(rings[k][i], rings[k][j], rings[k + 1][j], rings[k + 1][i])
                val mid = (q[0] + q[1] + q[2] + q[3]) * .25f
                b.face(q, PaperMaterial.Paper, V(mid.x - (ox + axisX), mid.y - axisY, (rings[k + 1][i].z - rings[k][i].z) * .1f))
            }
            if (top && closed) b.face(rings.last(), PaperMaterial.Paper, V(0f, 0f, 1f))
        }

        private fun rod(a: V, c: V, r: Float, n: Int = 6) = b.rod(a, c, r, PaperMaterial.Paper, n)
        private fun box(x: Float, y: Float, z: Float, w: Float, l: Float, h: Float) = b.box(ox + x, y, z, w, l, h, PaperMaterial.Paper)
        private fun bevel(x: Float, y: Float, z: Float, w: Float, l: Float, h: Float, bevel: Float = .18f) =
            b.bevelBox(ox + x, y, z, w, l, h, PaperMaterial.Paper, bevel)

        /** A darker shade of [c] (folds, pockets, cuffs). */
        private fun dark(c: Int, f: Float = .18f) = mix(c, 0xFF000000.toInt(), f)

        /** Details laid on a bigger surface (eyes, pockets, straps) sort in front of it ([PaperFace.lift]). */
        private inline fun decal(block: () -> Unit) {
            b.lift = DECAL_LIFT
            block()
            b.lift = 0f
        }

        /**
         * A tall thin box on a surface as short pieces, each sorting with the bit of surface beside it (one long box
         * would sort by its middle and vanish under the surface's upper faces).
         */
        private fun stripe(x: Float, y: Float, low: Float, high: Float, w: Float, l: Float) = decal {
            val pieces = ((high - low) / .08f).toInt().coerceAtLeast(1)
            val h = (high - low) / pieces
            for (i in 0 until pieces) box(x, y, low + h * (i + .5f), w, l, h)
        }

        /**
         * A flat brim between [inner] and [outer] (rings at about the same height; the edge [thick] deep) as a ring of
         * small quads, so each part sorts on its own (a whole disc sorts by its middle and hides a face it should not).
         */
        private fun brim(inner: List<V>, outer: List<V>, thick: Float, closed: Boolean = true) {
            val n = inner.size
            val segments = if (closed) n else n - 1
            for (i in 0 until segments) {
                val j = (i + 1) % n
                b.face(listOf(inner[i], inner[j], outer[j], outer[i]), PaperMaterial.Paper, V(0f, 0f, 1f))
                val lowI = V(outer[i].x, outer[i].y, outer[i].z - thick)
                val lowJ = V(outer[j].x, outer[j].y, outer[j].z - thick)
                val mid = (outer[i] + outer[j]) * .5f
                b.face(listOf(outer[i], outer[j], lowJ, lowI), PaperMaterial.Paper, V(mid.x - ox, mid.y, 0f))
            }
        }

        // ------------------------------------------------------------ body

        private fun legs() {
            // Under a skirt or dress only the lower leg swings: swung from the hip, the thigh came out through the cloth.
            val skirt = p.bottom == BottomStyle.Skirt || p.bottom == BottomStyle.Dress
            for (s in listOf(-1f, 1f)) {
                val x = s * .12f
                b.bone = b.bone(ox + x, 0f, if (skirt) HEM_PIVOT else 1.04f, -s * LEG_SWING * gait)
                when (p.bottom) {
                    BottomStyle.Trousers -> {
                        paint(bottom)
                        rod(v(x, 0f, 1.08f), v(x, .01f, .58f), .1f)
                        rod(v(x, .01f, .58f), v(x, 0f, .16f), .088f)
                    }
                    BottomStyle.Shorts -> {
                        paint(bottom)
                        rod(v(x, 0f, 1.08f), v(x, .006f, .76f), .107f)
                        paint(skin)
                        rod(v(x, .006f, .76f), v(x, .01f, .58f), .078f)
                        rod(v(x, .01f, .58f), v(x, 0f, .16f), .07f)
                    }
                    else -> {
                        paint(skin)
                        rod(v(x, .007f, HEM_PIVOT), v(x, .01f, .58f), .08f)
                        rod(v(x, .01f, .58f), v(x, 0f, .16f), .068f)
                    }
                }
                // Trainers in the theme's paper on a dark sole.
                theme()
                b.bevelBox(ox + x, .055f, .095f, .16f, .31f, .12f, PaperMaterial.Ivory, .2f)
                b.box(ox + x, .055f, .025f, .165f, .32f, .045f, PaperMaterial.Rubber)
                b.bone = 0
            }
        }

        private fun hips() {
            when (p.bottom) {
                BottomStyle.Trousers, BottomStyle.Shorts -> {
                    paint(bottom)
                    bevel(0f, 0f, 1.045f, .385f, .24f, .15f)
                }
                BottomStyle.Skirt, BottomStyle.Dress -> {
                    paint(bottom)
                    shell(listOf(flat(0f, 0f, .63f, .58f, .46f, 8), flat(0f, .005f, .98f, .45f, .32f, 8), flat(0f, 0f, 1.12f, .41f, .26f, 8)))
                }
            }
        }

        private fun torso() {
            // Short bands, so pockets and straps sort against the bit of shirt they sit on.
            fun band(z: Float): List<V> {
                val k = (1 until TORSO.size).first { z <= TORSO[it][0] + 1e-4f }
                val a = TORSO[k - 1]
                val c = TORSO[k]
                val f = ((z - a[0]) / (c[0] - a[0])).coerceIn(0f, 1f)
                return flat(0f, 0f, z, a[1] + (c[1] - a[1]) * f, a[2] + (c[2] - a[2]) * f, 8)
            }
            val lower = (0..4).map { band(1.08f + it * .12f) }
            val upper = listOf(band(1.64f), band(1.70f))
            paint(top)
            shell(lower, top = false)
            if (p.top == TopStyle.Tank) {
                // Bare shoulders above the neckline, two straps over them.
                paint(skin)
                shell(listOf(lower.last()) + upper)
                paint(top)
                decal { for (s in listOf(-1f, 1f)) box(s * .12f, 0f, 1.682f, .055f, .27f, .04f) }
            } else {
                shell(listOf(lower.last()) + upper)
            }
            when (p.top) {
                TopStyle.Hoodie -> {
                    paint(dark(top, .12f))
                    bevel(0f, -.14f, 1.70f, .34f, .13f, .15f)
                    decal { bevel(0f, .132f, 1.20f, .26f, .025f, .12f, .1f) }
                    paint(WHITE)
                    for (s in listOf(-1f, 1f)) stripe(s * .05f, .152f, 1.49f, 1.61f, .018f, .012f)
                }
                TopStyle.Jacket -> {
                    // Open at the front: the shirt underneath in the theme's light paper.
                    paint(mix(WHITE, top, .08f))
                    stripe(0f, .142f, 1.12f, 1.62f, .11f, .016f)
                    paint(dark(top, .2f))
                    for (s in listOf(-1f, 1f)) stripe(s * .085f, .145f, 1.12f, 1.62f, .022f, .018f)
                }
                else -> Unit
            }
        }

        private fun arms() {
            for (s in listOf(-1f, 1f)) {
                val held = holds != 0 && s == holds.toFloat()
                val sx = s * .30f
                b.bone = b.bone(ox + sx, 0f, 1.60f, s * gait * if (held) HELD_SWING else ARM_SWING)
                val shoulder = v(sx, 0f, 1.62f)
                // A held hand reaches across to meet the other's halfway between them.
                val handX = if (held) s * (COUPLE_GAP / 2 - .05f) else sx + s * .02f
                val elbow = v((sx + handX) / 2 + if (held) 0f else s * .01f, .0f, 1.30f)
                val wrist = v(handX, if (held) .05f else .07f, 1.04f)
                when (p.top) {
                    TopStyle.Tee -> {
                        val cut = V(shoulder.x + (elbow.x - shoulder.x) * .45f, 0f, shoulder.z + (elbow.z - shoulder.z) * .45f)
                        paint(top); rod(shoulder, cut, .088f)
                        paint(skin); rod(cut, elbow, .067f); rod(elbow, wrist, .062f)
                    }
                    TopStyle.Tank -> {
                        paint(skin); rod(shoulder, elbow, .066f); rod(elbow, wrist, .06f)
                    }
                    else -> {
                        paint(top); rod(shoulder, elbow, .078f); rod(elbow, wrist, .07f)
                        paint(dark(top, .14f)); rod(V(wrist.x, wrist.y - .006f, wrist.z + .045f), V(wrist.x, wrist.y + .004f, wrist.z - .005f), .076f)
                    }
                }
                paint(skin)
                b.bevelBox(wrist.x, wrist.y + .02f, .975f, .085f, .09f, .1f, PaperMaterial.Paper, .2f)
                b.bone = 0
            }
        }

        private fun neckAndHead() {
            paint(skin)
            rod(v(0f, 0f, 1.66f), v(0f, 0f, 1.78f), .07f)
            shell(
                listOf(
                    flat(0f, .01f, 1.72f, .20f, .20f), flat(0f, .01f, 1.78f, .31f, .30f), flat(0f, .01f, 1.97f, .36f, .34f),
                    flat(0f, .01f, 2.07f, .33f, .31f), flat(0f, .01f, 2.13f, .22f, .21f),
                ),
                axisY = .01f,
            )
            // Ears, unless the hair hangs over them (they sorted in front of it); no nose (owner, 2026-11-09: "maybe
            // remove nose too"), as no eyes: a plain face.
            if (p.hair != HairStyle.Long && p.hair != HairStyle.Bob) for (s in listOf(-1f, 1f)) bevel(s * .178f, 0f, 1.90f, .035f, .08f, .1f)
        }

        // ------------------------------------------------------------ hair

        /**
         * A cap of hair over the head: [front] is the hairline's height over the forehead, [side] at the temples,
         * [back] at the nape; [w] its width at the bottom.
         */
        private fun cap(front: Float, side: Float, back: Float, w: Float = capW, topZ: Float = 2.17f, topW: Float = .25f, color: Int = hair) {
            val d = w - .02f
            fun base(t: Double): Float {
                val s = sin(t).toFloat()
                return side + (front - side) * max(0f, s) + (back - side) * max(0f, -s)
            }
            // Up the sides at full width past the head's widest band (to 2.06) before it rounds over the top, so the
            // head never shows through at the back.
            val crown = (CROWN + topZ) / 2 + .01f
            val crownW = (w + topW) / 2 + .02f
            val sides = listOf(
                ring(0f, -.006f, w, d) { t -> base(t) },
                // One tall band from the nape to the crown sorted behind the head's upper band: skin showed across the back.
                ring(0f, -.006f, w, d) { t -> max(base(t) + .005f, TEMPLES) },
                ring(0f, -.006f, w - .008f, d - .008f) { t -> max(base(t) + .01f, CROWN) },
            )
            val top = listOf(ring(0f, -.006f, crownW, crownW - .02f) { crown }, ring(0f, -.006f, topW, topW - .01f) { topZ })
            // Under a hat the hair stops at the crown: its top showed through the hat in stripes.
            val bare = p.hat == Hat.None
            paint(color)
            shell(if (bare) sides + top else sides, axisY = -.006f, top = bare)
        }

        private fun hair() {
            val hat = p.hat != Hat.None
            when (p.hair) {
                HairStyle.Bald -> Unit
                HairStyle.Buzz -> cap(2.06f, 1.96f, 1.84f, w = .372f, topZ = 2.145f, topW = .235f, color = mix(hair, skin, .42f))
                HairStyle.Short -> {
                    cap(2.05f, 1.91f, 1.79f)
                    paint(hair)
                    decal { for (s in listOf(-1f, 1f)) box(s * .176f, .075f, 1.93f, .03f, .08f, .1f) }
                }
                HairStyle.Curly -> {
                    cap(2.04f, 1.93f, 1.80f, topZ = 2.25f, topW = .34f)
                    paint(hair)
                    // Curls standing out all round, and a few on top; under a hat they stay round the sides.
                    val curls = 11
                    for (i in 0 until curls) {
                        val t = i * 2 * PI / curls + .3
                        val front = sin(t) > .55
                        bevel((cos(t) * .235f).toFloat(), (-.006f + sin(t) * .225f).toFloat(), if (front && !hat) 2.09f else 2.0f, .12f, .12f, .12f, .3f)
                    }
                    if (!hat) for (i in 0 until 4) {
                        val t = i * PI / 2 + .6
                        bevel((cos(t) * .11f).toFloat(), (sin(t) * .1f).toFloat() - .01f, 2.21f, .13f, .13f, .1f, .3f)
                    }
                }
                HairStyle.Long -> {
                    cap(2.05f, 1.98f, 1.86f)
                    paint(hair)
                    // A curtain round the back of the head and down the back (to the bag's top), in short bands that
                    // sort with the back they hang over; closed at the front, so nothing shows through beside the neck.
                    val low = if (p.backpack) 1.62f else 1.42f
                    val rings = CURTAIN.filter { it[0] > low - .01f }.map { (z, w, d, y) -> ring(0f, y, w, d, 5, PI * 1.11, PI * 1.89, closed = false) { z } }
                    shell(rings, axisY = -.18f)
                    for (s in listOf(-1f, 1f)) bevel(s * .192f, -.01f, 1.81f, .07f, .25f, .38f, .2f)
                }
                HairStyle.Bob -> {
                    cap(2.05f, 1.99f, 1.90f)
                    paint(hair)
                    // Chin-length all round the sides and back, open at the face; a straight fringe.
                    val from = PI * .7
                    val to = PI * 2.3
                    val rings = listOf(
                        ring(0f, -.01f, .43f, .41f, 10, from, to, closed = false) { 1.74f },
                        ring(0f, -.01f, .42f, .40f, 10, from, to, closed = false) { 2.0f },
                    )
                    shell(rings, axisY = -.01f, closed = false, top = false)
                    decal { bevel(0f, .176f, 2.035f, .30f, .05f, .075f, .2f) }
                }
                HairStyle.Ponytail -> {
                    cap(2.05f, 1.95f, 1.86f)
                    paint(dark(hair, .15f))
                    bevel(0f, -.205f, 1.98f, .1f, .06f, .08f)
                    paint(hair)
                    b.bone = b.bone(ox, -.21f, 1.98f, 8f * gait)
                    rod(v(0f, -.225f, 1.97f), v(0f, -.27f, 1.78f), .07f)
                    rod(v(0f, -.27f, 1.78f), v(0f, -.255f, 1.62f), .05f)
                    b.bone = 0
                }
                HairStyle.Bun -> {
                    cap(2.05f, 1.95f, 1.84f)
                    if (!hat) {
                        paint(hair)
                        shell(listOf(flat(0f, -.09f, 2.13f, .12f, .12f, 8), flat(0f, -.09f, 2.19f, .2f, .2f, 8), flat(0f, -.09f, 2.26f, .13f, .13f, 8)), axisY = -.09f)
                    }
                }
            }
        }

        // ------------------------------------------------------------ face

        private fun face() {
            // No eyes (owner, 2026-11-09: "for avatars lose the eyes"): a plain paper face, like a figurine's.
            val stubble = mix(skin, hair, .55f)
            val from = PI * -.06
            val to = PI * 1.06
            when (p.facialHair) {
                FacialHair.None -> Unit
                FacialHair.Stubble -> {
                    paint(stubble)
                    shell(
                        listOf(ring(0f, .012f, .245f, .245f, 10, from, to, closed = false) { 1.73f }, ring(0f, .012f, .345f, .335f, 10, from, to, closed = false) { 1.86f }),
                        axisY = .012f, closed = false, top = false,
                    )
                }
                FacialHair.Moustache -> moustache(.186f)
                FacialHair.Goatee -> {
                    moustache(.186f)
                    paint(hair)
                    decal { bevel(0f, .163f, 1.75f, .11f, .06f, .09f, .25f) }
                }
                FacialHair.Beard -> {
                    paint(hair)
                    shell(
                        listOf(
                            ring(0f, .03f, .19f, .21f, 10, from, to, closed = false) { 1.665f },
                            ring(0f, .025f, .33f, .335f, 10, from, to, closed = false) { 1.75f },
                            ring(0f, .015f, .375f, .355f, 10, from, to, closed = false) { 1.89f },
                        ),
                        axisY = .02f, closed = false, top = false,
                    )
                    decal { for (s in listOf(-1f, 1f)) box(s * .18f, .02f, 1.94f, .035f, .1f, .1f) }
                    moustache(.2f)
                }
            }
        }

        private fun moustache(y: Float) {
            paint(hair)
            decal { bevel(0f, y, 1.835f, .15f, .035f, .042f, .3f) }
        }

        private fun glasses() {
            if (p.glasses == Glasses.None) return
            b.lift = DECAL_LIFT
            val sun = p.glasses == Glasses.Sunglasses
            paint(if (sun) 0xFF1E2229.toInt() else INK)
            for (s in listOf(-1f, 1f)) {
                box(s * .08f, .184f, 1.927f, if (sun) .1f else .092f, .012f, if (sun) .066f else .07f)
                box(s * .182f, .09f, 1.94f, .012f, .18f, .014f)
            }
            box(0f, .186f, 1.94f, .07f, .012f, .014f)
            if (!sun) {
                // Clear lenses (no eyes behind them: the faces have none).
                paint(mix(skin, WHITE, .45f))
                for (s in listOf(-1f, 1f)) box(s * .08f, .192f, 1.927f, .068f, .006f, .048f)
            }
            b.lift = 0f
        }

        // ------------------------------------------------------------ hat, bag

        private fun hat() {
            val w = capW + .035f
            when (p.hat) {
                Hat.None -> Unit
                Hat.Cap -> {
                    paint(hatColour)
                    shell(
                        listOf(
                            ring(0f, -.01f, w, w - .02f) { t -> 2.03f + .03f * sin(t).toFloat() },
                            flat(0f, -.01f, 2.15f, w - .04f, w - .06f),
                            flat(0f, -.01f, 2.22f, .27f, .25f),
                        ),
                        axisY = -.01f,
                    )
                    paint(dark(hatColour, .12f))
                    val from = PI * .08
                    val to = PI * .92
                    brim(
                        ring(0f, -.01f, w - .01f, w - .03f, 8, from, to, closed = false) { t -> 2.06f + .01f * sin(t).toFloat() },
                        ring(0f, .1f, .43f, .56f, 8, from, to, closed = false) { 2.065f },
                        .025f, closed = false,
                    )
                    paint(hatColour)
                    bevel(0f, -.01f, 2.235f, .045f, .045f, .03f)
                }
                Hat.Beanie -> {
                    paint(hatColour)
                    // A seam at the crown: one tall band up to 2.13 sorted behind the top of the head.
                    shell(
                        listOf(
                            flat(0f, -.008f, 1.98f, w, w - .02f), flat(0f, -.008f, CROWN, w - .011f, w - .031f), flat(0f, -.008f, 2.13f, w - .02f, w - .04f),
                            flat(0f, -.008f, 2.23f, .3f, .28f), flat(0f, -.008f, 2.27f, .14f, .13f),
                        ),
                        axisY = -.008f,
                    )
                    paint(dark(hatColour, .15f))
                    shell(listOf(flat(0f, -.008f, 1.955f, w + .03f, w + .01f), flat(0f, -.008f, 2.045f, w + .03f, w + .01f)), axisY = -.008f, top = false)
                    paint(WHITE)
                    bevel(0f, -.008f, 2.31f, .1f, .1f, .09f, .3f)
                }
                Hat.SunHat -> {
                    paint(hatColour)
                    brim(flat(0f, -.01f, 2.05f, w - .01f, w - .03f, 14), flat(0f, -.01f, 2.05f, .8f, .76f, 14), .025f)
                    shell(listOf(flat(0f, -.01f, 2.04f, w, w - .02f), flat(0f, -.01f, 2.19f, w - .03f, w - .05f), flat(0f, -.01f, 2.24f, .28f, .26f)), axisY = -.01f)
                    paint(dark(hatColour, .3f))
                    shell(listOf(flat(0f, -.01f, 2.045f, w + .012f, w - .008f), flat(0f, -.01f, 2.09f, w + .008f, w - .012f)), axisY = -.01f, top = false)
                }
            }
        }

        private fun extras() {
            if (!p.backpack) return
            theme()
            b.bevelBox(ox, -.23f, 1.33f, .36f, .19f, .46f, PaperMaterial.Terracotta, .16f)
            b.bevelBox(ox, -.235f, 1.53f, .37f, .2f, .09f, PaperMaterial.Terracotta, .16f)
            b.box(ox, -.33f, 1.25f, .24f, .03f, .16f, PaperMaterial.Navy)
            b.lift = DECAL_LIFT
            for (s in listOf(-1f, 1f)) for (z in listOf(1.30f, 1.385f, 1.47f, 1.555f)) b.box(ox + s * .12f, .143f, z, .05f, .02f, .085f, PaperMaterial.Navy)
            b.lift = 0f
        }
    }
}

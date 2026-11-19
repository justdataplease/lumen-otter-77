package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.Builder
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.V
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.bodyRing
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.hullRing
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.narrowWidth
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.oval
import com.justdataplease.boringtravel.clip.PaperVehicleMeshes.ring
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.justdataplease.boringtravel.clip.PaperMaterial.Glass
import com.justdataplease.boringtravel.clip.PaperMaterial.Ivory
import com.justdataplease.boringtravel.clip.PaperMaterial.Metal
import com.justdataplease.boringtravel.clip.PaperMaterial.Navy
import com.justdataplease.boringtravel.clip.PaperMaterial.Paper
import com.justdataplease.boringtravel.clip.PaperMaterial.Rubber
import com.justdataplease.boringtravel.clip.PaperMaterial.Terracotta

/**
 * The other rides ([RideModel]), built like today's paper vehicles (x right, y forward, z up). At 54 dp on a map only
 * the proportion and one signature part read, so each has one: the SUV's roof rack and spare wheel, the camper's
 * pop-top, the convertible's seats, the scooter's round tail, the motorbike's tank, the high-speed train's wedge nose,
 * the regional train's pantograph, the tram's pole, the sail, the speedboat's wake, the cruise ship's decks and
 * funnel, the small plane's high wing and propeller disc, the seaplane's floats and the helicopter's rotor. The
 * theme's body paper ([Ivory]) takes the ride's Body colour and its accent ([Terracotta]) the Trim, unless a part says
 * otherwise ([PaperPaint]).
 */
internal object RideMeshes {
    /** The helicopter's rotor, turns a second (a whole number a minute, so the clip's 60 s phase wraps unseen). */
    const val ROTOR_TURNS = .75f

    private fun Builder.wheels(ys: List<Float>, inner: Float, outer: Float, z: Float, radius: Float, hub: Float, hubMaterial: PaperMaterial = Ivory) {
        for (sign in listOf(-1f, 1f)) for (y in ys) {
            rod(V(sign * inner, y, z), V(sign * outer, y, z), radius, Rubber, 12)
            paint(PaperPaint.NONE) { rod(V(sign * (outer + .002f), y, z), V(sign * (outer + .015f), y, z), hub, hubMaterial, 10) }
        }
    }

    /** Glass let into a side at [x] (both sides), from [low] to [high], one pane per (from, to) in [panes]. */
    private fun Builder.sideWindows(x: Float, low: Float, high: Float, panes: List<Pair<Float, Float>>, material: PaperMaterial = Glass) {
        for (sign in listOf(-1f, 1f)) for ((a, b) in panes) {
            decal(listOf(V(sign * x, a, low), V(sign * x, a, high), V(sign * x, b, high), V(sign * x, b, low)), material, V(sign, 0f, 0f))
        }
    }

    /**
     * [block]'s parts sort [by] nearer the camera ([PaperFace.lift]): a long roof or wing is one face sorted by its
     * middle, and showed over what stands on its far half, or under what holds up its near half.
     */
    private inline fun Builder.lifted(by: Float, block: () -> Unit) {
        lift = by
        block()
        lift = 0f
    }

    // ------------------------------------------------------------------------------------------------ cars

    /** Tall and square, high off the road: a black roof rack and a spare wheel (its cover in the trim) on the back. */
    fun suv() = Builder().apply {
        loft(listOf(bodyRing(-1.0f, .47f, .42f, .84f), bodyRing(-.90f, .55f, .36f, .92f), bodyRing(.74f, .55f, .36f, .92f), bodyRing(1.0f, .50f, .42f, .82f)), Ivory)
        loft(listOf(bodyRing(-.86f, .50f, .88f, 1.46f), bodyRing(.24f, .50f, .88f, 1.46f), bodyRing(.56f, .48f, .88f, 1.0f)), Ivory)
        decal(listOf(V(-.36f, -.80f, 1.465f), V(.36f, -.80f, 1.465f), V(.36f, .20f, 1.465f), V(-.36f, .20f, 1.465f)), Terracotta, V(0f, 0f, 1f))
        for (x in listOf(-.33f, .33f)) {
            rod(V(x, -.78f, 1.54f), V(x, .16f, 1.54f), .024f, Navy, 4)
            for (y in listOf(-.72f, .10f)) rod(V(x, y, 1.46f), V(x, y, 1.54f), .02f, Navy, 4)
        }
        for (y in listOf(-.56f, -.24f, .06f)) rod(V(-.33f, y, 1.54f), V(.33f, y, 1.54f), .02f, Navy, 4)
        decal(listOf(V(-.34f, .27f, 1.432f), V(.34f, .27f, 1.432f), V(.33f, .53f, 1.058f), V(-.33f, .53f, 1.058f)), Glass, V(0f, 1f, .7f))
        decal(listOf(V(-.34f, -.875f, 1.06f), V(.34f, -.875f, 1.06f), V(.34f, -.875f, 1.36f), V(-.34f, -.875f, 1.36f)), Glass, V(0f, -1f, 0f))
        sideWindows(.505f, 1.03f, 1.30f, listOf(-.80f to -.47f, -.41f to -.08f, -.02f to .22f))
        wheels(listOf(-.60f, .62f), .46f, .64f, .31f, .28f, .15f)
        for (sign in listOf(-1f, 1f)) {
            box(sign * .56f, .30f, 1.0f, .05f, .07f, .06f, Navy)
            for (y in listOf(-.60f, .62f)) box(sign * .56f, y, .61f, .04f, .66f, .07f, Navy)
            paint(PaperPaint.NONE) {
                rod(V(sign * .30f, .985f, .70f), V(sign * .30f, 1.035f, .70f), .075f, Ivory, 8)
                box(sign * .36f, -1.005f, .72f, .10f, .02f, .14f, Terracotta)
            }
        }
        box(0f, 1.005f, .62f, .30f, .02f, .12f, Navy)
        bevelBox(0f, 1.03f, .46f, .84f, .08f, .13f, Navy)
        bevelBox(0f, -1.03f, .46f, .80f, .08f, .13f, Navy)
        rod(V(0f, -1.0f, .74f), V(0f, -1.13f, .74f), .22f, Rubber, 12, Terracotta)
    }.build().narrowWidth(.8f)

    /** Two-tone, round-cornered van with a big windscreen; the pop-top roof's lid in the trim over a canvas side. */
    fun camper() = Builder().apply {
        loft(listOf(ring(-1.0f, .50f, .30f, .86f, .10f, 0f), ring(-.95f, .55f, .27f, .86f, .12f, 0f), ring(.82f, .55f, .27f, .86f, .12f, 0f), ring(1.0f, .50f, .30f, .86f, .10f, 0f)), Ivory)
        loft(listOf(ring(-1.0f, .50f, .86f, 1.36f, 0f, .14f), ring(-.95f, .55f, .86f, 1.40f, 0f, .18f), ring(.72f, .55f, .86f, 1.40f, 0f, .18f), ring(.97f, .52f, .86f, 1.10f, 0f, .10f)), Paper)
        decal(listOf(V(-.37f, .74f, 1.391f), V(.37f, .74f, 1.391f), V(.355f, .95f, 1.139f), V(-.355f, .95f, 1.139f)), Glass, V(0f, 1f, .83f))
        decal(listOf(V(-.30f, -1.005f, .95f), V(.30f, -1.005f, .95f), V(.30f, -1.005f, 1.20f), V(-.30f, -1.005f, 1.20f)), Glass, V(0f, -1f, 0f))
        sideWindows(.555f, .94f, 1.17f, listOf(-.90f to -.56f, -.50f to -.16f, -.10f to .24f, .30f to .64f))
        // The pop-top: canvas sides, raised at the front, under a lid in the trim.
        val base = listOf(V(-.38f, -.72f, 1.40f), V(.38f, -.72f, 1.40f), V(.38f, .46f, 1.40f), V(-.38f, .46f, 1.40f))
        val top = listOf(V(-.39f, -.73f, 1.50f), V(.39f, -.73f, 1.50f), V(.39f, .47f, 1.86f), V(-.39f, .47f, 1.86f))
        solid(base + top, PaperVehicleMeshes.BOX_SIDES, Metal)
        slab(listOf(V(-.41f, -.76f, 1.50f), V(.41f, -.76f, 1.50f), V(.41f, .50f, 1.86f), V(-.41f, .50f, 1.86f)), V(0f, 0f, .045f), Terracotta)
        wheels(listOf(-.66f, .64f), .44f, .58f, .25f, .23f, .12f, Paper)
        for (sign in listOf(-1f, 1f)) paint(PaperPaint.NONE) {
            rod(V(sign * .32f, .995f, .62f), V(sign * .32f, 1.03f, .62f), .08f, Paper, 8)
            box(sign * .38f, -1.005f, .70f, .08f, .02f, .14f, Terracotta)
        }
        bevelBox(0f, 1.03f, .36f, .98f, .06f, .10f, Navy)
        bevelBox(0f, -1.03f, .36f, .96f, .06f, .10f, Navy)
    }.build().narrowWidth(.8f)

    /** Low and open: a dark cockpit let into the top, four seats in the trim, a framed windscreen. */
    fun convertible() = Builder().apply {
        loft(listOf(bodyRing(-1.0f, .42f, .30f, .62f), bodyRing(-.86f, .53f, .24f, .72f), bodyRing(.62f, .53f, .24f, .70f), bodyRing(.98f, .44f, .30f, .56f)), Ivory)
        decal(listOf(V(-.37f, -.66f, .725f), V(.37f, -.66f, .725f), V(.37f, .30f, .712f), V(-.37f, .30f, .712f)), Navy, V(0f, 0f, 1f))
        for (sign in listOf(-1f, 1f)) {
            bevelBox(sign * .17f, .06f, .77f, .26f, .26f, .10f, Terracotta, .2f)
            bevelBox(sign * .17f, -.09f, .89f, .26f, .07f, .30f, Terracotta, .2f)
            box(sign * .535f, -.12f, .58f, .02f, 1.40f, .035f, Terracotta)
        }
        bevelBox(0f, -.42f, .77f, .64f, .24f, .10f, Terracotta, .12f)
        bevelBox(0f, -.57f, .88f, .64f, .07f, .26f, Terracotta, .12f)
        slab(listOf(V(-.38f, .42f, .70f), V(.38f, .42f, .70f), V(.36f, .32f, .98f), V(-.36f, .32f, .98f)), V(0f, -.025f, 0f), Glass)
        rod(V(-.37f, .31f, .985f), V(.37f, .31f, .985f), .022f, Navy, 4)
        wheels(listOf(-.61f, .62f), .49f, .63f, .265f, .225f, .125f)
        for (sign in listOf(-1f, 1f)) paint(PaperPaint.NONE) {
            rod(V(sign * .29f, .905f, .49f), V(sign * .29f, .975f, .49f), .095f, Ivory, 10)
            box(sign * .32f, -.978f, .48f, .12f, .025f, .09f, Terracotta)
        }
        bevelBox(0f, .99f, .325f, .76f, .075f, .085f, Navy)
        bevelBox(0f, -1.01f, .325f, .72f, .07f, .08f, Navy)
    }.build().narrowWidth(.8f)

    // ------------------------------------------------------------------------------------------------ bikes

    /** A round-tailed scooter: small wheels, the tail cowl, leg shield and mudguard in the body, the seat in the trim. */
    fun scooter() = Builder().apply {
        paint(PaperPaint.NONE) {
            for (y in listOf(-.62f, .66f)) {
                rod(V(-.075f, y, .24f), V(.075f, y, .24f), .24f, Rubber, 12)
                lift = .02f
                rod(V(-.082f, y, .24f), V(.082f, y, .24f), .12f, Metal, 8)
                lift = 0f
            }
        }
        loft(listOf(oval(-.96f, .12f, .10f, .58f), oval(-.84f, .25f, .22f, .58f), oval(-.42f, .28f, .26f, .58f), oval(-.12f, .20f, .20f, .56f)), Ivory)
        paint(PaperPaint.TRIM) { bevelBox(0f, -.50f, .88f, .24f, .56f, .09f, Navy, .25f) }
        bevelBox(0f, .12f, .30f, .30f, .58f, .06f, Navy, .1f)
        slab(listOf(V(-.24f, .46f, .27f), V(.24f, .46f, .27f), V(.19f, .53f, 1.0f), V(-.19f, .53f, 1.0f)), V(0f, .06f, 0f), Ivory)
        box(0f, .61f, .78f, .07f, .02f, .24f, Terracotta)
        bevelBox(0f, .68f, .52f, .17f, .34f, .07f, Ivory, .25f)
        rod(V(0f, .66f, .24f), V(0f, .58f, .56f), .03f, Navy, 4)
        rod(V(0f, .57f, .95f), V(0f, .58f, 1.10f), .045f, Navy, 6)
        bevelBox(0f, .58f, 1.14f, .32f, .16f, .10f, Ivory, .25f)
        rod(V(-.33f, .57f, 1.15f), V(.33f, .57f, 1.15f), .028f, Navy, 4)
        paint(PaperPaint.NONE) {
            rod(V(0f, .66f, 1.14f), V(0f, .70f, 1.14f), .06f, Paper, 8)
            box(0f, -.97f, .60f, .10f, .02f, .05f, Terracotta)
        }
    }.build()

    /** Big solid wheels, an engine block, the tank (a trim stripe on it), seat, tail and mudguard. */
    fun motorbike() = Builder().apply {
        paint(PaperPaint.NONE) {
            for (y in listOf(-.72f, .74f)) {
                rod(V(-.09f, y, .34f), V(.09f, y, .34f), .34f, Rubber, 14)
                lift = .02f
                rod(V(-.096f, y, .34f), V(.096f, y, .34f), .19f, Metal, 10)
                lift = 0f
            }
            bevelBox(0f, 0f, .44f, .26f, .44f, .30f, Metal, .2f)
            bevelBox(0f, .14f, .66f, .22f, .20f, .16f, Metal, .2f)
            rod(V(.15f, .10f, .30f), V(.17f, -.76f, .44f), .045f, Metal, 6)
            for (sign in listOf(-1f, 1f)) rod(V(sign * .09f, .74f, .34f), V(sign * .09f, .48f, 1.02f), .03f, Metal, 6)
            rod(V(0f, .56f, .98f), V(0f, .62f, .98f), .09f, Paper, 10)
            box(0f, -.815f, .90f, .08f, .02f, .04f, Terracotta)
        }
        rod(V(0f, -.72f, .34f), V(0f, -.20f, .62f), .035f, Navy, 4)
        rod(V(0f, -.20f, .62f), V(0f, .42f, .96f), .04f, Navy, 4)
        bevelBox(0f, .14f, .90f, .32f, .48f, .22f, Ivory, .25f)
        decal(listOf(V(-.05f, -.06f, 1.012f), V(.05f, -.06f, 1.012f), V(.05f, .34f, 1.012f), V(-.05f, .34f, 1.012f)), Terracotta, V(0f, 0f, 1f))
        bevelBox(0f, -.32f, .90f, .26f, .46f, .08f, Navy, .25f)
        bevelBox(0f, -.66f, .90f, .18f, .30f, .10f, Ivory, .25f)
        for (sign in listOf(-1f, 1f)) box(sign * .13f, -.30f, .72f, .02f, .30f, .16f, Terracotta)
        bevelBox(0f, .78f, .72f, .14f, .40f, .05f, Ivory, .25f)
        rod(V(-.36f, .44f, 1.10f), V(.36f, .44f, 1.10f), .025f, Navy, 4)
    }.build()

    // ------------------------------------------------------------------------------------------------ trains

    private fun Builder.bogies(ys: List<Float>, z: Float = .15f) {
        for (y in ys) {
            bevelBox(0f, y, z, .60f, .54f, .12f, Navy, .1f)
            for (sign in listOf(-1f, 1f)) for (dy in listOf(-.15f, .15f)) rod(V(sign * .30f, y + dy, .12f), V(sign * .37f, y + dy, .12f), .11f, Metal, 8)
        }
    }

    /** Long and low with a wedge nose: one dark window band and a trim stripe along each side. */
    fun highSpeed() = Builder().apply {
        loft(
            listOf(
                bodyRing(-1.30f, .38f, .24f, .86f), bodyRing(-1.22f, .42f, .20f, .92f), bodyRing(.48f, .42f, .20f, .92f),
                bodyRing(.84f, .40f, .20f, .84f), bodyRing(1.10f, .33f, .21f, .62f), bodyRing(1.28f, .20f, .24f, .42f), bodyRing(1.38f, .07f, .28f, .33f),
            ),
            Ivory,
        )
        decal(listOf(V(-.27f, .86f, .837f), V(.27f, .86f, .837f), V(.22f, 1.07f, .659f), V(-.22f, 1.07f, .659f)), Glass, V(0f, 1f, 1.18f))
        sideWindows(.425f, .60f, .74f, listOf(-1.12f to .44f))
        sideWindows(.425f, .38f, .50f, listOf(-1.18f to .46f), Terracotta)
        lifted(.4f) {
            rod(V(0f, -.85f, .93f), V(0f, -.55f, 1.04f), .018f, Navy, 4)
            rod(V(0f, -.55f, 1.04f), V(0f, -.78f, 1.10f), .018f, Navy, 4)
            rod(V(-.18f, -.78f, 1.10f), V(.18f, -.78f, 1.10f), .02f, Navy, 4)
        }
        bogies(listOf(-.88f, .56f))
    }.build()

    /** A boxy electric unit: big front glass over a trim panel, trim doors, and the pantograph on the roof. */
    fun regional() = Builder().apply {
        loft(listOf(bodyRing(-1.16f, .44f, .21f, 1.08f), bodyRing(-1.10f, .46f, .18f, 1.12f), bodyRing(.94f, .46f, .18f, 1.12f), bodyRing(1.08f, .44f, .20f, 1.0f), bodyRing(1.13f, .42f, .24f, .92f)), Ivory)
        decal(listOf(V(-.30f, .95f, 1.123f), V(.30f, .95f, 1.123f), V(.29f, 1.07f, 1.021f), V(-.29f, 1.07f, 1.021f)), Glass, V(0f, 1f, 1.17f))
        decal(listOf(V(-.30f, 1.135f, .64f), V(.30f, 1.135f, .64f), V(.30f, 1.135f, .86f), V(-.30f, 1.135f, .86f)), Glass, V(0f, 1f, 0f))
        decal(listOf(V(-.32f, 1.135f, .32f), V(.32f, 1.135f, .32f), V(.32f, 1.135f, .56f), V(-.32f, 1.135f, .56f)), Terracotta, V(0f, 1f, 0f))
        sideWindows(.465f, .66f, .90f, listOf(-1.02f to -.78f, -.50f to -.26f, .02f to .26f, .54f to .80f))
        sideWindows(.465f, .40f, .90f, listOf(-.70f to -.58f, .36f to .48f), Terracotta)
        lifted(.4f) {
            box(0f, -.50f, 1.14f, .34f, .30f, .04f, Navy)
            rod(V(0f, -.60f, 1.16f), V(0f, -.24f, 1.42f), .02f, Navy, 4)
            rod(V(0f, -.24f, 1.42f), V(0f, -.56f, 1.66f), .02f, Navy, 4)
            rod(V(-.28f, -.56f, 1.66f), V(.28f, -.56f, 1.66f), .024f, Navy, 4)
        }
        bogies(listOf(-.78f, .74f))
    }.build()

    /** One short car, two-tone with a trim roof, its trolley pole reaching up and back. */
    fun tram() = Builder().apply {
        loft(listOf(ring(-.98f, .36f, .22f, .72f, .06f, 0f), ring(-.88f, .44f, .16f, .72f, .08f, 0f), ring(.88f, .44f, .16f, .72f, .08f, 0f), ring(.98f, .36f, .22f, .72f, .06f, 0f)), Ivory)
        loft(listOf(ring(-.98f, .36f, .72f, 1.16f, 0f, .08f), ring(-.88f, .44f, .72f, 1.24f, 0f, .12f), ring(.88f, .44f, .72f, 1.24f, 0f, .12f), ring(.98f, .36f, .72f, 1.16f, 0f, .08f)), Paper)
        bevelBox(0f, 0f, 1.27f, .74f, 1.72f, .07f, Terracotta, .10f)
        sideWindows(.445f, .80f, 1.10f, listOf(-.80f to -.50f, -.42f to -.12f, -.04f to .26f, .34f to .64f))
        for (end in listOf(-1f, 1f)) {
            decal(listOf(V(-.24f, end * .985f, .80f), V(.24f, end * .985f, .80f), V(.24f, end * .985f, 1.06f), V(-.24f, end * .985f, 1.06f)), Glass, V(0f, end, 0f))
        }
        lifted(.4f) {
            box(0f, -.18f, 1.33f, .14f, .14f, .05f, Navy)
            rod(V(0f, -.20f, 1.31f), V(0f, -.90f, 1.92f), .022f, Navy, 4)
            rod(V(-.05f, -.90f, 1.92f), V(.05f, -.90f, 1.92f), .04f, Navy, 6)
        }
        bevelBox(0f, 0f, .13f, .70f, 1.50f, .10f, Navy, .1f)
        for (sign in listOf(-1f, 1f)) for (y in listOf(-.56f, .56f)) rod(V(sign * .36f, y, .13f), V(sign * .42f, y, .13f), .12f, Metal, 8)
        paint(PaperPaint.NONE) { rod(V(0f, .985f, .48f), V(0f, 1.02f, .48f), .06f, Paper, 8) }
    }.build()

    // ------------------------------------------------------------------------------------------------ boats

    /** A sail with a belly: three triangles off the plane through [head], [tack] and [clew], seen from both sides. */
    private fun Builder.sail(head: V, tack: V, clew: V, belly: Float, material: PaperMaterial) {
        val middle = (head + tack + clew) * (1f / 3f) + V(belly, 0f, 0f)
        sheet(listOf(head, tack, middle), material)
        sheet(listOf(tack, clew, middle), material)
        sheet(listOf(clew, head, middle), material)
    }

    /** A small yacht: the hull in the body with a paper deck, a white mainsail and the jib and pennant in the trim. */
    fun sailboat() = Builder().apply {
        loft(
            listOf(hullRing(-.86f, .30f, .16f, .30f, .40f), hullRing(-.70f, .38f, .06f, .26f, .42f), hullRing(.16f, .40f, .04f, .26f, .44f), hullRing(.66f, .27f, .10f, .30f, .46f), hullRing(.96f, .02f, .32f, .42f, .48f)),
            Ivory,
        ) { _, edge -> if (edge == 3) Paper else Ivory }
        bevelBox(0f, -.22f, .51f, .42f, .56f, .16f, Paper, .2f)
        for (y in listOf(-.34f, -.22f, -.10f)) for (sign in listOf(-1f, 1f)) box(sign * .212f, y, .52f, .012f, .07f, .05f, Glass)
        rod(V(0f, .18f, .44f), V(0f, .18f, 1.98f), .026f, Navy, 6)
        rod(V(0f, .15f, .64f), V(0f, -.80f, .64f), .022f, Navy, 4)
        paint(PaperPaint.NONE) { sail(V(0f, .15f, 1.92f), V(0f, .15f, .68f), V(0f, -.78f, .68f), .11f, Paper) }
        sail(V(0f, .21f, 1.76f), V(0f, .92f, .51f), V(0f, .34f, .62f), .08f, Terracotta)
        sheet(listOf(V(0f, .18f, 1.98f), V(0f, -.08f, 1.93f), V(0f, .18f, 1.88f)), Terracotta)
    }.build()

    /** Low and pointed with a trim band, seats and a windscreen, an outboard, and its white wake on the water. */
    fun speedboat() = Builder().apply {
        loft(
            listOf(hullRing(-.78f, .40f, .10f, .24f, .38f), hullRing(-.56f, .44f, .04f, .22f, .40f), hullRing(.28f, .42f, .04f, .22f, .42f), hullRing(.74f, .26f, .10f, .28f, .44f), hullRing(1.04f, .03f, .28f, .38f, .46f)),
            Ivory,
        ) { _, edge -> when (edge) { 3 -> Paper; 2, 4 -> Terracotta; else -> Ivory } }
        decal(listOf(V(-.30f, -.52f, .415f), V(.30f, -.52f, .415f), V(.30f, .10f, .425f), V(-.30f, .10f, .425f)), Navy, V(0f, 0f, 1f))
        for (sign in listOf(-1f, 1f)) bevelBox(sign * .15f, -.06f, .47f, .22f, .20f, .10f, Terracotta, .2f)
        bevelBox(0f, -.42f, .46f, .54f, .18f, .10f, Terracotta, .15f)
        slab(listOf(V(-.36f, .20f, .42f), V(.36f, .20f, .42f), V(.32f, .10f, .62f), V(-.32f, .10f, .62f)), V(0f, -.02f, 0f), Glass)
        bevelBox(0f, -.86f, .44f, .18f, .14f, .24f, Navy, .2f)
        rod(V(0f, -.88f, .32f), V(0f, -.88f, .06f), .04f, Navy, 4)
        // The wake on the water: two arms spreading from the stern and the trail between, foam without ink.
        for (sign in listOf(-1f, 1f)) {
            face(listOf(V(sign * .30f, -.80f, .012f), V(sign * .18f, -.86f, .012f), V(sign * .60f, -1.24f, .012f)), PaperMaterial.Foam, V(0f, 0f, 1f))
        }
        face(listOf(V(-.11f, -.90f, .012f), V(.11f, -.90f, .012f), V(0f, -1.30f, .012f)), PaperMaterial.Foam, V(0f, 0f, 1f))
    }.build()

    /** Long and tall: the hull (body) over a dark waterline, three stepped decks with window bands, a trim funnel and lifeboats. */
    fun cruise() = Builder().apply {
        loft(
            listOf(hullRing(-1.30f, .32f, .14f, .34f, .62f), hullRing(-1.20f, .42f, .08f, .32f, .64f), hullRing(.80f, .43f, .08f, .32f, .64f), hullRing(1.18f, .26f, .14f, .40f, .68f), hullRing(1.42f, .03f, .32f, .56f, .70f)),
            Ivory,
        ) { _, edge -> when (edge) { 3 -> Paper; 0, 1, 5 -> Navy; else -> Ivory } }
        bevelBox(0f, -.18f, .74f, .74f, 2.0f, .20f, Paper, .10f)
        bevelBox(0f, -.28f, .93f, .64f, 1.60f, .18f, Paper, .10f)
        bevelBox(0f, -.38f, 1.09f, .52f, 1.10f, .14f, Paper, .12f)
        sideWindows(.375f, .70f, .79f, listOf(-.92f to .56f))
        sideWindows(.325f, .88f, .97f, listOf(-.86f to .30f))
        sideWindows(.265f, 1.05f, 1.12f, listOf(-.76f to 0f))
        decal(listOf(V(-.22f, .525f, .88f), V(.22f, .525f, .88f), V(.22f, .525f, .97f), V(-.22f, .525f, .97f)), Glass, V(0f, 1f, 0f))
        bevelBox(0f, -.78f, 1.33f, .26f, .36f, .34f, Terracotta, .2f)
        bevelBox(0f, -.78f, 1.515f, .27f, .37f, .03f, Navy, .2f)
        for (sign in listOf(-1f, 1f)) for (y in listOf(-.62f, -.26f, .10f)) rod(V(sign * .375f, y - .10f, .88f), V(sign * .375f, y + .10f, .88f), .05f, Terracotta, 6)
        rod(V(0f, .20f, 1.02f), V(0f, .20f, 1.30f), .02f, Navy, 4)
    }.build()

    // ------------------------------------------------------------------------------------------------ aircraft

    /**
     * A high-wing light plane: a boxy cabin under the wing (its tips in the trim), struts, the trim fin, and a
     * propeller disc on the nose. [floats]: the seaplane, on two floats instead of wheels.
     */
    fun prop(floats: Boolean) = Builder().apply {
        loft(
            listOf(
                bodyRing(-1.18f, .03f, .68f, .76f), bodyRing(-.70f, .10f, .54f, .80f), bodyRing(-.12f, .20f, .40f, .86f),
                bodyRing(.38f, .21f, .38f, .86f), bodyRing(.62f, .19f, .40f, .70f), bodyRing(.84f, .14f, .45f, .66f),
            ),
            Ivory,
        )
        // The wing in front of the struts under it: their ends showed as dots on top of it.
        lifted(.65f) {
            box(0f, .11f, .885f, 2.56f, .36f, .05f, Paper)
            for (sign in listOf(-1f, 1f)) box(sign * 1.29f, .11f, .885f, .04f, .37f, .06f, Terracotta)
        }
        for (sign in listOf(-1f, 1f)) {
            rod(V(sign * .20f, .10f, .46f), V(sign * .70f, .12f, .86f), .018f, Navy, 4)
            prism(listOf(0f to -.84f, sign * .50f to -.98f, sign * .50f to -1.10f, 0f to -1.12f), .70f, .735f, Paper)
        }
        decal(listOf(V(-.15f, .40f, .86f), V(.15f, .40f, .86f), V(.14f, .60f, .726f), V(-.14f, .60f, .726f)), Glass, V(0f, 1f, 1.5f))
        sideWindows(.212f, .62f, .75f, listOf(-.06f to .14f, .18f to .36f))
        slab(listOf(V(-.022f, -1.16f, .74f), V(-.022f, -.80f, .76f), V(-.022f, -1.02f, 1.12f), V(-.022f, -1.19f, 1.12f)), V(.044f, 0f, 0f), Terracotta)
        loft(listOf(oval(.84f, .085f, .085f, .555f, 8), oval(.97f, .02f, .02f, .555f, 8)), Terracotta)
        paint(PaperPaint.NONE) { sheet(oval(.885f, .30f, .30f, .555f, 16), Metal) }
        if (floats) {
            paint(PaperPaint.NONE) {
                for (sign in listOf(-1f, 1f)) {
                    val x = sign * .42f
                    loft(listOf(bodyRing(-.80f, .05f, .08f, .16f), bodyRing(-.45f, .10f, .02f, .20f), bodyRing(.45f, .10f, .02f, .20f), bodyRing(.78f, .07f, .08f, .20f), bodyRing(.90f, .03f, .14f, .19f)).map { r -> r.map { V(it.x + x, it.y, it.z) } }, Paper)
                    rod(V(x, -.20f, .19f), V(sign * .17f, -.15f, .45f), .02f, Navy, 4)
                    rod(V(x, .42f, .19f), V(sign * .17f, .40f, .43f), .02f, Navy, 4)
                }
            }
        } else {
            for (sign in listOf(-1f, 1f)) {
                rod(V(sign * .16f, .06f, .42f), V(sign * .36f, .06f, .12f), .02f, Navy, 4)
                rod(V(sign * .32f, .06f, .11f), V(sign * .42f, .06f, .11f), .11f, Rubber, 10)
            }
            rod(V(0f, .66f, .44f), V(0f, .66f, .12f), .02f, Navy, 4)
            rod(V(-.04f, .66f, .09f), V(.04f, .66f, .09f), .09f, Rubber, 10)
        }
    }.build()

    /** A bubble cabin and tail boom (body), a trim fin with its little rotor, skids, and the main rotor that turns. */
    fun helicopter() = Builder().apply {
        val glassFrom = 3
        loft(
            listOf(oval(-.52f, .10f, .14f, .70f), oval(-.36f, .25f, .29f, .64f), oval(-.06f, .30f, .34f, .62f), oval(.24f, .29f, .32f, .60f), oval(.46f, .21f, .25f, .57f), oval(.60f, .07f, .11f, .55f)),
            Ivory,
        ) { segment, edge ->
            val up = sin((edge + .5) * 2 * PI / 10)
            if ((segment >= glassFrom && up > -.5) || (segment == 2 && (edge == 0 || edge == 4))) Glass else Ivory
        }
        loft(listOf(oval(-.40f, .09f, .10f, .74f, 6), oval(-1.42f, .045f, .05f, .84f, 6)), Ivory)
        slab(listOf(V(-.02f, -1.30f, .80f), V(-.02f, -1.42f, .80f), V(-.02f, -1.56f, 1.14f), V(-.02f, -1.46f, 1.14f)), V(.04f, 0f, 0f), Terracotta)
        box(0f, -1.24f, .80f, .46f, .10f, .025f, Paper)
        paint(PaperPaint.NONE) { sheet(oval(0f, .17f, .17f, 1.0f, 12).map { V(.045f, -1.44f + it.x, it.z) }, Metal) }
        for (sign in listOf(-1f, 1f)) {
            rod(V(sign * .30f, -.46f, .05f), V(sign * .30f, .40f, .05f), .026f, Navy, 4)
            rod(V(sign * .30f, .40f, .05f), V(sign * .30f, .53f, .13f), .026f, Navy, 4)
            // Well into the cabin: ending at its skin, the rear struts stopped short of the flat faces.
            for (y in listOf(-.26f, .22f)) rod(V(sign * .30f, y, .05f), V(sign * .14f, y, .46f), .02f, Navy, 4)
        }
        rod(V(0f, 0f, .94f), V(0f, 0f, 1.07f), .04f, Navy, 6)
        rod(V(0f, 0f, 1.06f), V(0f, 0f, 1.11f), .08f, Navy, 8)
        bone = bone(0f, 0f, 1.10f, 0f, spin = ROTOR_TURNS)
        // Each blade in short pieces that sort on their own: whole, it sorted by the hub and the cabin showed over it.
        val pieces = 8
        for (angle in listOf(PI / 4, 3 * PI / 4)) {
            val along = V(cos(angle).toFloat(), sin(angle).toFloat(), 0f)
            for (i in 0 until pieces) {
                val at = (i + .5f) / pieces - .5f
                turnedBox(V(0f, 0f, 1.115f) + along * (at * 2.36f), along, V(-along.y, along.x, 0f), 2.36f / pieces, .085f, .022f, Navy)
            }
        }
        bone = 0
    }.build()
}

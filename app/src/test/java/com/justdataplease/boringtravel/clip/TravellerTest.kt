package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The traveller's look: stored strings, defaults, the couple's layout and the paper figure built for every option. */
class TravellerTest {
    /** Every value of every option, spread over a few people (each enum value appears at least once). */
    private val people: List<Person> = run {
        val n = maxOf(SkinTone.entries.size, HairStyle.entries.size, HairColour.entries.size, Cloth.entries.size)
        (0 until n).map { i ->
            fun <T> pick(values: List<T>, k: Int = 1) = values[(i * k) % values.size]
            Person(
                skin = pick(SkinTone.entries), hair = pick(HairStyle.entries), hairColour = pick(HairColour.entries),
                facialHair = pick(FacialHair.entries), top = pick(TopStyle.entries), topColour = pick(Cloth.entries),
                bottom = pick(BottomStyle.entries), bottomColour = pick(Cloth.entries, 3), hat = pick(Hat.entries),
                hatColour = pick(Cloth.entries, 5), glasses = pick(Glasses.entries), backpack = i % 2 == 0,
            )
        }
    }

    // --- Stored as a string -------------------------------------------------------------------------------------

    @Test fun everyLookSurvivesItsString() {
        for (who in Who.entries) for ((i, man) in people.withIndex()) {
            val t = Traveller(who, man, people[(i + 3) % people.size])
            assertEquals(t, Traveller.decode(t.encode()))
            assertEquals(t, Traveller.decodeOrNull(t.encode()))
        }
        assertEquals(Traveller.DEFAULT, Traveller.decode(Traveller.DEFAULT.encode()))
    }

    @Test fun missingOrUnknownValuesFallBackOneByOne() {
        assertEquals(Traveller.DEFAULT, Traveller.decode(null))
        assertEquals(Traveller.DEFAULT, Traveller.decode(""))
        assertEquals(Traveller.DEFAULT, Traveller.decode("nonsense"))
        // Only the woman's hair is known: everything else is the default.
        val t = Traveller.decode("who=couple;woman=hair:bun,hat:fez,skin:t9")
        assertEquals(Who.Couple, t.who)
        assertEquals(Person.MAN, t.man)
        assertEquals(Person.WOMAN.copy(hair = HairStyle.Bun), t.woman)
        // A newer app's extra keys and values are ignored.
        val newer = Traveller(Who.Woman).encode() + ";pet=dog"
        assertEquals(Traveller(Who.Woman), Traveller.decode(newer.replace("bag:", "scarf:red,bag:")))
        assertEquals(Person.MAN.copy(backpack = false), Person.decode("bag:off", Person.MAN))
        // A per-video choice that isn't one: none.
        assertNull(Traveller.decodeOrNull(null))
        assertNull(Traveller.decodeOrNull("  "))
        assertNull(Traveller.decodeOrNull("x".repeat(3000)))
        assertNull(Traveller.decodeOrNull("hair:short"))
    }

    @Test fun idsAreUniqueAndStable() {
        fun unique(ids: List<String>) = assertEquals(ids.size, ids.toSet().size)
        unique(SkinTone.entries.map { it.id })
        unique(HairStyle.entries.map { it.id })
        unique(HairColour.entries.map { it.id })
        unique(FacialHair.entries.map { it.id })
        unique(TopStyle.entries.map { it.id })
        unique(BottomStyle.entries.map { it.id })
        unique(Cloth.entries.map { it.id })
        unique(Hat.entries.map { it.id })
        unique(Glasses.entries.map { it.id })
        // Stored strings depend on these: changing one would forget people's choices.
        assertEquals("who=man;man=skin:t3,hair:short,hairColour:darkbrown,face:stubble,top:tee,topColour:teal,bottom:trousers," +
            "bottomColour:navy,hat:none,hatColour:mustard,glasses:none,bag:on;woman=skin:t5,hair:long,hairColour:brown,face:none," +
            "top:tee,topColour:coral,bottom:dress,bottomColour:denim,hat:none,hatColour:mustard,glasses:none,bag:off", Traveller.DEFAULT.encode())
        // Eight skin tones, light to deep; every colour opaque.
        assertEquals(8, SkinTone.entries.size)
        val light = SkinTone.entries.map { luminance(it.argb) }
        assertEquals(light.sortedDescending(), light)
        (SkinTone.entries.map { it.argb } + HairColour.entries.map { it.argb } + Cloth.entries.map { it.argb }).forEach { assertEquals(255, it ushr 24) }
    }

    // --- Defaults and who shows ---------------------------------------------------------------------------------

    @Test fun defaultsAreAFriendlyManAndWoman() {
        val d = Traveller.DEFAULT
        assertEquals(Who.Man, d.who)
        assertEquals(listOf(Person.MAN), d.people)
        assertEquals(listOf(Person.WOMAN), d.copy(who = Who.Woman).people)
        assertEquals(listOf(Person.MAN, Person.WOMAN), d.copy(who = Who.Couple).people)
        assertEquals(FacialHair.Stubble, Person.MAN.facialHair)
        assertTrue(Person.MAN.backpack)
        assertTrue(Person.WOMAN.dress)
        assertEquals(HairStyle.Long, Person.WOMAN.hair)
        // Changing one person leaves the other alone.
        val t = d.change(Who.Woman) { it.copy(facialHair = FacialHair.Beard) }
        assertEquals(Person.MAN, t.man)
        assertEquals(FacialHair.Beard, t.woman.facialHair)
        assertEquals(t.woman, t.person(Who.Woman))
        assertEquals(t.man, t.change(Who.Couple) { it }.man)
    }

    // --- The paper figure ---------------------------------------------------------------------------------------

    @Test fun everyOptionBuildsABoundedFigureInItsOwnColours() {
        val camera = PaperVehicleProjection()
        for ((i, p) in people.withIndex()) for (who in Who.entries) {
            val t = Traveller(who, p, people[(i + 5) % people.size])
            val mesh = TravellerMesh.build(t)
            assertEquals(TravellerMesh.RADIUS, mesh.radius, 0f)
            assertTrue("faces ${mesh.faces.size}", mesh.faces.size in 100..1600)
            mesh.faces.forEach { face ->
                assertTrue(face.indices.size in 3..16)
                assertTrue(face.indices.all { it in mesh.vertices.indices })
            }
            val colours = mesh.faces.map { it.color }.toSet()
            for (person in t.people) {
                assertTrue(person.skin.argb in colours)
                assertTrue(person.topColour.argb in colours)
                if (!person.dress) assertTrue(person.bottomColour.argb in colours)
                if (person.hair.hasHair && person.hair != HairStyle.Buzz || person.facialHair == FacialHair.Moustache) {
                    assertTrue("${person.hair} ${person.facialHair}", person.hairColour.argb in colours)
                }
                if (person.hat != Hat.None) assertTrue(person.hatColour.argb in colours)
            }
            // The theme's paper for shoes (and bags), never a person's colour.
            assertTrue(mesh.faces.any { it.color == 0 && it.material == PaperMaterial.Rubber })
            assertEquals(t.people.any { it.backpack }, mesh.faces.any { it.color == 0 && it.material == PaperMaterial.Terracotta })
            for (tilt in listOf(0f, 40f, 65f)) for (heading in listOf(0f, 95f, 180f, 270f)) {
                camera.pose(mesh, heading, tilt, .8f)
                assertTrue(camera.visibleFaces > 40)
                for (k in 0 until mesh.vertices.size * 3) assertTrue(camera.coordinates[k].isFinite())
                for (k in 0 until mesh.vertices.size) assertTrue(abs(camera.coordinates[k * 3 + 1]) <= .6f)
            }
        }
    }

    @Test fun aDressTakesTheTopsColourAndBaldHasNoHair() {
        val dress = Person(top = TopStyle.Long, topColour = Cloth.Red, bottom = BottomStyle.Dress, bottomColour = Cloth.Teal, hair = HairStyle.Bald, hairColour = HairColour.Pink, backpack = false)
        val colours = TravellerMesh.build(Traveller(man = dress)).faces.map { it.color }.toSet()
        assertTrue(Cloth.Red.argb in colours)
        assertFalse(Cloth.Teal.argb in colours)
        assertFalse(HairColour.Pink.argb in colours)
        // A beard brings the hair colour back.
        assertTrue(HairColour.Pink.argb in TravellerMesh.build(Traveller(man = dress.copy(facialHair = FacialHair.Beard))).faces.map { it.color })
    }

    @Test fun optionsChangeTheShape() {
        val base = TravellerMesh.build(Traveller()).faces.size
        assertNotEquals(base, TravellerMesh.build(Traveller(man = Person.MAN.copy(hat = Hat.SunHat))).faces.size)
        assertNotEquals(base, TravellerMesh.build(Traveller(man = Person.MAN.copy(hair = HairStyle.Curly))).faces.size)
        assertNotEquals(base, TravellerMesh.build(Traveller(man = Person.MAN.copy(glasses = Glasses.Glasses))).faces.size)
        assertNotEquals(base, TravellerMesh.build(Traveller(man = Person.MAN.copy(facialHair = FacialHair.Beard))).faces.size)
        val alone = TravellerMesh.build(Traveller())
        val tall = alone.vertices.maxOf { it.z } - alone.vertices.minOf { it.z }
        assertTrue("about as tall as the old walker: $tall", tall in 2.0f..2.4f)
    }

    // --- The couple ---------------------------------------------------------------------------------------------

    @Test fun aCoupleWalkSideBySideHoldingHands() {
        val gap = TravellerMesh.COUPLE_GAP
        assertEquals(listOf(0f), TravellerMesh.offsets(1).toList())
        assertEquals(listOf(-gap / 2, gap / 2), TravellerMesh.offsets(2).toList())
        val couple = TravellerMesh.build(Traveller(Who.Couple))
        val man = TravellerMesh.build(Traveller(Who.Man))
        // As tall as one alone (the same sphere), the man on the left, the woman on the right, apart but close.
        assertEquals(man.radius, couple.radius, 0f)
        val skinMan = Person.MAN.skin.argb
        val skinWoman = Person.WOMAN.skin.argb
        fun xs(skin: Int) = couple.faces.filter { it.color == skin }.flatMap { f -> f.indices.map { couple.vertices[it].x } }
        assertTrue(xs(skinMan).average() < -gap / 4)
        assertTrue(xs(skinWoman).average() > gap / 4)
        // Two arms hold hands: the same swing, the same shoulder height, either side of the middle.
        val held = couple.bones.filter { abs(it.swing) == TravellerMesh.HELD_SWING }
        assertEquals(2, held.size)
        assertEquals(held[0].swing, held[1].swing, 0f)
        assertEquals(held[0].z, held[1].z, 0f)
        assertEquals(0f, held[0].x + held[1].x, 1e-5f)
        // The two held hands meet in the middle.
        val heldBones = couple.bones.indices.filter { abs(couple.bones[it].swing) == TravellerMesh.HELD_SWING }
        val hands = heldBones.map { b -> couple.vertices.filter { it.bone == b && it.z < 1.04f } }
        assertTrue(hands.all { it.isNotEmpty() })
        val (left, right) = hands.sortedBy { h -> h.map { it.x }.average() }
        assertTrue(right.minOf { it.x } - left.maxOf { it.x } < .02f)
        assertTrue(abs(left.maxOf { it.x }) < .08f && abs(right.minOf { it.x }) < .08f)
        // Mirrored step: the man's left leg moves with the woman's right leg; the free arms swing fully.
        val legs = couple.bones.filter { abs(it.swing) == TravellerMesh.LEG_SWING }
        assertEquals(4, legs.size)
        val manLeft = legs.minBy { it.x }
        val womanRight = legs.maxBy { it.x }
        assertEquals(manLeft.swing, womanRight.swing, 0f)
        assertEquals(2, couple.bones.count { abs(it.swing) == TravellerMesh.ARM_SWING })
        // Alone, nobody's hand is held.
        assertTrue(man.bones.none { abs(it.swing) == TravellerMesh.HELD_SWING })
        assertEquals(2, man.bones.count { abs(it.swing) == TravellerMesh.ARM_SWING })
    }

    @Test fun theLimbsMoveWithTheStrideAndTheBodyStays() {
        val mesh = TravellerMesh.build(Traveller(Who.Couple))
        val camera = PaperVehicleProjection()
        camera.pose(mesh, 120f, 40f, 0f)
        val rest = camera.coordinates.copyOf()
        camera.pose(mesh, 120f, 40f, 1f)
        var moved = 0
        for (i in mesh.vertices.indices) {
            val changed = (0..2).any { abs(rest[i * 3 + it] - camera.coordinates[i * 3 + it]) > 1e-5f }
            if (mesh.vertices[i].bone == 0) assertFalse(changed) else if (changed) moved++
        }
        assertTrue(moved > 100)
        // Out-of-range strides are clamped; NaN stands still.
        camera.pose(mesh, 120f, 40f, 5f)
        val clamped = camera.coordinates.copyOf()
        camera.pose(mesh, 120f, 40f, 1f)
        for (i in 0 until mesh.vertices.size * 3) assertEquals(clamped[i], camera.coordinates[i], 0f)
        camera.pose(mesh, 120f, 40f, Float.NaN)
        for (i in 0 until mesh.vertices.size * 3) assertEquals(rest[i], camera.coordinates[i], 0f)
    }

    @Test fun detailsSortInFrontOfTheSurfaceTheySitOn() {
        val mesh = TravellerMesh.build(Traveller(man = Person.MAN.copy(top = TopStyle.Jacket, glasses = Glasses.Sunglasses)))
        val lifted = mesh.faces.filter { it.lift > 0f }
        assertTrue(lifted.isNotEmpty())
        // Long details come in short pieces, none taller than the torso's bands.
        for (f in lifted) {
            val z = f.indices.map { mesh.vertices[it].z }
            assertTrue(z.max() - z.min() <= .13f)
        }
        // The vehicles keep their own sorting.
        assertTrue(PaperVehicleMeshes.get(com.justdataplease.boringtravel.model.Vehicle.Car)!!.faces.all { it.lift == 0f && it.color == 0 })
    }

    private fun luminance(argb: Int): Double =
        0.2126 * (argb ushr 16 and 255) + 0.7152 * (argb ushr 8 and 255) + 0.0722 * (argb and 255)
}

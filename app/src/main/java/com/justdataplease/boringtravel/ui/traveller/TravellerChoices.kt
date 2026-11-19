package com.justdataplease.boringtravel.ui.traveller

import com.justdataplease.boringtravel.clip.BottomStyle
import com.justdataplease.boringtravel.clip.Cloth
import com.justdataplease.boringtravel.clip.FacialHair
import com.justdataplease.boringtravel.clip.Glasses
import com.justdataplease.boringtravel.clip.HairColour
import com.justdataplease.boringtravel.clip.HairStyle
import com.justdataplease.boringtravel.clip.Hat
import com.justdataplease.boringtravel.clip.Person
import com.justdataplease.boringtravel.clip.SkinTone
import com.justdataplease.boringtravel.clip.TopStyle
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.Who

/** One choice on a row: its name, and its colour when the row is swatches. */
internal data class TravellerChoice(val label: String, val argb: Int? = null)

/**
 * One row of a person's options (Me › Your traveller and the editor's Traveller tool draw the same rows in their own
 * controls): its [label], the [choices] (colour swatches when [swatches]), the one [selected], and how picking one
 * changes the person.
 */
internal class TravellerRow(
    val key: String,
    val label: String,
    val choices: List<TravellerChoice>,
    val selected: Int,
    val swatches: Boolean,
    private val apply: (Person, Int) -> Person,
) {
    /** [p] with choice [index] picked (an index off the row changes nothing). */
    fun pick(p: Person, index: Int): Person = if (index in choices.indices) apply(p, index) else p
}

/** The options a person has, as rows; rows that don't apply (a hat's colour without a hat) are left out. Pure. */
internal object TravellerChoices {
    private fun <T> chips(key: String, label: String, values: List<T>, selected: T, name: (T) -> String, set: (Person, T) -> Person) =
        TravellerRow(key, label, values.map { TravellerChoice(name(it)) }, values.indexOf(selected), false) { p, i -> set(p, values[i]) }

    private fun <T> swatches(key: String, label: String, values: List<T>, selected: T, name: (T) -> String, argb: (T) -> Int, set: (Person, T) -> Person) =
        TravellerRow(key, label, values.map { TravellerChoice(name(it), argb(it)) }, values.indexOf(selected), true) { p, i -> set(p, values[i]) }

    fun rows(p: Person): List<TravellerRow> = buildList {
        add(swatches("skin", "Skin", SkinTone.entries, p.skin, { it.label }, { it.argb }) { q, v -> q.copy(skin = v) })
        add(chips("hair", "Hair", HairStyle.entries, p.hair, { it.label }) { q, v -> q.copy(hair = v) })
        add(chips("face", "Facial hair", FacialHair.entries, p.facialHair, { it.label }) { q, v -> q.copy(facialHair = v) })
        if (p.hair.hasHair || p.facialHair != FacialHair.None) {
            add(swatches("hairColour", "Hair colour", HairColour.entries, p.hairColour, { it.label }, { it.argb }) { q, v -> q.copy(hairColour = v) })
        }
        add(chips("top", "Top", TopStyle.entries, p.top, { it.label }) { q, v -> q.copy(top = v) })
        add(swatches("topColour", if (p.dress) "Dress colour" else "Top colour", Cloth.entries, p.topColour, { it.label }, { it.argb }) { q, v -> q.copy(topColour = v) })
        add(chips("bottom", "Bottom", BottomStyle.entries, p.bottom, { it.label }) { q, v -> q.copy(bottom = v) })
        if (!p.dress) {
            add(swatches("bottomColour", "Bottom colour", Cloth.entries, p.bottomColour, { it.label }, { it.argb }) { q, v -> q.copy(bottomColour = v) })
        }
        add(chips("hat", "Hat", Hat.entries, p.hat, { it.label }) { q, v -> q.copy(hat = v) })
        if (p.hat != Hat.None) {
            add(swatches("hatColour", "Hat colour", Cloth.entries, p.hatColour, { it.label }, { it.argb }) { q, v -> q.copy(hatColour = v) })
        }
        add(chips("glasses", "Glasses", Glasses.entries, p.glasses, { it.label }) { q, v -> q.copy(glasses = v) })
        add(chips("bag", "Backpack", listOf(false, true), p.backpack, { if (it) "Backpack" else "None" }) { q, v -> q.copy(backpack = v) })
    }

    /** Whose look the rows change: the one shown, or for a couple the one picked ([editing]). */
    fun slot(t: Traveller, editing: Who): Who = when (t.who) {
        Who.Couple -> if (editing == Who.Woman) Who.Woman else Who.Man
        else -> t.who
    }

    /** "Man", "Woman" or "Couple" with a word about the look, for Me's row. */
    fun summary(t: Traveller): String = when (t.who) {
        Who.Couple -> "Couple, hand in hand"
        Who.Man -> "Man"
        Who.Woman -> "Woman"
    }
}

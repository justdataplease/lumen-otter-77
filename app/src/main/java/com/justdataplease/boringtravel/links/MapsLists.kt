package com.justdataplease.boringtravel.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/**
 * One saved place of a list shared from Google Maps: its [name] and pin, its [address] when the list gives one, the
 * [note] the list's owner wrote beside it, and the place's own Google Maps link ([mapsUrl]).
 */
data class MapsListPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    val note: String = "",
    val mapsUrl: String? = null,
)

/**
 * A list of saved places shared from Google Maps (a list › Share): its [name] and [description], the [places] that
 * have a pin (at most [MapsLists.MAX_PLACES]) and how many the list holds ([total]).
 */
data class MapsList(val id: String, val name: String, val description: String, val places: List<MapsListPlace>, val total: Int) {
    /** The list holds more places than were read. */
    val more: Boolean get() = total > places.size
}

/** A link to a Google Maps list that gave no list: it isn't shared, or Google answered something else. */
class MapsListException : IOException(MapsLists.NOT_READ)

/**
 * Lists of saved places shared from Google Maps ("Want to go", "Lisbon food": a list › Share gives a
 * `maps.app.goo.gl/…` link). The link passes on to a long Maps link that holds the list's id ([ref]); the places come
 * from the request the list's own page makes ([requestUrls], checked on 17 Nov 2026 with three public lists: it
 * answers the app's own User-Agent without sign-in or cookies). It is not a public API, so it may change: [parse]
 * reads only what it recognises and anything else is "couldn't be read". Pure and unit tested; the network step is
 * [LinkFetcher.mapsList].
 */
object MapsLists {
    /** Places asked for and kept from one list. */
    const val MAX_PLACES = 500

    /** A list's answer: about 450 bytes a place. */
    const val MAX_BYTES = 2L * 1024 * 1024

    const val NOT_READ = "This Google Maps list couldn't be read. In Google Maps open the list, choose Share, and paste that link."
    const val NOT_A_LIST = "This Google Maps link is one place, not a list. To add a place, share it from Google Maps to BoringTravel."

    private const val MAX_NAME = 120
    private const val MAX_ADDRESS = 200
    private const val MAX_NOTE = 500

    /** A list's id, and the token of one shared with the link only. */
    data class Ref(val id: String, val token: String? = null)

    /** "…/data=!4m3!11m2!2s<id>!3e3" (the address a shared list opens at). */
    private val inData = Regex("!11m\\d+!2s([A-Za-z0-9_-]{10,})")

    /** "…/maps/placelists/list/<id>". */
    private val inPath = Regex("/maps/placelists/list/([A-Za-z0-9_-]{10,})")
    private val tokenRe = Regex("(?:[?&]token=|token%3D)([A-Za-z0-9_-]+)")

    /** The list a long Maps link points at; null for a place, a search, a plain map, or a short link not followed yet. */
    fun ref(url: String): Ref? {
        if (!MapsLinks.isMaps(url) || MapsLinks.isShort(url)) return null
        val plain = url.replace("%21", "!")
        val id = (inPath.find(plain) ?: inData.find(plain))?.groupValues?.get(1) ?: return null
        return Ref(id, tokenRe.find(plain)?.groupValues?.get(1))
    }

    /** The list's own page address ("Open in Google Maps"). */
    fun pageUrl(ref: Ref): String = "https://www.google.com/maps/placelists/list/${ref.id}" + (ref.token?.let { "?token=$it" } ?: "")

    /**
     * What to ask, in order: the request the list's page makes (the page's address inside it, which carries the token
     * of a list shared with the link only), then the older form by the list's id alone.
     */
    fun requestUrls(ref: Ref, session: String = UUID.randomUUID().toString().replace("-", "").take(22)): List<String> {
        val base = "https://www.google.com/maps/preview/entitylist/getlist?authuser=0&hl=en&gl=us&pb="
        val page = URLEncoder.encode(pageUrl(ref), "UTF-8")
        return listOfNotNull(
            "$base!2e2!3e2!4i$MAX_PLACES!6m3!1s$session!15i204459!28e2!13s$page!16b1",
            if (ref.token == null) "$base!1m4!1s${ref.id}!2e1!3m1!1e1!2e2!3e2!4i$MAX_PLACES!16b1" else null,
        )
    }

    /**
     * The list in Google's answer (`)]}'` then nested arrays: the list at [0], its name at [0][4], its description at
     * [0][5], the places at [0][8], how many there are at [0][12]); null when the answer holds no list.
     */
    fun parse(answer: String): MapsList? {
        val text = answer.trimStart().removePrefix(")]}'").trimStart()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        val head = root.array(0) ?: return null
        val id = head.array(0)?.text(0) ?: return null
        val entries = head.array(8).orEmpty()
        val places = entries.mapNotNull(::place)
        val name = head.text(4).orEmpty()
        if (name.isEmpty() && places.isEmpty()) return null
        val total = head.number(12)?.toInt() ?: entries.size
        return MapsList(id, name.take(MAX_NAME), head.text(5).orEmpty().take(MAX_NOTE), places.take(MAX_PLACES), maxOf(total, places.size))
    }

    /**
     * One entry: `[null, [null, null, label, null, address, [null, null, lat, lng], [id, id], "/g/…"], name, note, …]`.
     * An entry without a pin is left out.
     */
    private fun place(entry: JsonElement): MapsListPlace? {
        val e = entry as? JsonArray ?: return null
        val info = e.array(1) ?: return null
        val point = info.array(5) ?: return null
        val lat = point.number(2) ?: return null
        val lng = point.number(3) ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        // A saved address has no place behind it: its label is "The White House, 1600 Pennsylvania Ave NW, …".
        val label = info.text(2).orEmpty()
        val name = e.text(2) ?: label.substringBefore(',').trim().ifEmpty { return null }
        val address = info.text(4) ?: label
        // The place's id as two signed numbers; the second, unsigned, is the number Maps links name a place by (cid).
        val cid = info.array(6)?.text(1)?.toLongOrNull()
        return MapsListPlace(
            name.take(MAX_NAME), lat, lng, address.take(MAX_ADDRESS), e.text(3).orEmpty().take(MAX_NOTE),
            cid?.let { "https://maps.google.com/?cid=" + java.lang.Long.toUnsignedString(it) },
        )
    }

    private val json = Json { isLenient = true }

    private fun JsonArray.array(i: Int): JsonArray? = getOrNull(i) as? JsonArray

    private fun JsonArray.text(i: Int): String? =
        (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonArray.number(i: Int): Double? =
        (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}

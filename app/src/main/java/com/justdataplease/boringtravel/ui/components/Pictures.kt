package com.justdataplease.boringtravel.ui.components

import android.graphics.Picture
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.justdataplease.boringtravel.art.SceneArt
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.art.ScenePalette
import com.justdataplease.boringtravel.art.ScenePalettes
import com.justdataplease.boringtravel.art.ScenePick
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlin.math.ceil

/*
 * Drawn pictures: wherever there is no real photo (a trip without a cover, an entry without photos, a place), the
 * app draws one of ten kinds of place (mountain, lake, city, village, coast, forest, glacier, countryside, desert,
 * night) in the current theme's colours. Real photos never change; a drawn one redraws when the theme changes.
 *
 *   DrawnScene(sceneFor(trip, country), Modifier.size(...), key = trip.id)
 *   DrawnScene(sceneFor(entry), ..., key = entry.id)
 *   DrawnScene(sceneFor("Laguna Capri", country = "AR"), ...)
 */

/** The one place a theme picks its drawn pictures' colours (the board's palettes share the themes' ids). */
fun Look.scenePalette(): ScenePalette = when (this) {
    Look.PostcardPop -> ScenePalettes.pop
    Look.Trail -> ScenePalettes.trail
    Look.StickerBook -> ScenePalettes.sticker
    Look.Sketchbook -> ScenePalettes.sketch
    Look.Market -> ScenePalettes.market
}

/** Lets a screen or preview force a palette; null follows the theme. */
val LocalScenePalette = staticCompositionLocalOf<ScenePalette?> { null }

/** The drawn pictures' colours for the current theme. */
val currentScenePalette: ScenePalette
    @Composable @ReadOnlyComposable get() = LocalScenePalette.current ?: Bt.tokens.look.scenePalette()

/**
 * A drawn picture of [kind] filling the given size (centred and cropped), no frame. [key] (a trip or entry id)
 * mirrors it for about half of them, so two trips of one kind don't look the same. Decorative unless [description].
 */
@Composable
fun DrawnScene(
    kind: SceneKind,
    modifier: Modifier = Modifier,
    key: String? = null,
    palette: ScenePalette = currentScenePalette,
    mirror: Boolean = key?.let(ScenePick::mirrorFor) ?: false,
    description: String? = null,
) {
    val labelled = if (description != null) Modifier.semantics { contentDescription = description; role = Role.Image } else Modifier
    Spacer(
        modifier.then(labelled).drawWithCache {
            val w = size.width
            val h = size.height
            // Recorded once per size, kind and palette, then replayed: cheap enough for every list row.
            val picture = Picture()
            val canvas = picture.beginRecording(ceil(w).toInt().coerceAtLeast(1), ceil(h).toInt().coerceAtLeast(1))
            SceneArt.draw(canvas, w, h, kind, palette, mirror)
            picture.endRecording()
            onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawPicture(picture) } }
        },
    )
}

/** A trip's drawn picture: from its name, then its country (ISO code, e.g. from the flags), then its id. */
fun sceneFor(trip: Trip, country: String? = null): SceneKind = ScenePick.forTrip(trip.id, trip.title, trip.subtitle, country)

/** An entry's drawn picture: from its place, then its title and text, then where it was written. */
fun sceneFor(entry: Entry, country: String? = null): SceneKind =
    ScenePick.forPlace(entry.placeName, "${entry.title} ${entry.plainText}", country, entry.lat, entry.lng, key = entry.id)

/** A place's drawn picture: from its name, then the country, then the spot on the map. */
fun sceneFor(placeName: String, country: String? = null, lat: Double? = null, lng: Double? = null): SceneKind =
    ScenePick.forPlace(placeName, country = country, lat = lat, lng = lng)

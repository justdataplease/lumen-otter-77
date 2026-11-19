package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.FlagBadges
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.globe.GlobePin
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.world.CountryStat
import com.justdataplease.boringtravel.world.World
import kotlin.math.roundToLong

/*
 * The small pieces every world screen uses, as the Android app's ui/world/WorldBits.kt: the flag sticker, a trip's
 * flags, the count lines, the stats under the globe, the totals, and where the pins and the globe's home are. Java's
 * NumberFormat writes "1,234" here ([grouped]), in the phone's language ("1.234" on a Greek phone).
 */

/** A whole number with thousands grouped, as Java's integer NumberFormat writes it in the phone's language: "1,234". */
fun grouped(n: Long): String = PhoneTexts.integer(n)

/**
 * A round flag sticker (the same badge the globe draws, [FlagBadges]). [dashed] = a country still to come: a dashed
 * ring, faded. [tilt]: leans a little in the notebook looks.
 */
@Composable
fun FlagSticker(code: String, size: Dp, modifier: Modifier = Modifier, dashed: Boolean = false, tilt: Boolean = true) {
    val density = LocalDensity.current.density
    val c = Bt.colors
    val notebook = Bt.tokens.look.isNotebook
    val px = with(LocalDensity.current) { size.roundToPx() }
    val ring = (if (size < 24.dp) 1.5f else 2.5f) * density
    val shadow = if (dashed || size < 24.dp) 0f else 2.5f * density
    val spec = FlagBadges.Spec(px, ring, shadow, if (notebook && tilt) -6f else 0f, c.chip.toArgb(), c.ink.toArgb())
    val measurer = rememberTextMeasurer()
    val image = remember(code, spec) { FlagBadges.badge(code, spec, measurer) }
    val margin = FlagBadges.margin(spec)
    val full = with(LocalDensity.current) { (px + 2 * margin).toDp() }
    // The picture carries a margin for its shadow; it overflows the sticker's box, centred.
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            image, contentDescription = CountryInfo.name(code),
            modifier = Modifier.requiredSize(full).then(if (dashed) Modifier.alpha(0.4f) else Modifier),
        )
        if (dashed) {
            val muted = c.muted
            Canvas(Modifier.size(size + 4.dp)) {
                val w = 2.dp.toPx()
                drawCircle(muted, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
        }
    }
}

/** A trip's countries as small quiet flags, in travel order ("+2" past [max]). */
@Composable
fun TripFlags(codes: List<String>, modifier: Modifier = Modifier, size: Dp = 16.dp, max: Int = 4) {
    if (codes.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-3).dp), verticalAlignment = Alignment.CenterVertically) {
        codes.take(max).forEach { FlagSticker(it, size, tilt = false) }
        if (codes.size > max) Text("+${codes.size - max}", Modifier.padding(start = 6.dp), style = Bt.text.mono.copy(fontSize = 11.sp))
    }
}

/** "7 countries · 4% of the world", "1 country · 1% of the world", "No countries yet". */
fun countLine(world: World?): String {
    val n = world?.countries ?: 0
    return when (n) {
        0 -> "No countries yet"
        1 -> "1 country · ${world!!.worldPercent}% of the world"
        else -> "$n countries · ${world!!.worldPercent}% of the world"
    }
}

fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

/** Countries, continents and days away under the globe; tapping opens Your world. */
@Composable
fun WorldStatsStrip(world: World?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val n = world?.countries ?: 0
    val continents = world?.continents?.size ?: 0
    val days = world?.daysAway ?: 0
    Column(modifier) {
        BtDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Your world", onClick = onClick) else Modifier)
                .padding(top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat(grouped(n.toLong()), plural(n, "country", "countries"))
            Stat(grouped(continents.toLong()), plural(continents, "continent", "continents"))
            Stat(grouped(days.toLong()), plural(days, "day away", "days away"))
            if (onClick != null) Icon(BtIcons.Forward, null, tint = Bt.colors.muted)
        }
    }
}

/** Trips, days away and km (the bottom of Your world). */
@Composable
fun TravelTotals(world: World?, modifier: Modifier = Modifier) {
    val trips = world?.trips ?: 0
    val days = world?.daysAway ?: 0
    Column(modifier) {
        BtDivider()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(grouped(trips.toLong()), plural(trips, "trip", "trips"))
            Stat(grouped(days.toLong()), plural(days, "day away", "days away"))
            // Km only once a route was recorded; without tracks it would always read 0.
            val km = (world?.km ?: 0.0).roundToLong()
            if (km > 0) Stat(grouped(km), "km")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Bt.text.mono.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = Bt.colors.ink)
        Text(label, style = Bt.text.fine)
    }
}

/** Flag pins for the globe: visited countries, the ones with the most days first (they win space when crowded). */
fun worldPins(world: World?): List<GlobePin> {
    if (world == null) return emptyList()
    return world.visited
        .sortedWith(compareByDescending<CountryStat> { it.days }.thenByDescending { it.tripIds.size }.thenBy { it.code })
        .mapNotNull { s ->
            val (lat, lng) = CountryInfo.labelPoint(s.code) ?: s.ownCenter?.let { it.lat to it.lng } ?: return@mapNotNull null
            GlobePin(s.code, lat, lng, CountryInfo.name(s.code))
        }
}

/** Where the globe starts: the middle of the traveller's countries, else the Mediterranean. */
fun worldHome(world: World?): Pair<Double, Double> {
    val points = world?.visited?.mapNotNull { s -> CountryInfo.labelPoint(s.code) ?: s.ownCenter?.let { it.lat to it.lng } }.orEmpty()
    val mean = GlobeMath.meanPoint(points) ?: return 36.0 to 8.0
    // Tilt towards the equator a little so the countries sit in the upper middle, where the eye goes.
    return (mean.first - 6.0).coerceIn(-50.0, 60.0) to mean.second
}

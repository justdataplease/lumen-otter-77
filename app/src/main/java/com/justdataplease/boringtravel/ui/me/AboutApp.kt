package com.justdataplease.boringtravel.ui.me

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * The app's public pages on justdataplease.com and the credits for what it shows. The app no longer has a credits
 * page (owner, 2026-11-14: Me keeps only the version): maps, clips, place search and Wikipedia photos carry their own
 * credit where they show, and [credits] is the full list for the app's page on the site.
 */
object AboutApp {
    const val PRIVACY_URL = "https://justdataplease.com/apps/boringtravel/privacy.html"
    const val DELETE_URL = "https://justdataplease.com/apps/boringtravel/delete-account.html"
    const val CONTACT = "contact@example.com"

    /** Who made the maps, places, pictures, music, fonts and libraries the app uses, one short line each. */
    val credits: List<Pair<String, String>> = listOf(
        "Maps" to "© OpenStreetMap contributors (ODbL). Tiles by OpenFreeMap, © OpenMapTiles.",
        "Terrain" to "Mapzen Terrain Tiles on AWS Open Data: USGS, NOAA, Copernicus EU-DEM and others.",
        "Earth" to "Sentinel-2 cloudless 2016 by EOX IT Services GmbH (CC BY 4.0), contains modified Copernicus Sentinel data 2016 & 2017.",
        "Place search" to "Photon by komoot, with OpenStreetMap data.",
        "Place photos" to "Wikipedia and Wikimedia Commons. Each photo shows its author and licence; tap them for the photo's page.",
        "Site icons" to "Google's favicon service.",
        "Countries" to "Natural Earth (public domain).",
        "Music" to "CC0 tracks by Victor Samalot, Some Weirdo, Shaloza, TRG Banks, Pro Sensory, Patrick Steel and jomusic.",
        "Fonts" to "SIL Open Font License 1.1; the licence texts ship with the app.",
        "Software" to "MapLibre Native (BSD 2-Clause); AndroidX, Jetpack Compose, Kotlin, OkHttp, Coil, Compose Rich Editor, Reorderable, the Anthropic Java SDK and Jackson (Apache License 2.0); Firebase and Google Play services (Google's terms).",
    )
}

/** Opens the privacy policy in the browser. */
fun openPrivacyPolicy(context: android.content.Context) {
    if (!Sharing.openUrl(context, AboutApp.PRIVACY_URL)) {
        Toast.makeText(context, AboutApp.PRIVACY_URL, Toast.LENGTH_LONG).show()
    }
}

/** Opens the page on deleting the account, the trips on the phone and the Drive backups. */
fun openDeletionPage(context: android.content.Context) {
    if (!Sharing.openUrl(context, AboutApp.DELETE_URL)) {
        Toast.makeText(context, AboutApp.DELETE_URL, Toast.LENGTH_LONG).show()
    }
}

/** A quiet "Privacy policy" link (Welcome). */
@Composable
fun PrivacyLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(
        "Privacy policy",
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { openPrivacyPolicy(context) }.padding(vertical = 14.dp),
        style = Bt.text.sub,
        color = Bt.colors.accentInk,
    )
}

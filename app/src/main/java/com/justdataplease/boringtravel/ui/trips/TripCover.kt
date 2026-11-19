package com.justdataplease.boringtravel.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.SceneKind
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.sceneFor
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * A trip's picture: its cover photo, else its first photo ([firstPhotoId], see MediaRepository.observeFirstPhotos),
 * else a drawn picture picked for the trip (name, then [country], then id) in the theme's colours.
 */
@Composable
fun TripCover(trip: Trip, firstPhotoId: String?, country: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val photo = trip.coverMediaId ?: firstPhotoId
    if (photo != null) {
        MediaImage(photo, modifier, contentDescription = contentDescription ?: "Cover photo", playBadge = false)
    } else {
        val kind = remember(trip.id, trip.title, trip.subtitle, country) { sceneFor(trip, country) }
        DrawnScene(kind, modifier, key = trip.id)
    }
}

/** A cover being chosen: the photo, or the drawn picture the trip shows without one ([kind]), else an empty frame. */
@Composable
fun CoverPlaceholder(modifier: Modifier = Modifier, kind: SceneKind? = null, key: String? = null) {
    if (kind != null) {
        DrawnScene(kind, modifier, key = key)
        return
    }
    Box(modifier.background(Bt.colors.chip), contentAlignment = Alignment.Center) {
        Icon(BtIcons.Photo, "No cover photo", Modifier.size(28.dp), tint = Bt.colors.muted)
    }
}

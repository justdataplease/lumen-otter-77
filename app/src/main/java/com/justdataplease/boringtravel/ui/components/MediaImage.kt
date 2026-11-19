package com.justdataplease.boringtravel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.justdataplease.boringtravel.art.DemoArt
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import java.io.File

/**
 * A stored photo (files/media/...), cropped to fill; a quiet colour block while loading or when missing. A demo
 * picture (see DemoArt) is drawn live in the current theme instead of showing its file. A video shows its first frame
 * (Coil's video frame decoder) with a play mark ([VideoBadge]) unless [playBadge] is off (covers, printed pages).
 */
@Composable
fun MediaImage(
    media: Media?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    playBadge: Boolean = true,
) {
    val context = LocalContext.current
    if (media == null) {
        Box(modifier.background(Bt.colors.chip))
        return
    }
    val drawn = remember(media.localPath) { DemoArt.live(media.localPath) }
    if (drawn != null) {
        val description = contentDescription ?: "Photo"
        val ratio = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 0f
        if (ratio > 0f && (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside)) {
            // Fitted like the photo would be: its own shape inside the box.
            Box(modifier, contentAlignment = Alignment.Center) {
                DrawnScene(drawn.kind, Modifier.aspectRatio(ratio), mirror = drawn.mirror, description = description)
            }
        } else {
            DrawnScene(drawn.kind, modifier, mirror = drawn.mirror, description = description)
        }
        return
    }
    // A photo from a shared trip may arrive after its tile is shown (the row comes first): its tile loads again then.
    val fetched = media.sharedFileId != null && (media.authorId != null || media.sha256 == null)
    val arrivals = if (fetched) context.graph.together.photosVersion.collectAsState().value else 0
    val request = remember(media.localPath, arrivals) {
        ImageRequest.Builder(context).data(File(context.filesDir, media.localPath))
            .apply { if (arrivals > 0) memoryCacheKeyExtra("arrivals", arrivals.toString()) }
            .build()
    }
    val description = if (media.isVideo && (contentDescription == null || contentDescription == "Photo")) "Video" else contentDescription ?: "Photo"
    Box(modifier.background(Bt.colors.chip)) {
        AsyncImage(model = request, contentDescription = description, contentScale = contentScale, modifier = Modifier.matchParentSize())
        if (playBadge && media.isVideo) VideoBadge(Modifier.matchParentSize())
    }
}

/** Same, by media id (looks the row up once). */
@Composable
fun MediaImage(
    mediaId: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    playBadge: Boolean = true,
) {
    val graph = LocalContext.current.graph
    var media by remember(mediaId) { mutableStateOf<Media?>(null) }
    LaunchedEffect(mediaId) { media = mediaId?.let { graph.media.get(it) } }
    MediaImage(media, modifier, contentDescription, contentScale, playBadge)
}

/**
 * The play mark on a video's picture: a dark round mark in the middle, about a third of the picture's short side (as
 * the trip's saved clips show it), so it reads on a list thumbnail and on a full-width note alike.
 */
@Composable
fun VideoBadge(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val d = (minOf(maxWidth, maxHeight) * 0.3f).coerceIn(18.dp, 52.dp)
        Box(Modifier.size(d).clip(CircleShape).background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Play, null, Modifier.size(d * 0.52f), tint = Color.White)
        }
    }
}

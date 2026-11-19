package com.justdataplease.boringtravel.ui.trip

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.links.LinkPreview
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.PlacePhotos
import com.justdataplease.boringtravel.links.PlaceQuery
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.ui.components.btCardShape
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/*
 * A plan item's link, or its place's photo, on screen. The preview is looked up only while a row or the sheet shows
 * it (keyed on the link or the place), off the main thread, and kept; pictures cross-fade in over what was there, so
 * nothing moves meanwhile. Me › Place photos off: no Wikipedia pictures anywhere.
 */

/**
 * The preview of [url] (null without a link): what is kept at once, then a fresh look while this is on screen.
 * [renewed] changes when the links were read again (Renew details), so what is kept is read again too.
 */
@Composable
internal fun rememberLinkPreview(url: String?, near: LatLng? = null, renewed: Int = 0): LinkPreview? {
    val links = LocalContext.current.graph.links
    val photosOn by links.placePhotosOn.collectAsState()
    var preview by remember(url, photosOn, renewed) { mutableStateOf(url?.let(links::peek)) }
    LaunchedEffect(url, photosOn, renewed) {
        if (url != null) links.preview(url, near)?.let { preview = it }
    }
    return preview
}

/**
 * The Wikipedia photo of a located item's place ([query]; null for none): what is kept at once, then a look while
 * this is on screen. Null while Me › Place photos is off.
 */
@Composable
internal fun rememberPlacePhoto(query: PlaceQuery?): LinkPreview? {
    val links = LocalContext.current.graph.links
    val photosOn by links.placePhotosOn.collectAsState()
    val key = query?.key
    var photo by remember(key, photosOn) { mutableStateOf(query?.let(links::peekPlace)) }
    LaunchedEffect(key, photosOn) {
        if (query != null && photosOn) links.placePhoto(query)?.let { photo = it }
    }
    return photo?.takeIf { photosOn && it.imageUrl != null }
}

/** A favicon's backing in dark looks: dark icons on the dark paper would vanish. */
@Composable
private fun iconTile(): Color = if (Bt.tokens.look.dark) Color(0xFFF4F1EA) else Bt.colors.surface

/**
 * Over a plan row's round kind badge (same size, already clipped to the circle): the site's picture cropped to
 * the circle; without one, the site's favicon in the middle of a plain circle; without either, nothing (the kind
 * icon underneath stays). Each layer appears only once its picture has loaded.
 */
@Composable
internal fun BoxScope.LinkBadgeLayers(link: String, size: Dp, fallback: PlaceQuery? = null) {
    val url = remember(link) { LinkUrls.normalize(link) } ?: return
    val loader = LocalContext.current.graph.links.imageLoader
    val preview = rememberLinkPreview(url)
    val icon = preview?.faviconUrl
    if (icon != null) {
        var shown by remember(icon) { mutableStateOf(false) }
        val tile = iconTile()
        val line = Bt.colors.line
        Box(
            Modifier.matchParentSize().then(if (shown) Modifier.background(tile).border(1.dp, line, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = icon, contentDescription = null, imageLoader = loader, modifier = Modifier.size(size * 0.5f),
                onSuccess = { shown = true }, onError = { shown = false },
            )
        }
    }
    val image = preview?.imageUrl
    if (image != null) {
        AsyncImage(model = image, contentDescription = null, imageLoader = loader, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    } else if (preview != null && fallback != null) {
        // The site has no picture: the place's photo goes over its icon (site picture, place photo, site icon).
        PlacePhotoLayer(fallback)
    }
}

/**
 * Over a plan row's round kind badge (same size, already clipped to the circle): the photo of the item's place,
 * cropped to the circle, once it has loaded; until then (or without one) the kind icon underneath stays.
 */
@Composable
internal fun BoxScope.PlacePhotoLayer(query: PlaceQuery) {
    val image = rememberPlacePhoto(query)?.imageUrl ?: return
    AsyncImage(
        model = image, contentDescription = null, imageLoader = LocalContext.current.graph.links.imageLoader,
        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
    )
}

/** Opens [url] in the browser (or the app that handles it, like Google Maps); false when nothing can. */
internal fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * The item sheet's link: with a picture, the picture (16:9, the theme's card shape; on a Wikipedia photo its credit,
 * "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia", which opens the file's page) over the favicon, the page's name
 * ([title] instead when given) and the domain ([source] instead when given); without one, a compact row with the
 * favicon, the domain and the [title] when given, or nothing when [pictureOnly]. A tap opens the link.
 */
@Composable
internal fun LinkPreviewCard(
    url: String,
    preview: LinkPreview?,
    modifier: Modifier = Modifier,
    source: String? = null,
    pictureOnly: Boolean = false,
    title: String? = null,
) {
    val c = Bt.colors
    val context = LocalContext.current
    val loader = context.graph.links.imageLoader
    var openError by remember(url) { mutableStateOf(false) }
    val maps = remember(url) { MapsLinks.isMaps(url) }
    val where = source ?: if (maps) MapsLinks.SITE_NAME else LinkUrls.domain(url)
    val name = (title ?: preview?.title ?: preview?.siteName)?.takeIf { it != where }
    val open = { openError = !openLink(context, url) }
    val image = preview?.imageUrl
    var imageFailed by remember(image) { mutableStateOf(false) }
    if (pictureOnly && (image == null || imageFailed)) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (image != null && !imageFailed) {
            Column(
                Modifier.fillMaxWidth().clip(btCardShape()).clickable(role = Role.Button, onClickLabel = "Open link", onClick = open),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(btCardShape()).background(c.chip)) {
                    AsyncImage(
                        model = image, contentDescription = name ?: where, imageLoader = loader,
                        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                        onError = { imageFailed = true },
                    )
                    val credit = preview.credit
                    if (credit != null) {
                        // Wikipedia photos are mostly CC BY-SA: author and licence wherever the photo shows large.
                        val page = preview.creditUrl
                        Text(
                            credit,
                            Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                                .then(if (page != null) Modifier.clickable(role = Role.Button, onClickLabel = "Photo credit") { openError = !openLink(context, page) } else Modifier)
                                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            style = Bt.text.fine, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Favicon(preview.faviconUrl, 20.dp)
                    // A long press copies the page's name, or the link from its address line.
                    Column(Modifier.weight(1f)) {
                        Text(
                            name ?: where, Modifier.copyOnLongPress(name ?: url, if (name != null) "Name" else "Link"),
                            style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (name != null) Text(where, Modifier.copyOnLongPress(url, "Link"), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = c.muted)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(btControlShape()).background(c.chip)
                    .clickable(role = Role.Button, onClickLabel = "Open link", onClick = open)
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Favicon(preview?.faviconUrl, 20.dp, fallback = BtIcons.World)
                val named = title?.takeIf { it != where }
                if (named != null) {
                    Column(Modifier.weight(1f)) {
                        Text(named, Modifier.copyOnLongPress(named, "Name"), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(where, Modifier.copyOnLongPress(url, "Link"), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(
                        where, Modifier.weight(1f).copyOnLongPress(url, "Link"),
                        style = Bt.text.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = c.muted)
            }
        }
        if (openError) Text("No app here can open this link.", style = Bt.text.fine, color = c.danger)
    }
}

/**
 * The item sheet's photo of a place without a link ([photo], from [rememberPlacePhoto]): the picture with its credit
 * ("Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia"), the article's name and "Wikipedia"; a tap opens the article.
 */
@Composable
internal fun PlacePhotoCard(photo: LinkPreview, modifier: Modifier = Modifier) {
    val title = photo.title ?: return
    val article = remember(title) { PlacePhotos.articleUrl(title) }
    LinkPreviewCard(article, photo.copy(url = article), modifier, source = PlacePhotos.SITE_NAME, pictureOnly = true)
}

/** A site's favicon at [size] (on a light tile in dark looks); [fallback] until it loads, or when there is none. */
@Composable
private fun Favicon(url: String?, size: Dp, fallback: ImageVector? = null) {
    if (url == null && fallback == null) return
    var shown by remember(url) { mutableStateOf(false) }
    val dark = Bt.tokens.look.dark
    val tile = iconTile()
    Box(
        Modifier.size(size).then(if (shown && dark) Modifier.clip(RoundedCornerShape(5.dp)).background(tile) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!shown && fallback != null) Icon(fallback, null, Modifier.size(size), tint = Bt.colors.muted)
        if (url != null) {
            AsyncImage(
                model = url, contentDescription = null, imageLoader = LocalContext.current.graph.links.imageLoader,
                modifier = Modifier.size(if (dark) size * 0.8f else size),
                onSuccess = { shown = true }, onError = { shown = false },
            )
        }
    }
}

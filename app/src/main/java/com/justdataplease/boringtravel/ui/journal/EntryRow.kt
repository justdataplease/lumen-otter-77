package com.justdataplease.boringtravel.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.DrawnScene
import com.justdataplease.boringtravel.ui.components.MediaImage
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.components.sceneFor
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One entry in a list: date block, title, two lines of text, the mono meta line and its first photo. */
@Composable
fun EntryRow(entry: Entry, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val videos by remember(graph) { graph.media.observeVideoIds() }.collectAsStateWithLifecycle(initialValue = emptySet())
    val info = remember(entry, videos) { EntryInfo(entry, videos) }
    EntryRow(info, onClick)
}

@Composable
fun EntryRow(info: EntryInfo, onClick: () -> Unit, index: Int = 0) {
    val c = Bt.colors
    val date = info.date
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }
    val monthLabel = remember(date, monthFormatter, locale) { monthFormatter.format(date).uppercase(locale) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The day in the display face, the month in mono (design boards "Journal").
                Text(
                    Bt.text.display(date.dayOfMonth.toString()),
                    style = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(28.sp), lineHeight = Bt.tokens.type.displaySize(30.sp)),
                )
                Text(
                    monthLabel,
                    style = Bt.text.eyebrow,
                    textAlign = TextAlign.Center,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    info.entry.title.ifBlank { "${Dates.weekday(date)} ${Dates.dayMonth(date)}" },
                    style = Bt.text.item,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.preview.isNotBlank()) {
                    Text(info.preview, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (info.meta.isNotBlank()) {
                    Text(info.meta, Modifier.padding(top = 6.dp), style = Bt.text.mono.copy(fontSize = Bt.text.eyebrow.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val photo = info.photoIds.firstOrNull()
            // Framed and leaning the theme's way, alternating down the list; no photo: a drawn picture of the place
            // (see Pictures.kt), in the theme's colours.
            PhotoFrame(Modifier.size(66.dp), index = index) {
                val album = info.firstAlbum
                if (album != null) AlbumMini(album, Modifier.fillMaxSize())
                else if (photo != null) MediaImage(photo, Modifier.fillMaxSize(), contentDescription = "Photo")
                else DrawnScene(remember(info.entry) { sceneFor(info.entry) }, Modifier.fillMaxSize(), key = info.entry.id)
            }
        }
        BtDivider()
    }
}

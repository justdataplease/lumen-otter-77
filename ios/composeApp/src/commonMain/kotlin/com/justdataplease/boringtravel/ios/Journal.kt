package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.NoteText
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.PhotoFrame
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlin.math.roundToInt

/*
 * The journal's lists, as the Android app has them (ui/journal): a trip's Journal tab, and Notes outside trips for the
 * notes written when no trip was on. Every note is in one of the two.
 */

/**
 * A note with what the lists show, read once (the Android app's EntryInfo): its day, the first words, its photos, and
 * the mono line "Naxos · 27° · 3 photos". [videoIds]: the ids of the trip's videos, counted apart from the photos.
 */
class NoteInfo(val entry: Entry, videoIds: Set<String> = emptySet()) {
    private val doc: EntryDoc = EntryDoc.parse(entry.body)
    val date: LocalDate = localDateAt(entry.occurredAt, entry.zoneId)
    val preview: String = NoteText.preview(doc)

    /** Its photos and videos, in reading order. */
    val photoIds: List<String> = doc.mediaIds().filter { it.isNotBlank() }

    /** The note's first photo when it is in a photo layout: the list shows that layout in small. */
    val firstAlbum: Block.Album? = doc.blocks.firstOrNull { (it is Block.Photo && it.mediaId.isNotBlank()) || it is Block.Album } as? Block.Album
    val meta: String = photoIds.count { it in videoIds }.let { videos -> noteMeta(entry.placeName, entry.tempC, photoIds.size - videos, videos) }
}

/** The ids of every video in [book] (the Android app's MediaRepository.observeVideoIds). */
fun videoIds(book: TripBook): Set<String> =
    book.snapshot.media.filter { it.deletedAt == null && it.mime.startsWith("video/") }.mapTo(HashSet()) { it.id }

/** The notes of trip [tripId] that aren't deleted, newest first (the Android app's observeForTrip). */
fun tripNotes(book: TripBook, tripId: String): List<NoteInfo> {
    val videos = videoIds(book)
    return book.entries(tripId).asReversed().map { NoteInfo(it, videos) }
}

/** The notes written when no trip was on, newest first (the Android app's observeEntries, outside trips). */
fun looseNotes(book: TripBook): List<NoteInfo> {
    val videos = videoIds(book)
    return book.snapshot.entries.filter { it.tripId == null && it.deletedAt == null }
        .sortedWith(compareByDescending<Entry> { it.occurredAt }.thenByDescending { it.id })
        .map { NoteInfo(it, videos) }
}

/** Mono line under a note: "Naxos · 27° · 3 photos", "Naxos · 2 photos, 1 video" (the Android app's JournalLogic.meta). */
fun noteMeta(place: String, tempC: Double?, photos: Int, videos: Int = 0): String = listOfNotNull(
    place.trim().ifBlank { null },
    tempC?.let { "${it.roundToInt()}°" },
    mediaCounts(photos, videos),
).joinToString(" · ")

/** "3 photos", "1 video", "2 photos, 1 video"; null for none. */
fun mediaCounts(photos: Int, videos: Int): String? = listOfNotNull(
    when {
        photos <= 0 -> null
        photos == 1 -> "1 photo"
        else -> "$photos photos"
    },
    when {
        videos <= 0 -> null
        videos == 1 -> "1 video"
        else -> "$videos videos"
    },
).joinToString(", ").ifEmpty { null }

/**
 * The Journal tab inside a trip (the Android app's TripJournalTab): the daily check-in card on top ([checkIn]), then the
 * trip's notes, newest first, and Write. With none yet: "Nothing written for this trip" and Write. [revision] changes
 * whenever the trips do.
 */
@Composable
fun TripJournalTab(
    book: TripBook, tripId: String, revision: Int, onOpenNote: (String) -> Unit, onWrite: () -> Unit,
    checkIn: @Composable () -> Unit = {},
) {
    val list = remember(book, tripId, revision) { tripNotes(book, tripId) }
    Column(Modifier.fillMaxSize()) {
        checkIn()
        if (list.isEmpty()) {
            EmptyState("Nothing written for this trip", "Write a line about today. It can be short.") {
                BtButton("Write", onClick = onWrite, icon = BtIcons.Add, small = true)
            }
        } else Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp)) {
                itemsIndexed(list, key = { _, info -> info.entry.id }) { i, info -> EntryRow(info, onClick = { onOpenNote(info.entry.id) }, index = i) }
            }
            BtFab("Write", onClick = onWrite, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

/**
 * Notes outside trips (the Android app's LooseNotesScreen): notes written when no trip was on, newest first. Opened from
 * the card at the end of Trips, which shows only when there are some. As on Android there is no Write here: notes
 * outside trips come from the widgets' and quick actions' Write when no trip is on.
 */
@Composable
fun LooseNotesScreen(book: TripBook, revision: Int, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val list = remember(book, revision) { looseNotes(book) }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Notes outside trips", onBack = onBack)
            if (list.isEmpty()) {
                EmptyState("No notes outside trips", "Notes written when no trip is on show up here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 32.dp)) {
                    item(key = "about") {
                        Text("Written when no trip was on.", Modifier.padding(bottom = 4.dp), style = Bt.text.sub)
                    }
                    itemsIndexed(list, key = { _, info -> info.entry.id }) { i, info -> EntryRow(info, onClick = { onOpenNote(info.entry.id) }, index = i) }
                }
            }
        }
    }
}

/** Notes written when no trip was on: one card at the end of Trips that opens them (the Android app's LooseNotesCard). */
@Composable
fun LooseNotesCard(count: Int, onClick: () -> Unit) {
    BtCard(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        padding = PaddingValues(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
                Icon(BtIcons.Journal, null, Modifier.size(19.dp), tint = Bt.colors.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("Notes outside trips", style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (count == 1) "1 note" else "$count notes", style = Bt.text.sub, maxLines = 1)
            }
            Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
        }
    }
}

/**
 * One note in a list (the Android app's EntryRow): the day in the display face over its month in mono, the title (else
 * the date), two lines of text, the mono line, and its first photo (or its first layout, in small) in the look's photo
 * frame, leaning the look's way and alternating down the list ([index]); a note without photos shows a drawn picture
 * of its place there ([DrawnScene]).
 */
@Composable
fun EntryRow(info: NoteInfo, onClick: () -> Unit, index: Int = 0) {
    val date = info.date
    // The month in the phone's language, in its capitals (Android's EntryRow: "MMM" in the configuration's locale).
    val locale = screenLocale()
    val monthLabel = remember(date, locale) { PhoneTexts.shortMonthUpper(date, locale) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The day in the display face, the month in mono (design boards "Journal").
                Text(
                    Bt.text.display(date.civil.day.toString()),
                    style = Bt.text.h2.copy(fontSize = Bt.tokens.type.displaySize(28.sp), lineHeight = Bt.tokens.type.displaySize(30.sp)),
                )
                Text(monthLabel, style = Bt.text.eyebrow, textAlign = TextAlign.Center)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    info.entry.title.ifBlank { "${Dates.weekday(date)} ${Dates.dayMonth(date)}" },
                    style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (info.preview.isNotBlank()) {
                    Text(info.preview, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (info.meta.isNotBlank()) {
                    Text(info.meta, Modifier.padding(top = 6.dp), style = Bt.text.mono.copy(fontSize = Bt.text.eyebrow.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val photo = info.photoIds.firstOrNull()
            PhotoFrame(Modifier.size(66.dp), index = index) {
                val album = info.firstAlbum
                when {
                    album != null -> AlbumMini(album, Modifier.fillMaxSize())
                    photo != null -> MediaImage(photo, Modifier.fillMaxSize(), contentDescription = "Photo")
                    // No photo: a drawn picture of the place, in the theme's colours.
                    else -> {
                        val kind = remember(info.entry.id, info.entry.placeName, info.entry.title) { sceneFor(info.entry) }
                        DrawnScene(kind, Modifier.fillMaxSize(), key = info.entry.id)
                    }
                }
            }
        }
        BtDivider()
    }
}

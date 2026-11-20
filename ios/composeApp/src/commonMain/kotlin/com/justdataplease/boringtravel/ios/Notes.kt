package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtDeleteButton
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlin.math.roundToInt

/**
 * Reading one note, as the Android app's EntryScreen: date · time · place · weather, the title, then the document with
 * its photos and font (lists, checklists, quotes, places, layouts). The title and the text can be selected and copied.
 * A video plays on a tap; a photo is only looked at (Android's DocView makes only videos tappable). Edit sits on top and
 * Delete at the end, full width, asking first; a checklist's boxes tick here too. A note in a shared trip that another
 * member wrote is only read.
 */
@Composable
fun NoteScreen(
    book: TripBook,
    entryId: String,
    revision: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    deletingAtStart: Boolean = false,
) {
    // A tick in a checklist changes the note here; it is read again then.
    var ticks by remember { mutableIntStateOf(0) }
    val entry = remember(book, entryId, revision, ticks) { book.entry(entryId) }
    var confirmDelete by remember { mutableStateOf(deletingAtStart) }
    var playing by remember { mutableStateOf<Media?>(null) }
    val kit = LocalMediaKit.current
    val rows = LocalMediaRows.current
    val own = entry?.authorId == null
    // In a shared trip only the author changes an entry; the others read it, with the author's name (round 26).
    val sharing = LocalTogether.current?.state?.collectAsState()?.value
    val authorName = entry?.authorId?.let { id -> sharing?.trips?.firstNotNullOfOrNull { t -> t.members.firstOrNull { it.id == id }?.name } }
    val doc = remember(entry?.body) { EntryDoc.parse(entry?.body) }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Journal entry", onBack = onBack) {
                if (entry != null && own) BtFab("Edit", onClick = onEdit, icon = BtIcons.Edit)
            }
            val e = entry ?: return@Column
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A long press copies the place (the Android app's copyOnLongPress).
                Eyebrow(header(e), Modifier.copyOnLongPress(e.placeName.trim(), "Place"))
                if (!own) Text("By ${authorName?.ifBlank { null } ?: "a friend"}", style = Bt.text.sub)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.title.isNotBlank()) H1(e.title)
                        Spacer(Modifier.height(10.dp))
                        DocView(
                            doc,
                            // Ticking a box changes the note: only for its author (a friend's ticks would overwrite theirs).
                            onToggleCheck = if (own) { b, i -> if (toggleCheck(book, e, doc, b, i)) ticks++ } else null,
                            // Only a video answers a tap: it plays.
                            onOpen = { id -> rows(id)?.takeIf { it.isVideo }?.let { playing = it } },
                        )
                    }
                }
                if (own) {
                    Spacer(Modifier.height(24.dp))
                    BtDeleteButton("Delete", { confirmDelete = true })
                }
            }
        }
    }
    playing?.let { PlayVideo(it, onClose = { playing = null }) }
    if (confirmDelete) BtConfirmDialog(
        title = "Delete this note?", body = "Are you sure you want to delete it? Its photos and videos go too. You can't undo this.", confirm = "Delete", danger = true,
        onConfirm = {
            confirmDelete = false
            val photos = doc.mediaIds().toSet()
            if (book.removeNote(entryId)) freeFiles(book, kit.files, book.snapshot.media.filter { it.id in photos })
            onDeleted()
        },
        onDismiss = { confirmDelete = false },
    )
}

/** Ticks or unticks item [itemIndex] of checklist [blockIndex], as the Android note does; true when it was saved. */
private fun toggleCheck(book: TripBook, e: Entry, doc: EntryDoc, blockIndex: Int, itemIndex: Int): Boolean {
    if (e.authorId != null) return false
    val block = doc.blocks.getOrNull(blockIndex) as? Block.Checklist ?: return false
    val items = block.items.mapIndexed { i, item -> if (i == itemIndex) item.copy(done = !item.done) else item }
    val blocks = doc.blocks.toMutableList().also { it[blockIndex] = Block.Checklist(items) }
    return book.saveNote(e, doc.copy(blocks = blocks)) != null
}

/** "Sat 19 Jun 2027 · 13:10 · Halki, Naxos · 27° sunny". */
private fun header(e: Entry): String {
    val day = localDateAt(e.occurredAt, e.zoneId)
    val weather = listOfNotNull(e.tempC?.let { "${it.roundToInt()}°" }, e.weather?.trim()?.ifBlank { null }).joinToString(" ")
    return listOfNotNull(Dates.dayLong(day), clockAt(e.occurredAt, e.zoneId), e.placeName.trim().ifBlank { null }, weather.ifBlank { null }).joinToString(" · ")
}

/**
 * The time of day of [ms] in zone [zoneId] ("13:10"), from the day's own start; left out on the two days a year the
 * clocks change, where that count isn't the clock's time.
 */
internal fun clockAt(ms: Long, zoneId: String?): String? {
    val day = localDateAt(ms, zoneId)
    val start = startOfDayMs(day, zoneId)
    if (startOfDayMs(day.plusDays(1), zoneId) - start != 86_400_000L) return null
    val minutes = ((ms - start) / 60_000L).toInt()
    return if (minutes in 0 until 24 * 60) Dates.time(minutes) else null
}

package com.justdataplease.boringtravel.ui.journal

import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.ui.components.BtDeleteButton
import com.justdataplease.boringtravel.ui.components.BtFab
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.copyOnLongPress
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Reading one entry: date · place · weather (a long press copies the place), the title, then the document with its
 * photos and font. The title and the text can be selected and copied (owner, 2026-11-12: "let me copy things").
 * Edit sits on top like the plan item's Done, Delete at the end, full width with a plain line, not pinned (owner, 2026-11-14).
 */
@Composable
fun EntryScreen(graph: AppGraph, actions: AppActions, entryId: String) {
    val entry by remember(entryId) { graph.journal.observeEntry(entryId) }.collectAsStateWithLifecycle(initialValue = null)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // In a shared trip only the author changes an entry; the others read it, with the author's name.
    val sharing by graph.together.state.collectAsStateWithLifecycle()
    val author = entry?.authorId
    val authorName = author?.let { id -> sharing.trips.firstNotNullOfOrNull { t -> t.members.firstOrNull { it.id == id }?.name } }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Journal entry", onBack = actions::back) {
                // Like Trips' New trip and the plan item's Done (owner, 2026-11-14).
                if (author == null) BtFab("Edit", onClick = { actions.write(entryId = entryId) }, icon = BtIcons.Edit)
            }
            val e = entry ?: return@Column
            if (e.deletedAt != null) return@Column
            val doc = remember(e.body) { EntryDoc.parse(e.body) }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Eyebrow(header(e), Modifier.copyOnLongPress(e.placeName.trim(), "Place"))
                if (author != null) Text("By ${authorName?.ifBlank { null } ?: "a friend"}", style = Bt.text.sub)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.title.isNotBlank()) H1(e.title)
                        Spacer(Modifier.height(10.dp))
                        // Ticking a box changes the entry: only for the author (a friend's ticks would overwrite their note).
                        DocView(doc, onToggleCheck = if (author == null) { b, i -> toggleCheck(graph, e, doc, b, i) } else null)
                    }
                }
                if (author == null) {
                    Spacer(Modifier.height(24.dp))
                    BtDeleteButton("Delete", { confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        BtConfirmDialog(
            title = "Delete this note?", body = "Are you sure you want to delete it? Its photos and videos go too. You can't undo this.", confirm = "Delete", danger = true,
            onConfirm = {
                confirmDelete = false
                val e = entry
                graph.scope.launch { if (e != null) graph.deletions.deleteEntry(e) }
                actions.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "Sat 19 Jun 2027 · 13:10 · Halki, Naxos · 27° sunny". */
private fun header(e: Entry): String {
    val date = Dates.localDate(e.occurredAt, e.zoneId)
    val weather = listOfNotNull(e.tempC?.let { "${it.roundToInt()}°" }, e.weather?.trim()?.ifBlank { null }).joinToString(" ")
    return listOfNotNull(
        Dates.dayLong(date),
        Dates.time(e.occurredAt, e.zoneId),
        e.placeName.trim().ifBlank { null },
        weather.ifBlank { null },
    ).joinToString(" · ")
}

private fun toggleCheck(graph: AppGraph, e: Entry, doc: EntryDoc, blockIndex: Int, itemIndex: Int) {
    if (e.authorId != null) return
    val block = doc.blocks.getOrNull(blockIndex) as? Block.Checklist ?: return
    val items = block.items.mapIndexed { i, item -> if (i == itemIndex) item.copy(done = !item.done) else item }
    val blocks = doc.blocks.toMutableList().also { it[blockIndex] = Block.Checklist(items) }
    graph.scope.launch { graph.journal.save(e, doc.copy(blocks = blocks)) }
}

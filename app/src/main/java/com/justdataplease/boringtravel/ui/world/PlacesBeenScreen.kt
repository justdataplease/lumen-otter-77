package com.justdataplease.boringtravel.ui.world

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Places I've been: every country by name in the phone's language, with a search. Tap one to add it; press and hold
 * one you have to remove it (a country found from your trips is then hidden, not deleted).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlacesBeenScreen(graph: AppGraph, actions: AppActions) {
    val world by graph.world.world.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val all = remember {
        val collator = Collator.getInstance()
        CountryInfo.allCodes.map { it to CountryInfo.name(it) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    val visited = world?.visitedCodes.orEmpty()
    val q = fold(query)
    val shown = if (q.isEmpty()) all else all.filter { (code, name) -> fold(name).contains(q) || code.equals(query.trim(), ignoreCase = true) }
    val been = shown.filter { it.first in visited }
    val rest = shown.filter { it.first !in visited }
    val c = Bt.colors

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Places I've been", onBack = actions::back)
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(btControlShape()).background(c.chip).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(BtIcons.Search, null, Modifier.size(18.dp), tint = c.muted)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search countries", style = Bt.text.body, color = c.muted, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = Bt.text.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.route),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
            }
            Text(
                "Tap to add. Press and hold to remove.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), style = Bt.text.fine,
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (been.isNotEmpty()) {
                    item(key = "h-been") { Eyebrow("Been · ${visited.size}", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)) }
                    items(been, key = { "b-" + it.first }) { (code, name) ->
                        CountryLine(code, name, checked = true, onClick = {}, onLongClick = { removing = code })
                    }
                }
                if (rest.isNotEmpty()) {
                    item(key = "h-all") { Eyebrow("All countries", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)) }
                    items(rest, key = { "a-" + it.first }) { (code, name) ->
                        CountryLine(code, name, checked = false, onClick = { scope.launch { graph.countries.markVisited(code) } }, onLongClick = null)
                    }
                }
                if (shown.isEmpty()) item(key = "none") { Text("No country by that name.", Modifier.padding(20.dp), style = Bt.text.sub) }
            }
        }
    }

    removing?.let { code ->
        val found = world?.byCode?.get(code)?.found == true
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = c.surface,
            title = { Text("Remove ${CountryInfo.name(code)}?", style = Bt.text.title) },
            text = {
                Text(
                    if (found) "It comes from your trips. It stays off your list until you add it again." else "You can add it again any time.",
                    style = Bt.text.sub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { graph.countries.remove(code, found) }
                }) { Text("Remove", style = Bt.text.button, color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", style = Bt.text.button, color = c.muted) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CountryLine(code: String, name: String, checked: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            role = Role.Button,
            onClickLabel = if (checked) null else "Add",
            onLongClickLabel = if (onLongClick != null) "Remove" else null,
            onLongClick = onLongClick,
            onClick = onClick,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlagSticker(code, 28.dp, tilt = false)
            Text(name, Modifier.weight(1f), style = Bt.text.item)
            if (checked) Icon(BtIcons.Check, "Been", Modifier.size(20.dp), tint = Bt.colors.ink)
        }
        HorizontalDivider(Modifier.padding(start = 60.dp), color = Bt.colors.line)
    }
}

/** Lower case without accents, so "cote" finds Côte d'Ivoire and "ellada" doesn't need tonos. */
private fun fold(s: String): String =
    Normalizer.normalize(s.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

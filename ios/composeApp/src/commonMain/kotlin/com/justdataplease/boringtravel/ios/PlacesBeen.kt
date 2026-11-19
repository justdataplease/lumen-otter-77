package com.justdataplease.boringtravel.ios

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.decomposed
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * Places I've been (the Android app's ui/world/PlacesBeenScreen.kt): every country by name in the phone's language,
 * with a search. Tap one to add it; press and hold one you have to remove it, after asking (a country found from your
 * trips is then hidden, not deleted). [removingAtStart] opens with that question (screenshots).
 */
@Composable
fun PlacesBeenScreen(
    book: TripBook, today: LocalDate, revision: Int, demoMode: Boolean, onBack: () -> Unit, onChanged: () -> Unit,
    removingAtStart: String? = null,
) {
    val world = rememberWorld(book, revision, demoMode, today)
    var query by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf(removingAtStart) }
    val all = remember { CountryInfo.allCodes.map { it to CountryInfo.name(it) }.sortedWith { a, b -> compareNames(a.second, b.second) } }
    val visited = world.visitedCodes
    val q = fold(query)
    val shown = if (q.isEmpty()) all else all.filter { (code, name) -> fold(name).contains(q) || code.equals(query.trim(), ignoreCase = true) }
    val been = shown.filter { it.first in visited }
    val rest = shown.filter { it.first !in visited }
    val c = Bt.colors

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Places I've been", onBack = onBack)
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
                        CountryLine(code, name, checked = false, onClick = { if (book.markCountryVisited(code)) onChanged() }, onLongClick = null)
                    }
                }
                if (shown.isEmpty()) item(key = "none") { Text("No country by that name.", Modifier.padding(20.dp), style = Bt.text.sub) }
            }
        }
    }

    removing?.let { code ->
        val found = world.byCode[code]?.found == true
        BtConfirmDialog(
            title = "Remove ${CountryInfo.name(code)}?",
            body = if (found) "It comes from your trips. It stays off your list until you add it again." else "You can add it again any time.",
            confirm = "Remove",
            danger = true,
            onConfirm = {
                removing = null
                if (book.removeCountry(code, found)) onChanged()
            },
            onDismiss = { removing = null },
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

/** Lower case without accents, so "cote" finds Côte d'Ivoire and "ellada" doesn't need tonos (Android: Normalizer NFD). */
private fun fold(s: String): String = decomposed(s.trim().lowercase())

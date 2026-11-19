package com.justdataplease.boringtravel.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ai.tools.TravelBrief
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * The travel agent's questions on one sheet (owner, 2026-11-12, point 11): chips for the choices, a few small fields,
 * filled in with what the planner already knew ([initial]). Every question may be left open: the planner decides it.
 * [onSend] gets the answers; the screen sends them as the traveller's message ([TravelBrief.message]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripQuestionsSheet(initial: TravelBrief, error: String?, sending: Boolean, onDismiss: () -> Unit, onSend: (TravelBrief) -> Unit) {
    val c = Bt.colors
    var b by remember(initial) { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Plan a trip", style = Bt.text.h2)
                Text("Answer what matters to you. Skip the rest and the planner decides.", style = Bt.text.sub)
            }
            Question("Where") {
                TextBox(b.where, { b = b.copy(where = it.take(FIELD), suggestWhere = false) }, "A city, region or country", minHeight = 48.dp, maxLines = 2)
                Chips { Chip("Suggest one", b.suggestWhere) { b = b.copy(suggestWhere = !b.suggestWhere, where = if (b.suggestWhere) b.where else "") } }
            }
            Question("When and how long") {
                TextBox(b.whenText, { b = b.copy(whenText = it.take(FIELD)) }, "Dates or a month: 3–7 May, or May", minHeight = 48.dp, maxLines = 2)
                Chips {
                    TravelBrief.DAY_CHOICES.forEach { d -> Chip(TravelBrief.daysLabel(d), b.days == d) { b = b.copy(days = if (b.days == d) null else d) } }
                }
            }
            Question("Who") {
                Chips { TravelBrief.Who.entries.forEach { w -> Chip(w.label, b.who == w) { b = b.copy(who = if (b.who == w) null else w, people = null) } } }
                if (b.asksPeople) {
                    Eyebrow("How many")
                    Chips {
                        TravelBrief.PEOPLE_CHOICES.forEach { n -> Chip(if (n == TravelBrief.PEOPLE_CHOICES.last()) "$n+" else "$n", b.people == n) { b = b.copy(people = if (b.people == n) null else n) } }
                    }
                }
            }
            Question("Pace") { One(TravelBrief.Pace.entries, b.pace) { b = b.copy(pace = it) } }
            Question("Interests") {
                Chips {
                    TravelBrief.Interest.entries.forEach { i ->
                        Chip(i.label, i in b.interests) { b = b.copy(interests = if (i in b.interests) b.interests - i else b.interests + i) }
                    }
                }
            }
            Question("Budget") { One(TravelBrief.Budget.entries, b.budget) { b = b.copy(budget = it) } }
            Question("Getting around") { One(TravelBrief.GettingAround.entries, b.gettingAround) { b = b.copy(gettingAround = it) } }
            Question("Starting from") {
                TextBox(b.startFrom, { b = b.copy(startFrom = it.take(FIELD)) }, "Your town, to plan the way there", minHeight = 48.dp, maxLines = 2)
            }
            Question("Must-sees and things to avoid") {
                TextBox(b.wishes, { b = b.copy(wishes = it.take(WISHES)) }, "Optional", minHeight = 48.dp, maxLines = 4)
            }
            error?.let { ErrorLine(it) }
            BtButton(if (sending) "Planning" else "Plan my trip", onClick = { onSend(b) }, modifier = Modifier.fillMaxWidth(), enabled = !sending)
        }
    }
}

private const val FIELD = 80
private const val WISHES = 300

@Composable
private fun Question(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** A choice chip that turns off when tapped again: no answer means the planner decides. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) = BtChip(text, selected, onClick)

/** One of [values], or none. */
@Composable
private fun <T : TravelBrief.Answer> One(values: List<T>, selected: T?, onSelect: (T?) -> Unit) {
    Chips { values.forEach { v -> Chip(v.label, selected == v) { onSelect(if (selected == v) null else v) } } }
}

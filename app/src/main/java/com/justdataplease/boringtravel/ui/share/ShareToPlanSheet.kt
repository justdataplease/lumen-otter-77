package com.justdataplease.boringtravel.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.links.MapsLinks
import com.justdataplease.boringtravel.links.MapsList
import com.justdataplease.boringtravel.links.SharedLink
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.site.SiteLinks
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.trip.LinkPreviewCard
import com.justdataplease.boringtravel.ui.trip.PlanDays
import com.justdataplease.boringtravel.ui.trip.kindIcon
import com.justdataplease.boringtravel.ui.trip.rememberLinkPreview
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val IDEAS = "ideas"

/**
 * Add to plan, for a link shared from another app (Google Maps, a browser): the link's preview, then the trip (the
 * one happening now, else the next, else the latest; the traveller's own, or the examples when there are no others),
 * the day (today when the trip is on, else its first day) and Place, Food or Stay. Add puts it at the end of that
 * day, filled from the link like the item sheet does, and [onAdded] opens the trip there. A link that looks like a
 * blog, a site's front page or a Google Doc ([SiteLinks.looksLikeSite]) also offers [onCopy]: copy its notes and
 * pictures (Copy from a link). A shared Google Maps list is many places, not one: the sheet then offers the trip and
 * [onList], which opens Copy from a link with the list and that trip (null: a new trip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareToPlanSheet(
    graph: AppGraph,
    link: SharedLink,
    onAdded: (tripId: String, day: LocalDate?) -> Unit,
    onNewTrip: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onList: ((tripId: String?) -> Unit)? = null,
) {
    val c = Bt.colors
    val today = remember { LocalDate.now() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val all by remember { graph.trips.observeEvery() }.collectAsState(initial = null)
    val choice = remember(all) { all?.let(ShareTargets::trips) }
    var pickedTrip by rememberSaveable(link.url) { mutableStateOf<String?>(null) }
    val trip = choice?.trips?.let { trips -> trips.firstOrNull { it.id == pickedTrip } ?: ShareTargets.defaultTrip(trips, today) }
    val items by remember(trip?.id) { trip?.let { graph.plan.observeItems(it.id) } ?: flowOf(emptyList<PlanItem>()) }
        .collectAsState(initial = emptyList())
    val days = remember(trip, items) { trip?.let { PlanDays.days(it.startDate, it.endDate, items) }.orEmpty() }
    var pickedDay by rememberSaveable(link.url, trip?.id) { mutableStateOf<String?>(null) }
    val day: LocalDate? = when (val k = pickedDay) {
        null -> trip?.let { ShareTargets.defaultDay(it, today) }?.takeIf { it in days }
        IDEAS -> null
        else -> LocalDate.ofEpochDay(k.toLong())
    }

    val near = remember(items, day) { LinkPlaces.near(items, day) }
    val preview = rememberLinkPreview(link.url, near)
    val name = ShareTargets.name(link, preview?.takeIf { it.url == link.url })
    var pickedKind by rememberSaveable(link.url) { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val kind = pickedKind?.let(PlanKind::of) ?: ShareTargets.defaultKind(name)
    // A shared list of saved places (Google Maps, a list › Share): read once, so the sheet can offer all of it.
    val list by produceState<MapsList?>(null, link.url) {
        value = if (onList == null || !MapsLinks.isMaps(link.url)) null else try {
            graph.links.mapsList(link.url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val places = list?.places?.size ?: 0

    fun hide(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    fun add() {
        val t = trip ?: return
        if (adding) return
        adding = true
        val shown = preview?.takeIf { it.url == link.url }
        val item = ShareTargets.item(newId(), t, day, kind, link, shown, items, now())
        graph.scope.launch {
            graph.plan.save(item)
            // A short Maps link (or one naming a place without its pin) only says where after a look on the network.
            if (item.lat == null && MapsLinks.isMaps(link.url)) {
                graph.links.mapsPlace(link.url, near)?.let { graph.plan.fillFromLink(item.id, link.url, it) }
            }
        }
        hide { onAdded(t.id, day) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.surface,
        contentColor = c.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.line) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add to plan", style = Bt.text.title)
            LinkPreviewCard(link.url, preview?.takeIf { it.url == link.url }, title = list?.name?.ifBlank { null } ?: name)
            if (places > 0) Text("A Google Maps list of $places ${if (places == 1) "place" else "places"}. Each becomes an idea in the plan.", style = Bt.text.sub)
            if (onCopy != null && SiteLinks.looksLikeSite(link.url)) {
                BtGhostButton("Copy its notes and pictures", onClick = { hide(onCopy) }, icon = BtIcons.Link, modifier = Modifier.fillMaxWidth())
            }

            if (choice == null) return@Column
            if (choice.trips.isEmpty()) {
                if (places > 0 && onList != null) {
                    BtButton("Add its $places places to a new trip", onClick = { hide { onList(null) } }, modifier = Modifier.fillMaxWidth())
                    return@Column
                }
                Text("No trips yet. Start one, then share the link again.", style = Bt.text.sub)
                BtGhostButton("New trip", onClick = { hide(onNewTrip) }, icon = BtIcons.Add, modifier = Modifier.fillMaxWidth())
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Trip")
                if (choice.demo) Text("No trips of your own yet. Add it to a demo trip?", style = Bt.text.sub)
                val selected = choice.trips.indexOf(trip).coerceAtLeast(0)
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = selected),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(choice.trips, key = { it.id }) { t ->
                        BtChip(t.title.ifBlank { "Untitled trip" }, t.id == trip?.id, { pickedTrip = t.id })
                    }
                }
            }

            // A list goes in whole, as ideas: no day or kind to choose here (Copy from a link shows it first).
            if (places > 0 && onList != null) {
                BtButton("Add its $places places", onClick = { hide { onList(trip?.id) } }, modifier = Modifier.fillMaxWidth(), enabled = trip != null)
                BtGhostButton("Add them to a new trip", onClick = { hide { onList(null) } }, icon = BtIcons.Add, modifier = Modifier.fillMaxWidth())
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("Day")
                val selected = days.indexOf(day)
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = ((if (selected >= 0) selected else days.size) - 1).coerceAtLeast(0)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(days, key = { it.toEpochDay() }) { d ->
                        BtChip(Dates.dayShort(d) + if (d == today) " ·" else "", day == d, { pickedDay = d.toEpochDay().toString() })
                    }
                    item(key = IDEAS) { BtChip("Ideas", day == null, { pickedDay = IDEAS }) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareTargets.kinds.forEach { k ->
                    BtChip(k.label, kind == k, { pickedKind = k.id }, icon = kindIcon(k.id))
                }
            }

            BtButton("Add", onClick = ::add, modifier = Modifier.fillMaxWidth(), enabled = trip != null && !adding)
        }
    }
}

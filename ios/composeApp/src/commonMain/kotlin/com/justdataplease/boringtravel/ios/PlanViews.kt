package com.justdataplease.boringtravel.ios

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.tags.ItemTags
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtTag
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

/*
 * The Plan tab's two views, as the Android app draws them (ui/trip/PlanViews.kt, PlanTab.kt's rows,
 * PlanItemActions.kt): the List, collapsed (one line per item) or expanded (each day in full with the travel between
 * places), and the Calendar (a month grid with the picked day's items under it). Same rows, words and touches: a tap
 * opens an item, its circle ticks it, press and hold lifts it to drag (the List) or opens its menu.
 */

/** What the List and Calendar views need from the Plan tab. */
class PlanViewContext(
    val trip: Trip,
    val items: List<PlanItem>,
    val byId: Map<String, PlanItem>,
    val stops: List<Stop>,
    val today: LocalDate,
    /** The leg arriving at each item, by the item's id (see [PlanTravelRows]); the expanded List shows it. */
    val travel: Map<String, PlanTravelConnection>,
    /** The vehicle of the leg arriving at each item, as the collapsed List folds it. */
    val legs: Map<String, Vehicle?>,
    /** The vehicle each item's badge shows: the leg's, else what a travel item's name says. */
    val badgeVehicle: (PlanItem) -> Vehicle?,
    val onToggle: (PlanItem) -> Unit,
    val onOpen: (PlanItem) -> Unit,
    val onAdd: (LocalDate?) -> Unit,
    /** Opens the vehicle choice for a leg. */
    val onTravel: (PlanTravelConnection) -> Unit,
    /** Shares the item as text (a row's menu: [PlanItemMenu]). */
    val onShare: (PlanItem) -> Unit,
    /** A row lifted to drag it, and where it was dropped (the List only, see [PlanDrop]). */
    val onLift: (PlanLine) -> Unit = {},
    val onDrop: (PlanLine) -> Unit = {},
    /** The days' own names; a named day shows its name, not its place. */
    val dayNames: Map<LocalDate, String> = emptyMap(),
    /** Opens the day's name to change it (a day's title pressed and held, or tapped when it opens no place page). */
    val onRenameDay: (LocalDate) -> Unit = {},
    /** Opens the place page around a plan item (a day's title, tapped). */
    val onPlace: (itemId: String) -> Unit = {},
) {
    /** Times get their own column only when some item in the plan has one (a start or an end). */
    val anyTime: Boolean = items.any { it.timeMinutes != null || it.endMinutes != null }

    private val places = HashMap<Long, String?>()

    /** Where [day] is spent ("Kyoto"): its stops, its places, else the latest arrival ([dayPlace]). */
    fun placeOf(day: LocalDate): String? = places.getOrPut(day.toEpochDay()) { dayPlace(day, stops, items) }
}

private val KIND_CIRCLE = 36.dp
private val ROW_START = 20.dp

fun PlanView.icon(): ImageVector = when (this) {
    PlanView.List -> BtIcons.List
    PlanView.Calendar -> BtIcons.Calendar
}

/**
 * The Plan tab's own row, above the plan and never on top of it: Expand/Collapse on the left (the List only), "12 days ·
 * 34 items", and List · Calendar on the right.
 */
@Composable
fun PlanToolbar(view: PlanView, expanded: Boolean, summary: String, onExpanded: (Boolean) -> Unit, onView: (PlanView) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (view == PlanView.List) PlanExpandToggle(expanded, onExpanded)
        Text(
            summary, Modifier.weight(1f).padding(start = if (view == PlanView.List) 0.dp else 8.dp),
            style = Bt.text.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        PlanViewSwitch(view, onView)
    }
}

/** "Expand" (every day in full, with travel between places) or "Collapse" (one line per item); the choice is kept. */
@Composable
fun PlanExpandToggle(expanded: Boolean, onChange: (Boolean) -> Unit) {
    val c = Bt.colors
    val shape = btControlShape()
    Row(
        Modifier.minimumInteractiveComponentSize().clip(shape).background(c.chip)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Show one line per item" else "Show every day in full") { onChange(!expanded) }
            .heightIn(min = 42.dp).padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (expanded) BtIcons.CollapseAll else BtIcons.ExpandAll, null, Modifier.size(18.dp), tint = c.ink)
        Text(if (expanded) "Collapse" else "Expand", style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1)
    }
}

/** List · Calendar as two small icons, the picked one raised (the same setting as Me › Plan view). */
@Composable
fun PlanViewSwitch(view: PlanView, onPick: (PlanView) -> Unit, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val shape = btControlShape()
    Row(modifier.clip(shape).background(c.chip).padding(3.dp)) {
        PlanView.entries.forEach { v ->
            val on = v == view
            Box(
                Modifier.size(width = 40.dp, height = 36.dp).clip(shape)
                    .background(if (on) c.surface else Color.Transparent)
                    .semantics { selected = on }
                    .clickable(role = Role.Tab, onClickLabel = v.label) { onPick(v) },
                contentAlignment = Alignment.Center,
            ) { Icon(v.icon(), v.label, Modifier.size(18.dp), tint = if (on) c.ink else c.muted) }
        }
    }
}

// ---------------------------------------------------------------- List

/**
 * The List: [rows] from [PlanListLogic.rows]. Collapsed, small day headers and one line per item; expanded, each day in
 * full ("Day 3 · Kyoto", items with their kind in a circle, the travel between two places). Empty days folded in both,
 * today marked, Ideas at the end. With [reorder], a held item lifts and drags within its day or onto another (day
 * headers, folded days and Ideas are where it can go).
 */
fun LazyListScope.planList(ctx: PlanViewContext, rows: List<PlanListLogic.Row>, expanded: Boolean, reorder: ReorderableLazyListState? = null) {
    items(rows, key = { it.key }, contentType = { it::class.simpleName }) { row ->
        if (reorder != null && PlanDrop.isTarget(row)) {
            ReorderableItem(reorder, key = row.key) { lifted ->
                LiftedRow(lifted) { PlanListRow(ctx, row, expanded, this) }
            }
        } else {
            Box(Modifier.animateItem()) { PlanListRow(ctx, row, expanded, null) }
        }
    }
}

/** A row being dragged: raised off the page on the surface colour, with a soft shadow. */
@Composable
private fun LiftedRow(lifted: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val elevation by animateDpAsState(if (lifted) 8.dp else 0.dp, label = "lift")
    Box(
        Modifier.shadow(elevation, shape, clip = false)
            .then(if (lifted) Modifier.background(Bt.colors.surface, shape) else Modifier),
    ) { content() }
}

/** One row of the List; [reorder] makes an item's row draggable. */
@Composable
private fun PlanListRow(ctx: PlanViewContext, row: PlanListLogic.Row, expanded: Boolean, reorder: ReorderableCollectionItemScope?) {
    when (row) {
        is PlanListLogic.Row.DayHead -> if (expanded) DayHeader(ctx, row.day, row.number) else CompactDayHeader(ctx, row.day, row.number)
        is PlanListLogic.Row.Line -> if (expanded) ExpandedItemRow(ctx, row.line, reorder) else PlanLineRow(ctx, row.line, reorder)
        is PlanListLogic.Row.DayEmpty -> Text(
            if (row.day == ctx.today) "Nothing planned for today yet." else "Nothing planned for this day yet.",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = if (expanded) 8.dp else 4.dp),
            style = if (expanded) Bt.text.sub else Bt.text.fine,
        )
        is PlanListLogic.Row.Folded -> EmptyDays(ctx, row.from, row.to, expanded)
        is PlanListLogic.Row.IdeasHead -> IdeasHeaderRow(ctx, row.count, expanded)
        PlanListLogic.Row.IdeasEmpty -> Text("Places and notes for later.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = Bt.text.sub)
    }
}

/**
 * "Day 3 · Kyoto" (or the day's own name) and the date, small; TODAY on today; the title opens the place page, pressed
 * and held it is renamed, + adds to the day.
 */
@Composable
private fun CompactDayHeader(ctx: PlanViewContext, day: LocalDate, number: Int?) {
    val c = Bt.colors
    val place = ctx.placeOf(day)
    val anchor = remember(day, ctx.items, place) { PlanDayAnchor.item(day, ctx.items, place) }
    val title = dayTitle(ctx, day, number)
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(btControlShape()).dayTitleTouch(ctx, day, anchor, place).heightIn(min = 36.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title, Modifier.weight(1f, fill = false),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), color = if (day == ctx.today) c.accentInk else c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (day == ctx.today) BtTag("TODAY")
            Text(if (number != null) "${Dates.weekday(day)} ${Dates.dayMonth(day)}" else "Outside the dates", style = Bt.text.mono, maxLines = 1)
        }
        AddIcon("Add to ${number?.let { "day $it" } ?: Dates.dayMonth(day)}") { ctx.onAdd(day) }
    }
}

/**
 * The expanded List's day: "Day 13 · El Chaltén" in the title face (a tap opens the place page, anchored on an item as
 * [PlanDayAnchor] picks, with a chevron; pressed and held, the day is renamed), TODAY on today, the date quietly at the
 * end and + to add to the day.
 */
@Composable
private fun DayHeader(ctx: PlanViewContext, day: LocalDate, number: Int?) {
    val c = Bt.colors
    val place = ctx.placeOf(day)
    val anchor = remember(day, ctx.items, place) { PlanDayAnchor.item(day, ctx.items, place) }
    val title = dayTitle(ctx, day, number)
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f, fill = false).clip(btControlShape()).dayTitleTouch(ctx, day, anchor, place).heightIn(min = 40.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, Modifier.weight(1f, fill = false), style = Bt.text.title, color = if (day == ctx.today) c.accentInk else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (anchor != null) Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = c.muted)
            }
            if (day == ctx.today) BtTag("TODAY")
        }
        if (number != null) Text("${Dates.weekday(day)} ${Dates.dayMonth(day)}", style = Bt.text.mono, maxLines = 1)
        AddIcon("Add to ${number?.let { "day $it" } ?: Dates.dayMonth(day)}") { ctx.onAdd(day) }
    }
    if (number == null && ctx.trip.startDate != null) {
        Text("Outside the trip's dates. Move these items to a day or to Ideas.", Modifier.padding(horizontal = 20.dp), style = Bt.text.fine)
    }
}

/** "Day 3 · Temples and tea": the day's own name when it has one, else where it is spent; outside the dates, its date. */
private fun dayTitle(ctx: PlanViewContext, day: LocalDate, number: Int?): String =
    listOfNotNull(number?.let { "Day $it" } ?: Dates.dayMonth(day), ctx.dayNames[day] ?: ctx.placeOf(day)).joinToString(" · ")

/**
 * A day's title under the finger: a tap opens the day's place page ([anchor]); pressed and held, the day's name is asked
 * (as an item's row opens its menu). A day with no place page to open asks for the name on a tap too.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.dayTitleTouch(ctx: PlanViewContext, day: LocalDate, anchor: PlanItem?, place: String?): Modifier = combinedClickable(
    role = Role.Button,
    onClickLabel = if (anchor != null) "Open ${place ?: anchor.title}" else "Rename this day",
    onLongClickLabel = "Rename this day",
    onLongClick = { ctx.onRenameDay(day) },
    onClick = { if (anchor != null) ctx.onPlace(anchor.id) else ctx.onRenameDay(day) },
)

/** "Ideas · 3" and + to add one; in the title face when expanded. */
@Composable
private fun IdeasHeaderRow(ctx: PlanViewContext, count: Int, expanded: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = if (expanded) 18.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count > 0) "Ideas · $count" else "Ideas", Modifier.weight(1f),
            style = if (expanded) Bt.text.title else Bt.text.label.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = Bt.colors.ink, maxLines = 1,
        )
        AddIcon("Add an idea") { ctx.onAdd(null) }
    }
}

/** + on a day's (or the Ideas') header: a filled round button in a 44 dp touch target. */
@Composable
fun AddIcon(label: String, onClick: () -> Unit) {
    val c = Bt.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(BtIcons.Add, label, Modifier.size(20.dp), tint = c.ink)
        }
    }
}

/** "Day 5 · nothing planned" or "Days 5–7 · nothing planned", one quiet line; a tap adds to the first of them. */
@Composable
private fun EmptyDays(ctx: PlanViewContext, from: LocalDate, to: LocalDate, expanded: Boolean) {
    val a = PlanDays.number(ctx.trip.startDate, ctx.trip.endDate, from)
    val b = PlanDays.number(ctx.trip.startDate, ctx.trip.endDate, to)
    val label = when {
        from == to -> a?.let { "Day $it" } ?: Dates.dayMonth(from)
        a != null && b != null -> "Days $a–$b"
        else -> "${Dates.dayMonth(from)}–${Dates.dayMonth(to)}"
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Add to ${label.lowercase()}") { ctx.onAdd(from) }
            .heightIn(min = if (expanded) 44.dp else 36.dp).padding(start = 20.dp, end = 20.dp, top = if (expanded) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label · nothing planned", style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An item in the expanded List: the leg that arrives at it ([LegRow], when it has one), then the item ([PlanRow]). A tap
 * opens the item's sheet; held, it drags ([reorder]) or opens its menu ([PlanRowTouch]).
 */
@Composable
private fun ExpandedItemRow(ctx: PlanViewContext, line: PlanLine, reorder: ReorderableCollectionItemScope? = null) {
    val item = line.item
    Column {
        ctx.travel[line.id]?.let { connection ->
            LegRow(LegRowModel.of(connection, ctx.byId[connection.leg.key.fromItemId], item), onClick = { ctx.onTravel(connection) })
        }
        PlanRowTouch(ctx, line, reorder) { touch, holding ->
            if (line.end) EndRow(item, ctx.badgeVehicle(item), modifier = touch)
            // A hold's release is not a tick.
            else PlanRow(item, ctx.badgeVehicle(item), onToggle = { if (!holding()) ctx.onToggle(item) }, modifier = touch)
        }
    }
}

/**
 * Between two items: a dashed line in the route colour under the kind circles, and a chip with the vehicle and a rough
 * time ("Car · ≈ 3 h"). The whole row opens the vehicle choice.
 */
@Composable
private fun LegRow(model: LegRowModel, onClick: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Choose travel", onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = ROW_START, end = 20.dp)
            .drawBehind {
                val x = KIND_CIRCLE.toPx() / 2
                drawLine(
                    c.route, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(KIND_CIRCLE + 12.dp))
        Row(
            Modifier.clip(btControlShape()).background(c.chip).heightIn(min = 30.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(vehicleIcon(model.vehicle), null, Modifier.size(16.dp), tint = c.ink)
            Text(model.text, style = Bt.text.label.copy(fontSize = Bt.text.small * 1.1f), color = c.ink, maxLines = 1)
        }
        model.aside?.let { aside ->
            Text(aside, Modifier.padding(start = 8.dp).weight(1f), style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The kind in a circle, then the title and "time · note". The circle ticks the item off (a check on its edge);
 * [modifier] carries the rest of the row's touch ([PlanRowTouch]: a tap opens the item).
 */
@Composable
private fun PlanRow(item: PlanItem, vehicle: Vehicle?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = ROW_START - 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .clickable(role = Role.Checkbox, onClickLabel = if (item.done) "Mark ${item.title} not done" else "Mark ${item.title} done", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            // The place photo also stands in for a link whose site has no picture of its own.
            val place = remember(item) { PlacePhotos.query(item.copy(url = null)) }
            KindBadge(item.kind, vehicle, item.done, KIND_CIRCLE, link = item.url, place = place)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            val tag = PlanSpan.startTag(item)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tag != null) BtTag(tag.uppercase())
            }
            // "19:00–22:00 · 3 h" or "20:30 → Sun 15 11:00 · 3 nights", then the note.
            val sub = listOfNotNull(PlanSpan.times(item), PlanSpan.length(item), item.note.trim().ifBlank { null }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.placeName.isNotBlank() && !item.placeName.startsWith(item.title, ignoreCase = true)) {
                Text(item.placeName, style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TagsLine(ItemTags.of(item))
        }
    }
}

/** The "#tags" line under an item (the Android app's ItemTagsLine). */
@Composable
private fun TagsLine(tags: List<String>) {
    if (tags.isNotEmpty()) Text(ItemTags.line(tags), style = Bt.text.fine, color = Bt.colors.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** The end of an item on a later day ("Hostal Alicante", CHECK-OUT, 11:00): the kind in a dashed circle; a tap opens the item. */
@Composable
private fun EndRow(item: PlanItem, vehicle: Vehicle?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = ROW_START - 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { EndBadge(item.kind, vehicle, KIND_CIRCLE) }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PlanSpan.endName(item), Modifier.weight(1f, fill = false), style = Bt.text.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
                BtTag(PlanSpan.endTag(item).uppercase())
            }
            item.endMinutes?.let { Text(Dates.time(it), Modifier.padding(top = 1.dp), style = Bt.text.sub, maxLines = 1) }
        }
    }
}

/**
 * One plan item on one line: the time (when the plan uses times), a small kind badge that ticks it off, the title and
 * its place in muted text, and the way there as a tiny vehicle at the end. The line opens the item's sheet; held, it
 * drags ([reorder], the List) or opens its menu ([PlanRowTouch]).
 */
@Composable
fun PlanLineRow(ctx: PlanViewContext, line: PlanLine, reorder: ReorderableCollectionItemScope? = null) =
    PlanRowTouch(ctx, line, reorder) { touch, holding -> PlanLineContent(ctx, line, holding, touch) }

@Composable
private fun PlanLineContent(ctx: PlanViewContext, line: PlanLine, holding: () -> Boolean, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val item = line.item
    val end = line.end
    val title = if (end) PlanSpan.endName(item) else item.title
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ctx.anyTime) {
            // The end row shows its end time; an item that ends the same day shows both, one under the other.
            val sameDayEnd = !end && PlanSpan.hasEnd(item) && PlanSpan.days(item) == 0
            Column(Modifier.width(44.dp).padding(start = 6.dp)) {
                Text((if (end) item.endMinutes else item.timeMinutes)?.let(Dates::time).orEmpty(), style = Bt.text.mono, maxLines = 1)
                if (sameDayEnd) item.endMinutes?.let { Text(Dates.time(it), style = Bt.text.mono, color = c.muted, maxLines = 1) }
            }
        }
        if (end) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { EndBadge(item.kind, ctx.badgeVehicle(item), 26.dp) }
        } else {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    // A hold's release is not a tick.
                    .clickable(role = Role.Checkbox, onClickLabel = if (item.done) "Mark ${item.title} not done" else "Mark ${item.title} done") {
                        if (!holding()) ctx.onToggle(item)
                    },
                contentAlignment = Alignment.Center,
            ) {
                val place = remember(item) { PlacePhotos.query(item.copy(url = null)) }
                KindBadge(item.kind, ctx.badgeVehicle(item), item.done, 26.dp, link = item.url, place = place)
            }
        }
        Spacer(Modifier.width(6.dp))
        val tag = if (end) PlanSpan.endTag(item) else PlanSpan.startTag(item)
        val until = if (end) null else PlanSpan.until(item)
        val tags = if (end) emptyList() else ItemTags.of(item)
        Column(Modifier.weight(1f).padding(vertical = if (until != null || tags.isNotEmpty()) 4.dp else 0.dp)) {
            val style = Bt.text.item.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (tag != null) {
                // A stay's check-in and check-out, an end: the name and its tag (the address would crowd them).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, Modifier.weight(1f, fill = false), style = style, color = if (item.done || end) c.muted else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BtTag(tag.uppercase())
                }
            } else {
                val placeName = item.placeName.trim().takeIf { it.isNotEmpty() && !it.startsWith(item.title.trim(), ignoreCase = true) }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = if (item.done) c.muted else c.ink)) { append(title) }
                        if (placeName != null) withStyle(SpanStyle(color = c.muted, fontWeight = FontWeight.Normal)) { append("  $placeName") }
                    },
                    style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // "until Sun 15 · 11:00 · 3 nights" under an item that ends on another day.
            if (until != null) Text(until, style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TagsLine(tags)
        }
        line.leg?.let { v -> Icon(vehicleIcon(v), "${v.label} there", Modifier.padding(start = 8.dp).size(14.dp), tint = c.muted) }
    }
}

// ---------------------------------------------------------------- touch and the row's menu

/**
 * A long press lifts the row to drag it (as the library's own long-press handle does); released without moving, it is
 * a hold and [onHold] runs too, so one gesture both drags and opens the row's menu without the two fighting.
 */
class HoldOrDrag(private val onHold: () -> Unit) : DragGestureDetector {
    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    ) {
        var moved = Offset.Zero
        var farthest = 0f
        detectDragGesturesAfterLongPress(
            onDragStart = {
                moved = Offset.Zero
                farthest = 0f
                onDragStart(it)
            },
            onDragEnd = {
                onDragEnd()
                if (farthest < viewConfiguration.touchSlop) onHold()
            },
            onDragCancel = onDragCancel,
            onDrag = { change, amount ->
                moved += amount
                farthest = maxOf(farthest, moved.getDistance())
                onDrag(change, amount)
            },
        )
    }
}

/**
 * A plan row's touch: a tap opens the item. In the List ([reorder] given) a long press lifts the row to drag it
 * ([PlanViewContext.onLift], then [PlanViewContext.onDrop]) and a hold released without moving opens the row's menu
 * ([PlanItemMenu]); elsewhere (the Calendar, an item's end row) a long press opens the menu. [content] gets the modifier
 * for the row and whether a hold is under way (a tick-off circle inside the row ignores the release of a hold).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRowTouch(
    ctx: PlanViewContext,
    line: PlanLine,
    reorder: ReorderableCollectionItemScope?,
    content: @Composable (row: Modifier, holding: () -> Boolean) -> Unit,
) {
    val item = line.item
    val title = if (line.end) PlanSpan.endName(item) else item.title
    var menu by remember { mutableStateOf(false) }
    val holding = remember { mutableStateOf(false) }
    val latestCtx by rememberUpdatedState(ctx)
    val latestLine by rememberUpdatedState(line)
    val detector = remember { HoldOrDrag { menu = true } }
    val row = if (reorder != null && !line.end) with(reorder) {
        Modifier
            .semantics { onLongClick("More for $title") { menu = true; true } }
            .clickable(onClickLabel = "Open $title") { ctx.onOpen(item) }
            .draggableHandle(
                onDragStarted = {
                    holding.value = true
                    latestCtx.onLift(latestLine)
                },
                onDragStopped = {
                    latestCtx.onDrop(latestLine)
                    holding.value = false
                },
                dragGestureDetector = detector,
            )
    } else {
        Modifier.combinedClickable(
            onClickLabel = "Open $title",
            onLongClickLabel = "More for $title",
            onLongClick = { menu = true },
            onClick = { ctx.onOpen(item) },
        )
    }
    Box {
        content(row) { holding.value }
        PlanItemMenu(item, menu, onDismiss = { menu = false }, onShare = { ctx.onShare(item) })
    }
}

/**
 * The small menu a plan row opens when held without moving: Copy name, Copy address (when it has one) and Share.
 * [onShare] shares the item ([shareText]).
 */
@Composable
fun PlanItemMenu(item: PlanItem, open: Boolean, onDismiss: () -> Unit, onShare: () -> Unit) {
    val copy = rememberCopy()
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = Bt.colors.surface) {
        MenuLine("Copy name", BtIcons.Duplicate, onDismiss) { copy(item.title) }
        val address = item.placeName.trim()
        if (address.isNotEmpty()) MenuLine("Copy address", BtIcons.Place, onDismiss) { copy(address) }
        MenuLine("Share", BtIcons.Share, onDismiss, onShare)
    }
}

@Composable
private fun MenuLine(label: String, icon: ImageVector, onDismiss: () -> Unit, action: () -> Unit) = DropdownMenuItem(
    text = { Text(label, style = Bt.text.body, color = Bt.colors.ink) },
    leadingIcon = { Icon(icon, null, tint = Bt.colors.ink) },
    onClick = {
        onDismiss()
        action()
    },
)

// ---------------------------------------------------------------- Calendar

/**
 * The Calendar view: a month grid for every month the plan touches, the trip's days filled, dots for planned items. The
 * picked day's items follow its month in the compact form; Ideas sit under the last month.
 */
fun LazyListScope.planCalendar(ctx: PlanViewContext, days: List<LocalDate>, selected: LocalDate?, firstDay: Int, onSelect: (LocalDate?) -> Unit) {
    val months = PlanCalendarLogic.months(days, firstDay)
    val counts = PlanCalendarLogic.counts(ctx.items)
    val doneDays = ctx.items.filter { it.day != null }.groupBy { it.day!!.toEpochDay() }.filterValues { l -> l.all { it.done } }.keys
    val daySet = days.mapTo(HashSet()) { it.toEpochDay() }
    for (m in months) {
        item(key = "cm-${m.month.index}") { MonthBlock(ctx, m, firstDay, daySet, counts, doneDays, selected, onSelect) }
        if (selected != null && selected in m.month) dayPanel(ctx, selected)
    }
    val ideas = PlanSpan.dayOrder(ctx.items.filter { it.day == null })
    if (days.isNotEmpty()) item(key = "cideas") {
        Row(Modifier.padding(start = 16.dp, top = 8.dp)) {
            BtChip(if (ideas.isNotEmpty()) "Ideas · ${ideas.size}" else "Ideas", selected == null, { onSelect(null) })
        }
    }
    if (selected == null) {
        if (ideas.isEmpty()) item(key = "cideas-empty") {
            Text("Places and notes for later.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = Bt.text.sub)
        }
        items(ideas, key = { "ci-${it.id}" }) { PlanLineRow(ctx, PlanLine(it, null)) }
    }
}

private fun LazyListScope.dayPanel(ctx: PlanViewContext, day: LocalDate) {
    val lines = PlanSpan.byDay(ctx.items)[day].orEmpty().map { PlanLine.of(it, ctx.legs) }
    item(key = "cd-head") { CompactDayHeader(ctx, day, PlanDays.number(ctx.trip.startDate, ctx.trip.endDate, day)) }
    if (lines.isEmpty()) item(key = "cd-empty") {
        Text("Nothing planned for this day yet.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = Bt.text.sub)
    }
    items(lines, key = { "cd-${it.id}" }) { PlanLineRow(ctx, it) }
}

/** A Monday, for the weekdays' names (1 = Monday … 7 = Sunday). */
private val A_MONDAY = localDate(2024, 1, 1)

@Composable
private fun MonthBlock(
    ctx: PlanViewContext,
    m: PlanCalendarLogic.Month,
    first: Int,
    days: Set<Long>,
    counts: Map<LocalDate, Int>,
    doneDays: Set<Long>,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    // The month and the weekdays in the phone's language (Android: "LLLL yyyy" titled, DayOfWeek's SHORT in capitals).
    val locale = screenLocale()
    val label = remember(m.month, locale) { PhoneTexts.monthTitle(m.month.year, m.month.month, locale) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
        Text(label, Modifier.padding(start = 6.dp, bottom = 4.dp), style = Bt.text.title)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            PlanCalendarLogic.weekdays(first).forEach { d ->
                Text(
                    PhoneTexts.weekdayUpper(A_MONDAY.plusDays(d - 1L), locale),
                    Modifier.weight(1f), style = Bt.text.eyebrow.copy(letterSpacing = 0.sp), textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
        m.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.days.forEach { d ->
                    DayCell(
                        d = d,
                        inTrip = d != null && PlanCalendarLogic.inTrip(ctx.trip.startDate, ctx.trip.endDate, d),
                        planDay = d != null && d.toEpochDay() in days,
                        count = d?.let { counts[it] } ?: 0,
                        allDone = d != null && d.toEpochDay() in doneDays,
                        selected = d != null && d == selected,
                        today = d == ctx.today,
                        onClick = { if (d != null) onSelect(d) },
                    )
                }
            }
        }
    }
}

/**
 * A day: its number; the trip's days on the chip colour; up to three dots for planned items (a count beyond that),
 * muted when everything is done; a ring in the route colour on the picked day.
 */
@Composable
private fun RowScope.DayCell(d: LocalDate?, inTrip: Boolean, planDay: Boolean, count: Int, allDone: Boolean, selected: Boolean, today: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(minOf(Bt.tokens.radius, 10.dp))
    Box(
        Modifier.weight(1f).height(50.dp).padding(2.dp).clip(shape)
            .then(if (inTrip) Modifier.background(c.chip) else Modifier)
            .then(if (selected) Modifier.border(2.dp, c.route, shape) else Modifier)
            .then(if (d != null && planDay) Modifier.clickable(role = Role.Button, onClickLabel = Dates.dayLong(d), onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (d == null) return@Box
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                d.civil.day.toString(),
                style = Bt.text.label.copy(fontSize = 14.sp, fontWeight = if (today || inTrip) FontWeight.Bold else FontWeight.Medium),
                color = when {
                    today -> c.accentInk
                    inTrip || planDay -> c.ink
                    else -> c.muted.copy(alpha = 0.55f)
                },
            )
            val dot = if (allDone) c.muted else c.route
            Box(Modifier.height(12.dp), contentAlignment = Alignment.Center) {
                when {
                    count in 1..3 -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(count) { Box(Modifier.size(5.dp).clip(CircleShape).background(dot)) }
                    }
                    count > 3 -> Text("$count", style = Bt.text.mono.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold), color = dot, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- badges and icons

/**
 * The kind of a plan item in a circle; a done item gets a small check in the route colour on the circle's edge. With a
 * [link], the site's picture (or its favicon) covers the kind once it has loaded; without one, the photo of its
 * [place], which also covers a link whose site has no picture.
 */
@Composable
fun KindBadge(kind: String, vehicle: Vehicle?, done: Boolean, size: Dp, link: String? = null, place: PlaceQuery? = null) {
    val c = Bt.colors
    Box(Modifier.size(size)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind, vehicle), null, Modifier.size(size * 0.53f), tint = c.ink)
            if (link != null) LinkBadgeLayers(link, size, fallback = place)
            else if (place != null) PlacePhotoLayer(place)
        }
        if (done) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(size * 0.44f).clip(CircleShape).background(c.route),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Check, "Done", Modifier.size(size * 0.3f), tint = readableOn(c.route)) }
        }
    }
}

/** The kind in a dashed circle: an item's end, which opens the item but isn't ticked off on its own. */
@Composable
fun EndBadge(kind: String, vehicle: Vehicle?, size: Dp) {
    val c = Bt.colors
    Box(
        Modifier.size(size).drawBehind {
            val w = 1.5.dp.toPx()
            drawCircle(c.muted, radius = this.size.minDimension / 2 - w / 2, style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
        },
        contentAlignment = Alignment.Center,
    ) { Icon(kindIcon(kind, vehicle), null, Modifier.size(size * 0.5f), tint = c.muted) }
}

/** Ink that reads on [fill]. */
fun readableOn(fill: Color): Color = if (fill.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White

/** The line icon for a vehicle. */
fun vehicleIcon(v: Vehicle?): ImageVector = when (v) {
    Vehicle.Walk -> BtIcons.Walk
    Vehicle.Bike -> BtIcons.Bike
    Vehicle.Car -> BtIcons.Car
    Vehicle.Train -> BtIcons.Train
    Vehicle.Ferry -> BtIcons.Ferry
    Vehicle.Plane -> BtIcons.Plane
    Vehicle.Auto, null -> BtIcons.Route
}

/** The line icon for a plan item's kind; travel items show how they travel when a vehicle is set. */
fun kindIcon(kind: String, vehicle: Vehicle? = null): ImageVector = when (PlanKind.of(kind)) {
    PlanKind.Place -> BtIcons.Place
    PlanKind.Food -> BtIcons.Food
    PlanKind.Stay -> BtIcons.Stay
    PlanKind.Transport -> if (vehicle != null && vehicle != Vehicle.Auto) vehicleIcon(vehicle) else BtIcons.Transport
    PlanKind.Activity -> BtIcons.Activity
    PlanKind.Note -> BtIcons.Note
}

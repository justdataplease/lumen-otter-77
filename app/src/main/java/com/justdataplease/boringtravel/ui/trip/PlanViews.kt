package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.model.PlanSpan
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.settings.PlanView
import com.justdataplease.boringtravel.links.PlacePhotos
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.places.DayPlace
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtTag
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.tags.ItemTags
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** What the List and Calendar views need from the Plan tab. */
internal class PlanViewContext(
    val trip: Trip,
    val items: List<PlanItem>,
    val byId: Map<String, PlanItem>,
    val stops: List<Stop>,
    val zone: ZoneId,
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
    val onPlace: (String) -> Unit,
    /** Opens the vehicle choice for a leg. */
    val onTravel: (PlanTravelConnection) -> Unit,
    /** Shares the item as text (a row's menu: [PlanItemMenu]). */
    val onShare: (PlanItem) -> Unit,
    /** A row lifted to drag it, and where it was dropped (the List only, see [PlanDrop]). */
    val onLift: (PlanLine) -> Unit = {},
    val onDrop: (PlanLine) -> Unit = {},
    /** The days' own names ([com.justdataplease.boringtravel.model.DayNames]); a named day shows its name, not its place. */
    val dayNames: Map<LocalDate, String> = emptyMap(),
    /** Opens the day's name to change it (a day's header, pressed and held). */
    val onRenameDay: (LocalDate) -> Unit = {},
) {
    /** Times get their own column only when some item in the plan has one (a start or an end). */
    val anyTime: Boolean = items.any { it.timeMinutes != null || it.endMinutes != null }
}

internal fun PlanView.icon(): ImageVector = when (this) {
    PlanView.List -> BtIcons.List
    PlanView.Calendar -> BtIcons.Calendar
}

/**
 * The Plan tab's own row, above the plan and never on top of it: Expand/Collapse on the left (the List only), "12 days ·
 * 34 items", and List · Calendar on the right.
 */
@Composable
internal fun PlanToolbar(
    view: PlanView,
    expanded: Boolean,
    summary: String,
    onExpanded: (Boolean) -> Unit,
    onView: (PlanView) -> Unit,
) {
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
internal fun PlanExpandToggle(expanded: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun PlanViewSwitch(view: PlanView, onPick: (PlanView) -> Unit, modifier: Modifier = Modifier) {
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

/** "12 days · 34 items", on the Plan tab's top row. */
internal fun planSummary(days: Int, items: Int): String = listOfNotNull(
    days.takeIf { it > 0 }?.let(Dates::days),
    if (items == 1) "1 item" else "$items items",
).joinToString(" · ")

// ---------------------------------------------------------------- List

/**
 * The List: [rows] from [PlanListLogic.rows]. Collapsed, small day headers and one line per item; expanded, each day
 * in full ("Day 3 · Kyoto", items with their kind in a circle, the travel between two places). Empty days folded in
 * both, today marked, Ideas at the end. With [reorder], a held item lifts and drags within its day or onto another
 * (day headers, folded days and Ideas are where it can go; owner, 2026-11-12: "give the possibility to drag").
 */
internal fun LazyListScope.planList(
    ctx: PlanViewContext,
    rows: List<PlanListLogic.Row>,
    expanded: Boolean,
    reorder: ReorderableLazyListState? = null,
) {
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
        is PlanListLogic.Row.DayHead ->
            if (expanded) DayHeader(ctx, row.day, row.number) else CompactDayHeader(ctx, row.day, row.number)
        is PlanListLogic.Row.Line ->
            if (expanded) ExpandedItemRow(ctx, row.line, reorder) else PlanLineRow(ctx, row.line, reorder)
        is PlanListLogic.Row.DayEmpty -> Text(
            if (row.day == ctx.today) "Nothing planned for today yet." else "Nothing planned for this day yet.",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = if (expanded) 8.dp else 4.dp),
            style = if (expanded) Bt.text.sub else Bt.text.fine,
        )
        is PlanListLogic.Row.Folded -> EmptyDays(ctx, row.from, row.to, expanded)
        is PlanListLogic.Row.IdeasHead -> IdeasHeaderRow(ctx, row.count, expanded)
        PlanListLogic.Row.IdeasEmpty -> Text(
            "Places and notes for later.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = Bt.text.sub,
        )
    }
}

/**
 * "Day 3 · Kyoto" (or the day's own name: "Day 3 · Temples and tea") and the date, small; TODAY on today; the title
 * opens the place page, pressed and held it is renamed, + adds to the day.
 */
@Composable
private fun CompactDayHeader(ctx: PlanViewContext, day: LocalDate, number: Int?) {
    val c = Bt.colors
    val place = remember(day, ctx.stops, ctx.items) { DayPlace.label(day, ctx.stops, ctx.items, ctx.zone) ?: PlanDayLabel.label(day, ctx.items) }
    val anchor = remember(day, ctx.items, place) { PlanDayAnchor.item(day, ctx.items, place) }
    val title = dayTitle(ctx, day, number, place)
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(btControlShape()).dayTitleTouch(ctx, day, anchor, place)
                .heightIn(min = 36.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title, Modifier.weight(1f, fill = false),
                style = Bt.text.label.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), color = if (day == ctx.today) c.accentInk else c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (day == ctx.today) TodayTag()
            Text(
                if (number != null) "${Dates.weekday(day)} ${Dates.dayMonth(day)}" else "Outside the dates",
                style = Bt.text.mono, maxLines = 1,
            )
        }
        AddIcon("Add to ${number?.let { "day $it" } ?: Dates.dayMonth(day)}") { ctx.onAdd(day) }
    }
}

/**
 * The expanded List's day: "Day 13 · El Chaltén" in the title face (a tap opens the place page, anchored on an item as
 * [PlanDayAnchor] picks; pressed and held, the day is renamed), TODAY on today, the date quietly at the end and + to
 * add to the day.
 */
@Composable
private fun DayHeader(ctx: PlanViewContext, day: LocalDate, number: Int?) {
    val c = Bt.colors
    val place = remember(day, ctx.stops, ctx.items) { DayPlace.label(day, ctx.stops, ctx.items, ctx.zone) ?: PlanDayLabel.label(day, ctx.items) }
    val anchor = remember(day, ctx.items, place) { PlanDayAnchor.item(day, ctx.items, place) }
    val title = dayTitle(ctx, day, number, place)
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f, fill = false).clip(btControlShape()).dayTitleTouch(ctx, day, anchor, place)
                    .heightIn(min = 40.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title, Modifier.weight(1f, fill = false), style = Bt.text.title,
                    color = if (day == ctx.today) c.accentInk else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (anchor != null) Icon(BtIcons.Forward, null, Modifier.size(16.dp), tint = c.muted)
            }
            if (day == ctx.today) TodayTag()
        }
        if (number != null) Text("${Dates.weekday(day)} ${Dates.dayMonth(day)}", style = Bt.text.mono, maxLines = 1)
        AddIcon("Add to ${number?.let { "day $it" } ?: Dates.dayMonth(day)}") { ctx.onAdd(day) }
    }
    if (number == null && ctx.trip.startDate != null) {
        Text("Outside the trip's dates. Move these items to a day or to Ideas.", Modifier.padding(horizontal = 20.dp), style = Bt.text.fine)
    }
}

/**
 * "Day 3 · Temples and tea": the day's own name when it has one (owner, 16 Nov 2026: the name in place of the place),
 * else its [place]; a day outside the trip's dates leads with its date.
 */
private fun dayTitle(ctx: PlanViewContext, day: LocalDate, number: Int?, place: String?): String =
    listOfNotNull(number?.let { "Day $it" } ?: Dates.dayMonth(day), ctx.dayNames[day] ?: place).joinToString(" · ")

/**
 * A day's title under the finger: a tap opens the day's place page ([anchor]); pressed and held, the day's name is
 * asked (as an item's row opens its menu). A day with no place page to open asks for the name on a tap too.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.dayTitleTouch(ctx: PlanViewContext, day: LocalDate, anchor: PlanItem?, place: String?): Modifier = combinedClickable(
    role = Role.Button,
    onClickLabel = if (anchor != null) "Open ${place ?: anchor.title}" else "Rename this day",
    onLongClickLabel = "Rename this day",
    onLongClick = { ctx.onRenameDay(day) },
    onClick = { if (anchor != null) ctx.onPlace(anchor.id) else ctx.onRenameDay(day) },
)

/** Marks today's day: the accent tag, made like PRO and DEMO. */
@Composable
private fun TodayTag() = BtTag("TODAY")

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

/**
 * + on a day's (or the Ideas') header: a filled round button in a 44 dp touch target (owner, 2026-11-10: "+ is too
 * small"; it was a bare grey 18 dp plus).
 */
@Composable
private fun AddIcon(label: String, onClick: () -> Unit) {
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
        from == to -> (a?.let { "Day $it" } ?: Dates.dayMonth(from))
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
 * One plan item on one line: the time (when the plan uses times), a small kind badge that ticks it off, the title and
 * its place in muted text, and the way there as a tiny vehicle at the end. The line opens the item's sheet; held, it
 * drags ([reorder], the List) or opens its menu ([PlanRowTouch]).
 */
@Composable
internal fun PlanLineRow(ctx: PlanViewContext, line: PlanLine, reorder: ReorderableCollectionItemScope? = null) =
    PlanRowTouch(ctx, line, reorder) { touch, holding -> PlanLineContent(ctx, line, holding, touch) }

@Composable
private fun PlanLineContent(ctx: PlanViewContext, line: PlanLine, holding: () -> Boolean, modifier: Modifier = Modifier) {
    val c = Bt.colors
    val item = line.item
    val end = line.end
    val title = if (end) PlanSpan.endName(item) else item.title
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        } else Box(
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
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // "until Sun 15 · 11:00 · 3 nights" under an item that ends on another day.
            if (until != null) Text(until, style = Bt.text.fine, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ItemTagsLine(tags)
        }
        line.leg?.let { v ->
            Icon(vehicleIcon(v), "${v.label} there", Modifier.padding(start = 8.dp).size(14.dp), tint = c.muted)
        }
    }
}

// ---------------------------------------------------------------- Calendar

/**
 * The Calendar view: a month grid for every month the plan touches, the trip's days filled, dots for planned items.
 * The picked day's items follow its month in the compact form; Ideas sit under the last month.
 */
internal fun LazyListScope.planCalendar(
    ctx: PlanViewContext,
    days: List<LocalDate>,
    selected: LocalDate?,
    firstDay: DayOfWeek,
    onSelect: (LocalDate?) -> Unit,
) {
    val months = PlanCalendarLogic.months(days, firstDay)
    val counts = PlanCalendarLogic.counts(ctx.items)
    val doneDays = ctx.items.filter { it.day != null }.groupBy { it.day!! }.filterValues { l -> l.all { it.done } }.keys
    val daySet = days.toSet()
    for (m in months) {
        item(key = "cm-${m.month}") {
            MonthBlock(ctx, m, firstDay, daySet, counts, doneDays, selected, onSelect)
        }
        if (selected != null && java.time.YearMonth.from(selected) == m.month) dayPanel(ctx, selected)
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

/** The week starts where the phone's locale says, as in the Journal's calendar. */
@Composable
internal fun rememberFirstDayOfWeek(): DayOfWeek {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return remember(locale) { WeekFields.of(locale).firstDayOfWeek }
}

@Composable
private fun MonthBlock(
    ctx: PlanViewContext,
    m: PlanCalendarLogic.Month,
    first: DayOfWeek,
    days: Set<LocalDate>,
    counts: Map<LocalDate, Int>,
    doneDays: Set<LocalDate>,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val label = remember(m.month, locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(m.month).replaceFirstChar { it.titlecase(locale) } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
        Text(label, Modifier.padding(start = 6.dp, bottom = 4.dp), style = Bt.text.title)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            PlanCalendarLogic.weekdays(first).forEach { d ->
                Text(
                    d.getDisplayName(java.time.format.TextStyle.SHORT, locale).uppercase(locale),
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
                        planDay = d != null && d in days,
                        count = d?.let { counts[it] } ?: 0,
                        allDone = d != null && d in doneDays,
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
private fun RowScope.DayCell(
    d: LocalDate?,
    inTrip: Boolean,
    planDay: Boolean,
    count: Int,
    allDone: Boolean,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
) {
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
                d.dayOfMonth.toString(),
                style = Bt.text.label.copy(fontSize = 14.sp, fontWeight = if (today || inTrip) FontWeight.Bold else FontWeight.Medium),
                color = when {
                    today -> c.accentInk
                    inTrip || planDay -> c.ink
                    else -> c.muted.copy(alpha = 0.55f)
                },
            )
            val dot = if (allDone) c.muted else c.route
            // Tall enough for the count's digits (a 6 dp row cut them in half).
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

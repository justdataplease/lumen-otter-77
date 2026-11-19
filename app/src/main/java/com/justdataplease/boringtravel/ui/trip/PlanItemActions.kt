package com.justdataplease.boringtravel.ui.trip

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.ui.components.copyText
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import com.justdataplease.boringtravel.ui.theme.Bt
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * Where the leg into [item] comes from, as the item sheet says it ("From Mendoza" between places, "From Boutique
 * hotel" within one), and how far that is in metres (null: one end has no pin).
 */
internal fun gettingThereFrom(connection: PlanTravelConnection, items: List<PlanItem>, item: PlanItem): Pair<String, Double?> {
    val from = items.firstOrNull { it.id == connection.leg.key.fromItemId }
    val distance = from?.let { f ->
        if (PlaceGrouping.located(f) && PlaceGrouping.located(item)) Geo.distanceM(f.lat!!, f.lng!!, item.lat!!, item.lng!!) else null
    }
    val label = from?.takeIf { distance == null || distance > PlaceGrouping.RADIUS_M }?.let { PlaceGrouping.placeOf(items, it) }
        ?: connection.fromLabel
    return label to distance
}

/** The whole item as text ([PlanItemText]), with how you get there when the plan has a leg into it. */
internal fun shareText(item: PlanItem, connection: PlanTravelConnection?, items: List<PlanItem>): String =
    PlanItemText.of(item, connection?.let { PlanItemText.gettingThere(it.mode, gettingThereFrom(it, items, item).first) })

/** Android's share sheet with [text] (any app that takes text: messages, mail, notes). */
internal fun shareItem(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * The small menu a plan row opens when held without moving: Copy name, Copy address (when it has one) and Share.
 * [onShare] shares the item ([shareText]).
 */
@Composable
internal fun PlanItemMenu(item: PlanItem, open: Boolean, onDismiss: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = Bt.colors.surface) {
        MenuLine("Copy name", BtIcons.Duplicate, onDismiss) { copyText(context, haptics, item.title, "Name") }
        val address = item.placeName.trim()
        if (address.isNotEmpty()) MenuLine("Copy address", BtIcons.Place, onDismiss) { copyText(context, haptics, address, "Address") }
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

/**
 * A long press lifts the row to drag it (as the library's own long-press handle does); released without moving, it
 * is a hold and [onHold] runs too, so one gesture both drags and opens the row's menu without the two fighting.
 */
internal class HoldOrDrag(private val onHold: () -> Unit) : DragGestureDetector {
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
 * ([PlanItemMenu]); elsewhere (the Calendar, an item's end row) a long press opens the menu. [content] gets the
 * modifier for the row and whether a hold is under way (a tick-off circle inside the row ignores the release of a hold).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlanRowTouch(
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

package com.justdataplease.boringtravel.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFonts

/**
 * Docked under the note, above the keyboard (the Android app's EditorToolbar, design boards "Write"): a row of font chips
 * (the look's own, then each journal face) over the tools Aa · B · lists · take a photo · choose photos and videos ·
 * record a video · place, and the round mic ([onMic]): Stop in the route colour while [listening].
 */
@Composable
internal fun EditorToolbar(
    model: WriteModel,
    rich: RichTextState?,
    menuAtStart: WriteMenu? = null,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onPlace: () -> Unit,
    listening: Boolean = false,
    onMic: () -> Unit = {},
) {
    val c = Bt.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FontChip("Default", Bt.tokens.type.body, model.font == null) { model.font = null }
            BtFonts.journalFonts.forEach { (id, family) ->
                FontChip(BtFonts.journalFontLabels[id] ?: id, family, model.font == id) { model.font = id }
            }
        }
        val ink = rich?.currentInk
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slot { TextStyleButton(rich, openAtStart = menuAtStart == WriteMenu.Style) }
            Slot { ToolText("B", "Bold", on = ink?.bold == true, enabled = rich != null, weight = FontWeight.Bold) { rich?.toggleBold() } }
            Slot { ListButton(model, rich, openAtStart = menuAtStart == WriteMenu.Lists) }
            // One tap each (owner, 2026-11-07: take photo, choose photo, place and record in one row).
            Slot { ToolIcon(BtIcons.Camera, "Take a photo", onClick = onCamera) }
            Slot { ToolIcon(BtIcons.Gallery, "Choose photos and videos", onClick = onPhoto) }
            Slot { ToolIcon(BtIcons.Movie, "Record a video", onClick = onVideo) }
            Slot { ToolIcon(BtIcons.Place, "Add place", onClick = onPlace) }
            Slot {
                val label = if (listening) "Stop dictation" else "Dictate"
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (listening) c.route else c.primary)
                        .then(KeepsFocus)
                        .clickable(role = Role.Button, onClickLabel = label, onClick = onMic)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (listening) BtIcons.Stop else BtIcons.Mic, null, Modifier.size(20.dp), tint = if (listening) Color.White else c.onPrimary)
                }
            }
        }
    }
}

/** One equal share of the tool row, so the tools fit any phone width. */
@Composable
private fun RowScope.Slot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun FontChip(label: String, family: FontFamily, selected: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Box(
        Modifier
            .height(40.dp)
            .clip(btControlShape())
            .background(if (selected) c.chipOn else c.chip)
            .then(KeepsFocus)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Bt.text.label.copy(fontFamily = family, fontSize = if (family == BtFonts.caveat) 17.sp else 13.sp),
            color = if (selected) c.onChipOn else c.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolText(text: String, label: String, on: Boolean, enabled: Boolean, weight: FontWeight = FontWeight.Bold, onClick: () -> Unit) {
    val c = Bt.colors
    ToolBox(label, on, enabled, onClick) {
        Text(text, style = Bt.text.button.copy(fontWeight = weight, fontFamily = Bt.tokens.type.body), color = if (on) c.onPrimary else if (enabled) c.ink else c.muted)
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Bt.colors
    ToolBox(label, on, enabled, onClick) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (on) c.onPrimary else if (enabled) c.ink else c.muted)
    }
}

@Composable
private fun ToolBox(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Bt.colors.primary else Color.Transparent)
            .then(KeepsFocus)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Aa: heading, subheading, normal text, larger, smaller, italic, underline, strikethrough, highlight and a few colours from the look. */
@Composable
private fun TextStyleButton(rich: RichTextState?, openAtStart: Boolean = false) {
    val c = Bt.colors
    var open by remember { mutableStateOf(openAtStart) }
    Box {
        ToolText("Aa", "Text style", on = open, enabled = rich != null, weight = FontWeight.SemiBold) { open = true }
        DropdownMenu(
            expanded = open && rich != null,
            onDismissRequest = { open = false },
            containerColor = c.surface,
            properties = PopupProperties(focusable = false),
        ) {
            val s = rich ?: return@DropdownMenu
            val ink = s.currentInk
            val line = s.currentLine
            fun pick(action: () -> Unit) {
                action()
                open = false
            }
            MenuText("Heading", line.heading == 2) { pick { s.setHeading(2) } }
            MenuText("Subheading", line.heading == 3) { pick { s.setHeading(3) } }
            MenuText("Normal text", line.heading == 0 && ink.size == null) {
                pick {
                    s.setHeading(0)
                    s.restyle { if (it.size == LARGER || it.size == SMALLER) it.copy(size = null) else it }
                }
            }
            HorizontalDivider(color = c.line)
            MenuText("Larger", ink.size == LARGER) { pick { s.toggleSize(LARGER) } }
            MenuText("Smaller", ink.size == SMALLER) { pick { s.toggleSize(SMALLER) } }
            MenuText("Italic", ink.italic) { pick { s.toggleItalic() } }
            MenuText("Underline", ink.underline) { pick { s.toggleUnderline() } }
            MenuText("Strikethrough", ink.strike) { pick { s.toggleStrike() } }
            // The highlighter: the look's accent behind the words (kept as background-color in the note).
            MenuText("Highlight", ink.background?.let { lookColor(it, c) } == c.accent) { pick { s.toggleHighlight(c.accent.argb()) } }
            HorizontalDivider(color = c.line)
            val swatches = listOf(c.route, c.danger, c.accentInk, c.muted).distinct().filter { it != c.ink }
            val now = ink.color?.let { lookColor(it, c) }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(c.ink, "Default colour", selected = now == null) { pick { s.restyle { it.copy(color = null) } } }
                swatches.forEach { col ->
                    // The same colour again takes it away (as the Android editor's swatches do).
                    Swatch(col, "Colour", selected = now == col) { pick { s.restyle { it.copy(color = if (now == col) null else col.argb()) } } }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(KeepsFocus)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .border(if (selected) 3.dp else 1.dp, if (selected) c.ink else c.line, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun MenuText(text: String, checked: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    DropdownMenuItem(text = { Text(text, style = Bt.text.body, color = if (checked) c.accentInk else c.ink) }, onClick = onClick, modifier = KeepsFocus)
}

/**
 * The toolbar's buttons and menus never take the focus: the text keeps its caret and selection, which the tools act on
 * (a touch never moves focus to a button, but a desktop's click does).
 */
private val KeepsFocus: Modifier = Modifier.focusProperties { canFocus = false }

/** Lists: bullets, numbers, or a checklist block where the writer is. */
@Composable
private fun ListButton(model: WriteModel, rich: RichTextState?, openAtStart: Boolean = false) {
    val c = Bt.colors
    var open by remember { mutableStateOf(openAtStart) }
    val list = rich?.currentLine?.list
    Box {
        ToolIcon(BtIcons.List, "Lists", on = open || list != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface, properties = PopupProperties(focusable = false)) {
            DropdownMenuItem(
                text = { Text("Bullets", style = Bt.text.body, color = if (list == ListStyle.Bullet) c.accentInk else c.ink) },
                leadingIcon = { Icon(BtIcons.List, null, tint = c.ink) },
                enabled = rich != null,
                modifier = KeepsFocus,
                onClick = {
                    rich?.toggleList(ListStyle.Bullet)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Numbers", style = Bt.text.body, color = if (list == ListStyle.Number) c.accentInk else c.ink) },
                leadingIcon = { Icon(BtIcons.NumberedList, null, tint = c.ink) },
                enabled = rich != null,
                modifier = KeepsFocus,
                onClick = {
                    rich?.toggleList(ListStyle.Number)
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Checklist", style = Bt.text.body, color = c.ink) },
                leadingIcon = { Icon(BtIcons.Checklist, null, tint = c.ink) },
                modifier = KeepsFocus,
                onClick = {
                    model.insertHere(listOf(model.newChecklist()))
                    open = false
                },
            )
        }
    }
}

/**
 * Over the toolbar (the Android editor's StatusLine): "Listening · Greek" with a small level meter while dictating (a tap
 * stops), else a short message: dictation's error in red, what went wrong, "Preparing the video… 40%" with an X that
 * stops it, or "Adding photos…" while they go in.
 */
@Composable
internal fun StatusLine(model: WriteModel, dictation: Dictation.State = Dictation.State.Off, level: Float = 0f, onStop: () -> Unit = {}, onDismissError: () -> Unit = {}) {
    val c = Bt.colors
    val video = model.videoProgress
    if (dictation is Dictation.State.Listening) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(c.chip)
                    .clickable(role = Role.Button, onClickLabel = "Stop dictation", onClick = onStop)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelBars(level)
                Text("Listening · ${dictation.language}", style = Bt.text.label.copy(fontSize = 12.sp), color = c.ink, maxLines = 1)
            }
        }
        return
    }
    val failed = dictation as? Dictation.State.Failed
    val message = failed?.message ?: model.notice ?: when {
        video != null -> "Preparing the video… ${com.justdataplease.boringtravel.data.repo.VideoLimits.percent(video)}%"
        model.importing -> "Adding photos…"
        else -> null
    }
    message ?: return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = Bt.text.sub, color = if (failed != null) c.danger else c.muted)
        if (video != null && failed == null && model.notice == null) {
            // The X stops the video being prepared: nothing of it stays (Android's cancelVideo).
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Stop adding the video", onClick = model::cancelVideo),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Close, "Stop adding the video", Modifier.size(16.dp), tint = c.muted) }
        } else if (failed != null || !model.importing || model.notice != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Dismiss") {
                    if (failed != null) onDismissError() else model.notice = null
                },
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Close, "Dismiss", Modifier.size(16.dp), tint = c.muted) }
        }
    }
}

/** Dictation's level as five small bars in the route colour. */
@Composable
private fun LevelBars(level: Float) {
    val c = Bt.colors
    val l by animateFloatAsState(level, label = "level")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(8f, 14f, 10f, 16f, 7f).forEachIndexed { i, h ->
            val k = 0.45f + 0.55f * (l * (if (i % 2 == 0) 0.8f else 1f)).coerceIn(0f, 1f)
            Box(Modifier.width(3.dp).height((h * k).dp).clip(RoundedCornerShape(2.dp)).background(c.route))
        }
    }
}

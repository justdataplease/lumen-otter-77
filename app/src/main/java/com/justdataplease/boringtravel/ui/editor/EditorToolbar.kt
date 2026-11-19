package com.justdataplease.boringtravel.ui.editor

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.speech.Dictation
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState

private val larger = SpanStyle(fontSize = 1.3.em)
private val smaller = SpanStyle(fontSize = 0.85.em)

/**
 * Always docked outside the document scroll, above the keyboard (design boards "Write"): a row of font chips (the
 * theme's own, then each journal face) over the tools Aa · B · lists · take a photo · choose photos and videos ·
 * record a video ([onVideo]) · place, and the round mic.
 */
@Composable
internal fun EditorToolbar(
    vm: WriteViewModel,
    listening: Boolean,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onPlace: () -> Unit,
    onMic: () -> Unit,
) {
    val c = Bt.colors
    val rich = vm.activeRich()
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FontChip("Default", Bt.tokens.type.body, vm.font == null) { vm.font = null }
            BtFonts.journalFonts.forEach { (id, family) ->
                FontChip(BtFonts.journalFontLabels[id] ?: id, family, vm.font == id) { vm.font = id }
            }
        }
        val span = rich?.currentSpanStyle
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slot { TextSizeButton(rich) }
            Slot {
                ToolText("B", "Bold", on = span?.fontWeight == FontWeight.Bold, enabled = rich != null, weight = FontWeight.Bold) {
                    rich?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                }
            }
            Slot { ListButton(vm, rich) }
            // One tap each (owner, 2026-11-07: take photo, choose photo, place and record in one row; italic,
            // underline and strikethrough live under Aa).
            Slot { ToolIcon(BtIcons.Camera, "Take a photo", onClick = onCamera) }
            Slot { ToolIcon(BtIcons.Gallery, "Choose photos and videos", onClick = onPhoto) }
            Slot { ToolIcon(BtIcons.Movie, "Record a video", onClick = onVideo) }
            Slot { ToolIcon(BtIcons.Place, "Add place", onClick = onPlace) }
            Slot {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (listening) c.route else c.primary)
                        .clickable(role = Role.Button, onClickLabel = if (listening) "Stop dictation" else "Dictate", onClick = onMic)
                        .semantics { contentDescription = if (listening) "Stop dictation" else "Dictate" },
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
private fun ToolText(
    text: String,
    label: String,
    on: Boolean,
    enabled: Boolean,
    weight: FontWeight = FontWeight.Bold,
    italic: Boolean = false,
    underline: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Bt.colors
    ToolBox(label, on, enabled, onClick) {
        Text(
            text,
            style = Bt.text.button.copy(
                fontWeight = weight,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                fontFamily = if (italic) BtFonts.literata else Bt.tokens.type.body,
                textDecoration = if (underline) TextDecoration.Underline else null,
            ),
            color = if (on) c.onPrimary else if (enabled) c.ink else c.muted,
        )
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
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Aa: heading, subheading, normal text, larger, smaller, italic, underline, strikethrough, highlight and a few colours from the look. */
@Composable
private fun TextSizeButton(rich: RichTextState?) {
    val c = Bt.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ToolText("Aa", "Text style", on = open, enabled = rich != null, weight = FontWeight.SemiBold) { open = true }
        DropdownMenu(
            expanded = open && rich != null,
            onDismissRequest = { open = false },
            containerColor = c.surface,
            properties = PopupProperties(focusable = false),
        ) {
            val s = rich ?: return@DropdownMenu
            fun pick(action: () -> Unit) {
                action()
                open = false
            }
            MenuText("Heading", s.currentHeadingStyle == HeadingStyle.H2) { pick { s.setHeadingStyle(HeadingStyle.H2) } }
            MenuText("Subheading", s.currentHeadingStyle == HeadingStyle.H3) { pick { s.setHeadingStyle(HeadingStyle.H3) } }
            MenuText("Normal text", s.currentHeadingStyle == HeadingStyle.Normal && !s.currentSpanStyle.fontSize.isSpecifiedSize()) {
                pick {
                    s.setHeadingStyle(HeadingStyle.Normal)
                    s.removeSpanStyle(larger)
                    s.removeSpanStyle(smaller)
                }
            }
            HorizontalDivider(color = c.line)
            MenuText("Larger", s.currentSpanStyle.fontSize == larger.fontSize) { pick { s.removeSpanStyle(smaller); s.toggleSpanStyle(larger) } }
            MenuText("Smaller", s.currentSpanStyle.fontSize == smaller.fontSize) { pick { s.removeSpanStyle(larger); s.toggleSpanStyle(smaller) } }
            MenuText("Italic", s.currentSpanStyle.fontStyle == FontStyle.Italic) {
                pick { s.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
            }
            MenuText("Underline", s.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true) {
                pick { s.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
            }
            MenuText("Strikethrough", s.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true) {
                pick { s.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }
            }
            // The highlighter: the theme's accent behind the words (kept as background-color in the entry).
            MenuText("Highlight", s.currentSpanStyle.background == c.accent) {
                pick { s.toggleSpanStyle(SpanStyle(background = c.accent)) }
            }
            HorizontalDivider(color = c.line)
            val swatches = listOf(c.route, c.danger, c.accentInk, c.muted).distinct().filter { it != c.ink }
            val now = s.currentSpanStyle.color
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(c.ink, "Default colour", selected = !now.isSpecified) {
                    pick { if (now.isSpecified) s.removeSpanStyle(SpanStyle(color = now)) }
                }
                swatches.forEach { col ->
                    Swatch(col, "Colour", selected = now == col) {
                        pick {
                            if (now.isSpecified) s.removeSpanStyle(SpanStyle(color = now))
                            if (now != col) s.addSpanStyle(SpanStyle(color = col))
                        }
                    }
                }
            }
        }
    }
}

private fun TextUnit.isSpecifiedSize(): Boolean = this != TextUnit.Unspecified

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .border(if (selected) 3.dp else 1.dp, if (selected) c.ink else c.line, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun MenuText(text: String, checked: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    DropdownMenuItem(
        text = { Text(text, style = Bt.text.body, color = if (checked) c.accentInk else c.ink) },
        onClick = onClick,
    )
}

/** Lists: bullets, numbers, or a checklist block where the writer is. */
@Composable
private fun ListButton(vm: WriteViewModel, rich: RichTextState?) {
    val c = Bt.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIcon(BtIcons.List, "Lists", on = open || rich?.isList == true) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.surface,
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                text = { Text("Bullets", style = Bt.text.body, color = if (rich?.isUnorderedList == true) c.accentInk else c.ink) },
                leadingIcon = { Icon(BtIcons.List, null, tint = c.ink) },
                enabled = rich != null,
                onClick = {
                    rich?.toggleUnorderedList()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Numbers", style = Bt.text.body, color = if (rich?.isOrderedList == true) c.accentInk else c.ink) },
                leadingIcon = { Icon(BtIcons.NumberedList, null, tint = c.ink) },
                enabled = rich != null,
                onClick = {
                    rich?.toggleOrderedList()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Checklist", style = Bt.text.body, color = c.ink) },
                leadingIcon = { Icon(BtIcons.Checklist, null, tint = c.ink) },
                onClick = {
                    vm.insertHere(listOf(vm.newChecklist()))
                    open = false
                },
            )
        }
    }
}

/**
 * "Listening · Greek" with a small level meter, or a short message (errors, photos being added, "Preparing the
 * video… 40%" with a button that stops it).
 */
@Composable
internal fun StatusLine(vm: WriteViewModel, state: Dictation.State, level: Float, onStop: () -> Unit) {
    val c = Bt.colors
    val video = vm.videoProgress
    val message = when {
        state is Dictation.State.Failed -> state.message
        vm.notice != null -> vm.notice
        video != null -> "Preparing the video… ${VideoLimits.percent(video)}%"
        vm.importing -> "Adding photos…"
        else -> null
    }
    when {
        state is Dictation.State.Listening -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                Text("Listening · ${state.language}", style = Bt.text.label.copy(fontSize = 12.sp), color = c.ink, maxLines = 1)
            }
        }
        message != null -> Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                Modifier.weight(1f),
                style = Bt.text.sub,
                color = if (state is Dictation.State.Failed) c.danger else c.muted,
            )
            if (video != null && state !is Dictation.State.Failed && vm.notice == null) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Stop adding the video", onClick = vm::cancelVideo),
                    contentAlignment = Alignment.Center,
                ) { Icon(BtIcons.Close, "Stop adding the video", Modifier.size(16.dp), tint = c.muted) }
            } else if (!vm.importing || state is Dictation.State.Failed || vm.notice != null) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Dismiss") {
                        if (state is Dictation.State.Failed) vm.dictation.dismissError() else vm.notice = null
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(BtIcons.Close, "Dismiss", Modifier.size(16.dp), tint = c.muted) }
            }
        }
    }
}

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

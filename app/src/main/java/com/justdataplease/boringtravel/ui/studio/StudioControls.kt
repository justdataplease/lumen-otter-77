package com.justdataplease.boringtravel.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.icons.BtIcons

private val S = StudioColors

// ---------------------------------------------------------------- top bar and buttons

/** The top bar: close, a title over a quiet line, then [actions] (round buttons, the main action). 56 dp. */
@Composable
fun StudioTopBar(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeLabel: String = "Close",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = closeLabel, onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(BtIcons.Close, null, Modifier.size(20.dp), tint = S.ink) }
        Column(Modifier.weight(1f)) {
            Text(title, style = StudioText.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = StudioText.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** A round button on the raised colour (previous / next page, a setting), [size] across in a 40 dp target. */
@Composable
fun StudioRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 34.dp) {
    Box(
        modifier.size(maxOf(size, 40.dp)).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(S.raised), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(size * 0.5f), tint = if (enabled) S.ink else S.muted.copy(alpha = 0.6f))
        }
    }
}

/** A plain icon button (undo, redo, an eye), 40 dp, no fill. */
@Composable
fun StudioIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = S.ink, iconSize: Dp = 18.dp) {
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else S.muted.copy(alpha = 0.55f)) }
}

/** The screen's main action (Export, PDF): coral with white words. */
@Composable
fun StudioMainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) S.coral else S.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = StudioText.button, color = if (enabled) Color.White else S.muted, maxLines = 1) }
}

/** A small yellow button ("+ Add"). */
@Composable
fun StudioPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(9.dp)).background(S.yellow)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = StudioText.chip.copy(fontSize = StudioText.chip.fontSize * (12f / 13f)), color = S.onYellow, maxLines = 1) }
}

/** A wide button in a panel or sheet: raised, or coral when [primary]. */
@Composable
fun StudioButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true,
    /** Deletes or removes something: in the danger colour (filled with it when [primary]). */
    danger: Boolean = false,
) {
    val fg = when {
        !enabled -> S.muted
        primary -> Color.White
        danger -> S.danger
        else -> S.ink
    }
    Row(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(if (primary && enabled) (if (danger) S.danger else S.coral) else S.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(text, style = StudioText.chip.copy(fontWeight = StudioText.button.fontWeight), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- the tool row

/** The tool row at the bottom: groups of tools side by side, scrolling when they don't fit. */
@Composable
fun StudioToolbar(modifier: Modifier = Modifier, spread: Int = 0, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(
        modifier.fillMaxWidth().background(S.bg)
            .drawBehind { drawLine(S.raised, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .height(88.dp),
    ) {
        // [spread] tools in one group fill the bar end to end (owner, 2026-11-09: "all icons in editor correctly
        // aligned end to end full width"), as long as each keeps [TOOL_MIN]; otherwise 54 dp each, scrolling.
        val fit = if (spread > 0) (maxWidth - 12.dp) / spread else 0.dp
        val width = if (fit >= TOOL_MIN) fit else TOOL_WIDTH
        CompositionLocalProvider(LocalToolWidth provides width) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), content = content)
        }
    }
}

/** A tool's usual width, and the least it may have when the bar spreads its tools across the screen. */
private val TOOL_WIDTH = 54.dp
private val TOOL_MIN = 44.dp

/** How wide each [StudioTool] is ([StudioToolbar] sets it). */
private val LocalToolWidth = compositionLocalOf { TOOL_WIDTH }

/**
 * One group of tools under its [label] (mono, upper-case); [highlight] = yellow, for the name of what the tools act
 * on. A hairline ends the group unless it is the [last].
 */
@Composable
fun StudioToolGroup(label: String, highlight: Boolean = false, last: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Column(
        Modifier.fillMaxHeight()
            .drawBehind { if (!last) drawLine(S.raised, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(start = 2.dp, end = 2.dp, top = 8.dp),
    ) {
        Text(label.uppercase(), Modifier.padding(start = 8.dp), style = StudioText.group, color = if (highlight) S.yellow else S.muted, maxLines = 1)
        Row(content = content)
    }
}

/** One tool: an icon over a short word, 54 dp wide (or as its bar spreads it). [selected] = its panel is open. */
@Composable
fun StudioTool(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean = false, danger: Boolean = false) {
    val fg = when {
        !enabled -> S.muted.copy(alpha = 0.5f)
        selected -> S.yellow
        danger -> S.danger
        else -> S.ink
    }
    Column(
        Modifier.width(LocalToolWidth.current).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(S.raised) else Modifier)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
        Text(label, style = StudioText.tool, color = fg, maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center)
    }
}

/** "PHOTO · FITZ ROY": a kind and a name for a tool group, the name cut short so the group stays narrow. */
fun groupLabel(kind: String, name: String?, max: Int = 22): String {
    val n = name?.trim()?.replace('\n', ' ').orEmpty()
    if (n.isEmpty() || n.equals(kind, ignoreCase = true)) return kind
    val room = (max - kind.length - 3).coerceAtLeast(4)
    return "$kind · " + if (n.length <= room) n else n.take(room - 1).trimEnd() + "…"
}

// ---------------------------------------------------------------- panels

/**
 * A tool's panel under the preview: a title, [header] (a reset chip, a switch), the yellow Done, and [content] in a
 * column that scrolls ([scroll] false when the content scrolls itself or needs the height, like a crop box).
 */
@Composable
fun StudioPanel(
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    header: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(S.panel)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, Modifier.weight(1f), style = StudioText.panelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            header()
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(S.yellow)
                    .clickable(role = Role.Button, onClickLabel = "Done", onClick = onDone).semantics { contentDescription = "Done" },
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Check, null, Modifier.size(17.dp), tint = S.onYellow) }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A labelled part of a panel; [value] shows at the end of the label (a size, an angle). */
@Composable
fun StudioSection(label: String, modifier: Modifier = Modifier, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), Modifier.weight(1f), style = StudioText.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) Text(value, style = StudioText.value)
        }
        content()
    }
}

/** A quiet line of help under controls. */
@Composable
fun StudioNote(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Text(text, modifier, style = StudioText.note, color = if (warn) S.warn else S.muted)
}

/** A choice: raised, yellow when [selected]. [fontFamily] sets its words in a face (font choices). */
@Composable
fun StudioChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fontFamily: FontFamily? = null,
    enabled: Boolean = true,
) {
    val fg = when {
        !enabled -> S.muted
        selected -> S.onYellow
        else -> S.ink
    }
    Row(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) S.yellow else S.raised)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(
            text, style = if (fontFamily != null) StudioText.chip.copy(fontFamily = fontFamily, fontSize = StudioText.chip.fontSize * 1.1f) else StudioText.chip,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [items] in rows of [columns] equal cells. */
@Composable
fun <T> StudioGrid(items: List<T>, columns: Int, modifier: Modifier = Modifier, spacing: Dp = 6.dp, cell: @Composable (T) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { Box(Modifier.weight(1f)) { cell(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Round colour swatches (#RRGGBB), the picked one ringed in yellow; [none] adds a first "no colour" swatch (or the
 * theme's own) with that name. Scrolls sideways when there are many.
 */
@Composable
fun StudioSwatches(colors: List<String>, selected: String?, onPick: (String?) -> Unit, none: String? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (none != null) Swatch(null, selected == null, none) { onPick(null) }
        colors.forEach { hex -> Swatch(hex, selected.equals(hex, ignoreCase = true), "Colour $hex") { onPick(hex) } }
    }
}

/**
 * Named colour choices as round swatches ([colors] with their [labels], read out and shown small under each), the
 * picked one ([selected]) ringed in yellow.
 */
@Composable
fun StudioColorChoices(colors: List<Color>, labels: List<String>, selected: Int, onPick: (Int) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEachIndexed { i, color ->
            val on = i == selected
            Column(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = labels.getOrElse(i) { "" }; this.selected = on }
                    .clickable(enabled = enabled, role = Role.RadioButton) { onPick(i) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).border(2.dp, if (on) S.yellow else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(color).border(1.dp, S.edge, CircleShape))
                }
                Text(labels.getOrElse(i) { "" }, style = StudioText.sub, color = if (on) S.ink else S.muted, maxLines = 1)
            }
        }
    }
}

/** "All scenes · This scene": which part of the clip a panel changes; [scene] false = all. */
@Composable
fun StudioScopeToggle(scene: Boolean, onScene: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(S.bg).padding(3.dp)) {
        listOf(false to "All scenes", true to "This scene").forEach { (value, label) ->
            val on = value == scene
            Box(
                Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) S.ink else Color.Transparent)
                    .semantics { this.selected = on }.clickable(role = Role.Tab) { onScene(value) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = StudioText.chip.copy(fontSize = StudioText.chip.fontSize * (12f / 13f)), color = if (on) S.bg else S.soft, maxLines = 1) }
        }
    }
}

@Composable
private fun Swatch(hex: String?, on: Boolean, label: String, onClick: () -> Unit) {
    val color = hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    Box(
        Modifier.size(38.dp).clip(CircleShape).border(2.dp, if (on) S.yellow else Color.Transparent, CircleShape)
            .semantics { contentDescription = label; this.selected = on }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(color ?: S.bg).border(1.dp, S.edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (color == null) Icon(BtIcons.Close, null, Modifier.size(14.dp), tint = S.muted) }
    }
}

/** A setting with a title, a line of help and a yellow switch. */
@Composable
fun StudioSwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = StudioText.item.copy(fontWeight = StudioText.chip.fontWeight))
            if (body != null) Text(body, style = StudioText.note)
        }
        Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (checked) S.yellow else S.raised).padding(3.dp)) {
            Box(Modifier.size(20.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(if (checked) S.onYellow else S.muted))
        }
    }
}

/** A slider in the studio's colours. */
@Composable
fun StudioSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(thumbColor = S.yellow, activeTrackColor = S.yellow, inactiveTrackColor = S.raised),
    )
}

/** A text field on the dark panel. [focus] puts the cursor in it (and the keyboard up) when it appears. */
@Composable
fun StudioField(value: String, onChange: (String) -> Unit, placeholder: String, minHeight: Dp = 48.dp, singleLine: Boolean = false, focus: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    val requester = remember { FocusRequester() }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
        textStyle = StudioText.body,
        cursorBrush = SolidColor(S.yellow),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(S.bg).border(1.dp, S.edge, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(placeholder, style = StudioText.body, color = S.muted)
                inner()
            }
        },
    )
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

/** A few options side by side in a dark well, the picked one light. */
@Composable
fun StudioSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(S.bg).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).background(if (on) S.ink else Color.Transparent)
                    .semantics { this.selected = on }.clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = StudioText.chip.copy(fontSize = StudioText.chip.fontSize * (12f / 13f)), color = if (on) S.bg else S.soft, maxLines = 1) }
        }
    }
}

/** A bottom sheet in the studio's colours with a title, for what is not a tool (the PDF, the whole book). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    StudioMaterial {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = S.panel,
            contentColor = S.ink,
            dragHandle = { BottomSheetDefaults.DragHandle(color = S.muted) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = StudioText.panelTitle)
                content()
            }
        }
    }
}

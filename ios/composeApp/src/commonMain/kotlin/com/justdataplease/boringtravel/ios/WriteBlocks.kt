package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/*
 * The editor's rows, as the Android app draws them (ui/editor/EditorBlocks.kt): the title, rich text areas, quotes,
 * checklists, place chips, lines and page breaks, each with its remove button, and the "+" between them.
 */

/** Focuses [requester] when the editor asks for [key] (new rows, new text areas). */
@Composable
internal fun FocusWhenAsked(model: WriteModel, key: String, requester: FocusRequester) {
    LaunchedEffect(model.focusKey) {
        if (model.focusKey == key) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            model.focusKey = null
        }
    }
}

/** The note's title in the look's display face (upper-case on screen with some looks' fonts). Enter moves to the text. */
@Composable
internal fun TitleField(model: WriteModel, onNext: () -> Unit) {
    val c = Bt.colors
    val style = Bt.text.h1.copy(color = c.ink)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        if (model.title.text.isEmpty()) Text(Bt.text.display("Title"), style = style.copy(color = c.muted.copy(alpha = 0.55f)))
        BasicTextField(
            value = model.title,
            onValueChange = { v ->
                if ('\n' in v.text) {
                    model.title = v.copy(text = v.text.replace("\n", ""))
                    onNext()
                } else {
                    model.title = v
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (it.isFocused) {
                    model.activeKey = null
                    model.selectedPhotoKey = null
                }
            },
            textStyle = style,
            cursorBrush = SolidColor(c.route),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            visualTransformation = if (Bt.tokens.type.displayUpper) UpperCase else VisualTransformation.None,
            maxLines = 4,
        )
    }
}

/** Shows text upper-case without changing it (only when that keeps every character in place). */
internal object UpperCase : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val up = text.text.uppercase()
        return if (up.length == text.text.length) TransformedText(AnnotatedString(up), OffsetMapping.Identity) else TransformedText(text, OffsetMapping.Identity)
    }
}

/**
 * A rich text area (paragraphs, lists, headings) or a quote's text, drawn as it will read ([RichTransformation]); list
 * items get their "•" or "2." in the margin, where the read view puts them.
 */
@Composable
internal fun RichArea(model: WriteModel, key: String, state: RichTextState, style: TextStyle, placeholder: String? = null) {
    val c = Bt.colors
    val requester = remember { FocusRequester() }
    FocusWhenAsked(model, key, requester)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()
    val value = state.value
    val markers = remember(value) { listMarkers(value) }
    val markerStyle = style.copy(color = c.muted)
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val l = layout ?: return@drawBehind
            for ((offset, label) in markers) {
                if (offset > l.layoutInput.text.length) continue
                val line = l.getLineForOffset(offset)
                drawText(measurer, label, topLeft = Offset(0f, l.getLineTop(line)), style = markerStyle, maxLines = 1)
            }
        },
    ) {
        if (placeholder != null && value.text.isEmpty()) Text(placeholder, style = style.copy(color = c.muted))
        BasicTextField(
            value = state.field,
            onValueChange = state::onFieldChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        model.activeKey = key
                        model.selectedPhotoKey = null
                    }
                },
            textStyle = style.copy(color = c.ink),
            cursorBrush = SolidColor(c.route),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            visualTransformation = RichTransformation(value, style, c),
            onTextLayout = { layout = it },
        )
    }
}

@Composable
internal fun QuoteItem(model: WriteModel, item: EdItem.Quote, base: TextStyle) {
    val c = Bt.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .weight(1f)
                .drawBehind { drawRect(c.route, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
        ) {
            RichArea(model, item.key, item.state, base.copy(fontStyle = FontStyle.Italic), placeholder = "Quote")
        }
        RemoveButton("Remove quote") { model.remove(item) }
    }
}

@Composable
internal fun ChecklistItem(model: WriteModel, item: EdItem.Check, base: TextStyle) {
    val c = Bt.colors
    Column {
        item.rows.forEach { row -> key(row.key) { CheckRowField(model, item, row, base) } }
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { model.addRow(item, item.rows.lastOrNull()) }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(BtIcons.Add, null, Modifier.size(18.dp), tint = c.muted)
            Text("Add item", style = Bt.text.sub)
        }
    }
}

@Composable
private fun CheckRowField(model: WriteModel, check: EdItem.Check, row: CheckRow, base: TextStyle) {
    val c = Bt.colors
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    FocusWhenAsked(model, row.key, requester)
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics { contentDescription = row.text.text.ifBlank { "Checklist item" } }
                .toggleable(value = row.done, role = Role.Checkbox, onValueChange = { row.done = it }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (row.done) BtIcons.CheckBox else BtIcons.CheckBoxEmpty, null, Modifier.size(22.dp), tint = if (row.done) c.route else c.muted)
        }
        Box(Modifier.weight(1f).padding(top = 5.dp)) {
            if (row.text.text.isEmpty()) Text("Item", style = base.copy(color = c.muted))
            BasicTextField(
                value = row.text,
                onValueChange = { v ->
                    if ('\n' in v.text) {
                        row.text = v.copy(text = v.text.replace("\n", ""))
                        model.addRow(check, row)
                    } else {
                        row.text = v
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) {
                            model.activeKey = check.key
                            model.selectedPhotoKey = null
                        }
                    }
                    .onPreviewKeyEvent {
                        if (it.key == Key.Backspace && it.type == KeyEventType.KeyDown && row.text.text.isEmpty()) {
                            model.removeRow(check, row)
                            true
                        } else {
                            false
                        }
                    },
                textStyle = base.copy(color = if (row.done) c.muted else c.ink, textDecoration = if (row.done) TextDecoration.LineThrough else null),
                cursorBrush = SolidColor(c.route),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { model.addRow(check, row) }),
            )
        }
        if (focused) RemoveButton("Remove item") { model.removeRow(check, row) }
    }
}

@Composable
internal fun PlaceItem(model: WriteModel, item: EdItem.Place, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlaceChip(item.block, Modifier.weight(1f, fill = false), onClick = onEdit)
        RemoveButton("Remove place") { model.remove(item) }
    }
}

@Composable
internal fun DividerItem(model: WriteModel, item: EdItem.Divider) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        RemoveButton("Remove line") { model.remove(item) }
    }
}

@Composable
internal fun PageBreakItem(model: WriteModel, item: EdItem.PageBreak) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        Text("New printed page", style = Bt.text.fine)
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        RemoveButton("Remove page break") { model.remove(item) }
    }
}

@Composable
internal fun KeptItem() {
    Text("Part of this entry was made in a newer version of the app. It stays as it is.", Modifier.padding(vertical = 4.dp), style = Bt.text.fine)
}

@Composable
internal fun RemoveButton(label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(BtIcons.Close, label, Modifier.size(16.dp), tint = Bt.colors.muted)
    }
}

/** The small "+" between blocks: opens the insert menu for that spot. */
@Composable
internal fun InsertPoint(onClick: () -> Unit) {
    val c = Bt.colors
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Insert here", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(BtIcons.Add, "Insert here", Modifier.size(16.dp), tint = c.muted)
            }
        }
    }
}

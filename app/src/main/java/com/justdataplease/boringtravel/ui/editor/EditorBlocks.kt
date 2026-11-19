package com.justdataplease.boringtravel.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.journal.PlaceChip
import com.justdataplease.boringtravel.ui.theme.Bt
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

/** Focuses [requester] when the view model asks for [key] (new rows, new text areas). */
@Composable
internal fun FocusWhenAsked(vm: WriteViewModel, key: String, requester: FocusRequester) {
    LaunchedEffect(vm.focusKey) {
        if (vm.focusKey == key) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            vm.focusKey = null
        }
    }
}

/** The entry title in the look's display face (upper-case on screen with Night Route's fonts). Enter moves to the text. */
@Composable
internal fun TitleField(vm: WriteViewModel, onNext: () -> Unit) {
    val c = Bt.colors
    val style = Bt.text.h1.copy(color = c.ink)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        if (vm.title.text.isEmpty()) Text(Bt.text.display("Title"), style = style.copy(color = c.muted.copy(alpha = 0.55f)))
        BasicTextField(
            value = vm.title,
            onValueChange = { v ->
                if ('\n' in v.text) {
                    vm.title = v.copy(text = v.text.replace("\n", ""))
                    onNext()
                } else {
                    vm.title = v
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (it.isFocused) {
                    vm.activeKey = null
                    vm.selectedPhotoKey = null
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
private object UpperCase : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val up = text.text.uppercase()
        return if (up.length == text.text.length) TransformedText(AnnotatedString(up), OffsetMapping.Identity) else TransformedText(text, OffsetMapping.Identity)
    }
}

/** A rich text area (paragraphs, lists, headings) or a quote's text. */
@Composable
internal fun RichArea(vm: WriteViewModel, key: String, state: RichTextState, style: TextStyle, placeholder: String? = null) {
    val c = Bt.colors
    val requester = remember { FocusRequester() }
    FocusWhenAsked(vm, key, requester)
    LaunchedEffect(state, c.route) {
        state.config.linkColor = c.route
        state.config.linkTextDecoration = TextDecoration.Underline
    }
    Box(Modifier.fillMaxWidth()) {
        if (placeholder != null && state.annotatedString.text.isEmpty()) Text(placeholder, style = style.copy(color = c.muted))
        BasicRichTextEditor(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        vm.activeKey = key
                        vm.selectedPhotoKey = null
                    }
                },
            textStyle = style.copy(color = c.ink),
            cursorBrush = SolidColor(c.route),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            contentPadding = PaddingValues(0.dp),
        )
    }
}

@Composable
internal fun QuoteItem(vm: WriteViewModel, item: EdItem.Quote, base: TextStyle) {
    val c = Bt.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .weight(1f)
                .drawBehind { drawRect(c.route, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
        ) {
            RichArea(vm, item.key, item.state, base.copy(fontStyle = FontStyle.Italic), placeholder = "Quote")
        }
        RemoveButton("Remove quote") { vm.remove(item) }
    }
}

@Composable
internal fun ChecklistItem(vm: WriteViewModel, item: EdItem.Check, base: TextStyle) {
    val c = Bt.colors
    Column {
        item.rows.forEach { row -> key(row.key) { CheckRowField(vm, item, row, base) } }
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { vm.addRow(item, item.rows.lastOrNull()) }
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
private fun CheckRowField(vm: WriteViewModel, check: EdItem.Check, row: CheckRow, base: TextStyle) {
    val c = Bt.colors
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    FocusWhenAsked(vm, row.key, requester)
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics { contentDescription = row.text.text.ifBlank { "Checklist item" } }
                .toggleable(value = row.done, role = Role.Checkbox, onValueChange = { row.done = it }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (row.done) BtIcons.CheckBox else BtIcons.CheckBoxEmpty,
                null,
                Modifier.size(22.dp),
                tint = if (row.done) c.route else c.muted,
            )
        }
        Box(Modifier.weight(1f).padding(top = 5.dp)) {
            if (row.text.text.isEmpty()) Text("Item", style = base.copy(color = c.muted))
            BasicTextField(
                value = row.text,
                onValueChange = { v ->
                    if ('\n' in v.text) {
                        row.text = v.copy(text = v.text.replace("\n", ""))
                        vm.addRow(check, row)
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
                            vm.activeKey = check.key
                            vm.selectedPhotoKey = null
                        }
                    }
                    .onPreviewKeyEvent {
                        if (it.key == Key.Backspace && it.type == KeyEventType.KeyDown && row.text.text.isEmpty()) {
                            vm.removeRow(check, row)
                            true
                        } else {
                            false
                        }
                    },
                textStyle = base.copy(
                    color = if (row.done) c.muted else c.ink,
                    textDecoration = if (row.done) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(c.route),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { vm.addRow(check, row) }),
            )
        }
        if (focused) RemoveButton("Remove item") { vm.removeRow(check, row) }
    }
}

@Composable
internal fun PlaceItem(vm: WriteViewModel, item: EdItem.Place, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlaceChip(item.block, Modifier.weight(1f, fill = false), onClick = onEdit)
        RemoveButton("Remove place") { vm.remove(item) }
    }
}

@Composable
internal fun DividerItem(vm: WriteViewModel, item: EdItem.Divider) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        RemoveButton("Remove line") { vm.remove(item) }
    }
}

@Composable
internal fun PageBreakItem(vm: WriteViewModel, item: EdItem.PageBreak) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        Text("New printed page", style = Bt.text.fine)
        HorizontalDivider(Modifier.weight(1f), color = Bt.colors.line)
        RemoveButton("Remove page break") { vm.remove(item) }
    }
}

@Composable
internal fun KeptItem() {
    Text(
        "Part of this entry was made in a newer version of the app. It stays as it is.",
        Modifier.padding(vertical = 4.dp),
        style = Bt.text.fine,
    )
}

@Composable
internal fun RemoveButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BtIcons.Close, label, Modifier.size(16.dp), tint = Bt.colors.muted)
    }
}

/** The small "+" between blocks: opens the insert menu for that spot. */
@Composable
internal fun InsertPoint(onClick: () -> Unit) {
    val c = Bt.colors
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Insert here", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(BtIcons.Add, "Insert here", Modifier.size(16.dp), tint = c.muted)
            }
        }
    }
}

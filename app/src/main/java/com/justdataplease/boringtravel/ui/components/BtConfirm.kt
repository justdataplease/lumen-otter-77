package com.justdataplease.boringtravel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/*
 * Asking and deleting look the same everywhere (owner, 2026-11-11: "the delete button in notes is not red … keep the
 * same style on all the app"): a delete is the bin in the theme's danger colour, outlined ([BtDeleteButton], full width
 * at the end of what it deletes, or BtIconButton(danger = true) in a row), and every question is [BtConfirmDialog], its action filled
 * in the danger colour when it deletes or removes. A delete always asks first, with a note in the danger colour
 * (owner, 2026-11-14: "upon delete have a danger note: are you sure you want to delete").
 */

/**
 * A question before an action: [title], what it does ([body]), [confirm] as the main button (in the danger colour when
 * [danger]: deleting or removing) and Cancel. A [danger] question shows its [body] as a note on the danger colour.
 * [busy] holds both while the action runs.
 */
@Composable
fun BtConfirmDialog(
    title: String,
    body: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    busy: Boolean = false,
    dismiss: String = "Cancel",
) {
    val c = Bt.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = { BtButton(confirm, onClick = onConfirm, small = true, danger = danger, enabled = !busy) },
        dismissButton = { BtGhostButton(dismiss, onClick = onDismiss, enabled = !busy) },
        title = { Text(title, style = Bt.text.title) },
        text = body?.let {
            {
                if (danger) {
                    Text(
                        it, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.danger.copy(alpha = 0.12f)).padding(12.dp),
                        style = Bt.text.body, color = c.ink,
                    )
                } else {
                    Text(it, style = Bt.text.body)
                }
            }
        },
        shape = btCardShape(),
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink,
    )
}

/**
 * One line of text asked in a dialog (a day's name, a link): the field starts from [value], shows [hint] while empty
 * and takes at most [max] characters; [note] under it says what an empty one does, [error] (in the danger colour) what
 * was wrong with the last try. [confirm] ("Save") hands back the text as typed, the empty one too (the caller takes the
 * name away).
 */
@Composable
fun BtTextDialog(
    title: String,
    value: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "",
    note: String? = null,
    max: Int = 80,
    confirm: String = "Save",
    error: String? = null,
) {
    val c = Bt.colors
    var text by rememberSaveable { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { BtButton(confirm, onClick = { onSave(text) }, small = true) },
        dismissButton = { BtGhostButton("Cancel", onClick = onDismiss) },
        title = { Text(title, style = Bt.text.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.replace('\n', ' ').take(max) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = Bt.text.body,
                    placeholder = if (hint.isEmpty()) null else ({ Text(hint, style = Bt.text.body, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.ink),
                )
                note?.let { Text(it, style = Bt.text.fine) }
                error?.let { Text(it, style = Bt.text.fine, color = c.danger) }
            }
        },
        shape = btCardShape(),
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink,
    )
}

/**
 * A delete as words, at the end of what it deletes (an item's sheet, Edit trip, a journal note): the bin and [text]
 * in the danger colour, as wide as the page, inside a plain thin line of that colour and without the theme's button
 * shadow (owner, 2026-11-14: "delete: make it full width, with a simple line, no shadow").
 */
@Composable
fun BtDeleteButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Bt.colors
    val shape = btControlShape()
    val ink = if (enabled) c.danger else c.muted.copy(alpha = 0.5f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, ink, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(BtIcons.Delete, null, Modifier.size(18.dp), tint = ink)
        Text(text, style = Bt.text.button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

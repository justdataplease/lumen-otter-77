package com.justdataplease.boringtravel.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.tags.ItemTags
import com.justdataplease.boringtravel.tags.TagTree
import com.justdataplease.boringtravel.tags.load
import com.justdataplease.boringtravel.tags.peek
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt

/**
 * A plan item's tags (owner, 2026-11-12: tags "on plan items only"; "clip inherits tags from items plan"): the chosen
 * ones as chips (a tap takes one off), a field that suggests tags from the owner's tree as you type or takes a tag of
 * your own, and Browse: category, subcategory, its tags. Browse stays folded until asked for: the sheet is long.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemTagsField(tags: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Read off the main thread the first time (an asset and its JSON); empty until then.
    val tree by produceState(TagTree.peek() ?: TagTree(emptyList()), context) { value = withContext(Dispatchers.IO) { TagTree.load(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    val full = tags.size >= ItemTags.MAX
    fun add(tag: String) {
        onChange(ItemTags.add(tags, tag))
        query = ""
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Tags")
        if (tags.isNotEmpty()) {
            ChipFlow { tags.forEach { t -> BtChip("#$t", true, { onChange(tags - t) }, icon = BtIcons.Close, role = Role.Button) } }
        }
        if (full) Text("Up to ${ItemTags.MAX} tags. Tap one to take it off.", style = Bt.text.fine) else AddTag(tree, tags, query, { query = it }, ::add) { onChange(tags - it) }
    }
}

/** The field with its suggestions, or Browse (category, subcategory, tags) while nothing is typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTag(tree: TagTree, tags: List<String>, query: String, onQuery: (String) -> Unit, add: (String) -> Unit, remove: (String) -> Unit) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    val typed = TagTree.normalize(query)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            query, { onQuery(it.take(TagTree.MAX_LEN + 1)) }, Modifier.fillMaxWidth(),
            placeholder = { Text("Add a tag, like sushi or hiking") },
            singleLine = true, textStyle = Bt.text.body,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { typed?.let(add) }),
        )
        if (typed != null) {
            val hits = remember(query, tags) { tree.suggest(query, tags, limit = SUGGESTED) }
            Column(Modifier.fillMaxWidth()) {
                hits.forEach { h -> SuggestionRow("#${h.tag}", h.path) { add(h.tag) } }
                // A tag of their own, when the tree has no such one.
                if (typed !in tags && hits.none { it.tag == typed }) SuggestionRow("#$typed", "Your own tag") { add(typed) }
            }
        } else {
            Text(
                if (browsing) "Hide the list" else "Browse tags",
                Modifier.clickable(role = Role.Button) { browsing = !browsing }.padding(vertical = 8.dp),
                style = Bt.text.label, color = Bt.colors.accentInk,
            )
            if (browsing) {
                ChipFlow {
                    tree.categories.forEach { c ->
                        BtChip(c.name, c.name == category, { category = if (category == c.name) null else c.name; sub = null })
                    }
                }
                tree.categories.firstOrNull { it.name == category }?.let { c ->
                    ChipFlow { c.subs.forEach { s -> BtChip(s.name, s.name == sub, { sub = if (sub == s.name) null else s.name }) } }
                    c.subs.firstOrNull { it.name == sub }?.let { s ->
                        ChipFlow {
                            s.tags.forEach { t ->
                                val chosen = t in tags
                                BtChip("#$t", chosen, { if (chosen) remove(t) else add(t) }, role = Role.Checkbox)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The item's tags as one quiet line under its name in the plan ("#pizza #streetfood"); nothing without tags. */
@Composable
internal fun ItemTagsLine(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Text(ItemTags.line(tags), modifier, style = Bt.text.fine, color = Bt.colors.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Chips that wrap onto the next line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/** A suggested tag and where it sits ("Food & Drink › Italian"); a tap adds it. */
@Composable
private fun SuggestionRow(tag: String, aside: String, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClickLabel = "Add $tag", onClick = onAdd).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tag, style = Bt.text.item, maxLines = 1)
        Text(aside, Modifier.weight(1f), style = Bt.text.fine, color = Bt.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Suggestions shown under the field. */
private const val SUGGESTED = 6

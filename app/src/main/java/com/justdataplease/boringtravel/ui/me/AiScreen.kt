package com.justdataplease.boringtravel.ui.me

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.ai.own.OwnAiModels
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.ai.own.OwnAiState
import com.justdataplease.boringtravel.ai.own.OwnKeyError
import com.justdataplease.boringtravel.ai.own.OwnKeyException
import com.justdataplease.boringtravel.ai.own.OwnKeyStore
import com.justdataplease.boringtravel.ai.own.modelsOrNull
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the owner asked the screen to answer: a chat subscription is not an API key. */
internal const val SUBSCRIPTION_NOTE =
    "A ChatGPT Plus or Claude Pro subscription doesn't include this. The key comes from the provider's developer site and is billed there by use."

/** Me › AI's top line while no own key answers. */
internal const val NO_KEY_IN_USE = "The built-in planner answers until a key works."

internal fun privacyNote(p: OwnAiProvider) =
    "With your own key, the trip, its plan and your text go straight from this phone to ${p.destination}. The key stays on this phone."

/**
 * Me › AI: the planner's answers from the built-in planner or from the traveller's own key. Each provider keeps its own
 * key and model; the chips show one at a time. Test checks the pasted (or saved) key and asks the chosen model, the
 * one picked or typed by hand, for a structured answer; only when that works are the key and model saved and the
 * planner switched to that provider. The line at the top says which one answers now.
 */
@Composable
fun AiScreen(graph: AppGraph, actions: AppActions) {
    val vm = viewModel { AiViewModel(graph) }
    val state by graph.ownAi.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    BtScreen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BtTopBar("AI", onBack = actions::back)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Planner answers come from", Modifier.padding(start = 4.dp))
                BtSegmented(listOf("Built-in", "Your own key"), if (state.useOwnKey) 1 else 0, onSelect = { vm.setUseOwnKey(it == 1) })
                if (!state.useOwnKey) {
                    Text("Google's Gemini through BoringTravel.", Modifier.padding(horizontal = 4.dp), style = Bt.text.sub)
                } else {
                    OwnKeySection(vm, state, context, focus)
                }
            }
        }
    }
}

/** "Your own key": the provider chips, the shown provider's key and model, Test and Remove key; emitted into the screen's column. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnKeySection(vm: AiViewModel, state: OwnAiState, context: Context, focus: FocusManager) {
    val p = vm.viewed
    val hasKey = p in state.withKey
    // Which provider answers now: looking at a provider without a key doesn't change it, a key that works does.
    Text(state.usingLine() ?: NO_KEY_IN_USE, Modifier.padding(horizontal = 4.dp), style = Bt.text.item)
    BtCard(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OwnAiProvider.entries.forEach { option ->
                BtChip(option.label, selected = option == p, onClick = { vm.selectProvider(option) })
            }
        }
        if (hasKey) Text("Key  ${vm.keyMask ?: "saved"}", Modifier.padding(top = 10.dp), style = Bt.text.item)
        InputField(
            vm.keyInput, vm::updateKey,
            placeholder = if (hasKey) "Paste a new key" else "Paste your ${p.label} key",
            hidden = true,
            onDone = { focus.clearFocus(); vm.test() },
            modifier = Modifier.padding(top = 10.dp),
        )
        KeyPageLink(p, context)
        Text(SUBSCRIPTION_NOTE, style = Bt.text.fine)
    }
    Eyebrow("Model", Modifier.padding(start = 4.dp, top = 4.dp))
    BtCard(Modifier.fillMaxWidth()) {
        val saved = state.model(p)
        val offered = vm.models
        if (!hasKey && offered == null && !vm.listUnavailable) {
            InputField("", {}, placeholder = "Add a key to see the models", enabled = false, onDone = {})
        } else {
            if (hasKey) Text("Model  ${saved ?: OwnAiModels.fallback(p)}", Modifier.padding(bottom = 4.dp), style = Bt.text.item)
            Text("Each name follows the provider's newest model, so nothing needs updating.", Modifier.padding(bottom = 10.dp), style = Bt.text.fine)
            InputField(
                vm.modelQuery, vm::updateModelQuery,
                placeholder = "Search or type a model id",
                onDone = { focus.clearFocus(); vm.test() },
            )
            // The chip marked is the one Test will try: picked (not tested yet) or saved.
            val chosen = vm.pendingModel ?: saved
            val custom = vm.customModel()
            if (offered != null) {
                val shown = OwnAiModels.shown(offered, chosen, vm.modelQuery)
                if (shown.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { m -> BtChip(m, selected = m == chosen, onClick = { focus.clearFocus(); vm.pickModel(m) }) }
                    }
                }
                when {
                    custom != null -> ModelHint("Not in the list. Test tries “$custom”.")
                    vm.pendingModel != null -> ModelHint("Tap Test to use ${vm.pendingModel}.")
                    vm.modelQuery.isBlank() && offered.size > shown.size -> ModelHint("Type to search all ${offered.size} names.")
                }
            } else if (vm.listUnavailable) {
                ModelHint(if (custom != null) "Test tries “$custom”." else "This key can't list the models. Type a model id.")
            } else if (vm.busy == AiViewModel.Busy.Listing) {
                Text("Checking the key…", Modifier.padding(top = 8.dp), style = Bt.text.sub)
            } else {
                ModelHint("Test the key to see the models.")
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BtButton(
            if (vm.busy == AiViewModel.Busy.Testing) "Testing…" else "Test",
            onClick = { focus.clearFocus(); vm.test() },
            modifier = Modifier.weight(1f),
            enabled = vm.busy == null && (hasKey || vm.keyInput.isNotBlank()),
        )
        if (hasKey) BtGhostButton("Remove key", onClick = vm::removeKey, modifier = Modifier.weight(1f), enabled = vm.busy == null, danger = true)
    }
    vm.note?.let { Text(it.text, Modifier.padding(horizontal = 4.dp), style = Bt.text.sub, color = if (it.error) Bt.colors.danger else Bt.colors.ink) }
    Text(privacyNote(p), Modifier.padding(horizontal = 4.dp), style = Bt.text.fine)
}

@Composable
private fun ModelHint(text: String) = Text(text, Modifier.padding(top = 8.dp), style = Bt.text.fine)

/** "Get a key at …" as a link: the link icon, underlined, in the theme's link ink, opening the provider's key page. */
@Composable
private fun KeyPageLink(p: OwnAiProvider, context: Context) {
    val ink = Bt.colors.accentInk
    Row(
        Modifier.heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Open ${p.keySite}") {
                if (!Sharing.openUrl(context, p.keyPage)) Toast.makeText(context, p.keyPage, Toast.LENGTH_LONG).show()
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(BtIcons.Link, null, Modifier.size(16.dp), tint = ink)
        Text(
            "Get a key at ${p.keySite}",
            style = Bt.text.sub.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
            color = ink,
        )
    }
}

/** A one-line box: the key (hidden like a password; paste works with a long press) or a model id. */
@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.radius)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = Bt.text.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (enabled) c.surface else c.chip).border(1.dp, c.line, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = Bt.text.body, color = c.muted)
                inner()
            }
        },
    )
}

/**
 * Me › AI's state: the provider shown, the typed key and the model picked (neither saved before Test works), the
 * provider's models, the model search and the last result.
 */
class AiViewModel(graph: AppGraph) : ViewModel() {
    private val ownAi = graph.ownAi

    enum class Busy { Listing, Testing }
    data class Note(val text: String, val error: Boolean = false)

    /**
     * The provider whose key and model the screen shows. The planner keeps its own ([OwnAiState.provider]) and moves to
     * this one only when it has a key: one already saved (it worked when saved), or once Test works.
     */
    var viewed by mutableStateOf(ownAi.state.value.provider)
        private set
    var keyInput by mutableStateOf("")
        private set
    var modelQuery by mutableStateOf("")
        private set
    var models by mutableStateOf<List<String>?>(null)
        private set

    /** This key may not list models (an OpenAI restricted key without "Models: Read"): a model id is typed instead. */
    var listUnavailable by mutableStateOf(false)
        private set

    /** A model chip picked and not tested yet: Test saves it once it answers. */
    var pendingModel by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var note by mutableStateOf<Note?>(null)
        private set
    var keyMask by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    init {
        showSaved()
    }

    fun updateKey(text: String) {
        keyInput = text
        note = null
    }

    fun updateModelQuery(text: String) {
        modelQuery = text
        note = null
    }

    fun setUseOwnKey(on: Boolean) {
        ownAi.setUseOwnKey(on)
        if (on) showSaved()
    }

    /**
     * Shows [p]'s own key and model. The planner moves to [p] only when [p] has a saved key; looking at a provider
     * without one leaves the planner as it was.
     */
    fun selectProvider(p: OwnAiProvider) {
        if (p == viewed) return
        job?.cancel()
        busy = null
        viewed = p
        if (p in ownAi.state.value.withKey) ownAi.setProvider(p)
        keyInput = ""
        modelQuery = ""
        note = null
        models = null
        listUnavailable = false
        pendingModel = null
        showSaved()
    }

    /** Marks [model] for Test; it is saved only once it answers. */
    fun pickModel(model: String) {
        pendingModel = model.takeIf { it != ownAi.state.value.model(viewed) }
        modelQuery = ""
        note = null
    }

    /** A model id typed by hand: text with no spaces that no listed model matches (or before the list came). */
    fun customModel(): String? {
        val q = modelQuery.trim()
        if (q.isEmpty() || q.any { it.isWhitespace() }) return null
        val listed = models ?: return q
        listed.firstOrNull { it.equals(q, ignoreCase = true) }?.let { return null }
        return q.takeIf { listed.none { it.contains(q, ignoreCase = true) } }
    }

    /** Removes only this provider's key (and its model). */
    fun removeKey() {
        job?.cancel()
        busy = null
        ownAi.removeKey(viewed)
        models = null
        listUnavailable = false
        pendingModel = null
        keyMask = null
        modelQuery = ""
        note = Note("Key removed.")
    }

    /**
     * Checks the pasted key (or the saved one) with the provider, then asks the model (typed by hand, picked, saved or
     * the first offered) for a structured answer. Only when it answers are the key and the model saved and the planner
     * switched to this provider; a failure leaves everything saved as it was. At most [TEST_TIMEOUT_MS].
     */
    fun test() {
        val p = viewed
        val typed = OwnKeyStore.clean(keyInput)
        val key = typed.ifEmpty { null } ?: ownAi.key(p) ?: run { note = Note("Paste a key first.", error = true); return }
        val listedMatch = modelQuery.trim().takeIf { q -> q.isNotEmpty() && models?.any { it.equals(q, ignoreCase = true) } == true }
        val custom = customModel() ?: listedMatch?.let { q -> models?.first { it.equals(q, ignoreCase = true) } }
        job?.cancel()
        note = null
        busy = Busy.Testing
        job = viewModelScope.launch {
            try {
                withTimeout(TEST_TIMEOUT_MS) {
                    val listed = ownAi.api(p).modelsOrNull(key)
                    val offered = listed?.let { OwnAiModels.offered(p, it) }
                    models = offered
                    listUnavailable = listed == null
                    val saved = ownAi.state.value.model(p)
                    val model = custom ?: pendingModel ?: (if (offered != null) OwnAiModels.choose(offered, saved) else saved ?: OwnAiModels.fallback(p))
                    if (model == null) {
                        note = Note("This key lists no model that can plan a trip. Type a model id.", error = true)
                        return@withTimeout
                    }
                    ownAi.api(p).ping(key, model)
                    // It works: now save, and the planner uses it.
                    if (typed.isNotEmpty()) {
                        ownAi.saveKey(p, typed)
                        keyInput = ""
                        keyMask = OwnKeyStore.mask(typed)
                    }
                    ownAi.setModel(p, model)
                    ownAi.setProvider(p)
                    pendingModel = null
                    if (custom != null) modelQuery = ""
                    note = Note("Works. $model answered.")
                }
            } catch (_: TimeoutCancellationException) {
                note = Note(OwnKeyError.Timeout.message(p, inAiSettings = true), error = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OwnKeyException) {
                logFailure(p, e)
                // A wrong key also can't list models: it isn't a restricted one, so no "type a model id".
                if (e.error == OwnKeyError.WrongKey) listUnavailable = false
                note = Note(e.error.message(p, inAiSettings = true), error = true)
            } catch (e: Exception) {
                logFailure(p, e)
                note = Note(OwnKeyError.Unknown.message(p, inAiSettings = true), error = true)
            } finally {
                busy = null
            }
        }
    }

    /** Debug builds only, class names and the HTTP status only: messages can hold a URL or the key. */
    private fun logFailure(p: OwnAiProvider, e: Exception) {
        if (!BuildConfig.DEBUG) return
        val classes = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }.take(6).joinToString(" → ") { it.javaClass.simpleName }
        Log.w("BoringTravelAi", "Test with ${p.id} failed ($classes); error=${(e as? OwnKeyException)?.error}; status=${(e as? OwnKeyException)?.status}")
    }

    /** The saved key, masked, and the provider's models (a free list call). Nothing is saved here. */
    private fun showSaved() {
        val p = viewed
        val key = ownAi.key(p)
        keyMask = key?.let(OwnKeyStore::mask)
        if (key == null || !ownAi.state.value.useOwnKey) return
        job?.cancel()
        busy = Busy.Listing
        job = viewModelScope.launch {
            try {
                val listed = withTimeout(LIST_TIMEOUT_MS) { ownAi.api(p).modelsOrNull(key) }
                models = listed?.let { OwnAiModels.offered(p, it) }
                listUnavailable = listed == null
            } catch (_: TimeoutCancellationException) {
                note = Note(OwnKeyError.Timeout.message(p, inAiSettings = true), error = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OwnKeyException) {
                logFailure(p, e)
                note = Note(e.error.message(p, inAiSettings = true), error = true)
            } catch (e: Exception) {
                logFailure(p, e)
                note = Note(OwnKeyError.Unknown.message(p, inAiSettings = true), error = true)
            } finally {
                busy = null
            }
        }
    }

    private companion object {
        /** Test: the key check and one tiny answer. */
        const val TEST_TIMEOUT_MS = 45_000L
        const val LIST_TIMEOUT_MS = 20_000L
    }
}

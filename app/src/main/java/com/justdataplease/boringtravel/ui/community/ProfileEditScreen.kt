package com.justdataplease.boringtravel.ui.community

import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import com.justdataplease.boringtravel.ui.me.TravellerSheet
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.community.ProfileAvatar
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.CommunityLimits
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.Profile
import com.justdataplease.boringtravel.community.UsernameTakenException
import com.justdataplease.boringtravel.community.Usernames
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the username field says while typing. */
sealed interface NameCheck {
    data object Idle : NameCheck
    data object Checking : NameCheck
    data object Free : NameCheck
    /** It is already the traveller's own. */
    data object Mine : NameCheck
    data object Taken : NameCheck
    data class Invalid(val problem: Usernames.Problem) : NameCheck
    /** No answer (offline): saving checks again. */
    data object Unknown : NameCheck
}

/** Make or change the signed-in traveller's profile: username (checked while typing), name, bio and picture. */
class ProfileEditViewModel(private val graph: AppGraph) : ViewModel() {
    private val community = graph.community

    val username = MutableStateFlow("")
    val name = MutableStateFlow("")
    val bio = MutableStateFlow("")
    /**
     * The picture: a traveller (their own from Me › Your traveller to start with; its look, view and framing chosen
     * here, [ProfileAvatar]), or the first letter of the username.
     */
    val useTraveller = MutableStateFlow(false)
    val traveller = MutableStateFlow<String?>(null)

    /** The picture changed: [f] over the one shown. */
    fun changeAvatar(f: (ProfileAvatar) -> ProfileAvatar) {
        val now = ProfileAvatar.decodeOrNull(traveller.value) ?: return
        traveller.value = f(now).encode()
    }

    private val _check = MutableStateFlow<NameCheck>(NameCheck.Idle)
    val check: StateFlow<NameCheck> = _check.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _loadFailed = MutableStateFlow(false)
    /** Their profile couldn't be looked up (offline): the form waits, so a save never overwrites one it didn't see. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The profile as it is now (null: none yet). */
    private var current: Profile? = null
    private var checkJob: Job? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    /** Fills the form from their profile, or with suggestions when they have none; Try again after a failed look-up. */
    fun load() {
        if (_loaded.value || loadJob?.isActive == true) return
        _loadFailed.value = false
        loadJob = viewModelScope.launch {
            community.user.filterNotNull().first()
            traveller.value = ProfileAvatar(graph.settings.traveller.first()).encode()
            if (!community.profileKnown.value) community.refreshMe()
            if (!community.profileKnown.value) {
                _loadFailed.value = true
                return@launch
            }
            val profile = community.profile.value
            current = profile
            if (profile != null) {
                username.value = profile.username
                name.value = profile.name
                bio.value = profile.bio
                useTraveller.value = profile.avatar != null
                // Their saved picture as it was (look, view, framing).
                ProfileAvatar.decodeOrNull(profile.avatar)?.let { traveller.value = it.encode() }
                _check.value = NameCheck.Mine
            } else {
                val googleName = graph.settings.accountName.first()
                val email = graph.settings.accountEmail.first() ?: community.user.value?.email
                name.value = googleName.orEmpty().take(CommunityLimits.NAME)
                useTraveller.value = graph.settings.travellerChosen.first()
                // A Greek or other non-Latin name leaves nothing to suggest: the email's name then.
                (Usernames.suggest(googleName) ?: Usernames.suggest(email))?.let { onUsername(it) }
            }
            _loaded.value = true
        }
    }

    val isNew: Boolean get() = current == null

    fun onUsername(raw: String) {
        val typed = raw.replace(" ", "").removePrefix("@").lowercase().take(Usernames.MAX)
        username.value = typed
        _error.value = null
        checkJob?.cancel()
        val problem = Usernames.problem(typed)
        _check.value = when {
            typed.isEmpty() -> NameCheck.Idle
            problem != null -> NameCheck.Invalid(problem)
            typed == current?.username -> NameCheck.Mine
            else -> NameCheck.Checking
        }
        if (_check.value != NameCheck.Checking) return
        checkJob = viewModelScope.launch {
            delay(350)
            val me = community.user.value?.uid
            _check.value = try {
                when (community.repo.usernameOwner(typed)) {
                    null -> NameCheck.Free
                    me -> NameCheck.Mine
                    else -> NameCheck.Taken
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                NameCheck.Unknown
            }
        }
    }

    val canSave: Boolean
        get() = !_saving.value && (_check.value == NameCheck.Free || _check.value == NameCheck.Mine || _check.value == NameCheck.Unknown)

    /** Saves; true when done. */
    suspend fun save(): Boolean {
        if (!canSave) return false
        _saving.value = true
        _error.value = null
        return try {
            val avatar = if (useTraveller.value) traveller.value else null
            val saved = withContext(NonCancellable) { community.repo.saveProfile(Usernames.normalize(username.value), name.value, bio.value, avatar) }
            community.setProfile(saved)
            current = saved
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsernameTakenException) {
            _check.value = NameCheck.Taken
            false
        } catch (e: Exception) {
            _error.value = communityMessage(e, "Couldn't save. Try again.")
            false
        } finally {
            _saving.value = false
        }
    }

    suspend fun unblock(uid: String) {
        try {
            community.unblock(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = communityMessage(e, "Couldn't unblock. Try again.")
        }
    }
}

/** The picture's choices: head and shoulders or the whole figure, the turn (a slider round the figure), who and their look. */
@Composable
private fun AvatarChoices(pic: ProfileAvatar, change: ((ProfileAvatar) -> ProfileAvatar) -> Unit) {
    val c = Bt.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BtSegmented(listOf("Head & shoulders", "Whole figure"), if (pic.full) 1 else 0, onSelect = { i -> change { it.copy(full = i == 1) } })
        Column {
            Text("Turn", style = Bt.text.label, color = c.muted)
            Slider(
                value = pic.heading, onValueChange = { v -> change { it.copy(heading = (kotlin.math.round(v / 5f) * 5f)) } },
                valueRange = 0f..355f,
                colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.line),
            )
        }
        BtGhostButton("Who and their look", onClick = { editing = true }, modifier = Modifier.fillMaxWidth())
    }
    if (editing) {
        TravellerSheet(pic.traveller, rides = null, onChange = { t -> change { it.copy(traveller = t) } }, onDismiss = { editing = false }, title = "Your picture")
    }
}

/**
 * Me › Community profile (and the Community tab's avatar before there is a profile): the username with its rules and a
 * live check, name, bio, the picture (their traveller or an initial), then the people they blocked and Delete profile.
 */
@Composable
fun ProfileEditScreen(graph: AppGraph, actions: AppActions) {
    val community = graph.community
    val user by community.user.collectAsStateWithLifecycle()
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Community profile", onBack = actions::back)
            if (!community.available || user == null) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your profile is how others see you in the community: a username, a name, a few words and a picture.", style = Bt.text.body)
                    SignInCard()
                }
                return@Column
            }
            ProfileForm(graph, actions)
        }
    }
}

@Composable
private fun ProfileForm(graph: AppGraph, actions: AppActions) {
    val vm: ProfileEditViewModel = viewModel(key = "profile-edit") { ProfileEditViewModel(graph) }
    val username by vm.username.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val bio by vm.bio.collectAsStateWithLifecycle()
    val useTraveller by vm.useTraveller.collectAsStateWithLifecycle()
    val traveller by vm.traveller.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val profile by graph.community.profile.collectAsStateWithLifecycle()
    val blocked by graph.community.blocked.collectAsStateWithLifecycle()
    val rulesAccepted by graph.settings.communityRulesAccepted.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    val c = Bt.colors

    if (loadFailed) {
        NoteCard(
            "Couldn't check your profile", "Check the connection and try again.", Modifier.padding(20.dp),
        ) { BtButton("Try again", onClick = vm::load, small = true) }
        return
    }
    val save: () -> Unit = {
        scope.launch {
            val wasNew = vm.isNew
            if (vm.save()) {
                Toast.makeText(context, if (wasNew) "Profile made" else "Saved", Toast.LENGTH_SHORT).show()
                actions.profileSaved()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(username.ifEmpty { null }, if (useTraveller) traveller else null, size = 96.dp)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BtChip("Initial", !useTraveller, { vm.useTraveller.value = false })
                BtChip("My traveller", useTraveller, { vm.useTraveller.value = true })
            }
            // The traveller's look, which way they face and how close (owner, 2026-11-12: "select avatar and the viewpoint").
            val pic = remember(traveller) { ProfileAvatar.decodeOrNull(traveller) }
            if (useTraveller && pic != null) AvatarChoices(pic, vm::changeAvatar)
        }
        CommunityField(
            username, vm::onUsername, "Username", prefix = "@", placeholder = "yourname",
            hint = "3–20 letters, numbers, _ and .",
            error = when (val k = check) {
                is NameCheck.Invalid -> k.problem.message
                NameCheck.Taken -> "@$username is taken. Try another."
                else -> null
            },
            ok = when (check) {
                NameCheck.Free -> "@$username is free"
                NameCheck.Mine -> if (profile != null) "This is your username" else null
                NameCheck.Checking -> "Checking…"
                NameCheck.Unknown -> "Couldn't check now. It's checked when you save."
                else -> null
            },
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            enabled = loaded,
        )
        CommunityField(
            name, { vm.name.value = it }, "Name", placeholder = "Optional", max = CommunityLimits.NAME,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), enabled = loaded,
        )
        CommunityField(
            bio, { vm.bio.value = it }, "Bio", placeholder = "A few words about how you travel", max = CommunityLimits.BIO,
            singleLine = false, minHeight = 96.dp, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), enabled = loaded,
        )
        Column {
            BtButton(
                when {
                    saving -> "Saving"
                    vm.isNew -> "Make my profile"
                    else -> "Save"
                },
                enabled = loaded && vm.canSave,
                modifier = Modifier.fillMaxWidth(),
                // A public profile is the first thing they share: the community's rules come first.
                onClick = { if (rulesAccepted) save() else showRules = true },
            )
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = c.danger) }
            Text("Others see your username, name, bio and picture.", Modifier.padding(top = 8.dp), style = Bt.text.fine)
        }
        if (profile != null) {
            Column {
                Eyebrow("Blocked", Modifier.padding(bottom = 4.dp))
                if (blocked.isEmpty()) {
                    Text("Nobody. People you block show here.", style = Bt.text.sub)
                } else {
                    BtCard(padding = PaddingValues(horizontal = 16.dp)) {
                        blocked.forEachIndexed { i, b ->
                            if (i > 0) BtDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(b.username, null, size = 32.dp)
                                Text(
                                    PostText.handle(b.username), Modifier.weight(1f).padding(start = 12.dp), style = Bt.text.item,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                BtGhostButton("Unblock", onClick = { scope.launch { vm.unblock(b.uid) } })
                            }
                        }
                    }
                }
            }
            Text(
                "Delete profile",
                Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { askDelete = true }.padding(vertical = 12.dp),
                style = Bt.text.item, color = c.danger,
            )
        }
    }
    if (askDelete) {
        DeleteProfileDialog(
            onDeleted = {
                askDelete = false
                actions.back()
            },
            onDismiss = { askDelete = false },
        )
    }
    if (showRules) {
        RulesSheet(
            onAgree = {
                showRules = false
                scope.launch {
                    graph.settings.setCommunityRulesAccepted(true)
                    save()
                }
            },
            onDismiss = { showRules = false },
        )
    }
}

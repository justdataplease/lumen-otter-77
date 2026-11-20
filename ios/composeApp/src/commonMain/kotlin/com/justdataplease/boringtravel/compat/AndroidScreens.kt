package com.justdataplease.boringtravel.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.ios.MemorySettingsFile
import com.justdataplease.boringtravel.ios.SettingsFile
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/*
 * The names of Android's screens the community's screens reach for (ui/community, read in at build time since round
 * 24), with the same meaning here: Toast (a line in passing: the app's snackbar, as every iPhone screen says things),
 * the share intent (the phone's share sheet), LocalContext (the screen's context that does both), and Jetpack's
 * ViewModel with its viewModelScope and viewModel(key) { … }: one view-model per key and stack entry, kept while the
 * entry is on the app's stack (a tab's pages too, while another tab shows) and cleared when the entry goes, as Android
 * keeps a view-model per back-stack entry.
 */

/** android.widget.Toast: [show] says the text through the screen's context ([Context.say]). */
class Toast private constructor(private val context: Context, private val text: String) {
    fun show() = context.say(text)

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @Suppress("UNUSED_PARAMETER")
        fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(context, text.toString())
    }
}

/** android.content.Intent as far as the community shares with it: ACTION_SEND with a text, through a chooser. */
class Intent(val action: String?) {
    var type: String? = null
        private set
    private val extras = LinkedHashMap<String, String>()

    /** The intent a chooser was made for ([createChooser]). */
    internal var chosen: Intent? = null
        private set

    fun setType(type: String?): Intent = apply { this.type = type }

    fun putExtra(name: String, value: String?): Intent = apply { if (value == null) extras.remove(name) else extras[name] = value }

    fun getStringExtra(name: String): String? = extras[name]

    companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"

        /** The phone's chooser for [target] (iPhone's share sheet has no title: [title] is left out). */
        @Suppress("UNUSED_PARAMETER")
        fun createChooser(target: Intent, title: CharSequence?): Intent = Intent(ACTION_CHOOSER).also { it.chosen = target }
    }
}

/**
 * A screen's context: [say] is the app's snackbar (Android's toasts), [share] the phone's share sheet for a text with a
 * subject (false when it can't). The settings are the app's.
 */
class ScreenContext(
    settings: SettingsFile,
    private val sayText: (String) -> Unit,
    private val share: (subject: String, text: String) -> Boolean,
) : Context(settings) {
    override fun say(text: String) = sayText(text)

    override fun startActivity(intent: Intent) {
        val send = intent.chosen ?: intent
        if (send.action != Intent.ACTION_SEND) return
        val text = send.getStringExtra(Intent.EXTRA_TEXT) ?: return
        share(send.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(), text)
    }
}

/**
 * Android's findActivity (ui/onboarding/GoogleUi.kt), as Travel together's sheets hand a context to Google's steps (round
 * 26): here the screen's context itself, the app's one window.
 */
fun Context.findActivity(): Context? = this

/** Compose's LocalContext on Android: here the screen's context ([ScreenContext]), which the app provides at its root. */
val LocalContext = staticCompositionLocalOf<Context> { Context(MemorySettingsFile()) }

/**
 * androidx.lifecycle.ViewModel: its coroutines ([viewModelScope]) run on the composition's dispatcher of the screen
 * that made it (the main thread on iPhone) and end when it is cleared ([onCleared]: its entry left the stack).
 */
abstract class ViewModel {
    internal val scope: CoroutineScope = CoroutineScope((ViewModelStore.making ?: Dispatchers.Default) + SupervisorJob())

    protected open fun onCleared() {}

    internal fun clear() {
        scope.cancel()
        onCleared()
    }
}

/** androidx.lifecycle.viewModelScope. */
val ViewModel.viewModelScope: CoroutineScope get() = scope

/**
 * The view-models of one stack entry by key (Android's ViewModelStore of a back-stack entry): made on first use with
 * [context] (the screens' dispatcher, without its job), cleared together.
 */
class ViewModelStore(context: CoroutineContext = Dispatchers.Default) {
    private val context: CoroutineContext = context.minusKey(Job)
    private val models = LinkedHashMap<String, ViewModel>()

    @Suppress("UNCHECKED_CAST")
    fun <VM : ViewModel> get(key: String, make: () -> VM): VM {
        models[key]?.let { return it as VM }
        val before = making
        making = context
        try {
            return make().also { models[key] = it }
        } finally {
            making = before
        }
    }

    /** Every view-model's coroutines end ([ViewModel.onCleared]). */
    fun clear() {
        val all = models.values.toList()
        models.clear()
        all.forEach { it.clear() }
    }

    internal companion object {
        /** The context a view-model being made now takes (made on the screens' thread, one at a time). */
        var making: CoroutineContext? = null
    }
}

/** The stack entry's view-models; null where a screen is drawn on its own (a picture, a test): it keeps its own then. */
val LocalViewModelStore = staticCompositionLocalOf<ViewModelStore?> { null }

/**
 * androidx.lifecycle.viewmodel.compose.viewModel(key) { … }: the entry's view-model under [key], made by [initializer]
 * the first time. A screen drawn outside the app's stack keeps its own, cleared when it leaves the composition.
 */
@Composable
fun <VM : ViewModel> viewModel(key: String? = null, initializer: () -> VM): VM {
    val given = LocalViewModelStore.current
    val scope = rememberCoroutineScope()
    val own = remember(given) { if (given == null) ViewModelStore(scope.coroutineContext) else null }
    DisposableEffect(own) { onDispose { own?.clear() } }
    val store = given ?: own!!
    return remember(store, key) { store.get(key ?: "default", initializer) }
}

/** java.time.YearMonth.parse("2027-03", "yyyy-MM") as the first day of that month; throws for anything else. */
fun yearMonthDay(key: String): LocalDate {
    val m = Regex("^([0-9]{4})-([0-9]{2})$").matchEntire(key) ?: throw DateTimeParseException(key)
    val month = m.groupValues[2].toInt()
    if (month !in 1..12) throw DateTimeParseException(key)
    return localDate(m.groupValues[1].toInt(), month, 1)
}

package com.justdataplease.boringtravel.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/*
 * The few names of android.os and Compose's Android platform the trip video's editor (ui/clip, read in at build time
 * since round 19) reaches for, with the same meaning here: a clock that only goes forward, the phone's locales, and
 * Java's uppercase in a locale.
 */

/** android.os.SystemClock: milliseconds on a clock that only goes forward (not the time of day). */
object SystemClock {
    private val start = TimeSource.Monotonic.markNow()

    /** Milliseconds since a fixed moment (Android: since the phone started). */
    fun elapsedRealtime(): Long = BASE_MS + start.elapsedNow().inWholeMilliseconds

    /** The same clock (Android's leaves out deep sleep; here the app's own clock, which a sleeping phone stops too). */
    fun uptimeMillis(): Long = elapsedRealtime()

    /** Never 0, as a phone that has just started never reads 0 either (0 means "not yet" in some callers). */
    private const val BASE_MS = 1_000L
}

/** android.os.LocaleList as the editor reads it: the phone's languages, the first one first. */
class LocaleList(private val locales: List<Locale>) {
    operator fun get(index: Int): Locale? = locales.getOrNull(index)

    val size: Int get() = locales.size
}

/** android.content.res.Configuration, as far as its locales. */
class Configuration(val locales: LocaleList)

/**
 * Compose's LocalConfiguration on Android: here the phone's locale (its language and region, [Locale.getDefault]); the
 * app provides it at its root ([com.justdataplease.boringtravel.ios.BoringTravelApp]).
 */
val LocalConfiguration = staticCompositionLocalOf { Configuration(LocaleList(listOf(Locale.getDefault()))) }

/**
 * Android's String.uppercase(Locale) (libcore's CaseMapper): Greek in capitals without its accents ("Τρί" → "ΤΡΙ",
 * Android's el-Upper; the diaeresis stays: "Μαΐ" → "ΜΑΪ"), the dotted capital I in Turkish and Azeri, Kotlin's own
 * capitals for every other language.
 */
fun String.uppercase(locale: Locale): String = when (locale.language) {
    "el" -> buildString {
        for (ch in this@uppercase) {
            if (ch == '́') continue
            append(GREEK_UNACCENTED[ch] ?: ch.uppercase())
        }
    }
    "tr", "az" -> replace('i', 'İ').uppercase()
    else -> uppercase()
}

/** Greek letters with an accent, as Android's Greek capitals write them. */
private val GREEK_UNACCENTED: Map<Char, String> = mapOf(
    'ά' to "Α", 'έ' to "Ε", 'ή' to "Η", 'ί' to "Ι", 'ό' to "Ο", 'ύ' to "Υ", 'ώ' to "Ω",
    'Ά' to "Α", 'Έ' to "Ε", 'Ή' to "Η", 'Ί' to "Ι", 'Ό' to "Ο", 'Ύ' to "Υ", 'Ώ' to "Ω",
    'ΐ' to "Ϊ", 'ΰ' to "Ϋ", 'ς' to "Σ",
)

/** Android's titlecase of the first letter in [locale] (the plan's and the Calendar's month titles). */
fun String.capitalizedIn(locale: Locale): String = if (isEmpty()) this else when (locale.language) {
    "tr", "az" -> (if (this[0] == 'i') "İ" else this[0].titlecase()) + substring(1)
    else -> this[0].titlecase() + substring(1)
}

/**
 * Lifecycle's collectAsStateWithLifecycle, as the editor's screens read the studio's states: Compose's collectAsState
 * here (the same value at once, then each change while the screen shows it). Lifecycle's own version collects on
 * Dispatchers.Main, which the desktop's tests and pictures don't have; the studio's flows hold their state, so nothing
 * is lost while the app is away.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = collectAsState()

/** The same for a flow that starts with [initialValue] (Me › Your traveller & rides reads the settings so, round 20). */
@Composable
fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): State<T> = collectAsState(initialValue)

/** android.Manifest's permission names the editor asks for (the microphone, for a voice line). */
object Manifest {
    object permission {
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}

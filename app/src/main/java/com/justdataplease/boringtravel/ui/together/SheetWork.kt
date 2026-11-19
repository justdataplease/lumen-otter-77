package com.justdataplease.boringtravel.ui.together

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.justdataplease.boringtravel.google.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One action at a time for a Travel together sheet: [busy] while it runs, [notice] when it fails, in plain words
 * ("No internet connection…", never Java's). Runs on [scope] (the app's), so closing the sheet doesn't stop it.
 */
@Stable
internal class SheetWork(private val scope: CoroutineScope, private val fallback: String) {
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    /** Runs [work], unless an action is running (a second tap does nothing). */
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch(Dispatchers.Main) { go(work) }
    }

    /** Runs [work] once the action running now is done: a step that follows Google's screens, which must not be lost. */
    fun runAfter(work: suspend () -> Unit) {
        scope.launch(Dispatchers.Main) {
            while (busy) delay(100)
            busy = true
            notice = null
            go(work)
        }
    }

    private suspend fun go(work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = e.readable(fallback)
        } finally {
            busy = false
        }
    }
}

@Composable
internal fun rememberSheetWork(scope: CoroutineScope, fallback: String): SheetWork = remember(scope) { SheetWork(scope, fallback) }

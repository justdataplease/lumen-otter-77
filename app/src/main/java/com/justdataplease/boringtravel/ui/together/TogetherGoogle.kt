package com.justdataplease.boringtravel.ui.together

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.SignInOutcome
import com.justdataplease.boringtravel.ui.onboarding.findActivity
import com.justdataplease.boringtravel.ui.onboarding.rememberDriveConnect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Everything Travel together needs from Google, each step only when it is missing: sign-in (the trip's mailbox needs
 * it), Drive (your own copy of the trip), then Firebase with that account, without a screen of its own. Returns the
 * function that starts it; [onDone] gets whether Drive is connected now and what went wrong (null: nothing to say,
 * also when a Google screen was closed). Without sign-in Drive still works: only the mailbox is missing then, and the
 * sheets offer the link instead.
 *
 * One tap like the one Google button (rememberGoogleConnect in ui/onboarding/GoogleUi.kt), but each step is skipped
 * when it is already done, and Firebase follows.
 */
@Composable
internal fun rememberTogetherGoogle(graph: AppGraph, onDone: (drive: Boolean, problem: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()

    fun finish(drive: Boolean, problem: String?) {
        scope.launch {
            if (drive) graph.together.signInForMail(context.findActivity() ?: context, quiet = true)
            latest(drive, problem)
        }
    }

    val connect = rememberDriveConnect(graph) { step ->
        when (step) {
            DriveStep.Connected -> finish(true, null)
            DriveStep.Cancelled -> finish(false, null)
            is DriveStep.Failed -> finish(false, step.message)
            else -> Unit
        }
    }
    return remember(graph, connect, scope) {
        {
            scope.launch {
                val driveOn = graph.settings.driveConnected.first()
                if (graph.google.signInConfigured && graph.settings.accountEmail.first() == null) {
                    val problem = when (val o = graph.google.signIn(context.findActivity() ?: context)) {
                        is SignInOutcome.Failed -> o.message
                        else -> null
                    }
                    // No sign-in: with Drive the trip still works through links; without it there is nothing to go on.
                    if (graph.settings.accountEmail.first() == null && !driveOn) {
                        latest(false, problem)
                        return@launch
                    }
                }
                if (driveOn) finish(true, null) else connect()
            }
        }
    }
}

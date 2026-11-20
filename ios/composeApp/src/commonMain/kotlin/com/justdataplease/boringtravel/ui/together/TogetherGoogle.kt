package com.justdataplease.boringtravel.ui.together

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.justdataplease.boringtravel.compat.LocalContext
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.GoogleConnect
import com.justdataplease.boringtravel.ios.TogetherGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Everything Travel together needs from Google, each step only when it is missing (the Android app's
 * ui/together/TogetherGoogle.kt written again, round 26): sign-in (the trip's mailbox needs it), Drive (your own copy of
 * the trip), then Firebase with that account, without a screen of its own. Returns the function that starts it;
 * [onDone] gets whether Drive is connected now and what went wrong (null: nothing to say, also when Google's page was
 * closed). Without sign-in Drive still works: only the mailbox is missing then, and the sheets offer the link instead.
 *
 * On iPhone one Google page asks for the account and Drive together (round 23's sign-in): signed in here, Drive is
 * connected in the same step, so no second page follows; an account already signed in (Google or Apple) gets Drive's
 * page alone, as Android's Drive consent.
 */
@Composable
internal fun rememberTogetherGoogle(graph: TogetherGraph, onDone: (drive: Boolean, problem: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()

    fun finish(drive: Boolean, problem: String?) {
        scope.launch {
            if (drive) graph.together.signInForMail(context, quiet = true)
            latest(drive, problem)
        }
    }

    return remember(graph, scope) {
        {
            scope.launch {
                val google = graph.google
                val driveOn = graph.settings.driveConnected.first()
                if (google != null && google.signInConfigured && graph.settings.accountEmail.first() == null) {
                    when (val r = google.signIn()) {
                        // The account and Drive in one page.
                        GoogleConnect.Connected -> {
                            finish(true, null)
                            return@launch
                        }
                        // Signed in, Drive unticked on Google's page (or Google failed it): nothing more to ask now.
                        is GoogleConnect.NoDrive -> if (!driveOn) {
                            latest(false, r.message)
                            return@launch
                        }
                        is GoogleConnect.NotSignedIn -> if (!driveOn) {
                            // No sign-in: with Drive the trip still works through links; without it there is nothing to go on.
                            latest(false, r.message)
                            return@launch
                        }
                    }
                }
                if (driveOn) finish(true, null) else when (val step = connect(google)) {
                    DriveStep.Connected -> finish(true, null)
                    DriveStep.Cancelled -> finish(false, null)
                    is DriveStep.Failed -> finish(false, step.message)
                }
            }
        }
    }
}

/** Drive's page alone (an account signed in already): Android's rememberDriveConnect. */
private suspend fun connect(google: GoogleAccount?): DriveStep = google?.connectDrive() ?: DriveStep.Failed(GoogleAccount.NOT_SET_UP)

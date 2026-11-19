package com.justdataplease.boringtravel.google

import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.NetFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/*
 * The Android app's google/Http.kt as this app needs it: a failed Drive call, the retries while Google is busy or the
 * network drops, and every failure in people's words. OkHttp's call is this app's Net (ios/Net.kt), whose NetException
 * says why no answer came.
 */

/** A failed Drive call. [busy]: Google is overloaded or rate-limiting, so the same call may work in a moment. */
class DriveException(
    val code: Int,
    message: String,
    val busy: Boolean = code == 429 || code >= 500,
    val reason: String = "",
) : IOException(message)

/**
 * Runs [call] up to [tries] times while Google is busy or the network drops, waiting about 1, 2, 4… seconds in
 * between ([wait]: the coroutines' delay, none in the tests). Refusals (a 4xx other than 429) and "not connected" fail
 * at once.
 */
suspend fun <T> retrying(tries: Int = 3, firstWait: Long = 1_000, wait: suspend (Long) -> Unit = { delay(it) }, call: suspend () -> T): T {
    var pause = firstWait
    repeat(tries - 1) {
        try {
            return call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (e is DriveException && !e.busy) throw e
        }
        wait(pause + Random.nextLong(pause / 2 + 1))
        pause *= 2
    }
    return call()
}

/** A message for people: network failures read as such (the Android app's words), the rest keep their own or [fallback]. */
fun Throwable.readable(fallback: String): String = when (this) {
    is DriveNotConnectedException -> "Google Drive isn't connected. Connect it in Me › Backup & sync."
    is NetException -> when (failure) {
        NetFailure.Offline -> "No internet connection. Check it and try again."
        NetFailure.Timeout -> "Google didn't answer in time. Try again."
        NetFailure.Insecure -> "Couldn't connect securely to Google. Check the network and try again."
    }
    else -> message?.takeIf { it.isNotBlank() } ?: fallback
}

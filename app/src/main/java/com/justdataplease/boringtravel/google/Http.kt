package com.justdataplease.boringtravel.google

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** A failed Drive call. [busy]: Google is overloaded or rate-limiting, so the same call may work in a moment. */
class DriveException(
    val code: Int,
    message: String,
    val busy: Boolean = code == 429 || code >= 500,
    val reason: String = "",
) : IOException(message)

/** Runs the call on OkHttp's dispatcher and cancels it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/**
 * Runs [call] up to [tries] times while Google is busy or the network drops, waiting about 1, 2, 4… seconds in
 * between. Refusals (a 4xx other than 429) and "not connected" fail at once.
 */
suspend fun <T> retrying(tries: Int = 3, firstWait: Long = 1_000, call: suspend () -> T): T {
    var wait = firstWait
    repeat(tries - 1) {
        try {
            return call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (e is DriveException && !e.busy) throw e
            if (e is NotSharedException) throw e
        }
        delay(wait + Random.nextLong(wait / 2 + 1))
        wait *= 2
    }
    return call()
}

/** A message for people, not Java's: network failures read as such, the rest keep their own words or [fallback]. */
fun Throwable.readable(fallback: String): String = when (this) {
    is DriveNotConnectedException -> "Google Drive isn't connected. Connect it in Me › Backup & sync."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
        "No internet connection. Check it and try again."
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "Google didn't answer in time. Try again."
    is javax.net.ssl.SSLException -> "Couldn't connect securely to Google. Check the network and try again."
    else -> message?.takeIf { it.isNotBlank() } ?: fallback
}

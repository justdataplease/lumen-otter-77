package com.justdataplease.boringtravel.ai.own

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Why a request with the traveller's own key failed, in the plain words of [message]. */
enum class OwnKeyError {
    WrongKey, NoCredit, TooMany, ModelUnavailable, Offline, Insecure, Busy, Blocked, Region, Incomplete, Timeout, BadRequest, Unknown;

    /** [inAiSettings]: shown in Me › AI itself, so no "go to Me › AI". */
    fun message(provider: OwnAiProvider, inAiSettings: Boolean = false): String = when (this) {
        WrongKey -> "Wrong key. Copy it again from ${provider.keySite}."
        NoCredit -> "No credit left on this key. Add some on ${provider.keySite}."
        TooMany -> "Too many requests. Wait a minute and try again."
        ModelUnavailable -> if (inAiSettings) "This model isn't available with your key. Pick another one." else "This model isn't available with your key. Pick another one in Me › AI."
        Offline -> "No internet. Check the connection and try again."
        // A certificate the phone doesn't trust: a captive portal, or a network or antivirus that opens HTTPS.
        Insecure -> "Couldn't connect securely to ${provider.label}. Try another network."
        Busy -> "${provider.label} is busy right now. Try again in a few minutes."
        Blocked -> "${provider.label} wouldn't plan from this text. Try other words."
        Region -> "${provider.label} isn't available in your country."
        Incomplete -> "The answer came back incomplete. Try again."
        Timeout -> "That took too long. Try again."
        // In Me › AI the request is Test's tiny one: a refusal there means the model can't give a structured answer.
        BadRequest -> if (inAiSettings) "This model can't plan trips here. Pick another one." else "${provider.label} couldn't use this request. Try a smaller part of the itinerary, or another model."
        Unknown -> "Something went wrong. Try again."
    }

    companion object {
        /** A refused HTTP call: the status first, then the provider's own error code or words. */
        fun ofHttp(status: Int, code: String = "", text: String = ""): OwnKeyError {
            val c = code.lowercase()
            val t = text.lowercase()
            fun says(vararg words: String) = words.any { it in c || it in t }
            return when {
                says("api_key_invalid", "invalid_api_key", "authentication_error", "api key not valid", "incorrect api key", "invalid x-api-key", "api key expired") -> WrongKey
                // Not plain "billing": Gemini's per-minute 429 also says "check your plan and billing details".
                says("insufficient_quota", "billing_error", "credit balance", "credits are depleted", "prepayment", "payment required") -> NoCredit
                says("user location is not supported", "unsupported_country", "unsupported location") -> Region
                status == 401 -> WrongKey
                status == 402 -> NoCredit
                status == 404 || says("model_not_found", "not_found_error", "does not exist", "is not found", "not supported for generatecontent") -> ModelUnavailable
                status == 403 -> if (says("model")) ModelUnavailable else WrongKey
                status == 429 -> TooMany
                status == 408 -> Timeout
                status >= 500 -> Busy
                status == 400 || status == 413 || status == 422 -> BadRequest
                else -> Unknown
            }
        }

        /** A failure before any answer: no network, a timeout, or something else. */
        fun ofNetwork(error: Throwable): OwnKeyError {
            val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            return when {
                chain.any { it is SocketTimeoutException } -> Timeout
                chain.any { it is SSLHandshakeException || it is java.security.cert.CertificateException } -> Insecure
                chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SSLException } -> Offline
                chain.any { it is InterruptedIOException } -> Timeout
                chain.any { it is IOException } -> Offline
                else -> Unknown
            }
        }
    }
}

/** A failed own-key request; [status] is the HTTP status when there was one. Never carries the key or the trip text. */
class OwnKeyException(val error: OwnKeyError, val status: Int? = null, cause: Throwable? = null) :
    Exception("Own-key request failed: ${error.name}${status?.let { " ($it)" }.orEmpty()}", cause)

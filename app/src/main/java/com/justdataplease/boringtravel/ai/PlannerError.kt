package com.justdataplease.boringtravel.ai

/** Why a plan couldn't be made, in plain words for the traveller. */
enum class PlannerError(val message: String) {
    NotSetUp("The AI planner isn't set up in this build. Use your own key in Me › AI."),
    Offline("No connection. Check the internet and try again."),
    Quota("The planner is busy right now. Try again in a few minutes."),
    // Only "not set up" and the testers' debug message point to own keys: ordinary users aren't sent to API keys.
    AppCheck("Google couldn't confirm this copy of the app. Install BoringTravel from Google Play and try again."),
    AppCheckDebug("App Check refused this debug build. Add its debug token in the Firebase console (Logcat shows it). Or use your own key in Me › AI."),
    Blocked("Gemini wouldn't plan from this text. Try other words."),
    Unreadable("The answer came back incomplete. Try again."),
    InvalidRequest("The planner couldn't use this request. Try a smaller part of the itinerary, or update the app."),
    Timeout("That took too long. Try again."),
    Region("The AI planner isn't available in your country yet."),
    Unknown("Something went wrong. Try again.");

    companion object {
        /** Out of quota: the model's requests per minute or per day are used up (429, RESOURCE_EXHAUSTED). */
        fun isQuota(error: Throwable): Boolean {
            val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return chain.any { it::class.java.simpleName == "QuotaExceededException" } || "quota" in text ||
                "resource_exhausted" in text || Regex("""\b429\b""").containsMatchIn(text)
        }

        /** A failure worth asking again a moment later: the model busy, a server deadline, a 5xx. */
        fun isTransient(error: Throwable): Boolean {
            val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return listOf("high demand", "overloaded", "unavailable", "try again later", "deadline expired", "deadline exceeded", "deadline_exceeded", "internal error")
                .any { it in text } || Regex("""\b(500|502|503|504)\b""").containsMatchIn(text)
        }

        /**
         * Sorts any failure by the Firebase AI Logic exception's class name and the messages in its cause chain, so
         * the mapping is testable without the SDK and survives SDK class moves.
         */
        fun of(error: Throwable, debugBuild: Boolean): PlannerError {
            val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).toList()
            val names = chain.map { it::class.java.simpleName }
            val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            fun named(vararg n: String) = names.any { it in n }
            fun code(c: Int) = Regex("\\b$c\\b").containsMatchIn(text)
            return when {
                error is PlannerNotSetUpException -> NotSetUp
                error is DraftParseException || named("SerializationException") -> Unreadable
                named("UnknownHostException", "ConnectException", "NoRouteToHostException", "SocketException") ||
                    "unable to resolve host" in text || "failed to connect" in text -> Offline
                "app check" in text || "appcheck" in text || "app_check" in text -> if (debugBuild) AppCheckDebug else AppCheck
                named("QuotaExceededException") || "resource_exhausted" in text || "quota" in text || code(429) -> Quota
                "high demand" in text || "overloaded" in text || "unavailable" in text || "try again later" in text -> Quota
                "deadline expired" in text || "deadline_exceeded" in text || "deadline exceeded" in text -> Timeout
                named("RequestTimeoutException", "SocketTimeoutException", "TimeoutCancellationException", "HttpRequestTimeoutException") -> Timeout
                named("InvalidArgumentException") || "invalid argument" in text || code(400) -> InvalidRequest
                code(500) || code(502) || code(503) || code(504) -> Quota
                named("PromptBlockedException", "ContentBlockedException", "ResponseStoppedException") -> Blocked
                named("UnsupportedUserLocationException") -> Region
                named("InvalidAPIKeyException", "ServiceDisabledException", "APINotConfiguredException") -> NotSetUp
                "permission_denied" in text || code(403) -> if (debugBuild) AppCheckDebug else AppCheck
                named("IOException") -> Offline
                else -> Unknown
            }
        }
    }
}

/** Thrown when the build has no Firebase ids. */
class PlannerNotSetUpException : Exception("The AI planner isn't set up in this build.")

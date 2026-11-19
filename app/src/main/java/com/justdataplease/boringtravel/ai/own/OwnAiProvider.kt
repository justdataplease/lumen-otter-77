package com.justdataplease.boringtravel.ai.own

/**
 * The providers whose developer keys the planner can use (Me › AI). [id] is what the phone stores; [keyPage] is where a
 * key is made (checked against each provider's docs on 2026-11-11); [destination] is where the trip text goes.
 */
enum class OwnAiProvider(val id: String, val label: String, val destination: String, val keyPage: String, val keySite: String) {
    Gemini("gemini", "Gemini", "Google", "https://aistudio.google.com/apikey", "Google AI Studio"),
    Claude("claude", "Claude", "Anthropic", "https://platform.claude.com/settings/keys", "the Claude Console"),
    ChatGpt("chatgpt", "ChatGPT", "OpenAI", "https://platform.openai.com/api-keys", "the OpenAI platform"),
    OpenRouter("openrouter", "OpenRouter", "OpenRouter and the model's provider", "https://openrouter.ai/keys", "OpenRouter");

    companion object {
        fun of(id: String?): OwnAiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which models of a provider the planner offers: only names that follow the newest model by themselves, so nothing
 * needs an app update when a model ships (owner, 2026-11-11: "have the latest versions only … like gemini flash
 * latest"). Google and OpenRouter have such aliases ("gemini-flash-latest", "~deepseek/deepseek-flash-latest"); for
 * Claude and ChatGPT the app keeps family names ("claude-sonnet-latest", "gpt-sol-latest") and [resolve] turns them
 * into the newest model of the family the key lists. A model typed by hand still works as typed.
 */
object OwnAiModels {
    /** The built-in planner's model, also the default with a Gemini key. */
    const val GEMINI_DEFAULT = com.justdataplease.boringtravel.ai.GeminiPlanner.DEFAULT_MODEL

    /** Google's own aliases, the default first (live list checked 2026-11-11). */
    val GEMINI = listOf(GEMINI_DEFAULT, "gemini-pro-latest", "gemini-flash-lite-latest")

    /** Claude's families, Sonnet first: it is the traveller's own bill, Opus one tap away. */
    val CLAUDE = listOf("claude-sonnet-latest", "claude-opus-latest", "claude-haiku-latest")

    /** OpenAI's families, the cost-efficient Sol first (developers.openai.com, 2026-11-11). */
    val OPENAI = listOf("gpt-sol-latest", "gpt-astra-latest", "gpt-terra-latest", "gpt-luna-latest")

    /** OpenRouter's "latest" aliases, DeepSeek Flash first (owner, 2026-11-11: "use default deepseek flash"). */
    const val OPENROUTER_DEFAULT = "~deepseek/deepseek-flash-latest"
    val OPENROUTER = listOf(
        OPENROUTER_DEFAULT, "~google/gemini-flash-latest", "~anthropic/claude-sonnet-latest", "~openai/gpt-sol-latest",
        "~deepseek/deepseek-pro-latest", "~google/gemini-pro-latest", "~anthropic/claude-haiku-latest", "~openai/gpt-luna-latest",
    )

    /** How many models show before a search. */
    const val SHORT_LIST = 12

    /** Parts of model names that mean pictures, sound, embeddings and other jobs that can't plan a trip. */
    private val NOT_TEXT = listOf(
        "embedding", "image", "imagen", "veo", "tts", "audio", "speech", "transcribe", "whisper", "realtime", "live-",
        "robotics", "computer-use", "aqa", "lyria", "moderation", "search", "cyber", "rosalind", "dall-e", "codex",
        "instruct", "translate", "babbage", "davinci", "antigravity", "deep-research", "pareto",
    )

    private fun isText(id: String) = NOT_TEXT.none { it in id.lowercase() }

    /** A name that follows the newest model: "gemini-flash-latest", "~x-ai/grok-latest". */
    fun isLatest(id: String): Boolean = id.endsWith("-latest") || id.startsWith("~")

    /** The models to offer from what the key listed: only "latest" names, the preferred ones first. */
    fun offered(provider: OwnAiProvider, listed: List<String>): List<String> {
        val ids = listed.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when (provider) {
            OwnAiProvider.Gemini -> {
                val own = ids.filter { it.startsWith("gemini-") && it.endsWith("-latest") && isText(it) }
                // The aliases are Google's: offer them even when a key lists none yet.
                GEMINI + own.filter { it !in GEMINI }
            }
            OwnAiProvider.Claude -> {
                val families = CLAUDE.filter { name -> ids.any { it.startsWith(prefix(name)) } }
                families + ids.filter { it.startsWith("claude-") && it.endsWith("-latest") && it !in families }
            }
            OwnAiProvider.ChatGpt -> {
                val families = ids.mapNotNull { openAiFamily(it) }.distinct()
                val ordered = OPENAI.filter { it in families } + families.filter { it !in OPENAI }.sorted()
                ordered + ids.filter { it.endsWith("-latest") && it.startsWith("gpt-") && isText(it) && it !in ordered }
            }
            OwnAiProvider.OpenRouter -> {
                val latest = ids.filter { isLatest(it) && isText(it) }
                OPENROUTER.filter { it in latest } + latest.filter { it !in OPENROUTER }
            }
        }
    }

    /**
     * The model to call for [model]: a family name ("claude-sonnet-latest", "gpt-sol-latest") becomes the newest model
     * of that family in [listed]; anything else (a provider's own alias, a typed id) is called as it is.
     */
    fun resolve(provider: OwnAiProvider, model: String, listed: List<String>): String {
        if (model in listed) return model
        return when (provider) {
            OwnAiProvider.Claude ->
                if (model in CLAUDE) newest(listed.filter { it.startsWith(prefix(model)) && !it.endsWith("-latest") }) ?: model else model
            OwnAiProvider.ChatGpt -> {
                val family = Regex("^gpt-([a-z]+)-latest$").matchEntire(model)?.groupValues?.get(1) ?: return model
                newest(listed.filter { Regex("^gpt-\\d+(?:\\.\\d+)?-$family$").matches(it) }) ?: model
            }
            else -> model
        }
    }

    /** The newest by version ("claude-sonnet-5-5" over "claude-sonnet-5" over "claude-sonnet-4-6"); a dated snapshot counts as its version. */
    internal fun newest(ids: List<String>): String? =
        ids.maxWithOrNull(compareBy<String> { versionKey(it) }.thenBy { -it.length })

    private fun versionKey(id: String): String =
        (Regex("\\d+").findAll(id).map { it.value }.filter { it.length < 6 }.toList() + List(4) { "0" })
            .take(4).joinToString(".") { it.padStart(4, '0') }

    /** "claude-sonnet-latest" → "claude-sonnet-". */
    private fun prefix(family: String) = family.removeSuffix("latest")

    /** "gpt-6.1-sol" → "gpt-sol-latest"; "gpt-6.1-sol-pro", snapshots and other names → null. */
    private fun openAiFamily(id: String): String? = Regex("^gpt-\\d+(?:\\.\\d+)?-([a-z]+)$").matchEntire(id)?.groupValues?.get(1)
        ?.takeIf { isText(id) }?.let { "gpt-$it-latest" }

    /**
     * A model saved before the "latest" names (1.6.0: "gemini-3.5-flash-lite", "claude-sonnet-5-5") as its family's
     * latest name; anything else as it was.
     */
    fun upgrade(provider: OwnAiProvider, saved: String): String {
        val s = saved.trim()
        if (isLatest(s)) return s
        return when (provider) {
            OwnAiProvider.Gemini -> when {
                !s.startsWith("gemini-") -> s
                "flash-lite" in s -> "gemini-flash-lite-latest"
                "flash" in s -> GEMINI_DEFAULT
                "pro" in s -> "gemini-pro-latest"
                else -> s
            }
            OwnAiProvider.Claude -> CLAUDE.firstOrNull { s.startsWith(prefix(it)) } ?: s
            OwnAiProvider.ChatGpt -> openAiFamily(s) ?: s
            OwnAiProvider.OpenRouter -> when {
                s.startsWith("deepseek/") && "flash" in s -> OPENROUTER_DEFAULT
                s.startsWith("google/gemini") && "flash" in s && "lite" !in s -> "~google/gemini-flash-latest"
                s.startsWith("google/gemini") && "pro" in s -> "~google/gemini-pro-latest"
                s.startsWith("anthropic/claude-sonnet") -> "~anthropic/claude-sonnet-latest"
                s.startsWith("anthropic/claude-opus") -> "~anthropic/claude-opus-latest"
                s.startsWith("anthropic/claude-haiku") -> "~anthropic/claude-haiku-latest"
                s.startsWith("openai/") ->
                    Regex("^openai/gpt-\\d+(?:\\.\\d+)?-([a-z]+)$").matchEntire(s)?.groupValues?.get(1)?.let { "~openai/gpt-$it-latest" } ?: s
                else -> s
            }
        }
    }

    /**
     * The chips to show: with no [query] the first [SHORT_LIST] offered, else every offered model containing it;
     * the [current] choice is always among them (also one typed by hand).
     */
    fun shown(offered: List<String>, current: String?, query: String): List<String> {
        val q = query.trim()
        val base = if (q.isEmpty()) offered.take(SHORT_LIST) else offered.filter { it.contains(q, ignoreCase = true) }.take(SHORT_LIST * 3)
        return if (current != null && current !in base && (q.isEmpty() || current.contains(q, ignoreCase = true))) listOf(current) + base else base
    }

    /**
     * The model to use: the traveller's choice when there is one (even typed by hand or no longer listed; Test says
     * whether it answers), else the first offered.
     */
    fun choose(offered: List<String>, current: String?): String? = current ?: offered.firstOrNull()

    /** The default before any list came back (a key saved but never tested). */
    fun fallback(provider: OwnAiProvider): String = when (provider) {
        OwnAiProvider.Gemini -> GEMINI_DEFAULT
        OwnAiProvider.Claude -> CLAUDE.first()
        OwnAiProvider.ChatGpt -> OPENAI.first()
        OwnAiProvider.OpenRouter -> OPENROUTER_DEFAULT
    }
}

package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.compat.OkHttpClient
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The Android app's ai/own/OwnAi.kt, written again here: Android keeps Me › AI in a SharedPreferences file whose keys
 * are sealed with an Android Keystore key, and sends with OkHttp and Anthropic's Java SDK. This phone keeps the same
 * values under the same names ([OwnKeyStore], read in) in its Keychain ([KeyValues]: iosMain/…/IosKeychain.kt, this
 * device only, never in a backup), and sends over [Net].
 */

/**
 * Me › AI on this phone: the saved choices and keys ([OwnKeyStore]), each provider's calls ([api]) and the planner
 * engine to use now ([engine]). The keys never leave the phone except to their own provider, and are never logged.
 * [values] is where they are kept (the Keychain on iPhone, memory in tests); [cipher] seals the keys in it (none on
 * iPhone: the Keychain keeps its items encrypted itself).
 */
class OwnAi(values: KeyValues, cipher: KeyCipher = PlainCipher, net: Net) {
    private val store = OwnKeyStore(values, cipher)
    private val _state = MutableStateFlow(store.state())
    val state: StateFlow<OwnAiState> = _state.asStateFlow()

    /** Its own client, as Android builds it: long reads for big drafts (20 s to connect, 180 s of silence at most). */
    private val http = OkHttpClient(net, connectTimeoutMs = 20_000, readTimeoutMs = 180_000, callTimeoutMs = NetRequest.NO_LIMIT)
    private val gemini by lazy { GeminiKeyApi(http) }
    private val openAi by lazy { OpenAiKeyApi(http) }
    private val claude by lazy { ClaudeKeyApi(net) }
    private val openRouter by lazy { OpenRouterKeyApi(http) }

    fun api(p: OwnAiProvider): OwnKeyApi = when (p) {
        OwnAiProvider.Gemini -> gemini
        OwnAiProvider.Claude -> claude
        OwnAiProvider.ChatGpt -> openAi
        OwnAiProvider.OpenRouter -> openRouter
    }

    fun setUseOwnKey(on: Boolean) = update { store.setUseOwnKey(on) }
    fun setProvider(p: OwnAiProvider) = update { store.setProvider(p) }
    fun setModel(p: OwnAiProvider, model: String?) = update { store.setModel(p, model) }
    fun saveKey(p: OwnAiProvider, key: String) = update { store.saveKey(p, key) }
    fun removeKey(p: OwnAiProvider) = update {
        store.removeKey(p)
        if (p == OwnAiProvider.Claude) claude.forget()
    }

    /** Every key, every choice and the encryption key (Android's Me › Account › Delete account). */
    fun clearAll() = update {
        store.clearAll()
        claude.forget()
    }

    fun key(p: OwnAiProvider): String? = store.key(p).also { if (it == null && p in _state.value.withKey) refresh() }

    /** The planner with the traveller's key when "Your own key" is on and the key is saved; null = the built-in one. */
    fun engine(): OwnKeyPlanner? = _state.value.engine(::key, ::api)

    private fun update(change: () -> Unit) {
        change()
        refresh()
    }

    private fun refresh() { _state.value = store.state() }

    companion object {
        /** The name Android's preferences file of Me › AI has; this phone's Keychain items are filed under it. */
        const val PREFS = "ai_own_key"
    }
}

/** No sealing of its own: the Keychain keeps its items encrypted, on this device only. */
object PlainCipher : KeyCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.copyOf()
    override fun decrypt(sealed: ByteArray): ByteArray = sealed.copyOf()
    override fun destroy() {}
}

/** Me › AI's values in memory: tests and previews. */
class MemoryKeyValues : KeyValues {
    private val values = LinkedHashMap<String, String>()

    /** The names kept, for tests. */
    val names: Set<String> get() = values.keys.toSet()

    override fun get(name: String): String? = values[name]
    override fun put(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }

    override fun clear() = values.clear()
}

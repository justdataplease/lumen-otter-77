package com.justdataplease.boringtravel.ai.own

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Me › AI on this phone: the saved choices and keys ([OwnKeyStore]), each provider's calls ([api]) and the planner
 * engine to use now ([engine]). The keys never leave the phone except to their own provider, and are never logged.
 */
class OwnAi(context: Context) {
    private val values = PrefsValues(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    // A replaced Keystore key can't open what the old one sealed: those keys go too.
    private val store = OwnKeyStore(values, KeystoreCipher(onReset = { OwnKeyStore.forgetKeys(values) }))
    private val _state = MutableStateFlow(store.state())
    val state: StateFlow<OwnAiState> = _state.asStateFlow()

    /** Its own client: long reads for big drafts, nothing shared with the Google calls. */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val gemini by lazy { GeminiKeyApi(http) }
    private val openAi by lazy { OpenAiKeyApi(http) }
    private val claude by lazy { ClaudeKeyApi() }
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

    /** Me › Account › Delete account: every key, every choice and the encryption key. */
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
        /** The preferences file of Me › AI: excluded from Android backup and never read by the app's own backups. */
        const val PREFS = "ai_own_key"
    }
}

private class PrefsValues(private val prefs: SharedPreferences) : KeyValues {
    override fun get(name: String): String? = prefs.getString(name, null)
    override fun put(name: String, value: String?) = prefs.edit { if (value == null) remove(name) else putString(name, value) }
    override fun clear() = prefs.edit { clear() }
}

/**
 * AES-256-GCM with a key made in, and never leaving, the Android Keystore. The 12-byte IV leads each sealed value.
 * Sealing replaces a broken or invalidated Keystore entry ([sealingKey]) and [onReset] forgets what it sealed; opening
 * never deletes anything, so a Keystore error right after boot loses no key.
 */
private class KeystoreCipher(private val onReset: () -> Unit) : KeyCipher {
    private fun keyStore() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun saved(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    private fun reset() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        onReset()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, sealingKey(::saved, ::reset, ::generate))
        } catch (_: KeyPermanentlyInvalidatedException) {
            reset()
            cipher.init(Cipher.ENCRYPT_MODE, generate())
        }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES)
        val key = saved() ?: throw UnreadableKeyException("No encryption key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw UnreadableKeyException("Encryption key invalidated")
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun destroy() { keyStore().deleteEntry(ALIAS) }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "boringtravel_ai_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

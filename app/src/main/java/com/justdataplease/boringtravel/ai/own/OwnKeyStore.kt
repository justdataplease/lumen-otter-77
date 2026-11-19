package com.justdataplease.boringtravel.ai.own

import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Encrypts the keys at rest. On the phone: [KeystoreCipher] (AES-GCM, a key that never leaves the Android Keystore).
 * [decrypt] throws [UnreadableKeyException] or [AEADBadTagException] when a sealed value can never be read again; any
 * other failure may pass (a Keystore that isn't ready yet right after boot).
 */
interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray

    /** Forgets the encryption key itself (Delete account). */
    fun destroy()
}

/** A sealed value that can never be read: its encryption key is gone or was replaced. */
class UnreadableKeyException(message: String) : GeneralSecurityException(message)

/**
 * The Keystore key to seal with: the saved one, read twice (a read can fail once while the Keystore starts), else a new
 * one. An entry that still can't be read is broken and would make every save fail, so [reset] deletes it (and forgets
 * what it sealed) before [generate] makes the new key. Plain logic, tested without a Keystore.
 */
internal fun <K : Any> sealingKey(read: () -> K?, reset: () -> Unit, generate: () -> K): K {
    fun twice(): K? = try {
        read()
    } catch (_: GeneralSecurityException) {
        read()
    } catch (_: ProviderException) {
        read()
    }
    val key = try {
        twice()
    } catch (_: GeneralSecurityException) {
        reset()
        null
    } catch (_: ProviderException) {
        reset()
        null
    }
    return key ?: generate()
}

/** A few strings kept on the phone. On the phone: its own SharedPreferences file ([OwnAi.PREFS]). */
interface KeyValues {
    fun get(name: String): String?
    fun put(name: String, value: String?)
    fun clear()
}

/** Me › AI as saved: where answers come from, the provider, which providers have a key, and each one's model. */
data class OwnAiState(
    val useOwnKey: Boolean = false,
    val provider: OwnAiProvider = OwnAiProvider.Gemini,
    val withKey: Set<OwnAiProvider> = emptySet(),
    val models: Map<OwnAiProvider, String> = emptyMap(),
) {
    /** The provider that answers the planner now, or null for the built-in planner (also when the key is missing). */
    val active: OwnAiProvider? get() = provider.takeIf { useOwnKey && it in withKey }

    fun model(p: OwnAiProvider): String? = models[p]

    /** The model [p] answers with: the chosen one, else the provider's default. */
    fun modelOrDefault(p: OwnAiProvider): String = model(p)?.let { OwnAiModels.upgrade(p, it) } ?: OwnAiModels.fallback(p)

    /** "Using your Claude key · claude-sonnet-5-5" while an own key answers, else null. */
    fun usingLine(): String? = active?.let { "Using your ${it.label} key · ${modelOrDefault(it)}" }

    /**
     * The planner to use now: [active]'s key with its model (or the provider's default before a test), or null for the
     * built-in planner, also when the key can't be read any more.
     */
    fun engine(key: (OwnAiProvider) -> String?, api: (OwnAiProvider) -> OwnKeyApi): OwnKeyPlanner? {
        val p = active ?: return null
        val k = key(p) ?: return null
        return OwnKeyPlanner(api(p), k, modelOrDefault(p))
    }
}

/**
 * The traveller's own AI keys and choices. Keys are stored only encrypted ([KeyCipher]) in their own preferences file,
 * which Android's backup and device transfer leave out (data_extraction_rules.xml, allowBackup=false) and the app's
 * Drive backup never reads (it copies [com.justdataplease.boringtravel.backup.BackupPreferences] from the DataStore
 * settings only). A key that can never be decrypted again is dropped; one that fails for another reason is kept for the
 * next try. Plain logic, tested with a fake cipher.
 */
class OwnKeyStore(private val values: KeyValues, private val cipher: KeyCipher) {
    fun state(): OwnAiState = OwnAiState(
        useOwnKey = values.get(MODE) == OWN,
        provider = OwnAiProvider.of(values.get(PROVIDER)) ?: OwnAiProvider.Gemini,
        withKey = OwnAiProvider.entries.filter { values.get(keyName(it)) != null }.toSet(),
        // A model saved before the "latest" names reads as its family's latest name (1.6.0 saved fixed ids).
        models = OwnAiProvider.entries.mapNotNull { p -> values.get(modelName(p))?.let { p to OwnAiModels.upgrade(p, it) } }.toMap(),
    )

    fun setUseOwnKey(on: Boolean) = values.put(MODE, if (on) OWN else BUILT_IN)
    fun setProvider(p: OwnAiProvider) = values.put(PROVIDER, p.id)
    fun setModel(p: OwnAiProvider, model: String?) = values.put(modelName(p), model?.trim()?.ifBlank { null })

    /** Saves [key] for [p], cleaned of what a paste may bring ([clean]). */
    fun saveKey(p: OwnAiProvider, key: String) {
        val clean = clean(key)
        require(clean.isNotEmpty()) { "Empty key" }
        values.put(keyName(p), PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(clean.toByteArray(Charsets.UTF_8))))
    }

    /**
     * [p]'s key, or null when there is none or it can't be read now. Only a key that can never be read again (bad
     * data, or its encryption key is gone) is dropped; after any other failure it stays for the next try.
     */
    fun key(p: OwnAiProvider): String? {
        val stored = values.get(keyName(p)) ?: return null
        val plain = try {
            require(stored.startsWith(PREFIX)) { "Unknown format" }
            String(cipher.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
        } catch (e: Exception) {
            if (isUnreadable(e)) removeKey(p)
            return null
        }
        return plain.ifEmpty { removeKey(p); null }
    }

    fun removeKey(p: OwnAiProvider) = removeKey(values, p)

    /** Everything, and the encryption key itself (Me › Account › Delete account). */
    fun clearAll() {
        values.clear()
        runCatching { cipher.destroy() }
    }

    companion object {
        const val MODE = "mode"
        const val PROVIDER = "provider"
        const val OWN = "own"
        const val BUILT_IN = "built_in"
        private const val PREFIX = "v1:"

        fun keyName(p: OwnAiProvider) = "key.${p.id}"
        fun modelName(p: OwnAiProvider) = "model.${p.id}"

        private fun removeKey(values: KeyValues, p: OwnAiProvider) {
            values.put(keyName(p), null)
            values.put(modelName(p), null)
        }

        /** Every provider's key and model, after the encryption key was replaced: nothing it sealed can be read. */
        fun forgetKeys(values: KeyValues) = OwnAiProvider.entries.forEach { removeKey(values, it) }

        /**
         * A pasted key as the provider wrote it: printable ASCII only (keys never hold anything else), so spaces, line
         * breaks, curly quotes and invisible zero-width characters go, then straight quotes around it. Anything else
         * would also fail the HTTP header check.
         */
        fun clean(key: String): String = key.filter { it in '!'..'~' }.trim('"', '\'', '`')

        /** Data that can never be decrypted: a wrong tag (another encryption key), bad Base64 or format, no key. */
        internal fun isUnreadable(e: Throwable): Boolean =
            e is AEADBadTagException || e is UnreadableKeyException || e is IllegalArgumentException

        /** The first three and last four characters of a key, enough to tell keys apart ("sk-…a1b2"). */
        fun mask(key: String): String = if (key.length >= 12) key.take(3) + "…" + key.takeLast(4) else "…" + key.takeLast(2)
    }
}

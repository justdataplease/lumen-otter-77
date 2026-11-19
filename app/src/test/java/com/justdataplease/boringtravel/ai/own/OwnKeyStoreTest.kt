package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** The key store's logic behind a fake cipher, and which planner answers. */
class OwnKeyStoreTest {
    /**
     * Reversible and visibly not the plain text. [failure] makes decryption throw it: a wrong tag like a replaced
     * Keystore key, or a Keystore that isn't ready.
     */
    private class FakeCipher : KeyCipher {
        var failure: Exception? = null
        var destroyed = false
        override fun encrypt(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun decrypt(sealed: ByteArray): ByteArray {
            failure?.let { throw it }
            require(sealed.first() == 7.toByte())
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        override fun destroy() { destroyed = true }
    }

    private class MapValues : KeyValues {
        val map = mutableMapOf<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
        override fun clear() = map.clear()
    }

    private val values = MapValues()
    private val cipher = FakeCipher()
    private val store = OwnKeyStore(values, cipher)
    private val key = "sk-ant-api03-ABCDEFGHIJKLMNOP-wxyz"

    @Test fun startsOnTheBuiltInPlanner() {
        val s = store.state()
        assertFalse(s.useOwnKey)
        assertEquals(OwnAiProvider.Gemini, s.provider)
        assertTrue(s.withKey.isEmpty())
        assertNull(s.active)
    }

    @Test fun keysAreStoredEncryptedAndReadBack() {
        store.saveKey(OwnAiProvider.Claude, "  $key\n")
        val stored = values.map.getValue(OwnKeyStore.keyName(OwnAiProvider.Claude))
        assertFalse(stored.contains(key))
        assertFalse(stored.contains("wxyz"))
        assertTrue(stored.startsWith("v1:"))
        assertEquals(key, store.key(OwnAiProvider.Claude))
        assertEquals("sk-…wxyz", OwnKeyStore.mask(store.key(OwnAiProvider.Claude)!!))
        assertEquals(setOf(OwnAiProvider.Claude), store.state().withKey)
        assertNull(store.key(OwnAiProvider.Gemini))
    }

    @Test fun pastedKeysLoseSpacesBreaksAndQuotes() {
        assertEquals("AIzaSy-123", OwnKeyStore.clean(" \"AIzaSy-123\"\r\n"))
        assertEquals("sk-proj-abc", OwnKeyStore.clean("sk-proj-\nabc "))
        assertEquals("sk-ant-abc", OwnKeyStore.clean("'sk-ant-abc'"))
    }

    @Test fun pastedKeysLoseCurlyQuotesAndInvisibleCharacters() {
        // A notes app or chat turns quotes curly; copying from a web page can bring zero-width and no-break spaces.
        assertEquals("sk-proj-abc", OwnKeyStore.clean("“sk-proj-abc”"))
        assertEquals("sk-proj-abc", OwnKeyStore.clean("‘sk-proj-abc’"))
        assertEquals("sk-proj-abc", OwnKeyStore.clean("﻿​sk-proj-‌abc‍⁠ "))
        assertEquals("AIzaSy-x_y", OwnKeyStore.clean("«AIzaSy-x_y»\t"))
        // What's left always passes OkHttp's header check (which threw before: "Something went wrong").
        val cleaned = OwnKeyStore.clean("“​sk-or-v1-0123 ”")
        okhttp3.Headers.Builder().add("Authorization", "Bearer $cleaned")
        assertTrue(cleaned.all { it in '!'..'~' })
    }

    @Test fun masksShowOnlyTheEnds() {
        assertEquals("sk-…a1b2", OwnKeyStore.mask("sk-or-v1-0123456789a1b2"))
        assertEquals("AIz…WXYZ", OwnKeyStore.mask("AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        assertEquals("…yz", OwnKeyStore.mask("short-xyz"))
    }

    @Test fun everyProviderKeepsItsOwnKeyAndModel() {
        val keys = mapOf(
            OwnAiProvider.Gemini to "AIzaSy-gemini-key-0001",
            OwnAiProvider.Claude to "sk-ant-api03-claude-0002",
            OwnAiProvider.ChatGpt to "sk-proj-openai-key-0003",
            OwnAiProvider.OpenRouter to "sk-or-v1-router-key-0004",
        )
        val models = mapOf(
            OwnAiProvider.Gemini to "gemini-3.8-flash",
            OwnAiProvider.Claude to "claude-sonnet-5-5",
            OwnAiProvider.ChatGpt to "gpt-6-luna",
            OwnAiProvider.OpenRouter to "my-lab/hand-typed-model",
        )
        keys.forEach { (p, k) -> store.saveKey(p, k) }
        models.forEach { (p, m) -> store.setModel(p, m) }
        store.setUseOwnKey(true)
        // Switching the provider chip loses nothing.
        for (p in OwnAiProvider.entries + OwnAiProvider.entries.reversed()) {
            store.setProvider(p)
            val s = store.state()
            assertEquals(p, s.active)
            assertEquals(keys.getValue(p), store.key(p))
            assertEquals(OwnAiModels.upgrade(p, models.getValue(p)), s.model(p))
            assertEquals(OwnAiProvider.entries.toSet(), s.withKey)
        }
        store.setProvider(OwnAiProvider.Claude)
        assertEquals("Using your Claude key · claude-sonnet-latest", store.state().usingLine()) // saved before the latest names: shown as its family
        // Remove key: only that provider's key and model.
        store.removeKey(OwnAiProvider.ChatGpt)
        assertNull(store.key(OwnAiProvider.ChatGpt))
        assertNull(store.state().model(OwnAiProvider.ChatGpt))
        (OwnAiProvider.entries - OwnAiProvider.ChatGpt).forEach { p ->
            assertEquals(keys.getValue(p), store.key(p))
            assertEquals(OwnAiModels.upgrade(p, models.getValue(p)), store.state().model(p))
        }
        store.setProvider(OwnAiProvider.ChatGpt)
        assertNull(store.state().usingLine()) // no ChatGPT key now: the built-in planner answers
    }

    @Test fun aKeyThatCanNeverBeDecryptedIsDropped() {
        listOf(AEADBadTagException("Tag mismatch"), UnreadableKeyException("No encryption key")).forEach { failure ->
            store.saveKey(OwnAiProvider.ChatGpt, "sk-proj-abc")
            store.setModel(OwnAiProvider.ChatGpt, "gpt-6-luna")
            cipher.failure = failure
            assertNull(store.key(OwnAiProvider.ChatGpt))
            assertFalse(OwnAiProvider.ChatGpt in store.state().withKey)
            assertNull(store.state().model(OwnAiProvider.ChatGpt))
            cipher.failure = null
        }
    }

    @Test fun badDataIsDropped() {
        values.map[OwnKeyStore.keyName(OwnAiProvider.Gemini)] = "v1:not base64!"
        assertNull(store.key(OwnAiProvider.Gemini))
        assertFalse(OwnAiProvider.Gemini in store.state().withKey)
        values.map[OwnKeyStore.keyName(OwnAiProvider.Gemini)] = "v9:" + java.util.Base64.getEncoder().encodeToString(byteArrayOf(7, 1))
        assertNull(store.key(OwnAiProvider.Gemini))
        assertFalse(OwnAiProvider.Gemini in store.state().withKey)
    }

    @Test fun aKeystoreErrorRightAfterBootKeepsTheKey() {
        store.saveKey(OwnAiProvider.Claude, key)
        store.setModel(OwnAiProvider.Claude, "claude-haiku-5-5")
        listOf(KeyStoreException("Keystore not ready"), UnrecoverableKeyException("Failed to obtain information about key"), ProviderException("Keystore operation failed"), IllegalStateException("x")).forEach { failure ->
            cipher.failure = failure
            assertNull(store.key(OwnAiProvider.Claude)) // can't be read now…
            assertTrue(OwnAiProvider.Claude in store.state().withKey) // …but nothing is deleted
        }
        cipher.failure = null
        assertEquals(key, store.key(OwnAiProvider.Claude))
        assertEquals("claude-haiku-latest", store.state().model(OwnAiProvider.Claude))
    }

    @Test fun forgetKeysDropsEveryKeyAndModelButNotTheChoices() {
        store.setUseOwnKey(true)
        store.setProvider(OwnAiProvider.Claude)
        OwnAiProvider.entries.forEach { store.saveKey(it, "key-${it.id}"); store.setModel(it, "model-${it.id}") }
        OwnKeyStore.forgetKeys(values)
        OwnAiProvider.entries.forEach { assertNull(store.key(it)); assertNull(store.state().model(it)) }
        assertTrue(store.state().useOwnKey)
        assertEquals(OwnAiProvider.Claude, store.state().provider)
    }

    @Test fun sealingUsesTheSavedKeyAndNeverResetsOnOneFailedRead() {
        var resets = 0
        var made = 0
        // Read fine.
        assertEquals("saved", sealingKey({ "saved" }, { resets++ }, { made++; "new" }))
        // One failed read (Keystore starting): the second read works, nothing is reset.
        var reads = 0
        assertEquals("saved", sealingKey({ if (reads++ == 0) throw KeyStoreException("busy") else "saved" }, { resets++ }, { made++; "new" }))
        assertEquals(0, resets)
        // No key yet: a new one, nothing reset.
        assertEquals("new", sealingKey<String>({ null }, { resets++ }, { made++; "new" }))
        assertEquals(0, resets)
        assertEquals(1, made)
    }

    @Test fun aBrokenKeystoreEntryIsReplacedSoSavingWorksAgain() {
        var resets = 0
        listOf<Exception>(UnrecoverableKeyException("corrupted"), KeyStoreException("bad alias"), ProviderException("bad")).forEach { e ->
            assertEquals("new", sealingKey<String>({ throw e }, { resets++ }, { "new" }))
        }
        assertEquals(3, resets)
        // Anything else is not the Keystore's: it isn't taken for a broken entry.
        try {
            sealingKey<String>({ throw IllegalStateException("bug") }, { resets++ }, { "new" })
            org.junit.Assert.fail("expected the error")
        } catch (_: IllegalStateException) {
        }
        assertEquals(3, resets)
    }

    @Test fun removeKeyForgetsItsModelOnly() {
        store.saveKey(OwnAiProvider.Claude, key)
        store.setModel(OwnAiProvider.Claude, "claude-sonnet-5-5")
        store.saveKey(OwnAiProvider.Gemini, "AIza-1")
        store.removeKey(OwnAiProvider.Claude)
        assertNull(store.key(OwnAiProvider.Claude))
        assertNull(store.state().model(OwnAiProvider.Claude))
        assertEquals("AIza-1", store.key(OwnAiProvider.Gemini))
    }

    @Test fun deleteAccountClearsEverythingAndTheEncryptionKey() {
        store.setUseOwnKey(true)
        store.setProvider(OwnAiProvider.Claude)
        store.saveKey(OwnAiProvider.Claude, key)
        store.clearAll()
        assertTrue(values.map.isEmpty())
        assertTrue(cipher.destroyed)
        assertNull(store.state().active)
    }

    @Test fun activeNeedsTheSwitchAndAKey() {
        store.setProvider(OwnAiProvider.Claude)
        store.saveKey(OwnAiProvider.Claude, key)
        assertNull(store.state().active) // "Built-in" still chosen
        store.setUseOwnKey(true)
        assertEquals(OwnAiProvider.Claude, store.state().active)
        store.setProvider(OwnAiProvider.Gemini)
        assertNull(store.state().active) // no Gemini key
    }

    private class FakeApi(override val provider: OwnAiProvider) : OwnKeyApi {
        var lastKey: String? = null
        var lastModel: String? = null
        var lastTurns: List<ChatTurn> = emptyList()
        override suspend fun models(key: String) = emptyList<String>()
        override suspend fun ping(key: String, model: String) = Unit
        override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply {
            lastKey = key; lastModel = model; lastTurns = turns
            return OwnKeyReply("""{"add_items":{"items":[{"kind":"food","title":"Pizza"}]},"finish":{"summary":"Done."}}""")
        }
    }

    /** A provider whose model list answers [status]. */
    private class Refusing(override val provider: OwnAiProvider, private val status: Int) : OwnKeyApi {
        override suspend fun models(key: String): List<String> = throw OwnKeyException(OwnKeyError.ofHttp(status), status)
        override suspend fun ping(key: String, model: String) = Unit
        override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>) = OwnKeyReply("")
    }

    @Test fun aRestrictedOpenAiKeySkipsTheModelList() = kotlinx.coroutines.runBlocking {
        // A restricted key without "Models: Read": Test goes on to ask the model itself.
        assertNull(Refusing(OwnAiProvider.ChatGpt, 401).modelsOrNull("sk-proj-restricted"))
        assertNull(Refusing(OwnAiProvider.ChatGpt, 403).modelsOrNull("sk-proj-restricted"))
        // Anything else is still the error it was.
        listOf(Refusing(OwnAiProvider.ChatGpt, 429), Refusing(OwnAiProvider.Claude, 401), Refusing(OwnAiProvider.Gemini, 403), Refusing(OwnAiProvider.OpenRouter, 401)).forEach { api ->
            try {
                api.modelsOrNull("k")
                org.junit.Assert.fail("expected an error from ${api.provider}")
            } catch (_: OwnKeyException) {
            }
        }
        assertEquals(listOf<String>(), FakeApi(OwnAiProvider.ChatGpt).modelsOrNull("k"))
    }

    @Test fun engineSelection() {
        val apis = OwnAiProvider.entries.associateWith { FakeApi(it) }
        fun engine() = store.state().engine(store::key) { apis.getValue(it) }

        assertNull(engine()) // built-in
        store.setUseOwnKey(true)
        store.setProvider(OwnAiProvider.Claude)
        assertNull(engine()) // own key chosen, none saved: built-in
        store.saveKey(OwnAiProvider.Claude, key)
        val claude = engine()
        assertNotNull(claude)
        assertEquals(OwnAiProvider.Claude, claude!!.provider)
        assertEquals("claude-sonnet-latest", claude.modelName) // the default before a test
        assertEquals("claude-sonnet-latest (own Claude key)", claude.reportName)
        store.setModel(OwnAiProvider.Claude, "claude-haiku-5-5")
        assertEquals("claude-haiku-latest", engine()!!.modelName)
        store.setUseOwnKey(false)
        assertNull(engine())
    }

    @Test fun ownKeyPlannerSendsThePromptAndRunsTheTools() = kotlinx.coroutines.runBlocking {
        val api = FakeApi(OwnAiProvider.ChatGpt)
        val planner = OwnKeyPlanner(api, "sk-proj-abc", "gpt-6-luna")
        val today = java.time.LocalDate.of(2026, 11, 11)
        val result = planner.plan(com.justdataplease.boringtravel.ai.PlanRequest(null, emptyList(), com.justdataplease.boringtravel.ai.tools.PendingPlan(), emptyList(), "Pizza in Naples", today))
        assertEquals("Pizza", result.pending.added.single().item.title)
        assertEquals("Done.", result.reply)
        assertEquals("sk-proj-abc", api.lastKey)
        assertEquals("gpt-6-luna", api.lastModel)
        assertEquals(1, api.lastTurns.size)
        assertTrue(api.lastTurns.single().fromTraveller)
        assertTrue(api.lastTurns.single().text.contains("Pizza in Naples"))
        // The key never shows up in logs through toString.
        assertFalse(planner.toString().contains("sk-proj"))

        planner.plan(com.justdataplease.boringtravel.ai.PlanRequest(null, emptyList(), result.pending, listOf(ChatTurn(true, "Naples"), ChatTurn(false, "Done.")), "Add Capri", today))
        assertEquals(listOf(true, false, true), api.lastTurns.map { it.fromTraveller })
        // The pending pizza is in the context of the next message.
        assertTrue(api.lastTurns.last().text.contains("new-1"))
    }
}

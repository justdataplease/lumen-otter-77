package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.google.AppleOutcome
import com.justdataplease.boringtravel.google.FirebaseAuthException
import com.justdataplease.boringtravel.google.FirebaseAuthRest
import com.justdataplease.boringtravel.google.FirebaseUser
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.GoogleConnect
import com.justdataplease.boringtravel.ios.AppSettings
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The community's account (round 24): the Android app's Firebase Auth sign-in written again over Firebase Auth's REST API
 * ([FirebaseAuthRest]), as round 23 signs in with Apple. The app's Google account is handed to Firebase only when the
 * community is used (its quiet sign-in on the Community tab, or Sign in on its screens), as on Android: a Google account
 * gives its ID token ([GoogleAccount.freshIdToken], signInWithIdp with google.com); a Sign in with Apple account is
 * already a Firebase account (round 23), whose refresh token it takes. Kept as Firebase's refresh token and the account's
 * id in [keys] (the Keychain on iPhone, this device only); the ID token for an hour in memory, renewed by Secure Token.
 *
 * Without the build's Firebase ids ([config]) [configured] is false and the community isn't available in this build.
 */
class CommunityAuth(
    net: Net,
    private val config: FirebaseSetup.Config,
    private val keys: KeyValues,
    private val google: GoogleAccount?,
    bundleId: String?,
    private val now: () -> Long = ::nowMs,
) {
    private val rest = FirebaseAuthRest(net, config, bundleId)
    private val lock = Mutex()
    private var session: Session? = load()
    private var idToken: String? = null
    private var idTokenAt = 0L

    private val _user = MutableStateFlow(session?.user)

    /** Who is signed in to the community (Android: FirebaseAuth.currentUser). */
    val user: StateFlow<CommunityUser?> = _user.asStateFlow()

    val configured: Boolean get() = config.isComplete

    /** What is kept between starts: the account's id, Firebase's refresh token, the email and name it was made with. */
    @Serializable
    private data class Session(val uid: String, val refresh: String, val email: String? = null, val name: String? = null, val apple: Boolean = false) {
        val user: CommunityUser get() = CommunityUser(uid, email, name)
    }

    private fun load(): Session? = keys.get(SESSION)?.let { runCatching { JSON.decodeFromString(Session.serializer(), it) }.getOrNull() }

    private fun keep(s: Session?) {
        session = s
        keys.put(SESSION, s?.let { JSON.encodeToString(Session.serializer(), it) })
        _user.value = s?.user
    }

    /**
     * The account's Firebase ID token for Firestore ([fresh]: a new one, the last was refused); null when nobody is signed
     * in. An account Firebase no longer has (deleted, disabled, its token revoked) is signed out.
     */
    suspend fun token(fresh: Boolean): String? = lock.withLock {
        val s = session ?: return@withLock null
        if (!fresh) idToken?.takeIf { now() - idTokenAt < TOKEN_AGE_MS }?.let { return@withLock it }
        val u = try {
            rest.refreshUser(s.refresh)
        } catch (e: FirebaseAuthException) {
            if (e.code in 400..403) {
                keep(null)
                idToken = null
                return@withLock null
            }
            throw e
        }
        if (u.refreshToken != s.refresh) keep(s.copy(refresh = u.refreshToken))
        idToken = u.idToken
        idTokenAt = now()
        u.idToken
    }

    /**
     * Android's Community.signIn: null when signed in, else what to tell the traveller ("" = nothing to say: cancelled,
     * or [quiet] and no quiet way in). [quiet] never shows a sign-in page: a Google account signed in on this phone gives
     * its ID token, an Apple account its Firebase account. Otherwise Google's page (the account and Drive, as the app's
     * one Google button), then the same.
     */
    suspend fun signIn(quiet: Boolean): String? {
        if (!configured) return UNAVAILABLE
        if (session != null) return null
        val g = google
        if (g?.provider == AppSettings.PROVIDER_APPLE) {
            val refresh = keys.get(GoogleAccount.APPLE_REFRESH) ?: return if (quiet) "" else FirebaseAuthRest.COMMUNITY_FAILED
            return attempt(apple = true) { rest.refreshUser(refresh) }
        }
        var token = if (g?.provider == AppSettings.PROVIDER_GOOGLE) g.freshIdToken() else null
        if (token == null && quiet) return ""
        if (token == null) {
            if (g == null || !g.signInConfigured) return GoogleAccount.NOT_SET_UP
            when (val r = g.signIn()) {
                is GoogleConnect.NotSignedIn -> return r.message.orEmpty()
                else -> token = g.freshIdToken() ?: return FirebaseAuthRest.COMMUNITY_FAILED
            }
        }
        val id = token
        return attempt(apple = false) { rest.signInWithGoogle(id) }
    }

    /** Sign in with Apple's sheet (Apple 4.8, beside Google on the community's sign-in card), then the community with it. */
    suspend fun signInWithApple(): String? {
        if (!configured) return UNAVAILABLE
        val g = google ?: return GoogleAccount.APPLE_NOT_SET_UP
        return when (val r = g.signInWithApple()) {
            AppleOutcome.SignedIn -> signIn(quiet = false)
            AppleOutcome.Cancelled -> ""
            is AppleOutcome.Failed -> r.message
        }
    }

    private suspend fun attempt(apple: Boolean, call: suspend () -> FirebaseUser): String? = try {
        val u = call()
        lock.withLock {
            idToken = u.idToken
            idTokenAt = now()
        }
        keep(Session(u.localId, u.refreshToken, u.email, u.name, apple))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        FirebaseAuthRest.COMMUNITY_FAILED
    }

    /** Whether the community's account is the Sign in with Apple account (its deletion is round 23's, with Apple's revoke). */
    val isAppleAccount: Boolean get() = session?.apple == true

    /** Signs out of the community on this phone (the app's sign-out does this too). */
    suspend fun signOut() {
        lock.withLock { idToken = null }
        keep(null)
    }

    /**
     * Deletes the community's Firebase account (Android: FirebaseUser.delete). Firebase wants a recent sign-in: a Google
     * account signs in again quietly once. Throws when it couldn't; the account then stays signed in.
     */
    suspend fun deleteAccount() {
        val s = session ?: return
        try {
            rest.delete(token(fresh = true) ?: return)
        } catch (e: FirebaseAuthException) {
            if (e.reason != "CREDENTIAL_TOO_OLD_LOGIN_AGAIN" || s.apple) throw e
            val google = google?.freshIdToken() ?: throw e
            rest.delete(rest.signInWithGoogle(google).idToken)
        }
        signOut()
    }

    companion object {
        /** The Keychain's name for the community's account (service ACCOUNT_SERVICE on iPhone, beside Google's tokens). */
        const val SESSION = "community.session"

        const val UNAVAILABLE = "The community isn't available in this build."

        /** Firebase's ID tokens last an hour; renewed a little before. */
        private const val TOKEN_AGE_MS = 50 * 60 * 1000L

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

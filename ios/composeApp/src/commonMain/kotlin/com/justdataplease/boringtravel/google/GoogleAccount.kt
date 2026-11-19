package com.justdataplease.boringtravel.google

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.ios.AppSettings
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.call
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Where a Drive connect stands (the Android app's DriveStep, without its consent intent: the sheet is the page itself). */
sealed interface DriveStep {
    data object Connected : DriveStep
    data object Cancelled : DriveStep
    data class Failed(val message: String) : DriveStep
}

/** How one tap on a Google button ended (the Android app's ui/onboarding/GoogleUi.kt GoogleConnect). */
sealed interface GoogleConnect {
    /** Signed in and Google Drive connected. */
    data object Connected : GoogleConnect

    /** Signed in, but Drive wasn't connected: [message] when Google failed, null when the person said no. */
    data class NoDrive(val message: String?) : GoogleConnect

    /** Not signed in: [message] when sign-in failed, null when the sheet was closed. */
    data class NotSignedIn(val message: String?) : GoogleConnect
}

/** How Sign in with Apple ended. */
sealed interface AppleOutcome {
    data object SignedIn : AppleOutcome
    data object Cancelled : AppleOutcome
    data class Failed(val message: String) : AppleOutcome
}

/**
 * The owner's account in the app (the Android app's google/GoogleAccount.kt, DriveAuth.kt and CredentialSignIn.kt): the
 * Google account (sign-in and Drive access in one page on Google's own sign-in sheet, [AuthBrowser], with the scopes
 * Android asks for: [GoogleOAuth]), Drive's tokens for [drive] (an access token for an hour in memory, the refresh token
 * in [keys]: the Keychain on iPhone, this device only), and Sign in with Apple beside Google (new on iPhone: [apple], an
 * account in the owner's Firebase project, [FirebaseAuthRest]). The account's email, name and who signed in are
 * [settings]' (this phone's own, never in a backup, as Android's).
 *
 * Without [clientId] (`google.iosClientId`, empty by default) there is no Google here: the screens say Google sign-in
 * isn't set up in this build. Sign in with Apple needs `apple.signIn` and the build's Firebase ids. [onDriveChanged]
 * runs after Drive is connected or disconnected (the daily backup is planned again there).
 */
class GoogleAccount(
    private val settings: AppSettings,
    private val net: Net,
    private val keys: KeyValues,
    files: MediaFiles,
    private val browser: AuthBrowser = NoAuthBrowser,
    private val apple: AppleSignIn? = null,
    private val clientId: String = AccountIds.GOOGLE_IOS_CLIENT_ID,
    appleOn: Boolean = AccountIds.APPLE_SIGN_IN.equals("true", ignoreCase = true),
    firebase: FirebaseSetup.Config = FirebaseSetup.config,
    bundleId: String? = null,
    private val now: () -> Long = ::nowMs,
    /** Random bytes for PKCE, the state and Apple's nonce: the phone's secure generator (SecRandomCopyBytes on iPhone). */
    private val random: (Int) -> ByteArray = { Random.nextBytes(it) },
    retryWait: Long = 1_000,
    wait: suspend (Long) -> Unit = { delay(it) },
) : AccessTokens {
    private val firebaseAuth = FirebaseAuthRest(net, firebase, bundleId)
    private val lock = Mutex()
    private var token: String? = null
    private var tokenAt = 0L
    private var tokenLife = TOKEN_AGE_MS

    /** Drive's REST client with this account's tokens; its folders' ids are kept in [settings]. */
    val drive = RestDriveApi(net, this, connected = { settings.driveConnected }, files, retryWait = retryWait, memory = settings.driveFolders, wait = wait)

    /** Runs after Drive is connected or disconnected (the app plans its daily backup there). */
    var onDriveChanged: suspend () -> Unit = {}

    /** False when this build has no Google iPhone client: sign-in and Drive are hidden and the app stays local. */
    val signInConfigured: Boolean get() = clientId.isNotBlank()

    /**
     * Whether a note's photos and videos go up to Drive (Android's WriteViewModel.goesToDrive: Drive connected and Back
     * up every day on; a shared trip's notes come with round 26).
     */
    val backsUpMedia: Boolean get() = settings.driveConnected && settings.autoBackup

    /** Whether Sign in with Apple is offered: the phone's sheet, the build's switch and its Firebase ids. */
    val appleConfigured: Boolean = apple != null && appleOn && firebaseAuth.configured

    /** True once Google wants the owner to confirm Drive access again; background backups wait until they do. */
    var needsReconnect by mutableStateOf(false)
        private set

    /** The account Drive is connected with, when known. */
    val driveAccount: String? get() = settings.driveAccount

    /** Who is signed in: Google, Apple (iPhone), or null. */
    val provider: String? get() = settings.accountProvider ?: if (settings.accountEmail != null) AppSettings.PROVIDER_GOOGLE else null

    /**
     * The one Google button (the Android owner's "To have 1 button"): Google's page asking for the account and for
     * Drive together; saying no to Drive (unticking it on Google's page) still signs in. An account used before goes
     * straight through (the sheet shares Safari's Google sign-in).
     */
    suspend fun signIn(): GoogleConnect {
        if (!signInConfigured) return GoogleConnect.NotSignedIn(NOT_SET_UP)
        val grant = when (val g = authorize(GoogleOAuth.SIGN_IN + DriveJson.SCOPES, hint = null, failure = GoogleOAuth.GOOGLE_FAILED)) {
            Grant.Closed, Grant.Denied -> return GoogleConnect.NotSignedIn(null)
            is Grant.Failed -> return GoogleConnect.NotSignedIn(g.message)
            is Grant.Given -> g
        }
        val who = GoogleOAuth.who(grant.tokens.idToken)
        val email = who.email ?: return GoogleConnect.NotSignedIn("Google didn't return an account.")
        keep(grant)
        settings.setAccount(email, who.name, AppSettings.PROVIDER_GOOGLE)
        if (DriveJson.DRIVE_FILE !in grant.scopes) return GoogleConnect.NoDrive(null)
        settings.driveConnected = true
        settings.driveAccount = email
        onDriveChanged()
        return GoogleConnect.Connected
    }

    /** Asks Google for Drive access only (Me › Backup & sync, Share's Google Docs): the account the app knows is suggested. */
    suspend fun connectDrive(): DriveStep {
        if (!signInConfigured) return DriveStep.Failed(NOT_SET_UP)
        val hint = settings.driveAccount ?: settings.accountEmail.takeIf { provider == AppSettings.PROVIDER_GOOGLE }
        val grant = when (val g = authorize(DriveJson.SCOPES, hint, failure = "Google Drive couldn't be connected. Try again.")) {
            Grant.Closed, Grant.Denied -> return DriveStep.Cancelled
            is Grant.Failed -> return DriveStep.Failed(g.message)
            is Grant.Given -> g
        }
        // Google's page lets people untick a box; without drive.file there is nothing to back up to.
        if (DriveJson.DRIVE_FILE !in grant.scopes) return DriveStep.Failed("Google Drive access wasn't given.")
        keep(grant)
        settings.driveConnected = true
        // Remember the account Drive is with, so later pages suggest the same one.
        settings.driveAccount = try {
            drive.accountEmail()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: hint
        onDriveChanged()
        return DriveStep.Connected
    }

    /** Sign in with Apple's sheet, then the account in the owner's Firebase project; its email when Apple shared one. */
    suspend fun signInWithApple(): AppleOutcome {
        val sheet = apple
        if (sheet == null || !appleConfigured) return AppleOutcome.Failed(APPLE_NOT_SET_UP)
        val nonce = FirebaseAuthRest.nonce(random(32))
        val credential = try {
            sheet.signIn(FirebaseAuthRest.hashed(nonce))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return AppleOutcome.Failed(FirebaseAuthRest.APPLE_FAILED)
        } ?: return AppleOutcome.Cancelled
        val user = try {
            firebaseAuth.signInWithApple(credential.identityToken, nonce)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetException) {
            return AppleOutcome.Failed(e.readable(FirebaseAuthRest.APPLE_FAILED))
        } catch (_: Exception) {
            return AppleOutcome.Failed(FirebaseAuthRest.APPLE_FAILED)
        }
        keys.put(APPLE_REFRESH, user.refreshToken)
        // Apple gives the name and email only the first time; its identity token carries the email each time.
        val email = credential.email ?: user.email ?: GoogleOAuth.who(credential.identityToken).email
        val name = credential.name ?: settings.accountName.takeIf { provider == AppSettings.PROVIDER_APPLE }
        settings.setAccount(email, name, AppSettings.PROVIDER_APPLE)
        return AppleOutcome.SignedIn
    }

    /**
     * For Delete account with an Apple account (Apple 5.1.1(v)): Apple's sheet once more for a fresh one-time code, then
     * Firebase revokes Apple's token with it and the Firebase account is deleted. Null when done (or there was no Apple
     * account); else why it didn't happen, and nothing was deleted.
     */
    suspend fun deleteAppleAccount(): String? {
        if (provider != AppSettings.PROVIDER_APPLE) return null
        val sheet = apple ?: return null
        val nonce = FirebaseAuthRest.nonce(random(32))
        val credential = try {
            sheet.signIn(FirebaseAuthRest.hashed(nonce))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return FirebaseAuthRest.APPLE_FAILED
        } ?: return APPLE_CLOSED
        val code = credential.authorizationCode ?: return FirebaseAuthRest.APPLE_FAILED
        return try {
            // A fresh sign-in first: Firebase deletes only an account signed in a moment ago.
            val user = firebaseAuth.signInWithApple(credential.identityToken, nonce)
            firebaseAuth.revokeApple(user.idToken, code)
            firebaseAuth.delete(user.idToken)
            keys.put(APPLE_REFRESH, null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: NetException) {
            DELETE_OFFLINE
        } catch (_: Exception) {
            DELETE_FAILED
        }
    }

    /** Signs out on this phone: the account is forgotten and Drive stops until the owner connects again; Google keeps the grant. */
    suspend fun signOut() {
        lock.withLock { token = null }
        keys.put(REFRESH, null)
        keys.put(APPLE_REFRESH, null)
        settings.driveAccount = null
        drive.forgetFolders()
        settings.setAccount(null, null, null)
        settings.driveConnected = false
        needsReconnect = false
        onDriveChanged()
    }

    /**
     * Removes the account from BoringTravel (Delete account): Drive's access is given back to Google, then the account
     * is signed out and forgotten on this phone. Trips stay on the phone, backups in the traveller's Drive.
     */
    suspend fun removeAccount() {
        revoke()
        signOut()
    }

    /** Gives Drive's access back to Google and stops backups. Files already in Drive stay there. */
    suspend fun disconnectDrive() {
        revoke()
        settings.driveAccount = settings.accountEmail.takeIf { provider == AppSettings.PROVIDER_GOOGLE }
        drive.forgetFolders()
        settings.driveConnected = false
        needsReconnect = false
        onDriveChanged()
    }

    override suspend fun token(): String = lock.withLock {
        token?.takeIf { now() - tokenAt < tokenLife }?.let { return@withLock it }
        val refresh = keys.get(REFRESH)
        if (refresh == null) {
            needsReconnect = true
            throw DriveNotConnectedException()
        }
        val answer = net.call(NetCall("POST", GoogleOAuth.TOKEN, body = GoogleOAuth.refreshForm(clientId, refresh).encodeToByteArray(), contentType = FORM, timeoutMs = 30_000))
        when (val t = GoogleOAuth.tokens(answer.code, answer.text())) {
            is GoogleOAuth.TokenAnswer.Error -> {
                if (t.error == "invalid_grant") {
                    // Revoked or expired: Google wants the owner to confirm Drive access again.
                    needsReconnect = true
                    throw DriveNotConnectedException()
                }
                throw DriveException(answer.code, "Google Drive couldn't be connected (error ${answer.code}).")
            }
            is GoogleOAuth.TokenAnswer.Ok -> {
                val scopes = t.tokens.scopes
                if (scopes != null && DriveJson.DRIVE_FILE !in scopes) {
                    needsReconnect = true
                    throw DriveNotConnectedException()
                }
                t.tokens.refreshToken?.let { keys.put(REFRESH, it) }
                token = t.tokens.accessToken
                tokenAt = now()
                tokenLife = lifeOf(t.tokens.expiresInS)
                needsReconnect = false
                t.tokens.accessToken
            }
        }
    }

    override suspend fun refreshed(stale: String): String {
        lock.withLock { if (token == null || token == stale) token = null }
        return token()
    }

    /** What Google's page gave, or why not. */
    private sealed interface Grant {
        data object Closed : Grant
        data object Denied : Grant
        data class Failed(val message: String) : Grant
        data class Given(val tokens: GoogleOAuth.Tokens, val scopes: Set<String>) : Grant
    }

    /** Google's page for [scopes], then the code changed for tokens (PKCE: the code is useless without the verifier). */
    private suspend fun authorize(scopes: List<String>, hint: String?, failure: String): Grant {
        val verifier = GoogleOAuth.verifier(random(48))
        val state = GoogleOAuth.state(random(16))
        val redirect = try {
            browser.open(GoogleOAuth.authUrl(clientId, scopes, state, verifier, hint), GoogleOAuth.scheme(clientId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Grant.Failed(failure)
        } ?: return Grant.Closed
        val code = when (val a = GoogleOAuth.answer(redirect, state)) {
            GoogleOAuth.Answer.Denied -> return Grant.Denied
            is GoogleOAuth.Answer.Failed -> return Grant.Failed(failure)
            is GoogleOAuth.Answer.Code -> a
        }
        val answer = try {
            net.call(NetCall("POST", GoogleOAuth.TOKEN, body = GoogleOAuth.codeForm(clientId, code.code, verifier).encodeToByteArray(), contentType = FORM, timeoutMs = 30_000))
        } catch (e: CancellationException) {
            throw e
        } catch (_: NetException) {
            return Grant.Failed(NO_CONNECTION)
        }
        return when (val t = GoogleOAuth.tokens(answer.code, answer.text())) {
            is GoogleOAuth.TokenAnswer.Error ->
                Grant.Failed(if (t.error == "invalid_client" || t.error == "unauthorized_client") GoogleOAuth.NOT_REGISTERED else failure)
            is GoogleOAuth.TokenAnswer.Ok -> Grant.Given(t.tokens, t.tokens.scopes ?: code.scopes)
        }
    }

    /** Keeps what Google gave: the refresh token in the Keychain, the access token for this run. */
    private suspend fun keep(grant: Grant.Given) {
        grant.tokens.refreshToken?.let { keys.put(REFRESH, it) }
        lock.withLock {
            token = grant.tokens.accessToken
            tokenAt = now()
            tokenLife = lifeOf(grant.tokens.expiresInS)
        }
        needsReconnect = false
    }

    /** The grant given back to Google (every token of it), and forgotten here. */
    private suspend fun revoke() {
        val old = lock.withLock { token.also { token = null } }
        val refresh = keys.get(REFRESH)
        keys.put(REFRESH, null)
        val t = refresh ?: old ?: return
        try {
            net.call(NetCall("POST", GoogleOAuth.REVOKE, body = GoogleOAuth.revokeForm(t).encodeToByteArray(), contentType = FORM, timeoutMs = 20_000))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline: the token is forgotten here; Google lets it go unused (the owner can remove the app in their account).
        }
    }

    /** Google's tokens last an hour; renewed a little before (Android: 45 minutes). */
    private fun lifeOf(expiresInS: Long): Long = minOf(TOKEN_AGE_MS, maxOf(60_000L, expiresInS * 1000 - 5 * 60_000L))

    companion object {
        /** The Keychain's names (service [IosKeychain] ACCOUNT_SERVICE on iPhone). */
        const val REFRESH = "google.refresh"
        const val APPLE_REFRESH = "apple.refresh"

        const val NOT_SET_UP = "Google sign-in isn't set up in this build."
        const val APPLE_NOT_SET_UP = "Sign in with Apple isn't set up in this build."
        const val NO_CONNECTION = "No connection to Google. Check your internet and try again."
        const val APPLE_CLOSED = "Apple's sign-in was closed, so nothing was deleted."
        const val DELETE_OFFLINE = "Couldn't delete your account. Check the connection and try again."
        const val DELETE_FAILED = "Couldn't delete your Apple sign-in. Try again."

        private const val FORM = "application/x-www-form-urlencoded"
        private const val TOKEN_AGE_MS = 45 * 60 * 1000L
    }
}

/** The owner's account for every screen; null where a screen is drawn on its own. */
val LocalGoogle = staticCompositionLocalOf<GoogleAccount?> { null }

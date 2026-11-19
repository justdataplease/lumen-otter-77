package com.justdataplease.boringtravel.google

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.IOException

/** Where a Drive connect stands. */
sealed interface DriveStep {
    data object Connected : DriveStep
    data object Cancelled : DriveStep

    /** Google wants the owner to pick an account or confirm access: launch [intent] and pass the result back. */
    data class NeedsConsent(val intent: PendingIntent) : DriveStep
    data class Failed(val message: String) : DriveStep
}

/**
 * Drive access through Google Play services' authorization client, with `drive.file` and `drive.appdata` only.
 * Google matches the request to the Android OAuth client by package name and signing certificate, so no client id
 * is embedded. The access token lives in memory; a stale one is cleared and renewed silently.
 */
class DriveAuth(context: Context) : AccessTokens {
    private val app = context.applicationContext
    private val client = Identity.getAuthorizationClient(app)
    private val prefs = app.getSharedPreferences("google_drive", Context.MODE_PRIVATE)
    private val lock = Mutex()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private val _needsReconnect = MutableStateFlow(false)

    /** True once Google wants the owner to confirm Drive access again; background backups wait until they do. */
    val needsReconnect: StateFlow<Boolean> = _needsReconnect.asStateFlow()

    /** The Google account Drive is connected with (from sign-in, or read from Drive); null lets Google ask. */
    var account: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, value) }

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(DriveJson.SCOPES.map(::Scope))
        .apply { account?.let { setAccount(Account(it, ACCOUNT_TYPE)) } }
        .build()

    /** Asks for Drive access: granted at once when already allowed, otherwise [DriveStep.NeedsConsent]. */
    suspend fun connect(): DriveStep = try {
        accept(client.authorize(request()).await())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.statusCode == CommonStatusCodes.CANCELED) DriveStep.Cancelled else DriveStep.Failed(describe(e))
    } catch (e: Exception) {
        DriveStep.Failed(e.readable("Couldn't reach Google."))
    }

    /** The answer from Google's consent screen. */
    fun fromConsent(resultCode: Int, data: Intent?): DriveStep {
        if (resultCode != Activity.RESULT_OK || data == null) return DriveStep.Cancelled
        return try {
            accept(client.getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.CANCELED) DriveStep.Cancelled else DriveStep.Failed(describe(e))
        }
    }

    private fun accept(result: AuthorizationResult): DriveStep {
        if (result.hasResolution()) {
            return result.pendingIntent?.let { DriveStep.NeedsConsent(it) } ?: DriveStep.Failed("Google asked to confirm but showed no screen.")
        }
        // Google's screen lets people untick a box; without drive.file there is nothing to back up to.
        if (DriveJson.DRIVE_FILE !in result.grantedScopes.orEmpty()) return DriveStep.Failed("Google Drive access wasn't given.")
        val fresh = result.accessToken ?: return DriveStep.Failed("Google didn't return Drive access.")
        token = fresh
        tokenAt = System.currentTimeMillis()
        _needsReconnect.value = false
        return DriveStep.Connected
    }

    override suspend fun token(): String = lock.withLock {
        token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_AGE_MS }?.let { return it }
        val result = try {
            client.authorize(request()).await()
        } catch (e: ApiException) {
            throw IOException(describe(e), e)
        }
        if (result.hasResolution()) {
            _needsReconnect.value = true
            throw DriveNotConnectedException()
        }
        val fresh = result.accessToken ?: throw DriveNotConnectedException()
        token = fresh
        tokenAt = System.currentTimeMillis()
        _needsReconnect.value = false
        fresh
    }

    override suspend fun refreshed(stale: String): String {
        lock.withLock {
            // Play services hands back its cached token until it is cleared, even after Google rejected it.
            if (token == null || token == stale) {
                token = null
                runCatching { client.clearToken(ClearTokenRequest.builder().setToken(stale).build()).await() }
            }
        }
        return token()
    }

    /** Revokes BoringTravel's Drive access with Google (when the account is known) and forgets the token. */
    suspend fun disconnect() {
        val old = lock.withLock { token.also { token = null } }
        val email = account
        runCatching {
            if (email != null) {
                client.revokeAccess(
                    RevokeAccessRequest.builder().setAccount(Account(email, ACCOUNT_TYPE)).setScopes(DriveJson.SCOPES.map(::Scope)).build(),
                ).await()
            } else if (old != null) {
                client.clearToken(ClearTokenRequest.builder().setToken(old).build()).await()
            }
        }
        _needsReconnect.value = false
    }

    /** Forgets the token and the account on this phone (sign-out); Google keeps the grant. */
    suspend fun forget() {
        lock.withLock { token = null }
        account = null
        _needsReconnect.value = false
    }

    /** Drops the cached token, e.g. when the signed-in account changes. */
    suspend fun dropToken() = lock.withLock { token = null }

    private fun describe(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.CANCELED -> "Drive wasn't connected."
        CommonStatusCodes.NETWORK_ERROR -> "No connection to Google. Check your internet and try again."
        CommonStatusCodes.DEVELOPER_ERROR ->
            "This copy of BoringTravel isn't registered with Google yet (error 10). Its Android OAuth client is missing in Google Cloud."
        CommonStatusCodes.SIGN_IN_REQUIRED -> "Choose a Google account to connect Drive."
        else -> "Google Drive couldn't be connected (error ${e.statusCode})."
    }

    private companion object {
        const val ACCOUNT_TYPE = "com.google"
        const val KEY_ACCOUNT = "account"

        /** Google's tokens last an hour; renew a little before. */
        const val TOKEN_AGE_MS = 45 * 60 * 1000L
    }
}

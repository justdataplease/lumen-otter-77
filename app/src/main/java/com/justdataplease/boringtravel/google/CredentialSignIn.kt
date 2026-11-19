package com.justdataplease.boringtravel.google

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException

sealed interface SignInOutcome {
    /** [idToken] is Google's ID token for [webClientId][CredentialSignIn]; Firebase Auth takes it as is. */
    data class SignedIn(val email: String, val name: String?, val idToken: String) : SignInOutcome

    /** This build has no Web client id (`google.webClientId` in local.properties): the app goes on without an account. */
    data object NotSetUp : SignInOutcome
    data object Cancelled : SignInOutcome
    data class Failed(val message: String) : SignInOutcome
}

/**
 * "Sign in with Google" through Credential Manager. An account already used with BoringTravel goes through without
 * the account sheet (authorized accounts first); otherwise Google's sign-in sheet opens.
 */
class CredentialSignIn(context: Context, private val webClientId: String) {
    private val manager = CredentialManager.create(context.applicationContext)

    val configured: Boolean get() = webClientId.isNotBlank()

    /** The sign-in button. [activity] must be an Activity context: Google's sheet opens over it. */
    suspend fun signIn(activity: Context): SignInOutcome {
        if (!configured) return SignInOutcome.NotSetUp
        try {
            return get(activity, authorizedOnly())
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            // No account has signed in to BoringTravel on this phone yet: show the full sign-in sheet.
        } catch (e: Exception) {
            return failed(e)
        }
        return try {
            get(activity, GetSignInWithGoogleOption.Builder(webClientId).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e)
        }
    }

    /**
     * A fresh ID token without asking, when one account already signed in (null otherwise). For the Firebase
     * sign-in that Wave 3G adds, whose token outlives a session.
     */
    suspend fun silent(activity: Context): SignInOutcome.SignedIn? {
        if (!configured) return null
        return try {
            get(activity, authorizedOnly()) as? SignInOutcome.SignedIn
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Forgets the chosen account in Credential Manager, so the next sign-in asks again. */
    suspend fun signOut() {
        try {
            manager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing stored, or Play services is missing: there is nothing to clear.
        }
    }

    private fun authorizedOnly(): GetGoogleIdOption = GetGoogleIdOption.Builder()
        .setServerClientId(webClientId)
        .setFilterByAuthorizedAccounts(true)
        .setAutoSelectEnabled(true)
        .build()

    private suspend fun get(activity: Context, option: CredentialOption): SignInOutcome {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return accept(manager.getCredential(activity, request).credential)
    }

    private fun accept(credential: Credential): SignInOutcome {
        val google = credential is CustomCredential && (
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
            )
        if (!google) return SignInOutcome.Failed("Google didn't return an account.")
        val id = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: GoogleIdTokenParsingException) {
            return SignInOutcome.Failed("Google's answer couldn't be read. Try again.")
        }
        return SignInOutcome.SignedIn(email = id.id, name = id.displayName?.ifBlank { null }, idToken = id.idToken)
    }

    private fun failed(e: Exception): SignInOutcome = when (e) {
        is GetCredentialCancellationException -> SignInOutcome.Cancelled
        is NoCredentialException -> SignInOutcome.Failed("There's no Google account on this phone. Add one in the phone's settings.")
        is GetCredentialException -> SignInOutcome.Failed(
            e.errorMessage?.toString()?.takeIf { it.isNotBlank() }?.let { "Google sign-in didn't work: $it" } ?: "Google sign-in didn't work. Try again.",
        )
        else -> SignInOutcome.Failed(e.readable("Google sign-in didn't work. Try again."))
    }
}

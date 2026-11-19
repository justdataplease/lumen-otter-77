package com.justdataplease.boringtravel.google

import android.content.Context
import android.content.Intent
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.data.repo.Stamps
import com.justdataplease.boringtravel.data.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The owner's Google account in the app: sign-in (Credential Manager), Drive access (authorization client) and
 * the Drive REST client. Sign-in and Drive are separate steps, and either may be skipped.
 *
 * [onDriveChanged] runs after Drive is connected or disconnected (the app re-plans its daily backup there).
 * [onSignedOut] runs after a sign-out on this phone (the community signs out too); its failure never fails the
 * sign-out. A sign-in here signs in to nothing else: the community asks for its own token when it is used.
 */
class GoogleAccount(
    context: Context,
    private val settings: Settings,
    private val onDriveChanged: suspend () -> Unit,
    private val onSignedOut: suspend () -> Unit = {},
) {
    private val app = context.applicationContext
    private val credentials = CredentialSignIn(app, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    private val auth = DriveAuth(app)

    /** The app's HTTP client for Google (Drive with the owner's token, and [PublicDrive] for shared files). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val drive = RestDriveApi(
        http, auth, connected = { settings.driveConnected.first() },
        onServerTime = { header -> Stamps.httpDate(header)?.let { Stamps.observe(it) } },
        memory = DriveFolderMemory(app),
    )

    /** Link-shared Drive files of other people (shared trips, community posts), read without their sign-in. */
    val publicDrive = PublicDrive(
        http, BuildConfig.DRIVE_API_KEY, app.packageName, signingSha1(app),
        onServerTime = { header -> Stamps.httpDate(header)?.let { Stamps.observe(it) } },
    )

    /** False when this build has no Web client id: sign-in is hidden and the app stays local. */
    val signInConfigured: Boolean get() = credentials.configured

    /** True when Google wants the owner to confirm Drive access again. */
    val needsReconnect: StateFlow<Boolean> get() = auth.needsReconnect

    /** The account Drive is connected with, when known. */
    val driveAccount: String? get() = auth.account

    /** Signs in and keeps the account's email and name. [activity]: an Activity context for Google's sheet. */
    suspend fun signIn(activity: Context): SignInOutcome {
        val outcome = credentials.signIn(activity)
        if (outcome is SignInOutcome.SignedIn) {
            settings.setAccount(outcome.email, outcome.name)
            if (auth.account != outcome.email) {
                auth.dropToken()
                auth.account = outcome.email
            }
        }
        return outcome
    }

    /** A fresh Google ID token without asking, when the owner signed in before (null otherwise). For Firebase Auth. */
    suspend fun freshIdToken(activity: Context): String? = credentials.silent(activity)?.idToken

    /** Signs out on this phone: the account is forgotten and Drive stops until the owner connects again. */
    suspend fun signOut() {
        credentials.signOut()
        auth.forget()
        drive.forgetFolders()
        settings.setAccount(null, null)
        settings.setDriveConnected(false)
        onDriveChanged()
        runCatching { onSignedOut() }
    }

    /**
     * Removes the Google account from BoringTravel: Drive access is revoked with Google, then the account is signed
     * out and forgotten on this phone. There is no BoringTravel account or server copy to delete; trips stay on the
     * phone, and backups already in the user's Drive stay in its BoringTravel folder until they delete it there.
     */
    suspend fun removeAccount() {
        auth.disconnect()
        signOut()
    }

    /** Starts the Drive connect; [DriveStep.NeedsConsent] must be launched and its result passed to [finishDriveConsent]. */
    suspend fun connectDrive(): DriveStep = finish(auth.connect())

    suspend fun finishDriveConsent(resultCode: Int, data: Intent?): DriveStep = finish(auth.fromConsent(resultCode, data))

    private suspend fun finish(step: DriveStep): DriveStep {
        if (step != DriveStep.Connected) return step
        settings.setDriveConnected(true)
        // Without sign-in Google picked the account; remember it so later silent renewals ask for the same one.
        if (auth.account == null) runCatching { drive.accountEmail() }.getOrNull()?.let { auth.account = it }
        onDriveChanged()
        return step
    }

    /** Revokes Drive access with Google and stops backups. Files already in Drive stay there. */
    suspend fun disconnectDrive() {
        auth.disconnect()
        auth.account = settings.accountEmail.first()
        drive.forgetFolders()
        settings.setDriveConnected(false)
        onDriveChanged()
    }
}

/** The SHA-1 of the app's signing certificate (upper-case hex), for Google's Android-restricted API keys. */
internal fun signingSha1(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
    info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { signer ->
        java.security.MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
    }
}.getOrNull()

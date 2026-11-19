@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.justdataplease.boringtravel.google.AppleCredential
import com.justdataplease.boringtravel.google.AppleSignIn
import com.justdataplease.boringtravel.google.AppleSignInException
import com.justdataplease.boringtravel.google.AuthBrowser
import com.justdataplease.boringtravel.google.AuthBrowserException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDButton
import platform.AuthenticationServices.ASAuthorizationAppleIDButtonStyle
import platform.AuthenticationServices.ASAuthorizationAppleIDButtonTypeSignIn
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASAuthorizationScopeFullName
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.lowPowerModeEnabled
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * The account's iPhone side (round 23), all reachable from Kotlin, so no Swift: Google's sign-in page in
 * AuthenticationServices' ASWebAuthenticationSession (it shares Safari's Google sign-in and catches the redirect to the
 * client's own scheme itself: nothing in Info.plist), Sign in with Apple's sheet (ASAuthorizationController with the
 * Apple ID provider) and Apple's own button (ASAuthorizationAppleIDButton, drawn in a UIKitView), the daily backup's
 * background task (BackgroundTasks' BGProcessingTaskRequest with network), Low Power Mode (NSProcessInfo) and the
 * secure random bytes PKCE, the state and Apple's nonce are made from (Security's SecRandomCopyBytes). Every Apple call
 * here is iOS 13 or older (the app needs iOS 16). Only type-checked on Windows; the Mac compiles and links it.
 */

/** The window Apple's sheets present over: the app's key window. */
private fun anchor(): ASPresentationAnchor = UIApplication.sharedApplication.keyWindow ?: platform.UIKit.UIWindow()

/**
 * [AuthBrowser] on iPhone: ASWebAuthenticationSession on Google's page, not ephemeral (Safari's Google sign-in is
 * shared, so an account used before goes straight through). iOS asks first "BoringTravel wants to use google.com to
 * sign in" (its own words, which no app can leave out). Closed by the traveller: null.
 */
class IosAuthBrowser : AuthBrowser {
    /** The session and its window's provider, kept while the page is open (the session holds its provider weakly). */
    private var open: Pair<ASWebAuthenticationSession, AnchorProvider>? = null

    override suspend fun open(url: String, callbackScheme: String): String? = suspendCancellableCoroutine { cont ->
        val address = NSURL.URLWithString(url)
        if (address == null) {
            cont.resumeWithException(AuthBrowserException("Google's page couldn't open."))
            return@suspendCancellableCoroutine
        }
        val provider = AnchorProvider()
        val session = ASWebAuthenticationSession(uRL = address, callbackURLScheme = callbackScheme) { callback: NSURL?, error: NSError? ->
            open = null
            if (!cont.isActive) return@ASWebAuthenticationSession
            when {
                callback != null -> cont.resume(callback.absoluteString)
                error == null || error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin -> cont.resume(null)
                else -> cont.resumeWithException(AuthBrowserException(error.localizedDescription))
            }
        }
        session.presentationContextProvider = provider
        session.prefersEphemeralWebBrowserSession = false
        open = session to provider
        cont.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { session.cancel() } }
        if (!session.start()) {
            open = null
            if (cont.isActive) cont.resumeWithException(AuthBrowserException("Google's page couldn't open."))
        }
    }

    private class AnchorProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor = anchor()
    }
}

/**
 * [AppleSignIn] on iPhone: ASAuthorizationController with the Apple ID provider, asking for the full name and the
 * email, with the hashed nonce Firebase checks. Closed by the traveller (ASAuthorizationErrorCanceled): null. Without
 * the Sign in with Apple capability (an unsigned build) Apple answers with an error, read as a failure.
 */
class IosAppleSignIn : AppleSignIn {
    /** The controller and its delegate, kept while Apple's sheet is up (the controller holds its delegate weakly). */
    private var open: Pair<ASAuthorizationController, AppleDelegate>? = null

    override suspend fun signIn(hashedNonce: String): AppleCredential? = suspendCancellableCoroutine { cont ->
        val request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
        request.nonce = hashedNonce
        val controller = ASAuthorizationController(authorizationRequests = listOf(request))
        val delegate = AppleDelegate { result ->
            open = null
            if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
        controller.delegate = delegate
        controller.presentationContextProvider = delegate
        open = controller to delegate
        controller.performRequests()
    }

    private class AppleDelegate(private val done: (Result<AppleCredential?>) -> Unit) :
        NSObject(), ASAuthorizationControllerDelegateProtocol, ASAuthorizationControllerPresentationContextProvidingProtocol {
        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
            val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
            val token = credential?.identityToken?.text()
            if (credential == null || token == null) {
                done(Result.failure(AppleSignInException("Apple didn't return an account.")))
                return
            }
            val name = credential.fullName?.let { n -> listOfNotNull(n.givenName, n.familyName).joinToString(" ").trim().ifEmpty { null } }
            done(Result.success(AppleCredential(credential.user, token, credential.authorizationCode?.text(), credential.email, name)))
        }

        override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
            if (didCompleteWithError.code == ASAuthorizationErrorCanceled) done(Result.success(null))
            else done(Result.failure(AppleSignInException(didCompleteWithError.localizedDescription)))
        }

        override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor = anchor()
    }
}

/** NSData of UTF-8 text as a String. */
private fun NSData.text(): String? {
    val n = length.toInt()
    if (n == 0) return null
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out.decodeToString()
}

/**
 * Apple's own Sign in with Apple button (ASAuthorizationAppleIDButton, Sign In type), white on the dark theme and black
 * on the light ones, as wide as its place. Compose takes the tap over it (the button is only drawn), so VoiceOver hears
 * it as one button (Street Gym's IosAppleButton).
 */
@OptIn(ExperimentalComposeUiApi::class)
object IosAppleButton : AppleButton {
    @Composable
    override fun Draw(dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.fillMaxWidth().height(48.dp)) {
            key(dark) {
                UIKitView(
                    factory = {
                        ASAuthorizationAppleIDButton(
                            authorizationButtonType = ASAuthorizationAppleIDButtonTypeSignIn,
                            authorizationButtonStyle = if (dark) ASAuthorizationAppleIDButtonStyle.ASAuthorizationAppleIDButtonStyleWhite else ASAuthorizationAppleIDButtonStyle.ASAuthorizationAppleIDButtonStyleBlack,
                        )
                    },
                    modifier = Modifier.matchParentSize(),
                    properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
                )
            }
            Box(Modifier.matchParentSize().clickable(onClick = onClick).semantics {
                contentDescription = APPLE_LABEL
                role = Role.Button
            })
        }
    }
}

/** [count] bytes from the phone's secure random generator (PKCE's verifier, the state, Apple's nonce). */
fun secureRandom(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count > 0) out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
    return out
}

/** Low Power Mode: the daily backup waits (Android's WorkManager waits for a battery that isn't low). */
fun lowPowerMode(): Boolean = NSProcessInfo.processInfo.lowPowerModeEnabled

/**
 * The daily backup's background task (Android's WorkManager job "drive-backup-daily"): a BGProcessingTask with network,
 * from 22:00, run when iOS gives the app time (best effort: iOS decides by the phone's use, charge and connection); a
 * failed run asks again 30 minutes later, up to three times (Android's backoff), then the next night.
 */
internal object IosBackupTask : BackupPlanner {
    const val DAILY = "com.justdataplease.boringtravel.backup.daily"
    private var registered = false

    /**
     * Before the app has finished starting (iOS's rule): [work] runs the backup and says how it went; [next] plans the
     * next night's as the settings say (a run that should be tried again is asked for 30 minutes later instead).
     */
    fun register(work: suspend () -> DailyResult, next: () -> Unit) {
        if (registered) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(DAILY, usingQueue = null) { task -> task?.let { run(it, work, next) } }
    }

    private fun run(task: BGTask, work: suspend () -> DailyResult, next: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            val job = MainScope().launch {
                var result = DailyResult.Done
                try {
                    result = work()
                } finally {
                    if (result == DailyResult.Retry) planDailyBackup(com.justdataplease.boringtravel.time.nowMs() + RETRY_MS) else next()
                    task.setTaskCompletedWithSuccess(result != DailyResult.Retry)
                }
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    override fun planDailyBackup(atMs: Long?) {
        val scheduler = BGTaskScheduler.sharedScheduler
        scheduler.cancelTaskRequestWithIdentifier(DAILY)
        if (atMs == null) return
        // The Simulator has no background tasks: the request is refused there, quietly.
        val request = BGProcessingTaskRequest(DAILY)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        scheduler.submitTaskRequest(request, null)
    }

    private const val RETRY_MS = 30 * 60 * 1000L
}

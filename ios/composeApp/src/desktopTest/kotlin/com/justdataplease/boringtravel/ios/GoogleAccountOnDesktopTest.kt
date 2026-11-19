package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.google.AppleOutcome
import com.justdataplease.boringtravel.google.DriveJson
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.google.FirebaseAuthRest
import com.justdataplease.boringtravel.google.GoogleAccount
import com.justdataplease.boringtravel.google.GoogleConnect
import com.justdataplease.boringtravel.google.GoogleOAuth
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account on the fakes (google/GoogleAccount.kt, GoogleOAuth.kt, AppleSignIn.kt): Google's page asked for exactly
 * the Android app's scopes with PKCE (S256) and a state, on the client's own scheme; signed in with Drive in one tap; a
 * no to Drive still signing in; a closed page or a refusal changing nothing; Drive connected alone; a token renewed and,
 * when Google refuses the refresh, Drive wanting the owner again; Disconnect giving the grant back to Google; Sign out
 * keeping it; Delete account revoking it; a build without the client saying so; Sign in with Apple into Firebase with
 * the nonce's hash, and its deletion revoking Apple's token with a fresh code (Apple 5.1.1(v)).
 */
class GoogleAccountOnDesktopTest {
    private fun setup(clientId: String = FakeGoogle.CLIENT, appleOn: Boolean = true): Triple<FakeGoogle, AppSettings, GoogleAccount> {
        val google = FakeGoogle()
        val settings = AppSettings.ready()
        return Triple(google, settings, google.account(settings, MemoryMediaFiles(), clientId, appleOn))
    }

    @Test fun pkceIsRfc7636s() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = GoogleOAuth.verifier(ByteArray(48) { it.toByte() })
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it in "-._~" }, v)
        assertEquals("com.googleusercontent.apps.test-client", GoogleOAuth.scheme(FakeGoogle.CLIENT))
        assertEquals("com.googleusercontent.apps.test-client:/oauth2redirect", GoogleOAuth.redirectUri(FakeGoogle.CLIENT))
    }

    @Test fun oneTapSignsInAndConnectsDriveWithAndroidsScopes() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        var changed = 0
        account.onDriveChanged = { changed++ }
        assertEquals(GoogleConnect.Connected, account.signIn())
        val page = WebUrl.of(google.browser.asked.single())!!
        assertEquals("accounts.google.com", page.host)
        assertEquals(FakeGoogle.CLIENT, page.queryParameter("client_id"))
        assertEquals("code", page.queryParameter("response_type"))
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        assertEquals("true", page.queryParameter("include_granted_scopes"))
        assertEquals(32, page.queryParameter("state")!!.length)
        // Exactly the Android app's: the account (Credential Manager's ID token) and Drive's two non-sensitive scopes.
        assertEquals(
            listOf("openid", "email", "profile", "https://www.googleapis.com/auth/drive.file", "https://www.googleapis.com/auth/drive.appdata"),
            page.queryParameter("scope")!!.split(' '),
        )
        // The code was changed for tokens with the verifier whose hash the page was given.
        val exchange = google.cloud.calls.single { it.host == "oauth2.googleapis.com" }
        val verifier = Regex("code_verifier=([^&]+)").find(exchange.text)!!.groupValues[1]
        assertEquals(page.queryParameter("code_challenge"), GoogleOAuth.challenge(java.net.URLDecoder.decode(verifier, Charsets.UTF_8)))
        assertTrue("redirect_uri=com.googleusercontent.apps.test-client%3A%2Foauth2redirect" in exchange.text, exchange.text)
        assertFalse("client_secret" in exchange.text)
        // Signed in as the account, Drive connected with it, the refresh token in the Keychain only.
        assertEquals("traveller@example.com", settings.accountEmail)
        assertEquals("Traveller", settings.accountName)
        assertEquals(AppSettings.PROVIDER_GOOGLE, settings.accountProvider)
        assertTrue(settings.driveConnected)
        assertEquals("traveller@example.com", settings.driveAccount)
        assertNotNull(google.keys.get(GoogleAccount.REFRESH))
        assertEquals(1, changed)
        // Drive answers with the token Google gave.
        assertEquals("traveller@example.com", account.drive.accountEmail())
    }

    @Test fun aNoToDriveStillSignsIn() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        google.browser.mode = FakeAuthBrowser.Mode.NoDrive
        assertEquals(GoogleConnect.NoDrive(null), account.signIn())
        assertEquals("traveller@example.com", settings.accountEmail)
        assertFalse(settings.driveConnected)
        // Nothing reaches Drive without it.
        assertFailsWith<DriveNotConnectedException> { account.drive.folder(listOf("BoringTravel")) }
    }

    @Test fun aClosedPageOrANoChangesNothing() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        google.browser.mode = FakeAuthBrowser.Mode.Closed
        assertEquals(GoogleConnect.NotSignedIn(null), account.signIn())
        google.browser.mode = FakeAuthBrowser.Mode.Denied
        assertEquals(GoogleConnect.NotSignedIn(null), account.signIn())
        assertEquals(DriveStep.Cancelled, account.connectDrive())
        google.browser.mode = FakeAuthBrowser.Mode.Broken
        assertEquals(GoogleConnect.NotSignedIn(GoogleOAuth.GOOGLE_FAILED), account.signIn())
        assertNull(settings.accountEmail)
        assertFalse(settings.driveConnected)
        assertNull(google.keys.get(GoogleAccount.REFRESH))
        // Offline when the code is changed: Android's words for no connection to Google.
        google.browser.mode = FakeAuthBrowser.Mode.Accept
        google.cloud.route = { c -> if (c.host == "oauth2.googleapis.com") throw NetException("offline") else null }
        assertEquals(DriveStep.Failed(GoogleAccount.NO_CONNECTION), account.connectDrive())
        // A client Google doesn't know.
        google.cloud.route = { c -> if (c.host == "oauth2.googleapis.com") NetAnswer(401, c.url, emptyMap(), """{"error":"invalid_client"}""".encodeToByteArray()) else null }
        assertEquals(GoogleConnect.NotSignedIn(GoogleOAuth.NOT_REGISTERED), account.signIn())
    }

    @Test fun driveAloneAndItsTokenRenewedThenWantingTheOwnerAgain() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        assertEquals(DriveStep.Connected, account.connectDrive())
        // Drive only: no sign-in asked, the account Drive is with read from Drive.
        assertEquals("https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/drive.appdata", WebUrl.of(google.browser.asked.single())!!.queryParameter("scope"))
        assertNull(settings.accountEmail)
        assertEquals("traveller@example.com", settings.driveAccount)
        // An hour later Google answers 401: one fresh token from the refresh token, and the call again.
        google.cloud.expireAccess()
        assertEquals("traveller@example.com", account.drive.accountEmail())
        assertTrue(google.cloud.calls.any { it.host == "oauth2.googleapis.com" && "grant_type=refresh_token" in it.text })
        assertFalse(account.needsReconnect)
        // Google refuses the refresh (access removed in the account): Drive wants the owner again.
        google.cloud.expireAccess()
        google.cloud.refreshRevoked = true
        assertFailsWith<DriveNotConnectedException> { account.drive.accountEmail() }
        assertTrue(account.needsReconnect)
        assertTrue(settings.driveConnected)
        // Connect again: Google's page with the account suggested; the token works again.
        google.cloud.refreshRevoked = false
        assertEquals(DriveStep.Connected, account.connectDrive())
        assertEquals("traveller@example.com", WebUrl.of(google.browser.asked.last())!!.queryParameter("login_hint"))
        assertFalse(account.needsReconnect)
    }

    @Test fun driveUntickedIsNotConnected() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        google.browser.mode = FakeAuthBrowser.Mode.NoDrive
        assertEquals(DriveStep.Failed("Google Drive access wasn't given."), account.connectDrive())
        assertFalse(settings.driveConnected)
    }

    @Test fun disconnectGivesTheGrantBackAndSignOutForgetsItHere() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        account.signIn()
        val refresh = google.keys.get(GoogleAccount.REFRESH)!!
        account.disconnectDrive()
        assertEquals(listOf(refresh), google.cloud.revoked)
        assertFalse(settings.driveConnected)
        // Still signed in, as Android: Drive disconnected only.
        assertEquals("traveller@example.com", settings.accountEmail)
        assertNull(google.keys.get(GoogleAccount.REFRESH))
        // Sign in again, then sign out: forgotten here, Google keeps the grant (nothing revoked).
        account.signIn()
        account.signOut()
        assertEquals(1, google.cloud.revoked.size)
        assertNull(settings.accountEmail)
        assertNull(settings.accountProvider)
        assertFalse(settings.driveConnected)
        assertNull(google.keys.get(GoogleAccount.REFRESH))
        // Remove (Delete account): revoked, then signed out.
        account.signIn()
        account.removeAccount()
        assertEquals(2, google.cloud.revoked.size)
        assertFalse(settings.signedIn)
    }

    @Test fun aBuildWithoutTheClientHasNoGoogle() = runBlocking<Unit> {
        val (google, _, account) = setup(clientId = "", appleOn = false)
        assertFalse(account.signInConfigured)
        assertFalse(account.appleConfigured)
        assertEquals(GoogleConnect.NotSignedIn("Google sign-in isn't set up in this build."), account.signIn())
        assertEquals(DriveStep.Failed("Google sign-in isn't set up in this build."), account.connectDrive())
        assertTrue(google.browser.asked.isEmpty())
        assertTrue(google.cloud.calls.isEmpty())
    }

    @Test fun signInWithAppleSignsInToFirebaseWithTheNonce() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        assertTrue(account.appleConfigured)
        assertEquals(AppleOutcome.SignedIn, account.signInWithApple())
        // Apple got the nonce's hash, Firebase the nonce itself.
        val call = google.cloud.calls.single { it.path == "/v1/accounts:signInWithIdp" }
        assertEquals("test-key", call.q("key"))
        assertEquals("com.justdataplease.boringtravel", call.header("X-Ios-Bundle-Identifier"))
        val nonce = java.net.URLDecoder.decode(Regex("nonce=([^&\"]+)").find(call.text)!!.groupValues[1], Charsets.UTF_8)
        assertEquals(google.apple.nonces.single(), MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals(FirebaseAuthRest.hashed(nonce), google.apple.nonces.single())
        assertEquals(AppSettings.PROVIDER_APPLE, settings.accountProvider)
        assertEquals("apple-traveller@example.com", settings.accountEmail)
        assertEquals("Traveller", settings.accountName)
        assertFalse(settings.driveConnected)
        assertNotNull(google.keys.get(GoogleAccount.APPLE_REFRESH))
        // Closed: nothing changes.
        account.signOut()
        google.apple.closes = true
        assertEquals(AppleOutcome.Cancelled, account.signInWithApple())
        assertFalse(settings.signedIn)
    }

    @Test fun deletingAnAppleAccountRevokesApplesTokenWithAFreshCode() = runBlocking<Unit> {
        val (google, settings, account) = setup()
        account.signInWithApple()
        // Apple's sheet closed at the deletion: nothing deleted, and it says so.
        google.apple.closes = true
        assertEquals(GoogleAccount.APPLE_CLOSED, account.deleteAppleAccount())
        assertTrue(google.cloud.appleRevoked.isEmpty())
        assertEquals(AppSettings.PROVIDER_APPLE, settings.accountProvider)
        // Again: a fresh code from Apple's sheet, Firebase revokes Apple's token with it, then the account goes.
        google.apple.closes = false
        assertNull(account.deleteAppleAccount())
        assertEquals(listOf("apple-code-3"), google.cloud.appleRevoked)
        assertEquals(1, google.cloud.firebaseDeleted.size)
        assertNull(google.keys.get(GoogleAccount.APPLE_REFRESH))
        // Offline: nothing deleted.
        val (other, _, second) = setup()
        second.signInWithApple()
        other.cloud.connected = false
        assertEquals(GoogleAccount.DELETE_OFFLINE, second.deleteAppleAccount())
        assertTrue(other.cloud.firebaseDeleted.isEmpty())
    }

    @Test fun appleIsOfferedOnlyWithTheBuildsSwitchAndFirebase() {
        assertFalse(setup(appleOn = false).third.appleConfigured)
        val settings = AppSettings.ready()
        val noFirebase = com.justdataplease.boringtravel.google.GoogleAccount(
            settings, FakeGoogleCloud(), com.justdataplease.boringtravel.ai.own.MemoryKeyValues(), MemoryMediaFiles(), FakeAuthBrowser(FakeGoogleCloud()), FakeAppleSignIn(),
            clientId = FakeGoogle.CLIENT, appleOn = true, firebase = com.justdataplease.boringtravel.firebase.FirebaseSetup.Config("", "", ""),
        )
        assertFalse(noFirebase.appleConfigured)
        assertTrue(DriveJson.SCOPES.all { it.startsWith("https://www.googleapis.com/auth/drive.") })
    }
}

package com.justdataplease.boringtravel.google

import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.Sha256
import com.justdataplease.boringtravel.ios.call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Sign in with Apple (new on iPhone: Apple's rule 4.8 asks for it beside Google, since the Google account also signs in
 * to the community). Apple's sheet gives the account's identity token and a one-time code; the account itself is the
 * owner's Firebase project's (Firebase Auth's Apple provider, over its REST API, as the built-in planner reaches Firebase
 * AI Logic over REST), which also revokes Apple's token when the account is deleted (Apple 5.1.1(v): Apple's revoke
 * endpoint needs the developer's private key, which Firebase holds, never the app).
 */

/** What Apple's sheet gave: the account ([user], Apple's stable id), its identity token (a JWT), the one-time code. */
data class AppleCredential(
    val user: String,
    val identityToken: String,
    val authorizationCode: String?,
    /** Only on the first sign-in with this app (Apple's rule), and only when the traveller shares it. */
    val email: String?,
    val name: String?,
)

/**
 * Apple's sign-in sheet (iPhone: AuthenticationServices' ASAuthorizationController with the Apple ID provider, asking
 * for the name and email). [signIn] asks with [hashedNonce] (SHA-256 of the nonce Firebase is then given) and returns
 * the credential, or null when the traveller closed the sheet; it throws [AppleSignInException] when it failed.
 */
interface AppleSignIn {
    suspend fun signIn(hashedNonce: String): AppleCredential?
}

class AppleSignInException(message: String) : Exception(message)

/**
 * The account in the owner's Firebase project: its id, a token for an hour, the refresh token, the email Apple (or
 * Google) shared, and the name Google gave (the community's account).
 */
data class FirebaseUser(val localId: String, val idToken: String, val refreshToken: String, val email: String?, val name: String? = null)

/**
 * Firebase Auth's REST API (Identity Toolkit and Secure Token) for the Apple account, with the build's web API key
 * ([FirebaseSetup]) and, as the built-in planner sends it, the app's bundle id (an API key kept to the iPhone app needs it).
 */
class FirebaseAuthRest(
    private val net: Net,
    private val config: FirebaseSetup.Config,
    private val bundleId: String? = null,
    private val identity: String = "https://identitytoolkit.googleapis.com",
    private val secureToken: String = "https://securetoken.googleapis.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    val configured: Boolean get() = config.isComplete

    /** Signs in to Firebase with Apple's identity token and the raw nonce whose hash Apple was given. */
    suspend fun signInWithApple(identityToken: String, rawNonce: String): FirebaseUser {
        val postBody = GoogleOAuth.form("id_token" to identityToken, "providerId" to "apple.com", "nonce" to rawNonce)
        val body = buildJsonObject {
            put("postBody", postBody)
            put("requestUri", "http://localhost")
            put("returnIdpCredential", true)
            put("returnSecureToken", true)
        }.toString()
        val o = post("$identity/v1/accounts:signInWithIdp", body)
        return FirebaseUser(
            localId = o.text("localId") ?: throw IOException(APPLE_FAILED),
            idToken = o.text("idToken") ?: throw IOException(APPLE_FAILED),
            refreshToken = o.text("refreshToken") ?: throw IOException(APPLE_FAILED),
            email = o.text("email"),
        )
    }

    /**
     * Signs in to Firebase with a Google ID token (round 24: the community's account, Android's Community.signInWith with
     * GoogleAuthProvider). Firebase takes a token whose audience is an OAuth client of its own Google Cloud project, or one
     * it allows: the owner's iPhone client.
     */
    suspend fun signInWithGoogle(googleIdToken: String): FirebaseUser {
        val body = buildJsonObject {
            put("postBody", GoogleOAuth.form("id_token" to googleIdToken, "providerId" to "google.com"))
            put("requestUri", "http://localhost")
            put("returnIdpCredential", true)
            put("returnSecureToken", true)
        }.toString()
        val o = post("$identity/v1/accounts:signInWithIdp", body)
        return FirebaseUser(
            localId = o.text("localId") ?: throw IOException(COMMUNITY_FAILED),
            idToken = o.text("idToken") ?: throw IOException(COMMUNITY_FAILED),
            refreshToken = o.text("refreshToken") ?: throw IOException(COMMUNITY_FAILED),
            email = o.text("email"),
            name = o.text("displayName"),
        )
    }

    /** The account behind [refreshToken] as Secure Token gives it again: its id, a fresh ID token, the refresh token to keep. */
    suspend fun refreshUser(refreshToken: String): FirebaseUser {
        val answer = net.call(
            NetCall(
                "POST", "$secureToken/v1/token?key=${config.apiKey}", headers(),
                GoogleOAuth.form("grant_type" to "refresh_token", "refresh_token" to refreshToken).encodeToByteArray(),
                "application/x-www-form-urlencoded", timeoutMs = 30_000,
            ),
        )
        val o = parse(answer.text())
        if (!answer.ok) throw FirebaseAuthException(answer.code, errorOf(o))
        return FirebaseUser(
            localId = o.text("user_id") ?: throw IOException(COMMUNITY_FAILED),
            idToken = o.text("id_token") ?: throw IOException(COMMUNITY_FAILED),
            refreshToken = o.text("refresh_token") ?: refreshToken,
            email = null,
        )
    }

    /** A fresh ID token for the account behind [refreshToken] (and the refresh token to keep). */
    suspend fun refresh(refreshToken: String): Pair<String, String> {
        val answer = net.call(
            NetCall(
                "POST", "$secureToken/v1/token?key=${config.apiKey}", headers(),
                GoogleOAuth.form("grant_type" to "refresh_token", "refresh_token" to refreshToken).encodeToByteArray(),
                "application/x-www-form-urlencoded", timeoutMs = 30_000,
            ),
        )
        val o = parse(answer.text())
        if (!answer.ok) throw FirebaseAuthException(answer.code, errorOf(o))
        return (o.text("id_token") ?: throw IOException(APPLE_FAILED)) to (o.text("refresh_token") ?: refreshToken)
    }

    /** Revokes Apple's token for the signed-in Apple account with Apple's one-time [code] (Firebase exchanges it and revokes). */
    suspend fun revokeApple(idToken: String, code: String) {
        val body = buildJsonObject {
            put("idToken", idToken)
            put("providerId", "apple.com")
            put("tokenType", "CODE")
            put("token", code)
        }.toString()
        post("$identity/v2/accounts:revokeToken", body)
    }

    /** Deletes the Firebase account [idToken] belongs to. */
    suspend fun delete(idToken: String) {
        post("$identity/v1/accounts:delete", buildJsonObject { put("idToken", idToken) }.toString())
    }

    private suspend fun post(address: String, body: String): JsonObject {
        val answer = net.call(NetCall("POST", "$address?key=${config.apiKey}", headers(), body.encodeToByteArray(), "application/json; charset=utf-8", timeoutMs = 30_000))
        val o = parse(answer.text())
        if (!answer.ok) throw FirebaseAuthException(answer.code, errorOf(o))
        return o
    }

    private fun headers(): Map<String, String> = buildMap {
        bundleId?.let { put("X-Ios-Bundle-Identifier", it) }
    }

    private fun parse(text: String): JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun errorOf(o: JsonObject): String = ((o["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }

    companion object {
        const val APPLE_FAILED = "Sign in with Apple didn't work. Try again."

        /** Android's Community.signInWith failure. */
        const val COMMUNITY_FAILED = "Couldn't sign in to the community. Try again."

        /** A nonce of 32 URL-safe characters from [random] bytes (Firebase's Sign in with Apple guide). */
        fun nonce(random: ByteArray): String {
            val set = "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
            return random.take(32).joinToString("") { set[(it.toInt() and 0xFF) % set.length].toString() }
        }

        /** What Apple's request is given: the nonce's SHA-256 as lowercase hex. */
        fun hashed(nonce: String): String = Sha256.hex(nonce.encodeToByteArray())
    }
}

/** Firebase Auth refused: its HTTP [code] and its message ("CREDENTIAL_TOO_OLD_LOGIN_AGAIN", "OPERATION_NOT_ALLOWED"…). */
class FirebaseAuthException(val code: Int, val reason: String) : IOException("Firebase Auth refused ($code): $reason")

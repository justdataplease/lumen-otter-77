package com.justdataplease.boringtravel.google

import com.justdataplease.boringtravel.ios.Sha256
import com.justdataplease.boringtravel.ios.UrlCodec
import com.justdataplease.boringtravel.ios.WebUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The phone's sign-in sheet for Google's own sign-in page (iPhone: AuthenticationServices' ASWebAuthenticationSession,
 * which shares Safari's Google sign-in, so an account used before goes straight through). [open] shows [url] and
 * returns the address Google sent the sheet back to (its scheme [callbackScheme]), or null when the traveller closed it;
 * it throws [AuthBrowserException] when the sheet couldn't open.
 */
interface AuthBrowser {
    suspend fun open(url: String, callbackScheme: String): String?
}

class AuthBrowserException(message: String) : Exception(message)

/** A phone without a sign-in sheet (previews): nothing opens. */
object NoAuthBrowser : AuthBrowser {
    override suspend fun open(url: String, callbackScheme: String): String? = throw AuthBrowserException("No sign-in sheet on this phone.")
}

/**
 * Google's OAuth 2.0 for installed apps, as an iPhone app signs in without Google's SDK: the authorization code flow
 * with PKCE (RFC 7636, S256) on the owner's OAuth client of type iOS ([AccountIds.GOOGLE_IOS_CLIENT_ID]), its redirect
 * the reversed client id as a custom scheme (Google gives every iOS client one), no client secret. The scopes are the
 * Android app's: sign-in (openid, email, profile: Android's Credential Manager's Google ID token) and Drive's
 * `drive.file` and `drive.appdata` (Android's DriveJson.SCOPES), asked in one page with `include_granted_scopes`, so a
 * later page adds to what was given; Google's page lets the traveller untick Drive, and the answer's scope says so.
 * Plain Kotlin, tested.
 */
@OptIn(ExperimentalEncodingApi::class)
object GoogleOAuth {
    const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN = "https://oauth2.googleapis.com/token"
    const val REVOKE = "https://oauth2.googleapis.com/revoke"

    /** Who the account is: the Google ID token's email and name (Android: Credential Manager's sign-in). */
    val SIGN_IN = listOf("openid", "email", "profile")

    private val json = Json { ignoreUnknownKeys = true }

    /** "123-abc.apps.googleusercontent.com" read backwards: "com.googleusercontent.apps.123-abc", the client's own scheme. */
    fun scheme(clientId: String): String = clientId.trim().split('.').reversed().joinToString(".")

    /** Where Google sends the sheet back: the reversed client id as a custom scheme. */
    fun redirectUri(clientId: String): String = scheme(clientId) + ":/oauth2redirect"

    /** A code verifier (RFC 7636): 64 characters of the unreserved set, from [random] bytes. */
    fun verifier(random: ByteArray): String = Base64.UrlSafe.encode(random).trimEnd('=').take(64).padEnd(43, 'A')

    /** The S256 challenge of [verifier]: BASE64URL(SHA-256(verifier)) without padding. */
    fun challenge(verifier: String): String = Base64.UrlSafe.encode(sha256(verifier.encodeToByteArray())).trimEnd('=')

    /** A state of 32 hex digits, which the answer must carry back. */
    fun state(random: ByteArray): String = random.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(32)

    /** Google's sign-in page asking for [scopes], with [loginHint] (the account the app knows) when there is one. */
    fun authUrl(clientId: String, scopes: List<String>, state: String, verifier: String, loginHint: String? = null): String {
        val params = buildList {
            add("client_id" to clientId)
            add("redirect_uri" to redirectUri(clientId))
            add("response_type" to "code")
            add("scope" to scopes.joinToString(" "))
            add("state" to state)
            add("code_challenge" to challenge(verifier))
            add("code_challenge_method" to "S256")
            add("include_granted_scopes" to "true")
            loginHint?.takeIf { it.isNotBlank() }?.let { add("login_hint" to it) }
        }
        return AUTH + "?" + params.joinToString("&") { (k, v) -> UrlCodec.queryComponent(k) + "=" + UrlCodec.queryComponent(v) }
    }

    /** What Google's page answered, from the address it sent the sheet back to. */
    sealed interface Answer {
        data class Code(val code: String, val scopes: Set<String>) : Answer

        /** The traveller said no on Google's page (access_denied). */
        data object Denied : Answer
        data class Failed(val message: String) : Answer
    }

    fun answer(redirect: String, state: String): Answer {
        val url = WebUrl.of(redirect.replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*:/"), "https://app/")) ?: return Answer.Failed(GOOGLE_FAILED)
        if (url.queryParameter("state") != state) return Answer.Failed(GOOGLE_FAILED)
        url.queryParameter("error")?.let { return if (it == "access_denied") Answer.Denied else Answer.Failed(GOOGLE_FAILED) }
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return Answer.Failed(GOOGLE_FAILED)
        return Answer.Code(code, url.queryParameter("scope").orEmpty().split(' ', '+').filter { it.isNotBlank() }.toSet())
    }

    /** The token endpoint's form for the code just given. */
    fun codeForm(clientId: String, code: String, verifier: String): String = form(
        "code" to code, "client_id" to clientId, "code_verifier" to verifier, "grant_type" to "authorization_code",
        "redirect_uri" to redirectUri(clientId),
    )

    /** The token endpoint's form for a fresh access token. */
    fun refreshForm(clientId: String, refreshToken: String): String = form(
        "client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken,
    )

    /** The revoke endpoint's form: the grant (and every token of it) given back to Google. */
    fun revokeForm(token: String): String = form("token" to token)

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> UrlCodec.queryComponent(k) + "=" + UrlCodec.queryComponent(v) }

    /** What the token endpoint gave: a token for an hour, the refresh token (on a code), the scopes, the ID token. */
    data class Tokens(val accessToken: String, val expiresInS: Long, val refreshToken: String?, val scopes: Set<String>?, val idToken: String?)

    /** The token endpoint's answer, or its error's code ("invalid_grant", "invalid_client"…). */
    sealed interface TokenAnswer {
        data class Ok(val tokens: Tokens) : TokenAnswer
        data class Error(val error: String) : TokenAnswer
    }

    fun tokens(code: Int, body: String): TokenAnswer {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return TokenAnswer.Error(if (code in 200..299) "bad_answer" else "http_$code")
        if (code !in 200..299 || o["access_token"] == null) return TokenAnswer.Error(o.text("error") ?: "http_$code")
        return TokenAnswer.Ok(
            Tokens(
                accessToken = o.text("access_token")!!,
                expiresInS = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L,
                refreshToken = o.text("refresh_token"),
                scopes = o.text("scope")?.split(' ')?.filter { it.isNotBlank() }?.toSet(),
                idToken = o.text("id_token"),
            ),
        )
    }

    /** What an ID token (a JWT straight from Google's token endpoint over TLS) says of the account. */
    data class Who(val email: String?, val name: String?, val subject: String?)

    fun who(idToken: String?): Who {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return Who(null, null, null)
        val o = runCatching {
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()).jsonObject
        }.getOrNull() ?: return Who(null, null, null)
        return Who(o.text("email"), o.text("name")?.ifBlank { null }, o.text("sub"))
    }

    /** "Google sign-in didn't work. Try again." (the Android app's CredentialSignIn). */
    const val GOOGLE_FAILED = "Google sign-in didn't work. Try again."

    /** The client id isn't one Google knows (Android's DEVELOPER_ERROR, for the iPhone's client). */
    const val NOT_REGISTERED = "This copy of BoringTravel isn't registered with Google yet. Its iPhone OAuth client is missing in Google Cloud."

    fun sha256(bytes: ByteArray): ByteArray {
        val hex = Sha256.hex(bytes)
        return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
}

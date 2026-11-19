package com.justdataplease.boringtravel.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** A file as Drive's list and metadata calls return it (times stay RFC 3339 text, as Drive sends them). */
data class RemoteFile(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val webViewLink: String? = null,
    val modifiedTime: String? = null,
    val createdTime: String? = null,
    val size: Long? = null,
    val appProperties: Map<String, String> = emptyMap(),
    val trashed: Boolean = false,
) {
    fun toDriveFile() = DriveFile(id, name, mimeType, webViewLink, modifiedTime, size)
    val createdAtMs: Long? get() = DriveJson.epochMs(createdTime)
    val modifiedAtMs: Long? get() = DriveJson.epochMs(modifiedTime)
}

/** Drive API v3: scopes, field lists, queries, metadata bodies and response parsing. Plain Kotlin, unit-tested. */
object DriveJson {
    /** Files this app created or the owner opened with it. Non-sensitive. */
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** The app's hidden folder in the owner's Drive (the backup marker). Non-sensitive. */
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    val SCOPES = listOf(DRIVE_FILE, DRIVE_APPDATA)

    /** Parent id and `spaces` value of the hidden app folder. */
    const val APP_DATA = "appDataFolder"

    /** What an upload asks Drive to return. */
    const val FILE_FIELDS = "id,name,mimeType,webViewLink,modifiedTime,size"

    /** What lists and metadata reads ask for. */
    const val LIST_FIELDS = "id,name,mimeType,webViewLink,modifiedTime,createdTime,size,appProperties,trashed"

    private val json = Json { ignoreUnknownKeys = true }

    /** Quotes a value for a Drive query string. */
    fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** A folder named [name] directly inside [parentId] ("root" = My Drive). */
    fun folderQuery(name: String, parentId: String): String =
        "name = ${quote(name)} and mimeType = '${DriveApi.FOLDER}' and ${quote(parentId)} in parents and trashed = false"

    /** Files (not folders) directly inside [parentId]. */
    fun filesIn(parentId: String): String =
        "${quote(parentId)} in parents and mimeType != '${DriveApi.FOLDER}' and trashed = false"

    fun nameQuery(name: String): String = "name = ${quote(name)} and trashed = false"

    /** The JSON metadata part of a create or upload call. [mimeType] set to a Google type makes Drive convert. */
    fun metadata(
        name: String,
        parents: List<String> = emptyList(),
        mimeType: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): String = buildJsonObject {
        put("name", name)
        if (parents.isNotEmpty()) put("parents", JsonArray(parents.map(::JsonPrimitive)))
        if (mimeType != null) put("mimeType", mimeType)
        if (appProperties.isNotEmpty()) put("appProperties", JsonObject(appProperties.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    fun parse(body: String): JsonObject = if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body).jsonObject

    fun parseFile(o: JsonObject): RemoteFile? {
        val id = o.text("id") ?: return null
        return RemoteFile(
            id = id,
            name = o.text("name").orEmpty(),
            mimeType = o.text("mimeType").orEmpty(),
            webViewLink = o.text("webViewLink"),
            modifiedTime = o.text("modifiedTime"),
            createdTime = o.text("createdTime"),
            size = o.text("size")?.toLongOrNull(),
            appProperties = (o["appProperties"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
            trashed = (o["trashed"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** One page of `files.list`: the files and the next page's token. */
    fun parseList(o: JsonObject): Pair<List<RemoteFile>, String?> {
        val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseFile) }
        return files to o.text("nextPageToken")
    }

    /** The signed-in Drive account's email from `about.get`. */
    fun parseAboutEmail(o: JsonObject): String? = (o["user"] as? JsonObject)?.text("emailAddress")

    /** Where a resumable upload continues after a 308: the `Range` header "bytes=0-N" means N + 1. */
    fun nextOffset(range: String?): Long {
        val end = range?.substringAfter("-", "")?.trim()?.toLongOrNull() ?: return 0L
        return end + 1
    }

    fun epochMs(rfc3339: String?): Long? = rfc3339?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Drive's own reason code ("storageQuotaExceeded", "rateLimitExceeded"…), or "". */
    fun reason(body: String?): String {
        val error = runCatching { parse(body.orEmpty())["error"] as? JsonObject }.getOrNull() ?: return ""
        val first = (error["errors"] as? JsonArray)?.firstOrNull() as? JsonObject
        return first?.text("reason") ?: (error["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /** Google is busy rather than refusing: 429 and 5xx, and Drive's rate limits, which come as 403. */
    fun busy(code: Int, body: String?): Boolean =
        code == 429 || code >= 500 || (code == 403 && reason(body).contains("rateLimitExceeded", ignoreCase = true))

    /** A readable message for a failed Drive call. */
    fun errorMessage(code: Int, body: String?): String {
        val reason = reason(body)
        val message = runCatching { (parse(body.orEmpty())["error"] as? JsonObject)?.text("message") }.getOrNull().orEmpty()
        return when {
            reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" || "has not been used in project" in message ->
                "The Google Drive API isn't switched on for BoringTravel's Google Cloud project yet."
            code == 401 -> "Google Drive access has expired. Connect Drive again in Me › Backup & sync."
            reason == "storageQuotaExceeded" || reason == "quotaExceeded" -> "Your Google Drive is full. Free some space and try again."
            reason == "insufficientPermissions" || reason == "insufficientFilePermissions" || reason == "appNotAuthorizedToFile" ->
                "BoringTravel can't open this file in Google Drive."
            code == 404 -> "This file is no longer in Google Drive."
            busy(code, body) && code < 500 -> "Google Drive is busy. Try again in a minute."
            code >= 500 -> "Google Drive had a problem on its side (error $code). Try again in a few minutes."
            message.isNotEmpty() -> message
            else -> "Google Drive returned error $code."
        }
    }

    fun error(code: Int, body: String?): DriveException =
        DriveException(code, errorMessage(code, body), busy(code, body), reason(body))

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

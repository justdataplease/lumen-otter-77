package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupMarker
import com.justdataplease.boringtravel.backup.BackupNames
import com.justdataplease.boringtravel.backup.BackupSchedule
import com.justdataplease.boringtravel.backup.BackupStamp
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.backup.BackupVideos
import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveJson
import com.justdataplease.boringtravel.google.FirebaseAuthRest
import com.justdataplease.boringtravel.google.GoogleOAuth
import com.justdataplease.boringtravel.google.Multipart
import com.justdataplease.boringtravel.google.RemoteFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 23's code on both phones (Kotlin/Native's regular expressions, text, Base64 and dates differ from Java's in
 * places): Drive's queries, metadata, answers and errors (google/DriveJson.kt, read in), a video's title cut to Drive's
 * property limit between whole code points (backup/BackupVideos.kt, read in), PKCE's challenge, Google's page address
 * and its answer, an ID token's claims, Apple's nonce hash, a multipart body, the backups' names and times, the marker
 * and stamp, and the daily backup's next 22:00.
 */
class DriveSmokeTest {
    @Test fun driveQueriesMetadataAndAnswers() {
        assertEquals("'Traveller\\'s trip'", DriveJson.quote("Traveller's trip"))
        assertEquals(
            "name = 'Backups' and mimeType = 'application/vnd.google-apps.folder' and 'abc' in parents and trashed = false",
            DriveJson.folderQuery("Backups", "abc"),
        )
        assertEquals(
            """{"name":"Trip","parents":["f1"],"mimeType":"application/vnd.google-apps.document","appProperties":{"trips":"2"}}""",
            DriveJson.metadata("Trip", listOf("f1"), DriveApi.GOOGLE_DOC, mapOf("trips" to "2")),
        )
        val (files, next) = DriveJson.parseList(
            DriveJson.parse("""{"nextPageToken":"p2","files":[{"id":"1","name":"Νάξος.zip","size":"2048","createdTime":"2026-11-05T20:00:00.000Z","appProperties":{"trips":"2"}}]}"""),
        )
        assertEquals("p2", next)
        assertEquals("Νάξος.zip", files.single().name)
        assertEquals(1793908800000L, files.single().createdAtMs)
        assertEquals(524288L, DriveJson.nextOffset("bytes=0-524287"))
        assertEquals("Your Google Drive is full. Free some space and try again.", DriveJson.error(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""").message)
        assertTrue(DriveJson.busy(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""))
    }

    @Test fun aVideosTitleIsCutBetweenWholeCodePoints() {
        val long = "Κυκλάδες 🌊 " + "ά".repeat(60)
        val cut = BackupVideos.fit(BackupVideos.TITLE, long)
        assertTrue(BackupVideos.fits(BackupVideos.TITLE, cut))
        assertTrue(cut.startsWith("Κυκλάδες 🌊"))
        // No half surrogate pair, no half Greek letter.
        assertEquals(cut, cut.encodeToByteArray().decodeToString())
        val emoji = "🌊".repeat(40)
        val c2 = BackupVideos.fit(BackupVideos.TITLE, emoji)
        assertEquals(0, c2.length % 2)
        assertEquals("Naxos ferry.mp4", BackupVideos.fileName(SavedClip("c1", "t1", "Naxos: ferry", 1, 1, 1, 1)))
    }

    @Test fun pkceGooglesPageAndItsAnswer() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val client = "test-client.apps.googleusercontent.com"
        assertEquals("com.googleusercontent.apps.test-client:/oauth2redirect", GoogleOAuth.redirectUri(client))
        val url = GoogleOAuth.authUrl(client, GoogleOAuth.SIGN_IN + DriveJson.SCOPES, "s1", "v".repeat(43), "traveller@example.com")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=test-client.apps.googleusercontent.com"), url)
        assertTrue("scope=openid%20email%20profile%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file" in url, url)
        assertTrue("code_challenge_method=S256" in url)
        val back = "com.googleusercontent.apps.test-client:/oauth2redirect?state=s1&code=4%2F0Ab&scope=email%20openid%20https://www.googleapis.com/auth/drive.file"
        val answer = GoogleOAuth.answer(back, "s1") as GoogleOAuth.Answer.Code
        assertEquals("4/0Ab", answer.code)
        assertTrue(DriveJson.DRIVE_FILE in answer.scopes)
        assertTrue(GoogleOAuth.answer(back, "other") is GoogleOAuth.Answer.Failed)
        assertEquals(GoogleOAuth.Answer.Denied, GoogleOAuth.answer("com.googleusercontent.apps.test-client:/oauth2redirect?state=s1&error=access_denied", "s1"))
    }

    @Test fun tokensClaimsAndApplesNonce() {
        val ok = GoogleOAuth.tokens(200, """{"access_token":"a","expires_in":3599,"refresh_token":"r","scope":"openid https://www.googleapis.com/auth/drive.file"}""")
        assertTrue(ok is GoogleOAuth.TokenAnswer.Ok && ok.tokens.refreshToken == "r" && DriveJson.DRIVE_FILE in ok.tokens.scopes!!)
        assertEquals(GoogleOAuth.TokenAnswer.Error("invalid_grant"), GoogleOAuth.tokens(400, """{"error":"invalid_grant"}"""))
        // {"email":"traveller@example.com","name":"Ταξιδιώτης"} as a JWT payload, Base64url without padding.
        val payload = "eyJlbWFpbCI6InRyYXZlbGxlckBleGFtcGxlLmNvbSIsIm5hbWUiOiLOpM6xzr7Ouc60zrnPjs-EzrfPgiJ9"
        val who = GoogleOAuth.who("e30.$payload.sig")
        assertEquals("traveller@example.com", who.email)
        assertEquals("Ταξιδιώτης", who.name)
        assertNull(GoogleOAuth.who("not a token").email)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FirebaseAuthRest.hashed("abc"))
        val nonce = FirebaseAuthRest.nonce(ByteArray(32) { it.toByte() })
        assertEquals(32, nonce.length)
    }

    @Test fun aVideoGoingToDriveSaysItTakesAWhile() {
        assertEquals("This video is 3 min long. It will take a while to upload.", com.justdataplease.boringtravel.ios.videoAdded(150_000, uploads = true))
        assertEquals("Videos take a while to upload.", com.justdataplease.boringtravel.ios.videoAdded(60_400, uploads = true))
        assertNull(com.justdataplease.boringtravel.ios.videoAdded(150_000, uploads = false))
    }

    @Test fun aMultipartBodyAsOkHttpWritesIt() {
        val body = Multipart.related("B", """{"name":"a"}""", byteArrayOf(1, 2, 3), "image/jpeg").decodeToString()
        assertTrue(body.startsWith("--B\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: 12\r\n\r\n{\"name\":\"a\"}\r\n--B\r\nContent-Type: image/jpeg\r\n"), body)
        assertTrue(body.endsWith("\r\n--B--\r\n"))
    }

    @Test fun backupsNamesTimesMarkerAndStamp() {
        val at = 1_793_908_800_000L
        assertEquals("BoringTravel backup 2026-11-05 20.00.zip", BackupNames.fileName(at, "UTC"))
        assertEquals(at, BackupNames.timeFromName("BoringTravel backup 2026-11-05 20.00.zip", "UTC"))
        assertNull(BackupNames.timeFromName("BoringTravel backup 2026-11-05 25.00.zip", "UTC"))
        val entry = BackupNames.entry(RemoteFile("z1", "BoringTravel backup 2026-11-05 20.00.zip", appProperties = mapOf("trips" to "1", "entries" to "2", "photos" to "3")), "UTC")
        assertEquals(BackupCounts(1, 2, 3), entry?.items)
        assertEquals(at, entry?.createdAt)
        val marker = BackupMarker(id = "z1", name = "n", createdAt = at, items = BackupCounts(1, 2, 3), appVersion = "1.21.1")
        assertEquals(marker, BackupCodec.decodeMarker(BackupCodec.encodeMarker(marker)))
        assertNull(BackupCodec.decodeMarker("""{"format":9,"id":"z1","name":"n","createdAt":1}"""))
        val stamp = BackupStamp("abc", marker)
        assertEquals(stamp, BackupCodec.decodeStamp(BackupCodec.encodeStamp(stamp)))
        assertEquals("5 Nov 2026, 20:00", BackupText.date(at, "UTC"))
        assertEquals("Yesterday, 20:00", BackupText.since(at, at + 86_400_000L, "UTC"))
        assertEquals("No backup for 4 days.", BackupText.trouble(true, true, false, at, at + 4 * 86_400_000L))
        // The next 22:00 in Athens after 18:00 UTC (20:00 there) is 20:00 UTC; after it, the next day's.
        assertEquals(at, BackupSchedule.nextAt(at - 2 * 3_600_000L, "Europe/Athens"))
        assertEquals(at + 86_400_000L, BackupSchedule.nextAt(at + 1, "Europe/Athens"))
        assertFalse(BackupSchedule.dueOnOpen(at + 3_600_000L, at + 2 * 3_600_000L, "Europe/Athens"))
    }
}

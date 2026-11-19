package com.justdataplease.boringtravel.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveJsonTest {
    @Test fun quoteEscapesQuotesAndBackslashes() {
        assertEquals("'Alexa\\'s trip'", DriveJson.quote("Alexa's trip"))
        assertEquals("'a\\\\b'", DriveJson.quote("a\\b"))
    }

    @Test fun folderQueryLooksInOneParentOnly() {
        assertEquals(
            "name = 'Backups' and mimeType = 'application/vnd.google-apps.folder' and 'abc' in parents and trashed = false",
            DriveJson.folderQuery("Backups", "abc"),
        )
        assertEquals("'abc' in parents and mimeType != 'application/vnd.google-apps.folder' and trashed = false", DriveJson.filesIn("abc"))
    }

    @Test fun metadataCarriesParentsConversionAndProperties() {
        val json = DriveJson.metadata("Trip", listOf("f1"), DriveApi.GOOGLE_DOC, mapOf("trips" to "2"))
        assertEquals(
            """{"name":"Trip","parents":["f1"],"mimeType":"application/vnd.google-apps.document","appProperties":{"trips":"2"}}""",
            json,
        )
        assertEquals("""{"name":"a \"b\""}""", DriveJson.metadata("a \"b\""))
    }

    @Test fun parsesFilesWithStringSizesAndProperties() {
        val o = DriveJson.parse(
            """{"nextPageToken":"p2","files":[
                {"id":"1","name":"BoringTravel backup 2026-11-05 22.00.zip","mimeType":"application/zip","size":"2048",
                 "createdTime":"2026-11-05T20:00:00.000Z","modifiedTime":"2026-11-05T20:00:01Z",
                 "appProperties":{"trips":"2","entries":"31"},"trashed":false},
                {"name":"no id"},
                {"id":"2","name":"x","trashed":true,"webViewLink":"https://drive.google.com/x"}
            ]}""",
        )
        val (files, next) = DriveJson.parseList(o)
        assertEquals("p2", next)
        assertEquals(2, files.size)
        val first = files[0]
        assertEquals(2048L, first.size)
        assertEquals(mapOf("trips" to "2", "entries" to "31"), first.appProperties)
        assertEquals(1793908800000L, first.createdAtMs)
        assertFalse(first.trashed)
        assertTrue(files[1].trashed)
        assertEquals("https://drive.google.com/x", files[1].toDriveFile().webViewLink)
        assertNull(DriveJson.parseList(DriveJson.parse("{}")).second)
    }

    @Test fun resumableRangeGivesTheNextOffset() {
        assertEquals(524288L, DriveJson.nextOffset("bytes=0-524287"))
        assertEquals(0L, DriveJson.nextOffset(null))
        assertEquals(0L, DriveJson.nextOffset("garbage"))
    }

    @Test fun errorsReadAsPlainMessages() {
        val quota = """{"error":{"code":403,"message":"The user's Drive storage quota has been exceeded.","errors":[{"reason":"storageQuotaExceeded"}]}}"""
        val e = DriveJson.error(403, quota)
        assertEquals("Your Google Drive is full. Free some space and try again.", e.message)
        assertFalse(e.busy)
        assertEquals("storageQuotaExceeded", e.reason)

        val rate = """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}],"message":"Rate Limit Exceeded"}}"""
        assertTrue(DriveJson.busy(403, rate))
        assertEquals("Google Drive is busy. Try again in a minute.", DriveJson.errorMessage(403, rate))

        val disabled = """{"error":{"code":403,"message":"Google Drive API has not been used in project 1 before","errors":[{"reason":"accessNotConfigured"}]}}"""
        assertEquals("The Google Drive API isn't switched on for BoringTravel's Google Cloud project yet.", DriveJson.errorMessage(403, disabled))

        assertEquals("This file is no longer in Google Drive.", DriveJson.errorMessage(404, ""))
        assertTrue(DriveJson.busy(503, null))
        assertTrue(DriveJson.errorMessage(503, "<html>").contains("error 503"))
        assertEquals("Odd thing", DriveJson.errorMessage(400, """{"error":{"message":"Odd thing"}}"""))
        assertEquals("Google Drive returned error 418.", DriveJson.errorMessage(418, "not json"))
    }

    @Test fun aboutGivesTheEmail() {
        assertEquals("a@b.c", DriveJson.parseAboutEmail(DriveJson.parse("""{"user":{"emailAddress":"a@b.c"}}""")))
        assertNull(DriveJson.parseAboutEmail(DriveJson.parse("{}")))
    }
}

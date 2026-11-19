package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.site.NetSiteWeb
import com.justdataplease.boringtravel.site.SiteReadException
import com.justdataplease.boringtravel.site.SiteReader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copy from a link's Google Doc through Drive (round 23, the Android app's DriveDocsOf): with Drive connected, a Doc
 * the app may open is read as Drive's HTML export first (with the owner's token, never the public export); without
 * Drive, the public export is asked as before.
 */
class DocsThroughDriveOnDesktopTest {
    private val docId = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd"

    private fun reader(google: FakeGoogle, drive: DriveApi?): SiteReader {
        val dir = kotlin.io.path.createTempDirectory("bt-docs").toString()
        return SiteReader(NetSiteWeb(google.cloud, "BoringTravel/1.21.1 (iPhone)"), pause = {}, drive = drive?.let(::DriveDocsOf), inlineDir = { File(dir) })
    }

    @Test fun aDocTheAppMayOpenIsReadThroughDrive() = runBlocking<Unit> {
        val google = FakeGoogle()
        val settings = AppSettings.ready()
        val account = google.account(settings, MemoryMediaFiles())
        account.connectDrive()
        google.cloud.put(
            "Naxos notes", listOf("root"),
            "<html><head><title>Naxos notes</title></head><body><h1>Day 1 · Halki</h1><p>The bus to Halki left at nine.</p></body></html>".encodeToByteArray(),
            DriveApi.GOOGLE_DOC, id = docId,
        )
        val read = reader(google, account.drive).read("https://docs.google.com/document/d/$docId/edit")
        assertTrue(read.posts.isNotEmpty())
        val text = read.posts.flatMap { it.blocks }.filterIsInstance<com.justdataplease.boringtravel.site.SiteBlock.Text>().joinToString(" ") { it.html }
        assertTrue(text.contains("The bus to Halki left at nine."), text)
        val export = google.cloud.calls.single { it.path == "/drive/v3/files/$docId/export" }
        assertEquals("text/html", export.q("mimeType"))
        assertTrue(export.header("Authorization")!!.startsWith("Bearer "))
        // Never the public export when Drive gave it.
        assertTrue(google.cloud.calls.none { it.host == "docs.google.com" })
    }

    @Test fun withoutDriveThePublicExportIsAsked() = runBlocking<Unit> {
        val google = FakeGoogle()
        val failure = runCatching { reader(google, null).read("https://docs.google.com/document/d/$docId/edit") }.exceptionOrNull()
        // The fake has no public export (404): the Android app's "not found".
        assertTrue(failure is SiteReadException, failure.toString())
        assertTrue(google.cloud.calls.any { it.host == "docs.google.com" })
        assertTrue(google.cloud.calls.none { it.host == "www.googleapis.com" })
    }
}

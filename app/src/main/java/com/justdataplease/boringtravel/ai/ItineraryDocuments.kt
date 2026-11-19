package com.justdataplease.boringtravel.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** SAF grants access to the chosen document only, including providers that export virtual Docs as plain text. */
object ItineraryDocuments {
    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw ItineraryImportException("Choose a text document with Open file.")
        val resolver = context.contentResolver
        val exported = resolver.getStreamTypes(uri, "text/plain")?.contains("text/plain") == true
        if (exported) {
            resolver.openTypedAssetFileDescriptor(uri, "text/plain", null)?.use { descriptor ->
                descriptor.createInputStream().use { ItineraryImport.read(it) { ensureActive() } }
            } ?: throw ItineraryImportException("This provider couldn't open the document as text. Export it as .txt and try again.")
        } else {
            resolver.openInputStream(uri)?.use { ItineraryImport.read(it) { ensureActive() } }
                ?: throw ItineraryImportException("This document couldn't be opened. Export it as .txt and try again.")
        }
    }
}

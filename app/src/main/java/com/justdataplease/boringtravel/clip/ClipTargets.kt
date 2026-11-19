package com.justdataplease.boringtravel.clip

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * The apps a finished clip goes straight to, with no chooser in between (owner, 2026-11-10: "very important: clip, add
 * the possibility to export to TikTok"). Each is the app's own "share a video" entry, the one the phone's share sheet
 * opens: TikTok lands in its editor, ready to post. Their packages are listed under `<queries>` in the manifest so the
 * app can tell which are installed.
 */
enum class ClipTarget(val label: String, val packages: List<String>) {
    /** The worldwide app, then the one some regions have. */
    TikTok("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
    Instagram("Instagram", listOf("com.instagram.android")),
    WhatsApp("WhatsApp", listOf("com.whatsapp"));

    /** The package of this app on the phone, or null when it isn't installed. */
    fun installed(context: Context): String? = packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    companion object {
        /** TikTok always (its button says so when the app is missing), then the others that are on the phone. */
        fun shown(context: Context): List<ClipTarget> = entries.filter { it == TikTok || it.installed(context) != null }

        /** The video [file] sent to [pkg] alone, with read access and [caption] for the apps that take one. */
        fun intent(context: Context, file: File, pkg: String, caption: String? = null): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                setPackage(pkg)
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!caption.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}

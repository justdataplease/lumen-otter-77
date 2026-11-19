package com.justdataplease.boringtravel.clip

/**
 * The apps a finished clip goes straight to from the Export sheet (the Android app's clip/ClipTargets.kt): TikTok
 * always (its button says so when the app is missing), then Instagram and WhatsApp when they are on the phone. Android
 * knows them by their packages; an iPhone by the addresses their apps open ([schemes], listed in the app's
 * LSApplicationQueriesSchemes so the phone may say whether they are there).
 */
enum class ClipTarget(val label: String, val schemes: List<String>) {
    /** The worldwide app's addresses. */
    TikTok("TikTok", listOf("tiktok", "snssdk1233")),
    Instagram("Instagram", listOf("instagram")),
    WhatsApp("WhatsApp", listOf("whatsapp"));

    companion object {
        /** TikTok always, then the others that are on the phone ([installed]). */
        fun shown(installed: (ClipTarget) -> Boolean): List<ClipTarget> = entries.filter { it == TikTok || installed(it) }

        /** What the sheet says when [target]'s app isn't on the phone (the Android app's words). */
        fun missing(target: ClipTarget): String = "${target.label} isn't on this phone. Save the video to your phone and add it there."
    }
}

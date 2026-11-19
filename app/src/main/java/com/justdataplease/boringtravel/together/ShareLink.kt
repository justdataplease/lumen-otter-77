package com.justdataplease.boringtravel.together

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The link that joins a shared trip, and the one a new member sends back. Both say the same thing: "member [memberId]
 * ([name]) keeps their copy of trip [tripId] in Drive file [fileId]". Everything sits after `#`, so the website that
 * opens the app never receives it.
 *
 * `https://justdataplease.com/apps/boringtravel/join/#v=1&t=<trip>&f=<file>&m=<member>&n=<name>&title=<trip title>`
 * (also `boringtravel://join#…`, and the same text pasted into Join a trip).
 */
data class ShareLink(
    val tripId: String,
    val fileId: String,
    val memberId: String,
    val name: String = "",
    val title: String = "",
) {
    val url: String
        get() = buildString {
            append(WEB).append("#v=1")
            append("&t=").append(tripId)
            append("&f=").append(fileId)
            append("&m=").append(memberId)
            if (name.isNotBlank()) append("&n=").append(enc(name))
            if (title.isNotBlank()) append("&title=").append(enc(title.take(MAX_TITLE)))
        }

    companion object {
        const val WEB = "https://justdataplease.com/apps/boringtravel/join/"
        const val APP = "boringtravel://join"
        const val MAX_TITLE = 80
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val FILE = Regex("[A-Za-z0-9_-]{5,200}")
        private val FOUND = Regex("""(?:https://(?:www\.)?justdataplease\.com/apps/boringtravel/join/?|boringtravel://join/?)[#?]([^\s<>"']+)""")

        /** The first join link anywhere in [text] (a chat message, a pasted line), or null. */
        fun find(text: String?): ShareLink? {
            if (text.isNullOrBlank()) return null
            return FOUND.findAll(text).firstNotNullOfOrNull { parse(it.groupValues[1]) }
        }

        /** The part after `#` (or `?`). */
        fun parse(fragment: String): ShareLink? {
            val p = fragment.trim().split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null else pair.substring(0, i) to dec(pair.substring(i + 1))
            }.toMap()
            if ((p["v"] ?: "1") != "1") return null
            val trip = p["t"]?.takeIf(ID::matches) ?: return null
            val file = p["f"]?.takeIf(FILE::matches) ?: return null
            val member = p["m"]?.takeIf(ID::matches) ?: return null
            return ShareLink(trip, file, member, ShareCodec.cleanName(p["n"].orEmpty()), clean(p["title"].orEmpty()).take(MAX_TITLE))
        }

        private fun clean(s: String) = s.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault("")
    }
}

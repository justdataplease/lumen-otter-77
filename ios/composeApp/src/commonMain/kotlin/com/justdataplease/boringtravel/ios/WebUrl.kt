package com.justdataplease.boringtravel.ios

import kotlin.math.abs

/*
 * Web addresses as the Android app reads and writes them. Its link code (links/, places/) parses with OkHttp's HttpUrl,
 * encodes with java.net.URLEncoder and URLDecoder, makes ids with java.util.UUID and numbers with String.format; this
 * is the part of each that code uses, in plain Kotlin, giving the same text for the same input (checked case for case
 * against the Android app's tests in LinksAgainstAndroidTest).
 */

/**
 * An http or https address, read the way OkHttp's HttpUrl reads one: the scheme and host in lower case, the default
 * port left out, the path and query percent-encoded where they must be (what is already encoded stays), "." and ".."
 * worked out of the path, and "/" for an empty path. Only what the app's link code asks of HttpUrl.
 */
class WebUrl private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    private val userInfo: String?,
    /** The path as written, starting with "/". */
    val encodedPath: String,
    val encodedQuery: String?,
    private val fragment: String?,
) {
    /** The path's segments as written ("/maps/place/Yasaka+Shrine" → maps, place, Yasaka+Shrine). */
    val encodedPathSegments: List<String> get() = encodedPath.removePrefix("/").split('/')

    /** The path's segments decoded. */
    val pathSegments: List<String> get() = encodedPathSegments.map { percentDecode(it, plusIsSpace = false) }

    /** The first value of query parameter [name], decoded ("+" is a space); null when it isn't there. */
    fun queryParameter(name: String): String? {
        val q = encodedQuery ?: return null
        for (pair in q.split('&')) {
            val eq = pair.indexOf('=')
            val key = percentDecode(if (eq < 0) pair else pair.substring(0, eq), plusIsSpace = true)
            if (key == name) return if (eq < 0) null else percentDecode(pair.substring(eq + 1), plusIsSpace = true)
        }
        return null
    }

    /** The same address over [newScheme] (the port follows when it was the old scheme's default). */
    fun withScheme(newScheme: String): WebUrl {
        val wasDefault = port == defaultPort(scheme)
        return WebUrl(newScheme, host, if (wasDefault) defaultPort(newScheme) else port, userInfo, encodedPath, encodedQuery, fragment)
    }

    /** [link] read against this address ("/img/a.jpg", "a.jpg", "../a.jpg", "//cdn.site/a.jpg", a whole address). */
    fun resolve(link: String): WebUrl? = parse(link, this)

    /** The fragment as written; null when there is none. */
    val encodedFragment: String? get() = fragment

    /** The same address with [query] and [fragment] (both as written), as OkHttp's HttpUrl.Builder builds it. */
    fun withQueryAndFragment(query: String?, fragment: String?): WebUrl = WebUrl(scheme, host, port, userInfo, encodedPath, query, fragment)

    override fun toString(): String = buildString {
        append(scheme).append("://")
        if (!userInfo.isNullOrEmpty()) append(userInfo).append('@')
        append(if (':' in host) "[$host]" else host)
        if (port != defaultPort(scheme)) append(':').append(port)
        append(encodedPath)
        if (encodedQuery != null) append('?').append(encodedQuery)
        if (fragment != null) append('#').append(fragment)
    }

    override fun equals(other: Any?): Boolean = other is WebUrl && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        /** OkHttp's `String.toHttpUrlOrNull()`: null for anything but a well-formed http(s) address. */
        fun of(input: String): WebUrl? = parse(input, null)

        fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

        private const val HEX = "0123456789ABCDEF"

        // The characters OkHttp percent-encodes in each part when it reads an address (besides controls and non-ASCII).
        private const val PATH_SET = " \"<>^`{}|\\?#"
        private const val QUERY_SET = " \"'<>#"
        private const val FRAGMENT_SET = ""

        private fun parse(raw: String, base: WebUrl?): WebUrl? {
            val input = raw.trim { it <= ' ' }
            var pos = 0
            val schemeEnd = schemeEnd(input)
            val scheme: String
            if (schemeEnd >= 0) {
                scheme = input.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") return null
                pos = schemeEnd + 1
            } else {
                scheme = base?.scheme ?: return null
            }
            val slashes = input.drop(pos).takeWhile { it == '/' || it == '\\' }.length
            if (slashes >= 2 || base == null || base.scheme != scheme) {
                // An authority: [user@]host[:port], then the path.
                pos += slashes
                val authorityEnd = input.indexOfAny(charArrayOf('/', '\\', '?', '#'), pos).let { if (it < 0) input.length else it }
                val authority = input.substring(pos, authorityEnd)
                val at = authority.lastIndexOf('@')
                val userInfo = if (at >= 0) authority.substring(0, at) else null
                val hostPort = if (at >= 0) authority.substring(at + 1) else authority
                val (host, port) = hostAndPort(hostPort, scheme) ?: return null
                pos = authorityEnd
                return rest(input, pos, scheme, host, port, userInfo?.let { canonical(it, " \"':;<=>@[]^`{}|/\\?#", alreadyEncoded = true) }, "/")
            }
            // Relative to [base]: a path, a query or a fragment.
            return rest(input, pos, scheme, base.host, base.port, base.userInfo, base.encodedPath, base)
        }

        /** The rest of an address from [start]: its path (read against [basePath] when relative), query and fragment. */
        private fun rest(input: String, start: Int, scheme: String, host: String, port: Int, userInfo: String?, basePath: String, base: WebUrl? = null): WebUrl {
            var pos = start
            val pathEnd = input.indexOfAny(charArrayOf('?', '#'), pos).let { if (it < 0) input.length else it }
            val rawPath = input.substring(pos, pathEnd).replace('\\', '/')
            pos = pathEnd
            var query: String? = null
            var fragment: String? = null
            if (pos < input.length && input[pos] == '?') {
                val end = input.indexOf('#', pos).let { if (it < 0) input.length else it }
                query = canonical(input.substring(pos + 1, end), QUERY_SET, alreadyEncoded = true)
                pos = end
            }
            if (pos < input.length && input[pos] == '#') fragment = canonical(input.substring(pos + 1), FRAGMENT_SET, alreadyEncoded = true)
            val path = when {
                rawPath.startsWith("/") -> normalizePath(rawPath)
                rawPath.isEmpty() -> if (base != null) basePath else "/"
                else -> normalizePath(basePath.substringBeforeLast('/') + "/" + rawPath)
            }
            // A relative link with only a fragment keeps the base's query; with a path it doesn't.
            val keptQuery = if (base != null && rawPath.isEmpty() && query == null) base.encodedQuery else query
            return WebUrl(scheme, host, port, userInfo, path, keptQuery, fragment)
        }

        /** Where "scheme:" ends in [input]; -1 when it has none. */
        private fun schemeEnd(input: String): Int {
            if (input.isEmpty() || !input[0].isAsciiLetter()) return -1
            for (i in 1 until input.length) {
                val c = input[i]
                when {
                    c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.' -> continue
                    c == ':' -> return i
                    else -> return -1
                }
            }
            return -1
        }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        /** The host in lower case and the port (the scheme's default when none); null when either is unusable. */
        private fun hostAndPort(text: String, scheme: String): Pair<String, Int>? {
            val (rawHost, rawPort) = if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                text.substring(1, close) to text.substring(close + 1).removePrefix(":").takeIf { text.length > close + 1 }
            } else {
                val colon = text.lastIndexOf(':')
                if (colon >= 0) text.substring(0, colon) to text.substring(colon + 1) else text to null
            }
            val host = percentDecode(rawHost, plusIsSpace = false).lowercase()
            if (host.isEmpty() || host.any { it <= ' ' || it in "#%/:?@[\\]<>^`{|}" && !(it == ':' && text.startsWith("[")) }) return null
            val port = if (rawPort.isNullOrEmpty()) defaultPort(scheme) else rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }

        /** "." and ".." segments worked out, as a browser and OkHttp do. */
        private fun normalizePath(path: String): String {
            val out = ArrayList<String>()
            val parts = path.removePrefix("/").split('/')
            parts.forEachIndexed { i, raw ->
                val last = i == parts.lastIndex
                when (raw.lowercase()) {
                    ".", "%2e" -> if (last) out += ""
                    "..", "%2e.", ".%2e", "%2e%2e" -> {
                        if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                        if (last) out += ""
                    }
                    else -> out += canonical(raw, PATH_SET, alreadyEncoded = true)
                }
            }
            return "/" + out.joinToString("/")
        }

        /**
         * [text] with every character in [set], every control character, space and non-ASCII letter percent-encoded
         * (as UTF-8); with [alreadyEncoded] a "%XX" stays as it is and a lone "%" becomes "%25".
         */
        fun canonical(text: String, set: String, alreadyEncoded: Boolean, plusAsSpace: Boolean = false): String {
            val out = StringBuilder(text.length + 8)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val code = c.code
                when {
                    alreadyEncoded && c == '%' && isHex(text.getOrNull(i + 1)) && isHex(text.getOrNull(i + 2)) -> {
                        out.append(text, i, i + 3)
                        i += 3
                        continue
                    }
                    plusAsSpace && c == '+' -> out.append("%2B")
                    code < 0x20 || code == 0x7f || code >= 0x80 || c in set || c == '%' -> {
                        // One character, or a surrogate pair, as its UTF-8 bytes.
                        val end = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i + 2 else i + 1
                        for (b in text.substring(i, end).encodeToByteArray()) {
                            val v = b.toInt() and 0xff
                            out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xf])
                        }
                        i = end
                        continue
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        private fun isHex(c: Char?): Boolean = c != null && (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')

        /** "%XX" as bytes read as UTF-8, and "+" as a space when [plusIsSpace]; a malformed escape stays as written. */
        fun percentDecode(text: String, plusIsSpace: Boolean): String {
            if ('%' !in text && !(plusIsSpace && '+' in text)) return text
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '%' && isHex(text.getOrNull(i + 1)) && isHex(text.getOrNull(i + 2)) -> {
                        bytes += text.substring(i + 1, i + 3).toInt(16).toByte()
                        i += 3
                        continue
                    }
                    c == '+' && plusIsSpace -> bytes += ' '.code.toByte()
                    else -> c.toString().encodeToByteArray().forEach { bytes += it }
                }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

/** What the Android app's code builds addresses with (OkHttp's HttpUrl.Builder, java.net's URLEncoder and URLDecoder). */
object UrlCodec {
    /** OkHttp's addQueryParameter: the set it encodes in a query's name or value. */
    private const val QUERY_COMPONENT_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"

    /** OkHttp's addPathSegment: the set it encodes in one segment. */
    private const val PATH_SEGMENT_SET = " \"<>^`{}|/\\?#"

    /** A query name or value as HttpUrl.Builder.addQueryParameter writes it ("+" too). */
    fun queryComponent(text: String): String = WebUrl.canonical(text, QUERY_COMPONENT_SET + "+", alreadyEncoded = false)

    /** A path segment as HttpUrl.Builder.addPathSegment writes it. */
    fun pathSegment(text: String): String = WebUrl.canonical(text, PATH_SEGMENT_SET, alreadyEncoded = false)

    /** [base] with the query parameters in order, as HttpUrl.Builder writes them. */
    fun withQuery(base: String, vararg params: Pair<String, String>): String =
        base + "?" + params.joinToString("&") { (k, v) -> queryComponent(k) + "=" + queryComponent(v) }

    /** java.net.URLEncoder.encode(text, "UTF-8"): letters, digits and ".-*_" stay, a space is "+", the rest "%XX". */
    fun formEncode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val v = b.toInt() and 0xff
            val c = v.toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_" -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0xf])
            }
        }
    }

    /** java.net.URLDecoder.decode(text, "UTF-8"); throws on a broken escape, as it does. */
    fun formDecode(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '+' -> bytes += ' '.code.toByte()
                '%' -> {
                    if (i + 2 >= text.length) throw IllegalArgumentException("Incomplete trailing escape (%) pattern")
                    val hex = text.substring(i + 1, i + 3)
                    bytes += (hex.toIntOrNull(16) ?: throw IllegalArgumentException("Illegal hex characters in escape")).toByte()
                    i += 3
                    continue
                }
                else -> c.toString().encodeToByteArray().forEach { bytes += it }
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

/** What the Android app writes with String.format(Locale.ROOT, "%.Nf", x) and java.util.UUID.nameUUIDFromBytes. */
object JavaText {
    /**
     * [x] with [decimals] digits after the point, as Java's Formatter writes "%.Nf": the shortest decimal form of the
     * number (Double.toString's), rounded half up.
     */
    fun fixed(x: Double, decimals: Int): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
        // The shortest digits and where the point goes.
        val (digits, point) = shortest(abs(x))
        // Digits after the point, padded.
        val intPart = if (point > 0) digits.take(point).padEnd(point, '0') else "0"
        val frac = if (point >= 0) digits.drop(point) else "0".repeat(-point) + digits
        val all = (intPart + frac.padEnd(decimals + 1, '0')).map { it - '0' }.toMutableList()
        val keep = intPart.length + decimals
        val roundUp = all[keep] >= 5
        val kept = all.subList(0, keep).toMutableList()
        if (roundUp) {
            var j = kept.lastIndex
            while (j >= 0) {
                if (kept[j] == 9) {
                    kept[j] = 0
                    j--
                } else {
                    kept[j]++
                    break
                }
            }
            if (j < 0) kept.add(0, 1)
        }
        val text = kept.joinToString("")
        val whole = text.dropLast(decimals).trimStart('0').ifEmpty { "0" }
        val out = if (decimals > 0) "$whole.${text.takeLast(decimals)}" else whole
        return if (negative) "-$out" else out
    }

    /** The digits of [x]'s shortest form (no leading zeros) and the position of the point among them. */
    private fun shortest(x: Double): Pair<String, Int> {
        val s = x.toString()
        val (mantissa, exp) = s.uppercase().split('E').let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
        val dot = mantissa.indexOf('.')
        val intDigits = if (dot < 0) mantissa else mantissa.substring(0, dot)
        val fracDigits = if (dot < 0) "" else mantissa.substring(dot + 1)
        var digits = intDigits + fracDigits
        var point = intDigits.length + exp
        val lead = digits.length - digits.trimStart('0').length
        digits = digits.trimStart('0')
        point -= lead
        if (digits.isEmpty()) return "0" to 1
        return digits.trimEnd('0').ifEmpty { "0" } to point
    }

    /** java.util.UUID.nameUUIDFromBytes: the MD5 of [bytes] as a version 3 id, written in lower case. */
    fun nameUuid(bytes: ByteArray): String {
        val md5 = Md5.digest(bytes)
        md5[6] = ((md5[6].toInt() and 0x0f) or 0x30).toByte()
        md5[8] = ((md5[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = md5.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

/** MD5 (RFC 1321), for the version 3 ids the Android app makes from names. */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i ->
        (abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt()
    }

    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padded = ((input.size + 8) / 64 + 1) * 64
        val msg = input.copyOf(padded)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padded - 8 + i] = (bitLength ushr (8 * i)).toByte()
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (chunk in 0 until padded / 64) {
            for (j in 0 until 16) {
                val o = chunk * 64 + j * 4
                m[j] = (msg[o].toInt() and 0xff) or ((msg[o + 1].toInt() and 0xff) shl 8) or
                    ((msg[o + 2].toInt() and 0xff) shl 16) or ((msg[o + 3].toInt() and 0xff) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val t = d
                d = c
                c = b
                b += (a + f + K[i] + m[g]).rotateLeft(S[i])
                a = t
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        for ((n, v) in listOf(a0, b0, c0, d0).withIndex()) for (k in 0 until 4) out[n * 4 + k] = (v ushr (8 * k)).toByte()
        return out
    }
}

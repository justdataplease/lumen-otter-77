import java.io.StringReader
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val composeVersion = "1.12.2"

// The network (place search, link previews, Wikipedia photos, Google Maps links and lists): Ktor, URLSession on iPhone
// (the Darwin engine) and Java's own HTTP client on the desktop. 3.4.3 is built with Kotlin 2.3.0 and coroutines 1.10.2,
// the versions this app and the engine use (3.5 needs coroutines 1.11).
val ktorVersion = "3.4.3"

// jsoup for iPhone (Copy from a link, read_link): Ksoup, built with Kotlin 2.3.10 (this app: 2.3.21).
val ksoupVersion = "0.2.6"

// The Android app: android-boringtravel/app in the suite, app/ at the root of the standalone repository (where this
// project is ios/).
val androidMain: File = listOf("../../android-boringtravel/app", "../../app").map { file(it) }
    .first { it.isDirectory }.resolve("src/main")
val pkg = "com/justdataplease/boringtravel"

/*
 * The AI planner (its screens come later): the process every model runs through, the prompt, the tools and their
 * checks, the pending changes, the review and the save, the trip questions, links and Google Maps lists in a message,
 * the allowance's rules, reading pictures, the own-key providers' requests, answers and errors, and a message's
 * attachments. Written again here because they call Firebase, the Anthropic SDK, OkHttp's client or android.graphics:
 * ai/GeminiPlanner.kt (Firebase AI Logic over REST), ai/own/ClaudeKeyApi.kt (the Messages API, streamed), ai/own/OwnAi.kt
 * (keys in the Keychain), ai/PicturePrep.kt (Skia), ai/ItineraryImport.kt and firebase/FirebaseSetup.kt. read_link's
 * ai/tools/LinkReader.kt is read in since round 17, with Copy from a link's readers (siteFiles).
 */
val aiFiles = listOf(
    "ai/PlannerProcess.kt",
    "ai/PlannerEngine.kt",
    "ai/PlannerPrompt.kt",
    "ai/TripDraft.kt",
    "ai/DraftLayout.kt",
    "ai/DraftApplier.kt",
    "ai/DraftEdits.kt",
    "ai/DraftSchema.kt",
    "ai/PlannerError.kt",
    "ai/PlannerAllowance.kt",
    "ai/PictureReading.kt",
    "ai/tools/ToolLoop.kt",
    "ai/tools/ToolRunner.kt",
    "ai/tools/PlannerTools.kt",
    "ai/tools/PendingPlan.kt",
    "ai/tools/PendingPlaces.kt",
    "ai/tools/TravelBrief.kt",
    "ai/tools/TripAnchors.kt",
    "ai/tools/StayNames.kt",
    "ai/tools/MessageLinks.kt",
    "ai/own/OwnAiProvider.kt",
    "ai/own/OwnKeyStore.kt",
    "ai/own/OwnKeyError.kt",
    "ai/own/OwnKeyApi.kt",
    "ai/own/ToolRest.kt",
    "ai/own/GeminiRest.kt",
    "ai/own/OpenAiRest.kt",
    "ai/own/OpenRouterRest.kt",
    "ai/own/JsonSchemas.kt",
    "ui/ai/Attachments.kt",
    // The trip questions' sheet, on this app's text box and error line (ios/PlannerScreen.kt, as Android's PlannerScreen).
    "ui/ai/TripQuestionsSheet.kt",
    // read_link's page for the planner, with Copy from a link's readers (site/, below).
    "ai/tools/LinkReader.kt",
)

/*
 * The AI files' lines rewritten on the way in: java.time by the engine's days (LocalDate.parse as
 * parseLocalDateOrNull, now() as today(), a sorted map or min/max of days by their epoch day, compat's isBefore,
 * isAfter, plusYears), this app's links, places and travel rules (ios/), Java's Normalizer as art/SceneText.kt's
 * decomposed(), String.format and Math's round in plain Kotlin, the clock as nowMs(), java.net's, java.security's and
 * OkHttp's names from compat/ (JavaNet.kt, JavaUtil.kt, OkHttp.kt) and Android's SharedPreferences as compat's.
 */
val aiRewrites: Map<String, List<Pair<String, String>>> = run {
    val day = "import com.justdataplease.boringtravel.time.LocalDate"
    val parse = "import com.justdataplease.boringtravel.time.parseLocalDateOrNull"
    val compat = "import com.justdataplease.boringtravel.compat.*"
    val today = "LocalDate.now()" to "com.justdataplease.boringtravel.time.today()"
    fun ios(pkg: String, name: String) = "import com.justdataplease.boringtravel.$pkg.$name" to "import com.justdataplease.boringtravel.ios.$name"
    fun compat(old: String, name: String) = "import $old" to "import com.justdataplease.boringtravel.compat.$name"
    mapOf(
        "ai/PlannerProcess.kt" to listOf("import java.time.LocalDate" to day, today),
        "ui/ai/TripQuestionsSheet.kt" to listOf(
            "import com.justdataplease.boringtravel.ui.theme.Bt" to
                "import com.justdataplease.boringtravel.ui.theme.Bt\nimport com.justdataplease.boringtravel.ios.TextBox\nimport com.justdataplease.boringtravel.ios.ErrorLine",
        ),
        "ai/PlannerEngine.kt" to listOf(ios("links", "LinkUrls"), "import java.time.LocalDate" to day, today),
        "ai/PlannerPrompt.kt" to listOf(
            ios("clip", "RouteLegs"),
            "import java.time.LocalDate" to day,
            "names.toSortedMap().entries" to "names.entries.sortedBy { it.key.toEpochDay() }",
        ),
        "ai/TripDraft.kt" to listOf(
            "import java.time.LocalDate" to parse,
            "java.net.URI(t)" to "com.justdataplease.boringtravel.compat.URI(t)",
            "\"%02d:%02d\".format(h, min)" to
                "com.justdataplease.boringtravel.compat.JavaFormat.twoDigits(h) + \":\" + com.justdataplease.boringtravel.compat.JavaFormat.twoDigits(min)",
            "LocalDate.parse(raw.trim().take(10)).toString()" to "parseLocalDateOrNull(raw.trim().take(10))!!.toString()",
        ),
        "ai/DraftLayout.kt" to listOf(
            "import java.text.Normalizer" to "import com.justdataplease.boringtravel.art.decomposed",
            "import java.time.LocalDate" to "$day\n$parse\n$compat",
            "import java.util.Locale" to "// Locale: Kotlin's own lowercase() here.",
            "dayDates.minOrNull()" to "dayDates.minByOrNull { it.toEpochDay() }",
            "dayDates.maxOrNull()" to "dayDates.maxByOrNull { it.toEpochDay() }",
            "}).minOrNull()" to "}).minByOrNull { it.toEpochDay() }",
            "}).max()" to "}).maxBy { it.toEpochDay() }",
            "sortedMapOf<LocalDate, MutableList<PlacedItem>>()" to "DayMap<MutableList<PlacedItem>>()",
            ".toSortedMap()" to ".entries.sortedBy { it.key.toEpochDay() }.associate { it.key to it.value }",
            "runCatching { LocalDate.parse(s) }.getOrNull()" to "parseLocalDateOrNull(s)",
            "Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)" to "decomposed(s.lowercase())",
        ),
        "ai/DraftApplier.kt" to listOf(
            ios("clip", "RouteLegs"), ios("links", "MapsLinks"),
            "import java.time.LocalDate" to "$day\n$parse",
            "runCatching { LocalDate.parse(it) }.getOrNull()" to "parseLocalDateOrNull(it)",
        ),
        "ai/DraftEdits.kt" to listOf(
            ios("links", "MapsLinks"),
            "import java.time.LocalDate" to "$day\n$parse\n$compat",
            "runCatching { LocalDate.parse(it) }.getOrNull()" to "parseLocalDateOrNull(it)",
            "runCatching { LocalDate.parse(raw) }.getOrNull()" to "parseLocalDateOrNull(raw)",
        ),
        // Exceptions are sorted by their simple names (Kotlin's own, on both phones).
        "ai/PlannerError.kt" to listOf("::class.java.simpleName" to "::class.simpleName"),
        "ai/PlannerAllowance.kt" to listOf(
            "import android.content.Context" to "import com.justdataplease.boringtravel.ios.SettingsFile\nimport com.justdataplease.boringtravel.compat.SharedPreferences",
            "import java.time.LocalDate" to day,
            "class PlannerUsage(context: Context) {" to "class PlannerUsage(file: SettingsFile) {",
            "context.applicationContext.getSharedPreferences(\"ai_planner\", Context.MODE_PRIVATE)" to "SharedPreferences(file, \"ai_planner\")",
            today,
        ),
        "ai/PictureReading.kt" to listOf(compat("java.util.Base64", "Base64")),
        "ai/tools/ToolLoop.kt" to listOf(
            "com.justdataplease.boringtravel.links.LinkUrls.domain(it)" to "com.justdataplease.boringtravel.ios.LinkUrls.domain(it)",
        ),
        "ai/tools/ToolRunner.kt" to listOf(
            ios("places", "LooseQueries"),
            "import java.time.LocalDate" to "$day\n$parse\n$compat\nimport kotlin.math.roundToLong",
            "LocalDate::parse" to "::parseLocalDateOrNull",
            "System.nanoTime()" to "(com.justdataplease.boringtravel.time.nowMs() * 1_000_000)",
            "Math.round(it / 100.0) / 10.0" to "(it / 100.0).roundToLong() / 10.0",
            "Math.round(v * 1e6) / 1e6" to "(v * 1e6).roundToLong() / 1e6",
        ),
        "ai/tools/PendingPlan.kt" to listOf(
            "import java.time.LocalDate" to day,
            ".groupBy { it.date!! }.toSortedMap()" to ".groupBy { it.date!! }.entries.sortedBy { it.key.toEpochDay() }",
            ".groupBy { it.dayIndex!! }.toSortedMap()" to ".groupBy { it.dayIndex!! }.entries.sortedBy { it.key }",
        ),
        "ai/tools/PendingPlaces.kt" to listOf(
            ios("places", "LooseQueries"),
            "System.currentTimeMillis()" to "com.justdataplease.boringtravel.time.nowMs()",
            "compareBy({ it.day == null }, { it.day }, { it.position })" to "compareBy({ it.day == null }, { it.day?.toEpochDay() }, { it.position })",
            "compareBy({ it.value.date == null }, { it.value.date }, { it.index })" to "compareBy({ it.value.date == null }, { it.value.date?.toEpochDay() }, { it.index })",
        ),
        "ai/tools/StayNames.kt" to listOf(
            "import java.text.Normalizer" to "import com.justdataplease.boringtravel.art.decomposed",
            "Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)" to "decomposed(text.lowercase())",
            // Kotlin/Native's regular expressions drop the characters after a \p{…} in a negated class such as
            // [^\p{L}\p{N}&] (the iPhone Simulator split "B&B" in two, 19 Nov 2026): the same match, written
            // as a plain [^\p{L}\p{N}] that isn't one of the extra characters.
            """Regex("[^\\p{L}\\p{N}&]+")""" to """Regex("(?:(?!&)[^\\p{L}\\p{N}])+")""",
        ),
        "ai/tools/MessageLinks.kt" to listOf(
            ios("links", "LinkFill"), ios("links", "LinkUrls"), ios("links", "MapsLinks"), ios("links", "MapsList"),
            ios("links", "MapsPlace"), ios("links", "SharedLinks"), ios("places", "LooseQueries"),
        ),
        // The page reader of read_link: Copy from a link's readers over this app's network (site/SiteWeb.kt's NetSiteWeb).
        "ai/tools/LinkReader.kt" to listOf(
            "import com.justdataplease.boringtravel.BuildConfig" to "// BuildConfig: the app's version comes with its Web here (forApp).",
            ios("links", "LinkFetcher"), ios("links", "LinkHttpException"), ios("links", "LinkUrls"), ios("links", "MapsLinks"),
            "import com.justdataplease.boringtravel.site.OkSiteWeb" to "import com.justdataplease.boringtravel.site.NetSiteWeb",
            "import okhttp3.HttpUrl.Companion.toHttpUrlOrNull" to "import com.justdataplease.boringtravel.compat.toHttpUrlOrNull",
            "import okhttp3.OkHttpClient" to "// OkHttpClient: this app's Net (NetSiteWeb).",
            "import org.jsoup.Jsoup" to "import com.fleeksoft.ksoup.Ksoup as Jsoup",
            "import java.io.IOException" to "import com.justdataplease.boringtravel.io.IOException",
            compat("java.time.ZoneOffset", "ZoneOffset"),
            "fun forApp(): LinkReader = LinkReader(" to "fun forApp(web: com.justdataplease.boringtravel.ios.Web): LinkReader = LinkReader(",
            "OkSiteWeb(OkHttpClient(), LinkFetcher.userAgent(BuildConfig.VERSION_NAME), LinkFetcher.crawlerUserAgent(BuildConfig.VERSION_NAME))," to
                "NetSiteWeb(web.net, LinkFetcher.userAgent(web.version), LinkFetcher.crawlerUserAgent(web.version)),",
        ),
        "ai/own/OwnKeyStore.kt" to listOf(
            compat("java.security.GeneralSecurityException", "GeneralSecurityException"),
            compat("java.security.ProviderException", "ProviderException"),
            compat("java.util.Base64", "Base64"),
            compat("javax.crypto.AEADBadTagException", "AEADBadTagException"),
            "String(cipher.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)" to
                "cipher.decrypt(Base64.getDecoder().decode(stored.removePrefix(PREFIX))).decodeToString()",
            "clean.toByteArray(Charsets.UTF_8)" to "clean.encodeToByteArray()",
        ),
        "ai/own/OwnKeyError.kt" to listOf(
            "import java.io.IOException" to "import com.justdataplease.boringtravel.io.IOException",
            compat("java.io.InterruptedIOException", "InterruptedIOException"),
            compat("java.net.ConnectException", "ConnectException"),
            compat("java.net.NoRouteToHostException", "NoRouteToHostException"),
            compat("java.net.SocketTimeoutException", "SocketTimeoutException"),
            compat("java.net.UnknownHostException", "UnknownHostException"),
            compat("javax.net.ssl.SSLException", "SSLException"),
            compat("javax.net.ssl.SSLHandshakeException", "SSLHandshakeException"),
            "java.security.cert.CertificateException" to "com.justdataplease.boringtravel.compat.CertificateException",
        ),
        "ai/own/OwnKeyApi.kt" to listOf(
            "import com.justdataplease.boringtravel.google.await" to "import com.justdataplease.boringtravel.compat.await",
            compat("okhttp3.MediaType.Companion.toMediaType", "toMediaType"),
            compat("okhttp3.OkHttpClient", "OkHttpClient"),
            compat("okhttp3.Request", "Request"),
            compat("okhttp3.RequestBody.Companion.toRequestBody", "toRequestBody"),
            "import java.io.IOException" to "import com.justdataplease.boringtravel.io.IOException\nimport com.justdataplease.boringtravel.compat.synchronized",
            "@Volatile private var answeredBy" to "@kotlin.concurrent.Volatile private var answeredBy",
            "System.currentTimeMillis()" to "com.justdataplease.boringtravel.time.nowMs()",
            "withContext(Dispatchers.IO)" to "withContext(Dispatchers.Default)",
        ),
        "ai/own/ToolRest.kt" to listOf(
            compat("okhttp3.MediaType.Companion.toMediaType", "toMediaType"),
            compat("okhttp3.OkHttpClient", "OkHttpClient"),
            compat("okhttp3.Request", "Request"),
            compat("okhttp3.RequestBody.Companion.toRequestBody", "toRequestBody"),
        ),
        "ai/own/GeminiRest.kt" to listOf(
            "java.net.URLEncoder.encode(it, \"UTF-8\")" to "com.justdataplease.boringtravel.ios.UrlCodec.formEncode(it)",
            "java.net.URLEncoder.encode(model.removePrefix(\"models/\"), \"UTF-8\")" to "com.justdataplease.boringtravel.ios.UrlCodec.formEncode(model.removePrefix(\"models/\"))",
        ),
        "ui/ai/Attachments.kt" to listOf(ios("links", "LinkUrls")),
    )
}

/*
 * Copy from a link: the Android app's site readers (a Google Doc, Tumblr, feeds, a site's dated articles, a page; the
 * page's HTML as note blocks; a long document cut at its headings and their dates), the sorting into trips (by the AI,
 * else by dates; a post about several places cut into a note per place), the notes and plan items with their
 * name-based ids, the places on the map, and the copy itself (SiteImporter, its state and its rules). Written again
 * here: site/SiteWeb.kt (OkHttp: NetSiteWeb over this app's Net) and site/AppSiteStore.kt (Room, files, Android's
 * pictures: ios/SiteCopy.kt's BookSiteStore on the trip store). jsoup has no iPhone build: Ksoup (com.fleeksoft.ksoup),
 * a Kotlin Multiplatform port of jsoup with its names, reads the HTML (imports rewritten).
 */
val siteFiles = listOf(
    "site/SitePost.kt",
    "site/CssFormats.kt",
    "site/HtmlBlocks.kt",
    "site/Feeds.kt",
    "site/GoogleDocs.kt",
    "site/HeadingDates.kt",
    "site/InlinePictures.kt",
    "site/PageReader.kt",
    "site/SiteArticles.kt",
    "site/TumblrApi.kt",
    "site/SiteSections.kt",
    "site/SiteParts.kt",
    "site/SiteNotes.kt",
    "site/SitePlaces.kt",
    "site/SiteOrganizer.kt",
    "site/SiteReader.kt",
    "site/SiteImporter.kt",
    "site/SiteLinks.kt",
)

/*
 * The site files' lines rewritten on the way in: jsoup's names as Ksoup's (its parse takes the parser before the base
 * address; a text node's whole text is a function there), OkHttp's HttpUrl as compat/HttpUrl.kt's, this app's link
 * rules (ios/), java.time by compat's names and the engine's days (a day at noon in a zone as compat's atTimeIn, days
 * ordered by their epoch day, today as the engine's), java.io and java.security as compat's, a HashMap's merge in plain
 * Kotlin, UUID v3 as JavaText.nameUuid and "%.5f" as JavaText.fixed (the same text), Java's Normalizer as
 * art/SceneText.kt's decomposed(), and the companions' function references as lambdas.
 */
val siteRewrites: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    fun to(old: String, new: String) = "import $old" to "import $new"
    fun links(name: String) = to("com.justdataplease.boringtravel.links.$name", "com.justdataplease.boringtravel.ios.$name")
    fun jsoup(name: String) = to("org.jsoup.$name", if (name == "Jsoup") "com.fleeksoft.ksoup.Ksoup as Jsoup" else "com.fleeksoft.ksoup.$name")
    fun compat(old: String, name: String) = to(old, "$c.$name")
    val httpUrl = to("okhttp3.HttpUrl.Companion.toHttpUrlOrNull", "$c.toHttpUrlOrNull")
    val httpUrlBuilder = to("okhttp3.HttpUrl.Companion.toHttpUrlOrNull", "$c.toHttpUrlOrNull\nimport $c.newBuilder")
    val instant = compat("java.time.Instant", "Instant")
    val zoneId = compat("java.time.ZoneId", "ZoneId")
    val locale = compat("java.util.Locale", "Locale")
    val file = compat("java.io.File", "File")
    val ioException = to("java.io.IOException", "com.justdataplease.boringtravel.io.IOException")
    val day = "import com.justdataplease.boringtravel.time.LocalDate"
    val merge = { map: String, key: String -> "val n = $map.merge($key, 1, Int::plus)!!" to "val n = (($map[$key] ?: 0) + 1).also { $map[$key] = it }" }
    val nowIn = "LocalDate.now(zone)" to "com.justdataplease.boringtravel.time.localDateAt(com.justdataplease.boringtravel.time.nowMs(), zone.platformId)"
    mapOf(
        "site/SitePost.kt" to listOf(file, instant),
        "site/CssFormats.kt" to listOf(jsoup("nodes.Element")),
        "site/HtmlBlocks.kt" to listOf(
            links("MetaTags"), compat("okhttp3.HttpUrl", "HttpUrl"), httpUrl,
            jsoup("Jsoup"), jsoup("nodes.Element"), jsoup("nodes.Node"), jsoup("nodes.TextNode"),
            "is TextNode -> text(n.wholeText)" to "is TextNode -> text(n.getWholeText())",
        ),
        "site/Feeds.kt" to listOf(
            links("MetaTags"), httpUrlBuilder, jsoup("Jsoup"), jsoup("nodes.Document"), jsoup("nodes.Element"), jsoup("parser.Parser"),
            instant, to("java.time.LocalDate", "com.justdataplease.boringtravel.time.parseLocalDateOrNull"),
            compat("java.time.LocalDateTime", "LocalDateTime"), compat("java.time.OffsetDateTime", "OffsetDateTime"),
            compat("java.time.ZoneOffset", "ZoneOffset"), compat("java.time.ZonedDateTime", "ZonedDateTime"),
            compat("java.time.format.DateTimeFormatter", "DateTimeFormatter"), locale,
            "Jsoup.parse(xml, feedUrl, Parser.xmlParser())" to "Jsoup.parse(xml, Parser.xmlParser(), feedUrl)",
            "return LocalDate.parse(s.take(10)).atTime(12, 0).toInstant(ZoneOffset.UTC)" to
                "return LocalDateTime.of(parseLocalDateOrNull(s.take(10))!!, 12, 0).toInstant(ZoneOffset.UTC)",
        ),
        "site/GoogleDocs.kt" to listOf(
            httpUrl, jsoup("Jsoup"), jsoup("nodes.Document"), to("java.net.URLDecoder", "com.justdataplease.boringtravel.ios.UrlCodec"),
            "URLDecoder.decode(it.replace(\"+\", \"%2B\"), \"UTF-8\")" to "UrlCodec.formDecode(it.replace(\"+\", \"%2B\"))",
        ),
        "site/HeadingDates.kt" to listOf(
            to("java.text.Normalizer", "com.justdataplease.boringtravel.art.decomposed"),
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.time.localDate\nimport $c.isAfter\nimport $c.plusYears\nimport $c.year"),
            "today: LocalDate = LocalDate.now()" to "today: LocalDate = com.justdataplease.boringtravel.time.today()",
            "runCatching { LocalDate.of(y, p.second, p.third) }" to "runCatching { localDate(y, p.second, p.third) }",
            "Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)" to "decomposed(text.lowercase())",
        ),
        "site/InlinePictures.kt" to listOf(
            compat("java.io.ByteArrayOutputStream", "ByteArrayOutputStream"), file, ioException,
            compat("java.io.InputStream", "InputStream"), compat("java.io.OutputStream", "OutputStream"),
            compat("java.security.MessageDigest", "MessageDigest"),
            to("java.util.Base64", "$c.Base64\nimport $c.Charsets\nimport $c.buffered\nimport $c.toByteArray"),
            "digest.digest().joinToString(\"\") { \"%02x\".format(it) }" to "digest.digest().joinToString(\"\") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }",
        ),
        "site/PageReader.kt" to listOf(
            links("MetaTags"), jsoup("Jsoup"), jsoup("nodes.Document"), jsoup("nodes.Element"),
            "java.time.Instant?" to "com.justdataplease.boringtravel.compat.Instant?",
        ),
        "site/SiteArticles.kt" to listOf(
            links("MetaTags"), httpUrlBuilder, jsoup("Jsoup"), jsoup("nodes.Document"), jsoup("parser.Parser"),
            "Jsoup.parse(xml, url, Parser.xmlParser())" to "Jsoup.parse(xml, Parser.xmlParser(), url)",
        ),
        "site/TumblrApi.kt" to listOf(
            links("MetaTags"), to("okhttp3.HttpUrl.Companion.toHttpUrl", "$c.toHttpUrl\nimport $c.newBuilder"), httpUrl, jsoup("Jsoup"),
            instant, compat("java.time.LocalDateTime", "LocalDateTime"), compat("java.time.ZoneOffset", "ZoneOffset"),
            compat("java.time.format.DateTimeFormatter", "DateTimeFormatter"),
        ),
        "site/SiteSections.kt" to listOf(
            jsoup("Jsoup"), to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport $c.atTimeIn"), zoneId,
            nowIn, merge("seen", "key"),
            "java.time.Instant.ofEpochMilli(it)" to "com.justdataplease.boringtravel.compat.Instant.ofEpochMilli(it)",
            "d.atTime(NOON, 0).atZone(zone).toInstant().toEpochMilli()" to "d.atTimeIn(NOON, 0, zone).toEpochMilli()",
        ),
        "site/SiteParts.kt" to listOf(jsoup("Jsoup"), merge("seen", "key")),
        "site/SiteNotes.kt" to listOf(
            links("LinkFill"), links("MapsListPlace"), httpUrl, to("java.time.LocalDate", day.removePrefix("import ")), zoneId,
            "import java.util.Locale" to "// Locale: Kotlin's own lowercase() here.",
            to("java.util.UUID", "com.justdataplease.boringtravel.ios.JavaText"),
            "lowercase(Locale.ROOT)" to "lowercase()",
            "String.format(Locale.ROOT, \"%.5f,%.5f\", pin.lat, pin.lng)" to "JavaText.fixed(pin.lat, 5) + \",\" + JavaText.fixed(pin.lng, 5)",
            merge("times", "p.url"),
            "UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()" to "JavaText.nameUuid(key.encodeToByteArray())",
        ),
        "site/SiteOrganizer.kt" to listOf(
            jsoup("Jsoup"), instant,
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.time.parseLocalDateOrNull\nimport $c.atTimeIn\nimport $c.isAfter\nimport $c.plusYears\nimport $c.year\nimport $c.month"),
            zoneId, compat("java.time.format.DateTimeFormatter", "DateTimeFormatter"), locale,
            "days.min()" to "days.minBy { it.toEpochDay() }",
            "days.max()" to "days.maxBy { it.toEpochDay() }",
            nowIn,
            "today: LocalDate = LocalDate.now()" to "today: LocalDate = com.justdataplease.boringtravel.time.today()",
            "d.atTime(SiteSections.NOON, 0).atZone(zone).toInstant()" to "d.atTimeIn(SiteSections.NOON, 0, zone)",
            "runCatching { LocalDate.parse(t) }.getOrNull()" to "parseLocalDateOrNull(t)",
        ),
        "site/SiteReader.kt" to listOf(
            links("LinkHttpException"), links("LinkUrls"), httpUrl, file, ioException, compat("java.io.InputStream", "InputStream"),
            to("java.time.LocalDate", day.removePrefix("import ")), zoneId,
            "ZoneId::systemDefault" to "{ ZoneId.systemDefault() }",
            "LocalDate::now" to "{ com.justdataplease.boringtravel.time.today() }",
        ),
        "site/SiteImporter.kt" to listOf(
            links("LinkUrls"), links("MapsLinks"), links("MapsList"), links("MapsListException"), links("MapsListPlace"), links("MapsLists"),
            file, ioException, instant,
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport $c.atTimeIn\nimport $c.isAfter\nimport $c.isBefore\nimport $c.compareTo"),
            zoneId, compat("java.util.Collections", "Collections"), locale, compat("java.util.concurrent.atomic.AtomicInteger", "AtomicInteger"),
            "ZoneId::systemDefault" to "{ ZoneId.systemDefault() }",
            "Locale::getDefault" to "{ Locale.getDefault() }",
            "System::currentTimeMillis" to "{ com.justdataplease.boringtravel.time.nowMs() }",
            "@Volatile private var" to "@kotlin.concurrent.Volatile private var",
            "nullsLast<java.time.Instant>()" to "nullsLast<Instant>()",
            "trip.start?.atTime(SiteSections.NOON, 0)?.atZone(zone)?.toInstant()?.toEpochMilli()" to "trip.start?.atTimeIn(SiteSections.NOON, 0, zone)?.toEpochMilli()",
            "listOfNotNull(joins.start, trip.start).minOrNull()" to "listOfNotNull(joins.start, trip.start).minByOrNull { it.toEpochDay() }",
            "listOfNotNull(joins.end, trip.end).maxOrNull()" to "listOfNotNull(joins.end, trip.end).maxByOrNull { it.toEpochDay() }",
        ),
        "site/SiteLinks.kt" to listOf(links("LinkUrls"), links("MapsLinks"), httpUrl),
    )
}

/*
 * The trip video (round 18): everything the Android app works out for a clip in plain Kotlin (the plan of every frame,
 * its timing, cameras, globe, zooms, texts, tags, photos, day cards and inserts, the paper vehicles' and the traveller's
 * meshes and walk, the music's beats, the look's choices and the remembered settings), and the renderer that draws a
 * frame (ClipRenderer, its globe ground, the paper vehicles, the brand mark, the picker's vehicle pictures), compiled
 * as they are: their android.graphics and android.text are compat/graphics' (the same names drawn with Skia), their
 * java.time, Locale and Math calls compat's or Kotlin's. The rest of the clip (MapLibre's snapshots, MediaCodec,
 * the editor's screens) is written again here (ios/Clip*.kt).
 */
val clipFiles = listOf(
    "clip/ClipPlan.kt", "clip/ClipDesign.kt", "clip/ClipTimeline.kt", "clip/ClipScenes.kt", "clip/ClipTiming.kt",
    "clip/ClipZoom.kt", "clip/SteadyCamera.kt", "clip/FollowCamera.kt", "clip/ReplayProjection.kt", "clip/ClipGlobe.kt",
    "clip/PaperVehicleMesh.kt", "clip/PaperVehicleProjection.kt", "clip/TravellerMesh.kt", "clip/RideMeshes.kt",
    "clip/Rides.kt", "clip/Traveller.kt", "clip/WalkCycle.kt", "clip/TrackShaping.kt", "clip/VehicleGuess.kt",
    "clip/ClipText.kt", "clip/ClipTags.kt", "clip/ClipEdits.kt", "clip/ClipPicks.kt", "clip/StopStays.kt",
    "clip/ClipInserts.kt", "clip/ClipBeats.kt", "clip/PlaceMedia.kt", "clip/ExportProgress.kt", "clip/PlannedReplay.kt",
    "clip/RouteLegs.kt", "clip/ClipPrefs.kt", "clip/music/MusicCatalog.kt", "clip/music/MusicShapes.kt",
    "clip/music/MusicMix.kt", "clip/music/MusicPrefs.kt", "ui/clip/PreviewMath.kt",
    // The picture: drawn on compat/graphics' Canvas (Skia).
    "clip/ClipRenderer.kt", "clip/GlobeGround.kt", "clip/PaperVehicle3D.kt", "clip/VehicleSprites.kt", "art/PlaneMarkBitmap.kt",
    // The editors' dark studio: its colours, type, Material defaults, controls and icons.
    "ui/studio/Studio.kt", "ui/studio/StudioControls.kt", "ui/studio/StudioIcons.kt",
    // The editor (round 19), compiled as it is: the tracks' model, layout and snapping (TrackLayout), the tracks under
    // the playhead, the stage with a name or card dragged on it, every tool's panel (Text, Audio, Media, Camera, and
    // Length, Route, Look and Stats, which round 20 opens), their sliders and thumb pad, the tags and a place's media.
    // The view-model they call is ios/ClipStudio.kt (Android's ClipViewModel written again: "ClipViewModel" is
    // ClipStudio in them); what they reach of the phone is compat's (SystemClock, LocalConfiguration, the photo picker's
    // and the microphone's launchers) and the music's player is ui/clip/MusicPreview.kt here (Android: MediaPlayer).
    "ui/clip/TrackLayout.kt", "ui/clip/ClipTracks.kt", "ui/clip/ClipStage.kt", "ui/clip/ClipTools.kt", "ui/clip/ClipSceneTools.kt",
    "ui/clip/ClipTagTools.kt", "ui/clip/ClipControls.kt", "ui/clip/ClipPlacePhotos.kt",
    // Round 20: the Route tool's "Traveller & rides" (People | Rides for this video), and Me › Your traveller & rides
    // with its sheet, drawn by the same paper figures (TravellerFigure, RideFigure on compat/graphics' Skia canvas) and
    // the same rows (TravellerChoices, RideChoices). Me's row reads this app's settings (ios/Traveller.kt).
    "ui/clip/ClipTravellerTool.kt", "ui/traveller/TravellerFigure.kt", "ui/traveller/RideFigure.kt",
    "ui/traveller/TravellerChoices.kt", "ui/traveller/RideChoices.kt", "ui/me/TravellerSetting.kt",
)

val clipRewrites: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    val g = "$c.graphics"
    fun to(old: String, new: String) = "import $old" to "import $new"
    fun gfx(vararg names: String) = names.map { to("android.graphics.$it", "$g.$it") }
    fun text(vararg names: String) = names.map { to("android.text.$it", "$g.$it") }
    val day = to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate")
    val zone = to("java.time.ZoneId", "$c.ZoneId")
    val instant = to("java.time.Instant", "$c.Instant")
    val locale = to("java.util.Locale", "$c.Locale")
    val context = to("android.content.Context", "$c.Context")
    val edit = to("androidx.core.content.edit", "$c.edit")
    val radians = "Math.toRadians(" to "0.017453292519943295 * ("
    val degrees = "Math.toDegrees(" to "57.29577951308232 * ("
    val rint = "Math.rint(" to "kotlin.math.round("
    mapOf(
        "clip/ClipPlan.kt" to listOf(
            instant, to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport $c.atTimeIn"), zone, locale,
            radians, degrees, rint,
            "java.util.TreeMap<" to "$c.TreeMap<",
            "java.util.SortedMap<" to "$c.TreeMap<",
            "val keep = sortedSetOf(0, pts.size - 1)" to "val keep = $c.SortedIntSet(0, pts.size - 1)",
            "days[s.id]?.atTime(12, 0)?.atZone(input.zone)?.toInstant()?.toEpochMilli()" to "days[s.id]?.atTimeIn(12, 0, input.zone)?.toEpochMilli()",
        ),
        "clip/ClipDesign.kt" to listOf("fun dayCardKey(date: java.time.LocalDate)" to "fun dayCardKey(date: com.justdataplease.boringtravel.time.LocalDate)"),
        // "0:34": Java's "%d:%02d", written out.
        "clip/ClipTiming.kt" to listOf(
            "import java.util.Locale" to "// Locale: the clock is written out here.",
            "\"%d:%02d\".format(Locale.ROOT, s / 60, s % 60)" to "\"\${s / 60}:\" + (s % 60).toString().padStart(2, '0')",
        ),
        "clip/ClipZoom.kt" to listOf(locale),
        // Java's copySign: Kotlin's withSign.
        "clip/SteadyCamera.kt" to listOf(
            "import kotlin.math.abs" to "import kotlin.math.abs\nimport kotlin.math.withSign",
            "Math.copySign(frac * w, dx)" to "(frac * w).withSign(dx)",
            "Math.copySign(frac * h, dy)" to "(frac * h).withSign(dy)",
        ),
        "clip/ClipGlobe.kt" to listOf(radians, degrees, rint),
        // "8.4 km", "1,234 km" as Java writes them in the locale asked for ("18,4 km" in Greek: tracking/RouteShapeText.kt)
        // and the countries' names in the phone's language (ios/Countries.kt countryName).
        "clip/ClipText.kt" to listOf(
            locale,
            "String.format(locale, \"%.1f km\", km)" to "com.justdataplease.boringtravel.tracking.RouteShapeText.oneDecimalKm(km, locale)",
            "String.format(locale, \"%,d km\", km.roundToLong())" to "com.justdataplease.boringtravel.tracking.RouteShapeText.groupedKm(km.roundToLong(), locale)",
            "it.trim().uppercase(Locale.ROOT)" to "it.trim().uppercase()",
            "Locale.Builder().setRegion(code).build().getDisplayCountry(locale)" to "com.justdataplease.boringtravel.ios.countryName(code)",
        ),
        "clip/ClipInserts.kt" to listOf(day),
        // The JVM's lock on each call has no common form: the export (round 20) must report its progress from one
        // thread at a time.
        "clip/ExportProgress.kt" to listOf("@Synchronized fun " to "/* one caller at a time (no JVM lock here) */ fun "),
        // The place's town is this app's PlaceGrouping (ios/PlaceScreen.kt); a zone by its id.
        "clip/PlannedReplay.kt" to listOf(
            to("com.justdataplease.boringtravel.ui.place.PlaceGrouping", "com.justdataplease.boringtravel.ios.PlaceGrouping"), day, zone,
            "LocalDate.of(2000, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()" to
                "com.justdataplease.boringtravel.time.startOfDayMs(com.justdataplease.boringtravel.time.localDate(2000, 1, 1), zone.platformId)",
        ),
        "clip/RouteLegs.kt" to listOf(instant, day, zone),
        // The settings files: this phone's settings in a backup's form (compat/AndroidContext.kt).
        "clip/ClipPrefs.kt" to listOf(context, edit),
        "clip/music/MusicPrefs.kt" to listOf(context, edit),
        // Java's Math.round (half up).
        "clip/music/MusicCatalog.kt" to listOf("Math.round((wanted - first) / bar)" to "kotlin.math.floor((wanted - first) / bar + 0.5).toLong()"),
        "clip/ClipRenderer.kt" to gfx(
            "Bitmap", "BitmapShader", "Canvas", "ColorMatrix", "ColorMatrixColorFilter", "DashPathEffect", "LinearGradient",
            "Matrix", "Paint", "Path", "Rect", "RectF", "Shader", "Typeface",
        ) + text("Layout", "StaticLayout", "TextPaint", "TextUtils") + listOf(
            context, locale,
            to("androidx.compose.ui.text.font.createFontFamilyResolver", "$g.createFontFamilyResolver"),
            ") : java.io.Closeable {" to ") : AutoCloseable {",
            "java.util.Random(7L)" to "$c.JavaRandom(7L)",
        ),
        // The Map tab's globe here is the app's own (globe/GlobeRenderer.kt), behind Android's names (compat/graphics).
        "clip/GlobeGround.kt" to gfx("BitmapShader", "Canvas", "ColorFilter", "Paint", "Path", "RectF", "Shader", "Typeface") + listOf(
            to("com.justdataplease.boringtravel.globe.GlobeRenderer", "$g.AndroidGlobeRenderer as GlobeRenderer"),
            radians, degrees,
        ),
        "clip/PaperVehicle3D.kt" to gfx("Canvas", "Paint", "Path"),
        // The status bar's light icons go through compat/AndroidViews.kt (no bars to change on iPhone yet).
        "ui/studio/Studio.kt" to listOf(
            to("android.app.Activity", "$c.Activity"), context, to("android.content.ContextWrapper", "$c.ContextWrapper"),
            to("androidx.compose.ui.platform.LocalView", "$c.LocalView"),
            to("androidx.compose.ui.window.DialogWindowProvider", "$c.DialogWindowProvider"),
            to("androidx.core.view.WindowCompat", "$c.WindowCompat"),
        ),
        "ui/studio/StudioControls.kt" to listOf("android.graphics.Color.parseColor(it)" to "$g.AndroidColor.parseColor(it)"),
        "art/PlaneMarkBitmap.kt" to gfx("Bitmap", "Canvas", "Paint", "Path", "RectF"),
        // The look and the rides come from the caller (this app's settings); the cache keeps its newest in access order.
        "clip/VehicleSprites.kt" to gfx("Bitmap", "Canvas") + listOf(
            context,
            "import com.justdataplease.boringtravel.graph" to "// graph: the look and the rides come from the caller here.",
            "    suspend fun load(context: Context): Map<Vehicle, Bitmap> = load(context, context.graph.settings.rides.first())" to
                "    // load(context): the caller passes this phone's look and rides (load(look, rides)).",
            "    suspend fun load(context: Context, rides: Rides): Map<Vehicle, Bitmap> {" to
                "    suspend fun load(look: com.justdataplease.boringtravel.ui.theme.Look, rides: Rides): Map<Vehicle, Bitmap> {",
            "        val look = context.graph.settings.look.first()" to "        // The look is the caller's.",
            "object : LinkedHashMap<Pair<PaperVehicle3D.Palette, Any>, Bitmap>(16, .75f, true) {" to
                "object : $c.AccessOrderMap<Pair<PaperVehicle3D.Palette, Any>, Bitmap>(16, .75f, true) {",
        ),
    ) + editorRewrites(c, g)
}

/*
 * The editor's screens (round 19): Android's view-model is ClipStudio here (ios/ClipStudio.kt, the same members and
 * states), android.graphics and android.os are compat's, Java's number formats are written out as Java writes them
 * (JavaFormat, JavaText.fixed), the phone's pictures are this app's MediaImage, and the travel rules this app's
 * (ios/PlanTravel.kt). The stage's own renderer and buffers are closed and freed when it lets go of them (Skia's memory
 * is outside Kotlin's): ios/ClipFrames.kt's rememberClosing and rememberBitmaps.
 */
fun editorRewrites(c: String, g: String): Map<String, List<Pair<String, String>>> {
    fun to(old: String, new: String) = "import $old" to "import $new"
    val studio = "ClipViewModel" to "ClipStudio"
    fun studioImport(after: String) = "import $after" to "import $after\nimport com.justdataplease.boringtravel.ios.ClipStudio"
    val bitmap = to("android.graphics.Bitmap", "$g.Bitmap")
    val asImage = "import androidx.compose.ui.graphics.asImageBitmap" to "// asImageBitmap: compat/graphics' Bitmap has its own."
    val mediaImage = to("com.justdataplease.boringtravel.ui.components.MediaImage", "com.justdataplease.boringtravel.ios.MediaImage")
    val clock = to("android.os.SystemClock", "$c.SystemClock")
    val configuration = to("androidx.compose.ui.platform.LocalConfiguration", "$c.LocalConfiguration")
    val collect = to("androidx.lifecycle.compose.collectAsStateWithLifecycle", "$c.collectAsStateWithLifecycle")
    fun travel(name: String) = to("com.justdataplease.boringtravel.ui.trip.$name", "com.justdataplease.boringtravel.ios.$name")
    val fixed0 = "com.justdataplease.boringtravel.ios.JavaText.fixed"
    return mapOf(
        // "1:30": Java's "%d:%02d", written out.
        "ui/clip/TrackLayout.kt" to listOf(
            "\"%d:%02d\".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)" to "\"\${seconds / 60}:\" + (seconds % 60).toString().padStart(2, '0')",
        ),
        "ui/clip/ClipControls.kt" to listOf(
            "import java.util.Locale" to "import $c.JavaFormat",
            "String.format(Locale.ROOT, if (v % 1f == 0f) \"%.0f s\" else \"%.1f s\", v).replace(\".0 s\", \" s\")" to
                "(if (v % 1f == 0f) $fixed0(v.toDouble(), 0) + \" s\" else JavaFormat.oneDecimal(v) + \" s\").replace(\".0 s\", \" s\")",
        ),
        "ui/clip/ClipTracks.kt" to listOf(clock, mediaImage),
        "ui/clip/ClipStage.kt" to listOf(
            bitmap, asImage, studio, studioImport("com.justdataplease.boringtravel.clip.SceneOverride"),
            "renderer.draw(frame, android.graphics.Canvas(bitmap), bitmap)" to "renderer.draw(frame, $g.Canvas(bitmap), bitmap)",
            "val renderer = remember(plan, base, style, photos, isPro, design, videos, width, height) {" to
                "val renderer = com.justdataplease.boringtravel.ios.rememberClosing(plan, base, style, photos, isPro, design, videos, width, height) {",
            "val buffers = remember(width, height) { Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } }" to
                "val buffers = com.justdataplease.boringtravel.ios.rememberBitmaps(width, height, 2)",
        ),
        "ui/clip/ClipTools.kt" to listOf(
            bitmap, asImage, configuration, collect, studio, studioImport("com.justdataplease.boringtravel.ui.theme.BtFonts"),
            to("androidx.activity.compose.rememberLauncherForActivityResult", "$c.rememberLauncherForActivityResult"),
            to("androidx.activity.result.PickVisualMediaRequest", "$c.PickVisualMediaRequest"),
            to("androidx.activity.result.contract.ActivityResultContracts", "$c.ActivityResultContracts"),
            travel("travel"), travel("countsKm"), travel("legKmChoice"), travel("legKmLine"),
            "import java.util.Locale" to "import $c.Locale\nimport $c.uppercase\nimport $c.JavaFormat",
            "android.Manifest.permission.RECORD_AUDIO" to "$c.Manifest.permission.RECORD_AUDIO",
            "renderer.draw(canvas.nativeCanvas, " to "renderer.draw($g.Canvas.of(canvas.nativeCanvas), ",
            "String.format(locale, if (p.pace < 10) \"%.1f\" else \"%.0f\", p.pace)" to
                "(if (p.pace < 10) JavaFormat.oneDecimal(p.pace, locale) else JavaFormat.fixed(p.pace, 0, locale))",
        ),
        "ui/clip/ClipSceneTools.kt" to listOf(
            clock, configuration, collect, studio, studioImport("com.justdataplease.boringtravel.ui.studio.StudioChip"),
            "import androidx.compose.ui.platform.LocalContext" to "import com.justdataplease.boringtravel.ios.LocalClipDevice",
            "    val context = LocalContext.current" to "    val device = LocalClipDevice.current",
            "    val preview = remember { MusicPreview(context.applicationContext) }" to "    val preview = remember(device) { MusicPreview(device.sounds) }",
            "import java.util.Locale" to "import $c.Locale\nimport $c.JavaFormat",
            "String.format(locale, \"%.1f s\", length)" to "JavaFormat.oneDecimal(length, locale) + \" s\"",
            "String.format(locale, \"%.1f s\", start)" to "JavaFormat.oneDecimal(start, locale) + \" s\"",
            "String.format(locale, \"%.1f\", v.durationMs / 1000f)" to "JavaFormat.oneDecimal(v.durationMs / 1000f, locale)",
        ),
        "ui/clip/ClipPlacePhotos.kt" to listOf(mediaImage, collect, studio, studioImport("com.justdataplease.boringtravel.ui.studio.StudioText")),
        // Round 20: the traveller and the rides. The figures draw on compat/graphics' canvas over Compose's (Skia); they
        // walk and turn on the spot unless the phone asks for less motion (an endless animation never lets a UI test
        // settle either: the tests run with Reduce Motion on, ios/Motion.kt).
        "ui/clip/ClipTravellerTool.kt" to listOf(collect),
        "ui/traveller/TravellerFigure.kt" to listOf(
            "canvas.nativeCanvas, traveller, centerX" to "$g.Canvas.of(canvas.nativeCanvas), traveller, centerX",
            "    val phase = if (walking) {" to "    val phase = if (walking && !com.justdataplease.boringtravel.ios.reduceMotion()) {",
        ),
        "ui/traveller/RideFigure.kt" to listOf(
            "renderer.drawRide(canvas.nativeCanvas, ride," to "renderer.drawRide($g.Canvas.of(canvas.nativeCanvas), ride,",
            "    val seconds = if (moving) {" to "    val seconds = if (moving && !com.justdataplease.boringtravel.ios.reduceMotion()) {",
        ),
        // Me's row and sheet read and keep the traveller and the rides through this app's settings (ios/Traveller.kt's
        // TravellerGraph: the same flows and setters as Android's AppGraph.settings).
        "ui/me/TravellerSetting.kt" to listOf(
            to("com.justdataplease.boringtravel.AppGraph", "com.justdataplease.boringtravel.ios.TravellerGraph as AppGraph"),
            collect,
        ),
    )
}

/*
 * The book (round 21): the Android app's whole book, compiled as it is. Its model and stored form (BookModel, BookJson:
 * the same JSON in the same `books` rows, so a backup carries a book between the phones), the first layout and the text
 * poured page to page (BookLayout, TextFit: Android's line breaking, measured here by the phone's TextMeasure), where
 * everything sits on each template (PageLayout, PageGeometry), the looks, fonts, CSS and SVG (BookFonts, BookCss,
 * BookSvg), the page HTML WebKit prints (BookHtml), the things placed by hand and the editing steps round 22 builds on
 * (FreeEl, FreeLayout, FreeEdit, FreeMath, Layers, AlbumEdit). And its screens as far as they are plain Compose: the
 * overview of all pages (BookScreen.kt without what reaches Android: BookScreen, BookEditor, MakeSheet, which are
 * written again in ios/BookScreen.kt), the Book look sheet, the layouts, the photo grids and album pages (BookSheets),
 * the page thumbnails (PageViews.kt without its WebView, PagePreview: ui/book/PagePreview.kt here, WebKit on iPhone)
 * and the four looks' mini pages (ThemeThumbs). The view-model they read is ui/book/BookViewModel.kt, written again
 * on this app's trip store; the files and the PDF are ios/BookExports.kt (Android's BookExporter).
 */
val bookFiles = listOf(
    "book/BookModel.kt", "book/BookJson.kt", "book/BookLayout.kt", "book/TextFit.kt", "book/PageLayout.kt", "book/BookFonts.kt",
    "book/BookCss.kt", "book/BookHtml.kt", "book/FreeEl.kt", "book/FreeLayout.kt", "book/FreeEdit.kt", "book/FreeMath.kt",
    "book/Layers.kt", "book/AlbumEdit.kt",
    "ui/book/BookScreen.kt", "ui/book/BookSheets.kt", "ui/book/PageViews.kt", "ui/export/ThemeThumbs.kt",
    // Round 22: the page editor, compiled as it is. One page large with its top bar, the stage and its "+ Photos", the
    // tool row and the open tool's panel (PageEditor); the layer over the page that selects, moves with snapping,
    // resizes and turns, its handles and guides, and the live move it hands the page while the finger moves
    // (PageCanvas); every tool's panel (ToolPanels); "On this page · top first" with its rows, eyes, handles and the
    // background row (LayerList). They edit through the same view-model (ui/book/BookViewModel.kt here); the page under
    // them is ui/book/PagePreview.kt (WebKit on iPhone, its moves through evaluateJavaScript).
    "ui/book/PageEditor.kt", "ui/book/PageCanvas.kt", "ui/book/ToolPanels.kt", "ui/book/LayerList.kt",
)

/*
 * The book's lines rewritten on the way in: the engine's days and compat's java.time (an entry's "HH:mm" in its zone),
 * new ids from the engine (Android: java.util.UUID, the same random kind), Java's round as Kotlin's (ties up, the same),
 * "%.1f" and "%.3f" as Java writes them (JavaFormat), Math's radians and degrees, Android's colour parsing as compat's,
 * this app's MediaImage; and in the screens, the imports of what was cut out (Android's WebView, Activity, launchers,
 * view-model factory, Drive and sharing) left as notes, the overview made reachable from ios/BookScreen.kt.
 */
val bookRewrites: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    val day = "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate"
    val radians = "Math.toRadians(" to "0.017453292519943295 * ("
    val degrees = "Math.toDegrees(" to "57.29577951308232 * ("
    val mediaImage = "import com.justdataplease.boringtravel.ui.components.MediaImage" to "import com.justdataplease.boringtravel.ios.MediaImage"
    fun gone(name: String, why: String) = "import $name" to "// $name: $why"
    val cutHere = "cut out on the way in (written again in this app)."
    mapOf(
        "book/BookModel.kt" to listOf(day, "\"%.1f\".format(java.util.Locale.ROOT, v)" to "$c.JavaFormat.oneDecimal(v)"),
        "book/BookJson.kt" to listOf(
            "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.parseLocalDateOrNull\nimport kotlin.math.roundToLong",
            "runCatching { LocalDate.parse(it) }.getOrNull()" to "parseLocalDateOrNull(it)",
            "Math.round(v * 10000) / 10000.0" to "(v * 10000).roundToLong() / 10000.0",
            "Math.round(v * 1000) / 1000.0" to "(v * 1000).roundToLong() / 1000.0",
        ),
        "book/BookLayout.kt" to listOf(
            "import java.time.Instant" to "import $c.Instant",
            "import java.time.ZoneId" to "import $c.ZoneId",
            "import java.time.format.DateTimeFormatter" to "import $c.DateTimeFormatter",
            "import java.util.Locale" to "import $c.Locale",
            gone("java.util.UUID", "new ids are the engine's (data.repo.newId: a random UUID, as Android's)."),
            "private val newId: () -> String = { UUID.randomUUID().toString() }," to
                "private val newId: () -> String = { com.justdataplease.boringtravel.data.repo.newId() },",
            "day: java.time.LocalDate? = null" to "day: com.justdataplease.boringtravel.time.LocalDate? = null",
        ),
        "book/AlbumEdit.kt" to listOf(day, "slotOf.putIfAbsent(it, e.slot)" to "slotOf.getOrPut(it) { e.slot }"),
        // A day stamp's day, month and year from the engine's days (compat's), the month's name as Java's Month names it.
        "book/PageLayout.kt" to listOf(
            "import kotlin.math.roundToInt" to "import kotlin.math.roundToInt\nimport $c.dayOfMonth\nimport $c.year\nimport com.justdataplease.boringtravel.time.civil",
            "small = d.date.month.name.take(3)" to "small = $c.JavaFormat.monthName(d.date.civil.month).take(3)",
        ),
        // Java's Map.putIfAbsent, as Kotlin's getOrPut.
        "book/FreeLayout.kt" to listOf(radians, "photoIds.putIfAbsent(e.slot, derivedId(i))" to "photoIds.getOrPut(e.slot) { derivedId(i) }"),
        "book/FreeMath.kt" to listOf(radians, degrees),
        "ui/export/ThemeThumbs.kt" to listOf(mediaImage),
        "ui/book/BookSheets.kt" to listOf(mediaImage, "import androidx.core.graphics.toColorInt" to "import $c.graphics.toColorInt"),
        "ui/book/PageViews.kt" to listOf(
            mediaImage,
            gone("android.annotation.SuppressLint", cutHere), gone("android.webkit.WebResourceRequest", cutHere),
            gone("android.webkit.WebResourceResponse", cutHere), gone("android.webkit.WebView", cutHere),
            gone("android.webkit.WebViewClient", cutHere), gone("androidx.compose.ui.viewinterop.AndroidView", cutHere),
            gone("java.io.ByteArrayInputStream", cutHere), gone("java.io.File", cutHere),
            "Color(android.graphics.Color.parseColor(s))" to "Color($c.graphics.AndroidColor.parseColor(s))",
            "String.format(java.util.Locale.ROOT, \"%.3f\", v)" to "$c.JavaFormat.threeDecimals(v)",
        ),
        "ui/book/BookScreen.kt" to listOf(
            "android.app.Activity", "android.content.Context", "android.content.ContextWrapper", "androidx.activity.compose.BackHandler",
            "androidx.activity.compose.rememberLauncherForActivityResult", "androidx.activity.result.PickVisualMediaRequest",
            "androidx.activity.result.contract.ActivityResultContracts", "androidx.compose.ui.platform.LocalContext",
            "androidx.lifecycle.viewmodel.compose.viewModel", "com.justdataplease.boringtravel.AppGraph",
            "com.justdataplease.boringtravel.export.ExportException", "com.justdataplease.boringtravel.export.ExportProgress",
            "com.justdataplease.boringtravel.export.Sharing", "com.justdataplease.boringtravel.google.DriveNotConnectedException",
            "com.justdataplease.boringtravel.ui.nav.AppActions", "com.justdataplease.boringtravel.google.DriveFile", "java.io.File",
        ).map { gone(it, cutHere) } + listOf(
            "private fun PagesOverview(" to "internal fun PagesOverview(",
        ),
        // Round 22. The editor's page: the photos' folder is the view-model's here (Android: AppGraph's books), its
        // paper colour compat's, its fonts beside it under fonts/ (as round 21's page); the keyboard's insets as every
        // phone has them (Compose's isImeVisible is Android's only: compat/ImeInsets.kt).
        "ui/book/PageEditor.kt" to listOf(
            gone("com.justdataplease.boringtravel.AppGraph", "the photos' folder is the view-model's here (vm.books.photoDir)."),
            "    graph: AppGraph," to "    // graph: AppGraph: not needed here (the page's folder is vm.books.photoDir).",
            "import androidx.compose.foundation.layout.isImeVisible" to "import $c.isImeVisible",
            "BookHtml.page(layout, doc, pageId, { id, _ -> state.previews[id] }, zoom)" to
                "BookHtml.page(layout, doc, pageId, { id, _ -> state.previews[id] }, zoom, fontBase = \"fonts/\")",
            "PagePreview(html, graph.books.photoDir, Color(android.graphics.Color.parseColor(pl.look.paperColor)), Modifier.fillMaxSize(), live)" to
                "PagePreview(html, vm.books.photoDir, Color($c.graphics.AndroidColor.parseColor(pl.look.paperColor)), Modifier.fillMaxSize(), live)",
        ),
        // The size or turn shown while dragging: android.graphics' paint and text on the canvas, compat/graphics' (Skia).
        "ui/book/PageCanvas.kt" to listOf(
            "drawContext.canvas.nativeCanvas.apply {" to "$c.graphics.Canvas.of(drawContext.canvas.nativeCanvas).apply {",
            "android.graphics.Color.WHITE" to "$c.graphics.AndroidColor.WHITE",
            "android.graphics.Color.argb(" to "$c.graphics.AndroidColor.argb(",
            "android.graphics.Paint" to "$c.graphics.Paint",
            "android.graphics.Typeface.DEFAULT_BOLD" to "$c.graphics.Typeface.DEFAULT_BOLD",
        ),
        // A face's font for its chip and row: Font(R.font.x) as Fonts.kt has it (ui/theme/FontFiles.kt), and Face.fontRes
        // here (book/FaceFonts.kt: the same files as Android's PaintTextMeasure names).
        "ui/book/ToolPanels.kt" to listOf("import androidx.compose.ui.text.font.Font" to "import com.justdataplease.boringtravel.ui.theme.Font"),
        "ui/book/LayerList.kt" to listOf(
            mediaImage,
            "import androidx.compose.ui.text.font.Font" to "import com.justdataplease.boringtravel.ui.theme.Font",
            "Color(android.graphics.Color.parseColor(it))" to "Color($c.graphics.AndroidColor.parseColor(it))",
        ),
    )
}

/*
 * The community (round 24), compiled as it is: its words and numbers (CommunityModel: posts, profiles, the feed's texts
 * and counts; Usernames; ProfileAvatar), the repository's operations every screen calls (CommunityRepository), the
 * leaderboard and the hearts across the screens (Leaderboard with LikeBook), the Following feed's merged pages
 * (FollowingFeed), follows and their keeper (Follows, FollowKeeper), a post's link (CommunityLinks), a trip as a post
 * carries it (SharedTrip) and Copy this trip (TripCopy). And the screens, as far as they are plain Compose: the Community
 * tab (CommunityScreen with FeedViewModel), a post (PostScreen with PostViewModel), the shared parts (CommunityParts),
 * the sheets and dialogs (CommunitySheets: rules, Report, the ⋮ menu, Copy this trip's first day), the photo viewer
 * (MediaViewer), Follow's pieces (FollowParts), a profile, its follow lists and Community profile (ProfileScreen,
 * FollowListScreen, ProfileEditScreen). Written again here: community/Community.kt (Android's Community: Firebase,
 * Coil), community/FirestoreCommunity.kt (Firestore's REST API in place of the SDK), and what the cuts below take out
 * (ui/community/CommunityPhone.kt). What the screens reach of Android is compat's (compat/AndroidScreens.kt: Toast,
 * the share Intent, LocalContext, ViewModel and viewModel(key)) and this app's (ios/CommunityNav.kt: AppGraph and
 * AppActions as far as the community calls them).
 */
val communityFiles = listOf(
    "community/CommunityModel.kt", "community/CommunityRepository.kt", "community/Leaderboard.kt", "community/FollowingFeed.kt",
    "community/Follows.kt", "community/FollowKeeper.kt", "community/CommunityLinks.kt", "community/SharedTrip.kt",
    "community/TripCopy.kt", "community/Usernames.kt", "community/ProfileAvatar.kt",
    "ui/community/CommunityParts.kt", "ui/community/CommunityScreen.kt", "ui/community/FeedViewModel.kt",
    "ui/community/PostScreen.kt", "ui/community/PostViewModel.kt", "ui/community/CommunitySheets.kt",
    "ui/community/MediaViewer.kt", "ui/community/FollowParts.kt", "ui/community/ProfileScreen.kt",
    "ui/community/FollowListScreen.kt", "ui/community/ProfileEditScreen.kt",
    // Round 25, posting: the draft's rules, the upload, the trip-to-post links, an update's rules (PostSyncs; postSource
    // and the PostSync class are cut and written again in community/PostPhone.kt), the composer and its view-model, and
    // the trip's Community post sheet.
    "community/PostDraft.kt", "community/PostUploader.kt", "community/PostLinks.kt", "community/PostSync.kt",
    "ui/community/ComposeScreen.kt", "ui/community/ComposeViewModel.kt", "ui/community/PostSyncSheet.kt",
)

/*
 * The community's lines rewritten on the way in: java.time, java.text and java.util by compat's names and the engine's
 * days (days ordered by their epoch day), Java's number formats as JavaFormat writes them, Normalizer as art/SceneText.kt's
 * decomposed(), the JVM's lock as compat's, the clock as nowMs(); in the screens Android's Toast, share intent,
 * LocalContext and view-models as compat's, AppGraph and AppActions as this app's (ios/CommunityNav.kt), this app's
 * pictures, flags, kind badges, maps and place photos (ios/), and Coil's, the onboarding's and the WebView's imports left
 * as notes where the functions using them are cut out. Two private parts of CommunityScreen become internal, for the
 * sign-in gate written again here. The Report sheet gets the contact line Apple's rule 1.2 asks for (iPhone only).
 */
val communityRewrites: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    fun to(old: String, new: String) = "import $old" to "import $new"
    fun gone(name: String, why: String) = "import $name" to "// $name: $why"
    fun ios(pkg: String, name: String) = to("com.justdataplease.boringtravel.$pkg.$name", "com.justdataplease.boringtravel.ios.$name")
    val graph = to("com.justdataplease.boringtravel.AppGraph", "com.justdataplease.boringtravel.ios.CommunityGraph as AppGraph")
    val actions = to("com.justdataplease.boringtravel.ui.nav.AppActions", "com.justdataplease.boringtravel.ios.CommunityActions as AppActions")
    val viewModel = to("androidx.lifecycle.ViewModel", "$c.ViewModel")
    val scope = to("androidx.lifecycle.viewModelScope", "$c.viewModelScope")
    val viewModelOf = to("androidx.lifecycle.viewmodel.compose.viewModel", "$c.viewModel")
    val collect = to("androidx.lifecycle.compose.collectAsStateWithLifecycle", "$c.collectAsStateWithLifecycle")
    val toast = to("android.widget.Toast", "$c.Toast")
    val context = to("androidx.compose.ui.platform.LocalContext", "$c.LocalContext")
    val day = to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate")
    val now = "System.currentTimeMillis()" to "com.justdataplease.boringtravel.time.nowMs()"
    val cut = "its function is cut out on the way in (written again in ui/community/CommunityPhone.kt)."
    val cutPost = "its function is cut out on the way in (written again in community/PostPhone.kt)."
    mapOf(
        "community/CommunityModel.kt" to listOf(
            to("java.text.NumberFormat", "$c.JavaFormat"), to("java.time.Instant", "$c.Instant"), to("java.time.YearMonth", "$c.yearMonthDay"),
            to("java.time.ZoneOffset", "$c.ZoneOffset"), to("java.time.format.DateTimeFormatter", "$c.DateTimeFormatter"), to("java.util.Locale", "$c.Locale"),
            "NumberFormat.getIntegerInstance(locale).format(km)" to "JavaFormat.groupedInt(km.toLong(), locale)",
            "\"%.1f\".format(Locale.ROOT, n / 1_000.0)" to "JavaFormat.oneDecimal(n / 1_000.0)",
            "\"%.1f\".format(Locale.ROOT, n / 1_000_000.0)" to "JavaFormat.oneDecimal(n / 1_000_000.0)",
            "YearMonth.parse(key, MONTH_KEY).format(DateTimeFormatter.ofPattern(\"MMM yyyy\", locale))" to
                "DateTimeFormatter.ofPattern(\"MMM yyyy\", locale).format(yearMonthDay(key))",
        ),
        "community/CommunityRepository.kt" to listOf(to("java.io.IOException", "com.justdataplease.boringtravel.io.IOException")),
        // One lock for the follows' short sections (compat's synchronized); the clock is the engine's.
        "community/FollowKeeper.kt" to listOf(
            to("kotlinx.coroutines.withTimeoutOrNull", "kotlinx.coroutines.withTimeoutOrNull\nimport $c.synchronized"),
            "private val clock: () -> Long = System::currentTimeMillis," to "private val clock: () -> Long = { com.justdataplease.boringtravel.time.nowMs() },",
        ),
        "community/SharedTrip.kt" to listOf(
            to("java.time.Instant", "$c.Instant"), to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport $c.isBefore"),
            to("java.time.ZoneId", "$c.ZoneId"),
            "val first = listOfNotNull(trip.startDate, itemDays.minOrNull()).minOrNull()" to
                "val first = listOfNotNull(trip.startDate, itemDays.minByOrNull { it.toEpochDay() }).minByOrNull { it.toEpochDay() }",
            "val last = listOfNotNull(trip.endDate, itemDays.maxOrNull()).maxOrNull()" to
                "val last = listOfNotNull(trip.endDate, itemDays.maxByOrNull { it.toEpochDay() }).maxByOrNull { it.toEpochDay() }",
            "encode(t).toByteArray(Charsets.UTF_8).size" to "encode(t).encodeToByteArray().size",
        ),
        "community/TripCopy.kt" to listOf(ios("links", "LinkPlaces"), day),
        "community/Usernames.kt" to listOf(
            to("java.text.Normalizer", "com.justdataplease.boringtravel.art.decomposed"),
            "import java.util.Locale" to "// Locale: Kotlin's own lowercase() here.",
            "lowercase(Locale.ROOT)" to "lowercase()",
            "Normalizer.normalize(source, Normalizer.Form.NFD)" to "decomposed(source)",
        ),
        "ui/community/CommunityParts.kt" to listOf(
            gone("androidx.compose.ui.platform.LocalContext", cut), gone("coil3.compose.AsyncImage", cut), gone("coil3.network.NetworkHeaders", cut),
            gone("coil3.network.httpHeaders", cut), gone("coil3.request.ImageRequest", cut), gone("com.justdataplease.boringtravel.graph", cut),
            gone("com.justdataplease.boringtravel.ui.onboarding.findActivity", cut),
            ios("ui.components", "DrawnScene"), ios("ui.world", "TripFlags"),
            "    now: Long = System.currentTimeMillis()," to "    now: Long = com.justdataplease.boringtravel.time.nowMs(),",
        ),
        "ui/community/CommunityScreen.kt" to listOf(
            graph, actions, viewModelOf, collect, ios("ui.components", "DrawnScene"),
            gone("androidx.compose.ui.platform.LocalContext", cut), gone("com.justdataplease.boringtravel.ui.onboarding.findActivity", cut),
            gone("java.time.LocalDate", cut),
            "val now = remember(state.posts) { System.currentTimeMillis() }" to "val now = remember(state.posts) { com.justdataplease.boringtravel.time.nowMs() }",
            "private fun CommunityHeader(" to "internal fun CommunityHeader(",
            "private fun GateLine(" to "internal fun GateLine(",
            // A build without the community's Firebase setup: on iPhone the store is the App Store.
            "\"The community needs the app from Google Play or GitHub.\"" to "\"The community needs the app from the App Store.\"",
        ),
        "ui/community/FeedViewModel.kt" to listOf(
            viewModel, scope, graph,
            "import com.justdataplease.boringtravel.data.repo.Stamps" to "// Stamps: the engine's clock here (nowMs).",
            "PostText.monthKey(Stamps.now())" to "PostText.monthKey(com.justdataplease.boringtravel.time.nowMs())",
        ),
        "ui/community/PostScreen.kt" to listOf(
            to("android.content.Intent", "$c.Intent"), toast, context, collect, viewModelOf, graph, actions,
            ios("links", "PlacePhotos"), ios("ui.map", "BtMap"), ios("ui.trip", "KindBadge"), ios("ui.world", "TripFlags"),
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.time.parseLocalDateOrNull"),
            "context: android.content.Context, actions: AppActions" to "context: $c.Context, actions: AppActions",
            "runCatching { LocalDate.parse(it) }.getOrNull()" to "parseLocalDateOrNull(it)",
            now,
        ),
        "ui/community/PostViewModel.kt" to listOf(
            viewModel, scope, graph, day, to("java.io.File", "$c.File"),
            to("com.justdataplease.boringtravel.data.repo.now", "com.justdataplease.boringtravel.time.nowMs as now"),
            "val ROW_START: LocalDate = LocalDate.of(2000, 1, 1)" to "val ROW_START: LocalDate = com.justdataplease.boringtravel.time.localDate(2000, 1, 1)",
        ),
        "ui/community/CommunitySheets.kt" to listOf(
            toast, context, to("java.time.Instant", "$c.Instant"), day, to("java.time.ZoneOffset", "$c.ZoneOffset"),
            // Apple's rule 1.2: a way to reach the developer where content is reported (iPhone only, line 1170).
            "            error?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = c.danger) }" to
                "            error?.let { Text(it, Modifier.padding(top = 8.dp), style = Bt.text.sub, color = c.danger) }\n" +
                "            CommunityContact(Modifier.padding(top = 12.dp))",
        ),
        "ui/community/MediaViewer.kt" to listOf(
            gone("android.net.Uri", cut), gone("android.view.Gravity", cut), gone("android.widget.FrameLayout", cut),
            gone("android.widget.VideoView", cut), gone("androidx.compose.ui.viewinterop.AndroidView", cut), to("java.io.File", "$c.File"),
            "DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)" to
                "DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false, scrimColor = Color.Black)",
        ),
        "ui/community/FollowParts.kt" to listOf(toast, context, actions),
        "ui/community/ProfileScreen.kt" to listOf(toast, context, viewModel, scope, viewModelOf, collect, graph, actions),
        "ui/community/FollowListScreen.kt" to listOf(viewModel, scope, viewModelOf, collect, graph, actions),
        "ui/community/ProfileEditScreen.kt" to listOf(toast, context, viewModel, scope, viewModelOf, collect, graph, actions),
        // Round 25, posting. The draft: java.io.File and java.time's month by compat's names, the first planned day by
        // its epoch day (the engine's days order that way).
        "community/PostDraft.kt" to listOf(
            to("java.io.File", "$c.File"), to("java.time.format.DateTimeFormatter", "$c.DateTimeFormatter"), to("java.util.Locale", "$c.Locale"),
            "val first = source.trip.startDate ?: source.liveItems.mapNotNull { it.day }.minOrNull()" to
                "val first = source.trip.startDate ?: source.liveItems.mapNotNull { it.day }.minByOrNull { it.toEpochDay() }",
        ),
        // The upload: compat's File and java.time, the engine's clock, Kotlin's own upper case for the country codes.
        "community/PostUploader.kt" to listOf(
            to("java.io.File", "$c.File"), to("java.time.Instant", "$c.Instant"), to("java.time.ZoneId", "$c.ZoneId"),
            to("java.time.format.DateTimeFormatter", "$c.DateTimeFormatter"), to("java.util.Locale", "$c.Locale"),
            "    private val now: () -> Long = System::currentTimeMillis," to "    private val now: () -> Long = { com.justdataplease.boringtravel.time.nowMs() },",
            "it.uppercase(Locale.ROOT)" to "it.uppercase()",
        ),
        // The trip-to-post links: one small file under the app's files (MediaFiles: the phone's files, or memory in the
        // tests), written beside and moved over as Android writes its file; never in a backup.
        "community/PostLinks.kt" to listOf(
            to("java.io.File", "com.justdataplease.boringtravel.ios.MediaFiles"),
            "class PostLinks(private val file: File) {" to "class PostLinks(private val files: MediaFiles, private val path: String = \"community/post-links.json\") {",
            "@Volatile private var loaded = false" to "@kotlin.concurrent.Volatile private var loaded = false",
            "_all.value = withContext(Dispatchers.IO) { runCatching { if (file.isFile) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()) }" to
                "_all.value = withContext(Dispatchers.Default) { runCatching { files.read(path)?.let { decode(it.decodeToString()) } ?: emptyMap() }.getOrDefault(emptyMap()) }",
            "withContext(Dispatchers.IO) { write(now) }" to "withContext(Dispatchers.Default) { write(now) }",
            "        file.parentFile?.mkdirs()" to "        // MediaFiles.write makes the folder.",
            "        val tmp = File(file.path + \".tmp\")" to "        val tmp = \"\$path.tmp\"",
            "        tmp.writeText(encode(links))" to "        files.write(tmp, encode(links).encodeToByteArray())",
            "        if (!tmp.renameTo(file)) {" to "        if (!files.move(tmp, path)) {",
            "            file.delete()" to "            files.delete(path)",
            "            tmp.renameTo(file)" to "            files.move(tmp, path)",
        ),
        // An update's rules (PostSyncs) as they are; postSource and the PostSync class reach Android's AppGraph, WorkManager
        // and files (cut below, written again in community/PostPhone.kt). The fingerprint's SHA-256 is compat's.
        "community/PostSync.kt" to listOf(
            gone("android.content.Context", cutPost), gone("androidx.work.WorkManager", cutPost), gone("com.justdataplease.boringtravel.AppGraph", cutPost),
            gone("com.justdataplease.boringtravel.data.repo.now", cutPost), gone("java.io.File", cutPost), gone("java.io.IOException", cutPost),
            to("java.security.MessageDigest", "$c.MessageDigest"),
            "val digest = MessageDigest.getInstance(\"SHA-256\").digest(text.toByteArray(Charsets.UTF_8))" to
                "val digest = MessageDigest.getInstance(\"SHA-256\").apply { update(text.encodeToByteArray()) }.digest()",
            "return digest.joinToString(\"\") { \"%02x\".format(it) }.take(32)" to
                "return digest.joinToString(\"\") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(32)",
        ),
        // The composer: Android's BackHandler as this app's (ios/Back.kt), a clip's picture on the phone through
        // CommunityPhone.kt's AsyncImage (Coil's there), this app's photos, Drive's connect (CommunityPhone.kt's
        // rememberDriveConnect), the engine's days and clock; the store a build without Firebase names is the App Store.
        "ui/community/ComposeScreen.kt" to listOf(
            to("androidx.activity.compose.BackHandler", "com.justdataplease.boringtravel.ios.BackHandler"), collect, viewModelOf, graph, actions,
            gone("coil3.compose.AsyncImage", cut), ios("ui.components", "MediaImage"), ios("ui.nav", "CommunityCompose"),
            gone("com.justdataplease.boringtravel.ui.onboarding.rememberDriveConnect", cut),
            "\"The community needs the app from Google Play or GitHub.\"" to "\"The community needs the app from the App Store.\"",
            "val startedAt = remember { System.currentTimeMillis() }" to "val startedAt = remember { com.justdataplease.boringtravel.time.nowMs() }",
            "Dates.localDate(e.occurredAt, e.zoneId)" to "com.justdataplease.boringtravel.time.localDateAt(e.occurredAt, e.zoneId)",
            "java.time.Instant.ofEpochMilli(clip.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()" to
                "com.justdataplease.boringtravel.time.localDateAt(clip.createdAt, null)",
        ),
        // Its view-model: compat's ViewModel, the saved clips as files under the app's files (ios/PostClips), the engine's
        // clock, this app's route.
        "ui/community/ComposeViewModel.kt" to listOf(
            viewModel, scope, graph, ios("ui.nav", "CommunityCompose"),
            to("com.justdataplease.boringtravel.data.SavedClips", "com.justdataplease.boringtravel.ios.PostClips as SavedClips"),
            to("com.justdataplease.boringtravel.data.repo.now", "com.justdataplease.boringtravel.time.nowMs as now"),
            "val clips: SavedClips = SavedClips.of(graph.app)" to "val clips: SavedClips = SavedClips(graph.files)",
        ),
        // The trip's Community post sheet: its "5 minutes ago" (Android's DateUtils) is cut and written again.
        "ui/community/PostSyncSheet.kt" to listOf(gone("android.text.format.DateUtils", cut), graph, actions),
    )
}

/*
 * Travel together (round 26), compiled as it is: a member's copy as a file (ShareFormat), its gzip JSON with every check
 * and mend (ShareCodec), the join link (ShareLink), what this phone keeps of each shared trip (ShareState), the merge
 * (TogetherMerge: the later edit wins, deletions travel, authors kept), the engine of a sync, a join, an add, leaving and
 * the photos (TogetherEngine, over its own interfaces), the trips' mailboxes' rules (TripMail, TogetherMail), the send
 * queue's rule (SendQueue), and the screens: Trips' "Join a friend's trip" (JoinTripRow), the Travel together sheet with
 * its texts (TogetherSheet, TogetherText), the Join sheet (JoinSheet) and their one-action-at-a-time (SheetWork).
 * Written again here: together/Together.kt (Android's Together: Room, WorkManager, Drive and the photos' copies on this
 * app's trip store, Drive REST, PublicDrive, the send queue kept on the phone and BGAppRefreshTask), together/
 * FirestoreTripMail.kt (Firestore's REST API in place of the SDK), ui/together/TogetherGoogle.kt (Google's steps on
 * iPhone: one page for the account and Drive) and ios/TogetherNav.kt (what the sheets reach of AppGraph).
 */
val togetherFiles = listOf(
    "together/ShareFormat.kt", "together/ShareState.kt", "together/ShareLink.kt", "together/ShareCodec.kt",
    "together/TogetherMerge.kt", "together/TogetherEngine.kt", "together/TogetherMail.kt", "together/TripMail.kt",
    "together/SendQueue.kt",
    "ui/together/JoinTripRow.kt", "ui/together/SheetWork.kt", "ui/together/TogetherSheet.kt", "ui/together/JoinSheet.kt",
)

/*
 * Travel together's lines rewritten on the way in: java.io's streams, java.util.zip's gzip, java.net's form encoding,
 * java.security's SHA-256 and java.util.concurrent's maps by compat's names (compat/Gzip.kt, JavaIo.kt, JavaUtil.kt) and
 * UrlCodec's, java.time's date check by the engine's, UUID by the engine's ids, "%02x" written out; in the sheets Android's
 * Context, Intent and LocalContext as compat's, AppGraph as this app's (ios/TogetherNav.kt), Dispatchers.IO as Default and
 * Dispatchers.Main as the sheets' own scope (the app frame's, on the main thread). The join link's pattern is said without
 * a negated class holding \s and other characters (the shape Kotlin/Native misread with \p{…}: RegexShapesSmokeTest).
 */
val togetherRewrites: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    fun to(old: String, new: String) = "import $old" to "import $new"
    val hex = "\"%02x\".format(it)" to "(it.toInt() and 0xFF).toString(16).padStart(2, '0')"
    val sha = to("java.security.MessageDigest", "$c.MessageDigest\nimport $c.Charsets\nimport $c.toByteArray")
    val concurrent = to("java.util.concurrent.ConcurrentHashMap", "$c.ConcurrentHashMap")
    val graph = to("com.justdataplease.boringtravel.AppGraph", "com.justdataplease.boringtravel.ios.TogetherGraph as AppGraph")
    val context = to("androidx.compose.ui.platform.LocalContext", "$c.LocalContext")
    mapOf(
        "together/ShareLink.kt" to listOf(
            to("java.net.URLDecoder", "com.justdataplease.boringtravel.ios.UrlCodec"),
            "import java.net.URLEncoder" to "// URLEncoder and URLDecoder: UrlCodec's form encoding here (Java's own rules).",
            "URLEncoder.encode(s, \"UTF-8\")" to "UrlCodec.formEncode(s)",
            "URLDecoder.decode(s.replace(\"+\", \"%2B\"), \"UTF-8\")" to "UrlCodec.formDecode(s.replace(\"+\", \"%2B\"))",
            """[#?]([^\s<>"']+)""" to """[#?]((?:(?![<>"'])\S)+)""",
        ),
        "together/ShareCodec.kt" to listOf(
            to("com.justdataplease.boringtravel.links.LinkPlaces", "com.justdataplease.boringtravel.ios.LinkPlaces"),
            to("java.io.ByteArrayInputStream", "$c.ByteArrayInputStream"),
            to("java.io.ByteArrayOutputStream", "$c.ByteArrayOutputStream\nimport $c.Charsets\nimport $c.toByteArray"),
            to("java.io.IOException", "com.justdataplease.boringtravel.io.IOException"),
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.parseLocalDateOrNull"),
            to("java.util.zip.GZIPInputStream", "$c.GZIPInputStream"),
            to("java.util.zip.GZIPOutputStream", "$c.GZIPOutputStream"),
            "runCatching { LocalDate.parse(it) }.isFailure" to "parseLocalDateOrNull(it) == null",
        ),
        "together/TogetherMerge.kt" to listOf(sha, hex),
        "together/TripMail.kt" to listOf(sha, hex),
        "together/TogetherEngine.kt" to listOf(
            to("java.io.File", "$c.File"),
            to("java.io.IOException", "com.justdataplease.boringtravel.io.IOException"),
            to("java.security.MessageDigest", "$c.MessageDigest"),
            to("java.util.UUID", "com.justdataplease.boringtravel.data.repo.newId as newRowId"),
            "private val newId: () -> String = { UUID.randomUUID().toString() }," to "private val newId: () -> String = { newRowId() },",
            hex,
        ),
        "together/TogetherMail.kt" to listOf(concurrent),
        "together/SendQueue.kt" to listOf(concurrent),
        // One action at a time on the sheets' scope (the app frame's, on the main thread: Android launches on Main).
        "ui/together/SheetWork.kt" to listOf(
            "scope.launch(Dispatchers.Main) { go(work) }" to "scope.launch { go(work) }",
            "scope.launch(Dispatchers.Main) {" to "scope.launch {",
        ),
        "ui/together/TogetherSheet.kt" to listOf(
            to("android.content.Context", "$c.Context"), to("android.content.Intent", "$c.Intent"), context, graph,
            to("com.justdataplease.boringtravel.ui.onboarding.findActivity", "$c.findActivity"),
            "System.currentTimeMillis()" to "com.justdataplease.boringtravel.time.nowMs()",
            "withContext(Dispatchers.IO)" to "withContext(Dispatchers.Default)",
            "        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)" to
                "        // The share sheet comes over the app's own screen (Android adds a new task outside an Activity).",
        ),
        "ui/together/JoinSheet.kt" to listOf(context, graph),
    )
}

/*
 * Sharing into the app (round 27): Add to plan, the sheet a link shared from another app opens (ShareToPlanSheet: the
 * link's preview, the trip and day chips, Place / Food / Stay, Add, "Copy its notes and pictures", a Google Maps list's
 * "Add its N places"), and its rules (ShareTargets: the trips offered, the trip, day and kind first chosen, the item it
 * makes), compiled as they are. Written again here: what Android's MainActivity does with a share (ios/ShareIn.kt: the
 * share extension's folder in the App Group, the routing, Add to plan's AppGraph) and the extension itself
 * (iosApp/BoringTravelShare, Swift).
 */
val shareFiles = listOf("ui/share/ShareTargets.kt", "ui/share/ShareToPlanSheet.kt")

/*
 * Their lines rewritten on the way in: this app's link and plan rules and the link's preview card (ios/), the engine's
 * days (trips ordered by their epoch day, compat's isAfter, a day from its epoch day), the sheet's today the app frame's
 * (graph.today(): the real day in the app, the previews' own in the tests), the stamp Stamps.now() (Android's
 * repositories' now()), and AppGraph this app's ShareGraph.
 */
val shareRewrites: Map<String, List<Pair<String, String>>> = run {
    fun to(old: String, new: String) = "import $old" to "import $new"
    fun ios(pkg: String, name: String) = to("com.justdataplease.boringtravel.$pkg.$name", "com.justdataplease.boringtravel.ios.$name")
    mapOf(
        "ui/share/ShareTargets.kt" to listOf(
            ios("links", "LinkFill"), ios("links", "LinkPlaces"), ios("links", "LinkPreview"), ios("links", "MapsLinks"),
            ios("links", "MapsPlace"), ios("links", "SharedLink"), ios("ui.trip", "PlanOrder"),
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.isAfter"),
            "compareBy<Trip>({ it.startDate }, { it.createdAt })" to "compareBy<Trip>({ it.startDate?.toEpochDay() }, { it.createdAt })",
            "compareBy<Trip>({ it.startDate }, { -it.createdAt })" to "compareBy<Trip>({ it.startDate?.toEpochDay() }, { -it.createdAt })",
            "compareBy<Trip>({ it.endDate ?: it.startDate }, { it.createdAt })" to
                "compareBy<Trip>({ (it.endDate ?: it.startDate)?.toEpochDay() }, { it.createdAt })",
        ),
        "ui/share/ShareToPlanSheet.kt" to listOf(
            to("com.justdataplease.boringtravel.AppGraph", "com.justdataplease.boringtravel.ios.ShareGraph as AppGraph"),
            to("com.justdataplease.boringtravel.data.repo.now", "com.justdataplease.boringtravel.data.repo.Stamps"),
            ios("links", "LinkPlaces"), ios("links", "MapsLinks"), ios("links", "MapsList"), ios("links", "SharedLink"),
            ios("ui.trip", "LinkPreviewCard"), ios("ui.trip", "PlanDays"), ios("ui.trip", "kindIcon"), ios("ui.trip", "rememberLinkPreview"),
            to("java.time.LocalDate", "com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.time.localDateOfEpochDay"),
            "val today = remember { LocalDate.now() }" to "val today = remember { graph.today() }",
            "LocalDate.ofEpochDay(k.toLong())" to "localDateOfEpochDay(k.toLong())",
            "ShareTargets.item(newId(), t, day, kind, link, shown, items, now())" to
                "ShareTargets.item(newId(), t, day, kind, link, shown, items, Stamps.now())",
        ),
    )
}

/*
 * Whole functions cut out of a file on the way in, where they reach Android (a WebView, an Activity, Android's
 * view-model and launchers, Drive): from the first text to the end of the function ("\n}\n", a top-level declaration's
 * closing brace). This app writes them again (named in each note); the rest of the file is compiled as it is. The build
 * stops if a cut no longer finds its text.
 */
val cuts: Map<String, List<Pair<String, String>>> = mapOf(
    // Round 24, the community: what reaches Firebase, Coil, Android's sign-in and its video view (ui/community/CommunityPhone.kt
    // here). communityOf() is one line, so its end is its own.
    "ui/community/CommunityParts.kt" to listOf(
        "/** The app's community. */\n@Composable\ninternal fun communityOf()" to ".graph.community\n",
        "/**\n * Picture [fileId] from a poster's Drive" to "\n}\n",
        "/**\n * \"Sign in with Google to see and post\": the community's sign-in card" to "\n}\n",
    ),
    "ui/community/CommunityScreen.kt" to listOf(
        "/**\n * Signed out: what the community is, three drawn postcards, and Sign in with Google." to "\n}\n",
        "/** Post from the Community tab: the traveller's own trips" to "\n}\n",
    ),
    "ui/community/MediaViewer.kt" to listOf(
        "/**\n * A video full screen, with sound: plays at once, a tap pauses or plays." to "\n}\n",
    ),
    // Round 25: a trip as Post to community sees it and the PostSync class (Android's AppGraph, WorkManager, files:
    // community/PostPhone.kt here); the sheet's "5 minutes ago" (Android's DateUtils: ui/community/CommunityPhone.kt).
    "community/PostSync.kt" to listOf(
        "/** The trip [tripId] as Post to community sees it" to "\n}\n",
        "/**\n * Trip posts kept in step with their trips (owner, 17 Nov 2026), by hand only" to "\n}\n",
    ),
    "ui/community/PostSyncSheet.kt" to listOf("/** \"just now\", \"5 minutes ago\", \"yesterday\". */\nprivate fun ago(" to "\n}\n"),
    // PagePreview (a WebView): ui/book/PagePreview.kt here (WebKit on iPhone, the stand-in on the desktop).
    "ui/book/PageViews.kt" to listOf("@SuppressLint(\"ClickableViewAccessibility\", \"SetJavaScriptEnabled\")\n@Composable\nfun PagePreview(" to "\n}\n"),
    // BookScreen, BookEditor, MakeSheet and Context.findActivity: ios/BookScreen.kt here.
    "ui/book/BookScreen.kt" to listOf(
        "/**\n * Make a book, in the dark studio" to "\n}\n",
        "@Composable\nprivate fun BookEditor(" to "\n}\n",
        "/**\n * The PDF: save to the phone" to "\n}\n",
        "private tailrec fun Context.findActivity()" to "\n}\n",
    ),
)

/*
 * The Android app's screen files this app compiles exactly as they are, so both phones look the same: the looks, the
 * icons and the components every screen is built from. (The trips, the plan's rules, the trip ideas and the backup
 * format are not here: they are the engine, the :shared module.) A file joins this list once it is plain
 * Kotlin and Compose (tools/portability.py finds candidates; `:composeApp:compileCommonMainKotlinMetadata` proves it).
 * Nothing is copied into git: the files are read from the Android app at build time.
 */
val sharedFiles = listOf(
    "ui/theme/Looks.kt",
    "ui/theme/Theme.kt",
    "ui/theme/NavBars.kt",
    "ui/theme/Fonts.kt",
    "ui/icons/BtIcons.kt",
    "ui/components/Components.kt",
    "ui/components/BtConfirm.kt",
    "ui/programs/ProgramPreview.kt",
    // The bottom bar's two modes (Trips · Calendar · Map · Community · Me, and a trip's Plan · Journal · Map · Media)
    // and what a tap on it does.
    "ui/nav/TripNav.kt",
    "art/PlaneMark.kt",
    // The journal: the editor's text blocks in their groups, a paragraph's HTML read into styled runs (what the note
    // and the editor draw), and where a photo sits in the text column.
    "ui/editor/EditorHtml.kt",
    "ui/journal/HtmlRuns.kt",
    "doc/PhotoPlacement.kt",
    // Me › Trips look: Tickets or Pictures.
    "data/settings/TripsLook.kt",
    // Edit trip › Photos in notes: each trip's size and frame for new photos and videos, kept as one text under a
    // backup's "tripPhotos" (the second parity round).
    "data/settings/TripPhotos.kt",
    // The drawn pictures where there is no photo: the ten kinds as shapes, the themes' palettes, which kind a trip or
    // place gets, and each trip idea's own. Drawn here by ios/DrawnScenes.kt (Android: art/SceneArt.kt).
    "art/SceneShapes.kt",
    "art/Scenes.kt",
    "programs/ProgramScenes.kt",
    // Maps and routes: the map looks and their style JSON (Street, Terrain, Earth), distances, bearings and boxes on
    // the globe, the route cleaned of bad fixes, split at gaps and measured, stays found and merged into stops, plan
    // items ticked when the route passed them, what a map shows (routes, pins, the planned route as GeoJSON) and the
    // trip map's leg sheet. Drawn here by ios/BtMap.kt (MapLibre on iPhone; Android: ui/map/BtMap.kt).
    "clip/MapStyles.kt",
    "geo/Geo.kt",
    "tracking/RouteShape.kt",
    "tracking/SpikeFilter.kt",
    "tracking/StopDetector.kt",
    "places/VisitMatcher.kt",
    "ui/map/MapData.kt",
    "ui/map/LegSheetModel.kt",
    // Dictation: how spoken words join the text (spaces, capitals, punctuation) and stream into it, and what to do after
    // each recognizer error. The recognizer is the phone's (ios/Speech.kt; Android: speech/Dictation.kt).
    "speech/DictationText.kt",
    "speech/DictationRecovery.kt",
    // The daily check-in: today's places, whether today is written, a fresh fix near a place, the day's check-in note.
    "checkin/DailyCheckIn.kt",
    // Your world and the globe: the countries' outlines and the lookup on them, the countries' facts (continent, flag
    // pin, flag, name, % of the world), the globe's orthographic maths, its shapes, the clipping at the horizon, the flag
    // pins pushed apart and the colours from the theme. Drawn here by globe/GlobeRenderer.kt (Android: android.graphics).
    "geo/Countries.kt",
    "geo/CountryInfo.kt",
    "globe/GlobeMath.kt",
    "globe/GlobeGeometry.kt",
    "globe/RingClipper.kt",
    "globe/PinLayout.kt",
    "globe/GlobePalette.kt",
    // Backup files: the rules that keep a backup or a restore from failing (places off the Earth mended, links tidied,
    // nothing written for a phone with nothing on it).
    "backup/BackupSafety.kt",
    // Share (print and export): the trip as print HTML (made into a PDF by the phone's web view: ios/PagePrinter.kt), as
    // plain text and as a Word file; the days, photos and plan they print, the styles' CSS, photo rows, file names,
    // and the book's facts, stamps and flags they draw with. Their java.time, java.io and String.format calls go to
    // compat/ (JavaTime.kt, JavaIo.kt): the same names in plain Kotlin.
    "export/ExportOptions.kt",
    "export/ExportFiles.kt",
    "export/PhotoRows.kt",
    "export/PrintCss.kt",
    "export/RouteSvg.kt",
    "export/TripBook.kt",
    "export/TripHtml.kt",
    "export/TripText.kt",
    "export/TripDocx.kt",
    "book/BookFacts.kt",
    "book/BookSvg.kt",
    "book/Flags.kt",
    "book/CropMath.kt",
    "book/PageGeometry.kt",
    "doc/DocRender.kt",
    // Route tracking's pure rules (round 10): what records today and at which level, the steps it asks for, the stop
    // for today, Balanced's best fix backing off and Detailed resting while the phone stays put. The recording itself
    // is the phone's (ios/Tracking.kt over Core Location; Android: a location service).
    "tracking/TrackingPlan.kt",
    // The home-screen widgets' words and looks ("Day 4 · Naxos", "No trip today · Fri 6 Nov", each theme's paper and
    // buttons) and the Quick bar's layout at each width; the widget itself is SwiftUI (iosApp/BoringTravelWidget),
    // drawing the snapshot the app writes (ios/Widgets.kt).
    "widget/WidgetModel.kt",
    "widget/QuickBarLayout.kt",
    // Google Drive (round 23): Drive's scopes, field lists, queries, upload metadata, answers and errors in plain words
    // (its REST calls are google/RestDrive.kt here, over this app's Net), the too-big export's exception, and the saved
    // trip videos in Drive's Videos folder (each once, their private properties, what a restore fetches).
    "google/DriveJson.kt",
    "google/DriveExportTooLargeException.kt",
    "backup/BackupVideos.kt",
) + aiFiles + siteFiles + clipFiles + bookFiles + communityFiles + togetherFiles + shareFiles

/*
 * A few lines are rewritten on the way in, where a shared file calls Java for something Kotlin has too (iPhone has no
 * Java). The result is the same. Each rule goes when the line is changed in the Android app itself.
 */
val rewrites: Map<String, List<Pair<String, String>>> = mapOf(
    // Two numbers formatted with String.format.
    "art/PlaneMark.kt" to listOf(
        "import java.util.Locale" to "import kotlin.math.roundToInt",
        "String.format(Locale.ROOT, \"#%06X\", c and 0xFFFFFF)" to "\"#\" + (c and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')",
        "String.format(Locale.ROOT, \"%.2f\", v)" to "((v * 100).roundToInt() / 100.0).toString()",
    ),
    // Java's floorMod; Kotlin's mod is the same. Me › Fonts › Text's "Phone (Roboto)" is FontFamily.Default, Roboto on
    // Android; on iPhone the default face is San Francisco, so it is Google's Roboto bundled here (ui/theme/FontFiles.kt
    // BtRoboto), the same face on both phones. This rule stays (the Android line is right for Android).
    "ui/theme/Looks.kt" to listOf(
        "Math.floorMod(index, LIST_TILTS.size)" to "index.mod(LIST_TILTS.size)",
        "TextFont.Phone -> FontFamily.Default to Face.System" to "TextFont.Phone -> BtRoboto.family to Face.System",
    ),
    // This one stays: without the import, Font(R.font.x, …) is this app's own (ui/theme/FontFiles.kt), which reads
    // the font file; Compose's has no Android resources to read on iPhone and would quietly draw the system font.
    "ui/theme/Fonts.kt" to listOf(
        "import androidx.compose.ui.text.font.Font" to "// Font(resId, …): FontFiles.kt, in this package.",
    ),
    // A character from its code point (an HTML entity such as &#127965;): Java's Character.toChars, as a surrogate pair.
    "ui/journal/HtmlRuns.kt" to listOf(
        "String(Character.toChars(cp))" to
            "(if (cp < 0x10000) cp.toChar().toString() else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString())",
    ),
    // Degrees to radians.
    "doc/PhotoPlacement.kt" to listOf(
        "Math.toRadians(" to "(kotlin.math.PI / 180.0) * (",
    ),
    // A trip's photo width with two decimals, as Java writes it ("0.65").
    "data/settings/TripPhotos.kt" to listOf(
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.JavaFormat",
        "String.format(Locale.ROOT, \"%.2f\", s.width)" to "JavaFormat.twoDecimals(s.width.toDouble())",
    ),
    // Which drawn picture a name asks for: words compared without accents (Java's Normalizer; here art/SceneText.kt),
    // a spot's key with one decimal, and the hash's bytes and remainder in plain Kotlin.
    "art/Scenes.kt" to listOf(
        "import java.text.Normalizer" to "// Normalizer: decomposed() in art/SceneText.kt here.",
        "import java.util.Locale" to "// Locale: Kotlin's own uppercase() and lowercase() here.",
        "code?.uppercase(Locale.ROOT)" to "code?.uppercase()",
        "\"%.1f,%.1f\".format(Locale.ROOT, lat, lng)" to "oneDecimal(lat) + \",\" + oneDecimal(lng)",
        "key.toByteArray(Charsets.UTF_8)" to "key.encodeToByteArray()",
        "Math.floorMod(h, n)" to "h.mod(n)",
        "Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)" to "decomposed(text.lowercase())",
    ),
    // A colour as "#RRGGBB" for a map style.
    "clip/MapStyles.kt" to listOf(
        "String.format(java.util.Locale.ROOT, \"#%06X\", color and 0xFFFFFF)" to "\"#\" + (color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')",
    ),
    // Java's degrees and radians (the same constants Math multiplies by), its pow and its round.
    "geo/Geo.kt" to listOf(
        "import kotlin.math.sqrt" to "import kotlin.math.sqrt\nimport kotlin.math.pow\nimport kotlin.math.roundToLong",
        "Math.toRadians(" to "0.017453292519943295 * (",
        "Math.toDegrees(" to "57.29577951308232 * (",
        "Math.pow(10.0, precision.toDouble())" to "10.0.pow(precision.toDouble())",
        "Math.round(p.lat * factor)" to "(p.lat * factor).roundToLong()",
        "Math.round(p.lng * factor)" to "(p.lng * factor).roundToLong()",
    ),
    // "18.4 km" and "1,234 km" as Java writes them in the phone's language ("18,4 km" in Greek: tracking/RouteShapeText.kt).
    "tracking/RouteShape.kt" to listOf(
        "String.format(java.util.Locale.getDefault(), \"%.1f km\", meters / 1_000)" to "RouteShapeText.oneDecimalKm(meters / 1_000)",
        "String.format(java.util.Locale.getDefault(), \"%,d km\", (meters / 1_000).toLong())" to "RouteShapeText.groupedKm((meters / 1_000).toLong())",
    ),
    // A point's day in a time zone given by its id (the engine's days), where Android takes a java.time zone.
    "places/VisitMatcher.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.time.localDateAt",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate",
        "import java.time.ZoneId" to "// ZoneId: a zone by its id here (null: the phone's).",
        "zone: ZoneId" to "zone: String?",
        "Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate()" to "localDateAt(it.time, zone)",
    ),
    "ui/map/MapData.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.time.localDateAt",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.time.startOfDayMs",
        "zone: ZoneId" to "zone: String?",
        "Instant.ofEpochMilli(now).atZone(zone).toLocalDate()" to "localDateAt(now, zone)",
        "today.atStartOfDay(zone).toInstant().toEpochMilli()" to "startOfDayMs(today, zone)",
        "today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()" to "startOfDayMs(today.plusDays(1), zone)",
    ),
    // The leg sheet: this app's travel rules (ios/PlanTravel.kt, ios/DayPlaces.kt) and the engine's days.
    "ui/map/LegSheetModel.kt" to listOf(
        "import com.justdataplease.boringtravel.clip.RouteLegs" to "import com.justdataplease.boringtravel.ios.RouteLegs",
        "import com.justdataplease.boringtravel.ui.place.PlaceGrouping" to "import com.justdataplease.boringtravel.ios.PlaceGrouping",
        "import com.justdataplease.boringtravel.ui.trip.LegEstimate" to "import com.justdataplease.boringtravel.ios.LegEstimate",
        "import com.justdataplease.boringtravel.ui.trip.travel" to "import com.justdataplease.boringtravel.ios.travel",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate",
        "!it.isAfter(today)" to "it.toEpochDay() <= today.toEpochDay()",
        "day.isBefore(start) || day.isAfter(end)" to "day.toEpochDay() < start.toEpochDay() || day.toEpochDay() > end.toEpochDay()",
    ),
    // A country's name in the phone's language (ios/CountryName: Foundation on iPhone), a flag's code points as a
    // surrogate pair, and Java's round (half up, as roundToLong).
    "geo/CountryInfo.kt" to listOf(
        "import java.util.IllformedLocaleException" to "import kotlin.math.roundToLong",
        "import java.util.Locale" to "// Locale: the phone's own names, ios/CountryName here.",
        "for (ch in c) sb.appendCodePoint(REGIONAL_A + (ch - 'A'))" to
            "for (ch in c) (REGIONAL_A + (ch - 'A')).let { cp -> sb.append((0xD800 + ((cp - 0x10000) shr 10)).toChar()).append((0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()) }",
        "Math.round(100.0 * n / WORLD_COUNT).toInt()" to "(100.0 * n / WORLD_COUNT).roundToLong().toInt()",
        "fun name(code: String, locale: Locale = Locale.getDefault()): String {" to "fun name(code: String): String {",
        "Locale.Builder().setRegion(c).build().getDisplayCountry(locale)" to "com.justdataplease.boringtravel.ios.countryName(c)",
        "catch (_: IllformedLocaleException)" to "catch (_: IllegalArgumentException)",
    ),
    // This app's link rules (ios/Links.kt), the same as the Android app's links/LinkPlaces.kt.
    "backup/BackupSafety.kt" to listOf(
        "import com.justdataplease.boringtravel.links.LinkPlaces" to "import com.justdataplease.boringtravel.ios.LinkPlaces",
    ),
    // Drive's answers (round 23): a time in RFC 3339 read by compat's Instant (the same moment).
    "google/DriveJson.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.compat.Instant",
    ),
    // Drive's private properties are UTF-8 bytes; a title is cut between whole code points (Java's, as compat's).
    "backup/BackupVideos.kt" to listOf(
        ".toByteArray(Charsets.UTF_8)" to ".encodeToByteArray()",
        "val cp = value.codePointAt(i)" to "val cp = com.justdataplease.boringtravel.compat.JavaFormat.codePointAt(value, i)",
        "String(Character.toChars(cp))" to "com.justdataplease.boringtravel.compat.JavaFormat.codePoint(cp)",
        "Character.charCount(cp)" to "com.justdataplease.boringtravel.compat.JavaFormat.charCount(cp)",
    ),
    // The globe: Java's degrees and radians (the same constants Math multiplies by) and its π.
    "globe/GlobeMath.kt" to listOf(
        "Math.toRadians(" to "0.017453292519943295 * (",
        "Math.toDegrees(" to "57.29577951308232 * (",
    ),
    "globe/GlobeGeometry.kt" to listOf(
        "Math.PI / 2" to "kotlin.math.PI / 2",
    ),
    // A language's name from its tag in the phone's own words (speech/LanguageNames.kt here), not Java's Locale.
    "speech/DictationText.kt" to listOf(
        "import java.util.Locale" to "// Locale: a language by its tag here (speech/LanguageNames.kt).",
        "fun languageName(tag: String, display: Locale): String {" to "fun languageName(tag: String, display: String): String {",
        "Locale.forLanguageTag(tag).getDisplayLanguage(display)" to "displayLanguage(tag, display)",
        "name.replaceFirstChar { it.titlecase(display) }" to "name.replaceFirstChar { it.titlecase() }",
    ),
    // Android's recognizer error codes, which this app's recognizer reports too (speech/SpeechErrors.kt).
    "speech/DictationRecovery.kt" to listOf(
        "import android.speech.SpeechRecognizer" to "// SpeechRecognizer: Android's error codes, speech/SpeechErrors.kt here.",
    ),
    // The engine's days and zones by id, this app's ItemNotes and its version 3 ids (ios/WebUrl.kt's JavaText).
    "checkin/DailyCheckIn.kt" to listOf(
        "import com.justdataplease.boringtravel.ui.trip.ItemNotes" to "import com.justdataplease.boringtravel.ios.ItemNotes",
        "import java.time.Instant" to "import com.justdataplease.boringtravel.time.localDateAt",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.time.systemZoneId",
        "import java.util.UUID" to "import com.justdataplease.boringtravel.ios.JavaText",
        "zone: ZoneId" to "zone: String?",
        "Instant.ofEpochMilli(now).atZone(zone).toLocalDate()" to "localDateAt(now, zone)",
        "it.name.lowercase(java.util.Locale.ROOT)" to "it.name.lowercase()",
        "Instant.ofEpochMilli(e.occurredAt).atZone(runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.systemDefault())).toLocalDate()" to "localDateAt(e.occurredAt, e.zoneId)",
        "UUID.nameUUIDFromBytes(" to "JavaText.nameUuid(",
        ".toByteArray(Charsets.UTF_8)" to ".encodeToByteArray()",
        "    ).toString()" to "    )",
        "zoneId = zone.id" to "zoneId = zone ?: systemZoneId()",
        "UUID.randomUUID().toString()" to "com.justdataplease.boringtravel.data.repo.newId()",
    ),
    // Share (print and export): java.time, java.util, java.io and java.net by the same names from compat/ (dates in the
    // locale asked for: the phone's by default), Java's number formats as JavaFormat writes them, Math's radians and round.
    "export/TripHtml.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.compat.Instant",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.*",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.compat.ZoneId",
        "import java.time.format.DateTimeFormatter" to "import com.justdataplease.boringtravel.compat.DateTimeFormatter",
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
        "Integer.toHexString(ch.code)" to "ch.code.toString(16)",
        "String.format(locale, \"%.1f km\", v)" to "JavaFormat.oneDecimal(v, locale) + \" km\"",
        "String.format(locale, \"%,.0f km\", v)" to "JavaFormat.grouped(v, locale) + \" km\"",
        "k.lowercase(locale)" to "k.lowercase()",
        "\"%02d:%02d\".format(Locale.ROOT, minutes / 60, minutes % 60)" to "JavaFormat.twoDigits(minutes / 60) + \":\" + JavaFormat.twoDigits(minutes % 60)",
        "\"%.3f\".format(Locale.ROOT, v)" to "JavaFormat.threeDecimals(v)",
    ),
    "export/TripBook.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.compat.Instant",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.*",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.compat.ZoneId",
        "sortedSetOf<LocalDate>()" to "DaySet()",
        "Math.toRadians(" to "0.017453292519943295 * (",
    ),
    "export/TripText.kt" to listOf(
        "import java.time.Instant" to "import com.justdataplease.boringtravel.compat.Instant",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.compat.ZoneId",
        "import java.time.format.DateTimeFormatter" to "import com.justdataplease.boringtravel.compat.DateTimeFormatter",
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale\nimport com.justdataplease.boringtravel.compat.*",
    ),
    "export/ExportOptions.kt" to listOf(
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.*",
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
    ),
    "export/ExportFiles.kt" to listOf(
        "import java.net.URI" to "import com.justdataplease.boringtravel.compat.URI",
        // Kotlin/Native's regular expressions drop the characters after a \p{…} in a negated class such as
        // [^\p{L}\p{N}&] (the iPhone Simulator dropped a Greek file name's comma, 19 Nov 2026): the same match, written
        // as a plain [^\p{L}\p{N}] that isn't one of the extra characters.
        """Regex("[^\\p{L}\\p{N} .,_-]+")""" to """Regex("(?:(?![ .,_-])[^\\p{L}\\p{N}])+")""",
    ),
    "export/TripDocx.kt" to listOf(
        "import java.io.File" to "import com.justdataplease.boringtravel.compat.File",
        "import java.io.OutputStream" to "import com.justdataplease.boringtravel.compat.OutputStream",
        "import java.io.OutputStreamWriter" to "import com.justdataplease.boringtravel.compat.OutputStreamWriter\nimport com.justdataplease.boringtravel.compat.*",
        "import java.io.Writer" to "import com.justdataplease.boringtravel.compat.Writer",
        "import java.time.Instant" to "import com.justdataplease.boringtravel.compat.Instant",
        "import java.time.ZoneId" to "import com.justdataplease.boringtravel.compat.ZoneId",
        "import java.time.format.DateTimeFormatter" to "import com.justdataplease.boringtravel.compat.DateTimeFormatter",
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
        "import java.util.zip.ZipEntry" to "import com.justdataplease.boringtravel.compat.ZipEntry",
        "import java.util.zip.ZipOutputStream" to "import com.justdataplease.boringtravel.compat.ZipOutputStream",
        "l.days.lowercase(locale)" to "l.days.lowercase()",
        "l.photos.lowercase(locale)" to "l.photos.lowercase()",
        "Character.isValidCodePoint(code)) String(Character.toChars(code))" to "code in 0..0x10FFFF) JavaFormat.codePoint(code)",
    ),
    "export/RouteSvg.kt" to listOf(
        "import java.util.Locale" to "import kotlin.math.roundToLong",
        "Math.toRadians(" to "0.017453292519943295 * (",
        "Math.round(v * 10) / 10.0" to "(v * 10).roundToLong() / 10.0",
        "String.format(Locale.ROOT, \"%.1f\", r)" to "com.justdataplease.boringtravel.compat.JavaFormat.oneDecimal(r)",
    ),
    "book/BookFacts.kt" to listOf(
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.*",
        "import java.time.format.DateTimeFormatter" to "import com.justdataplease.boringtravel.compat.DateTimeFormatter",
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
        "java.time.ZoneId.of(" to "ZoneId.of(",
        "java.time.Instant.ofEpochMilli(" to "Instant.ofEpochMilli(",
        "String.format(locale, \"%.1f km\", v)" to "JavaFormat.oneDecimal(v, locale) + \" km\"",
        "String.format(locale, \"%,.0f km\", v)" to "JavaFormat.grouped(v, locale) + \" km\"",
        "String.format(locale, \"%.1f\", v)" to "JavaFormat.oneDecimal(v, locale)",
        "String.format(locale, \"%,.0f\", v)" to "JavaFormat.grouped(v, locale)",
    ),
    "book/BookSvg.kt" to listOf(
        "import kotlin.math.pow" to "import kotlin.math.pow\nimport kotlin.math.roundToLong",
        "Math.round(v * 100) / 100.0" to "(v * 100).roundToLong() / 100.0",
        "java.lang.String.format(java.util.Locale.ROOT, \"%.2f\", r)" to "com.justdataplease.boringtravel.compat.JavaFormat.twoDecimals(r)",
    ),
    "book/Flags.kt" to listOf(
        "Math.toRadians(" to "0.017453292519943295 * (",
    ),
    "doc/DocRender.kt" to listOf(
        "\"%.3f\".format(java.util.Locale.ROOT, " to "com.justdataplease.boringtravel.compat.JavaFormat.threeDecimals(",
        "\"%.1f\".format(java.util.Locale.ROOT, " to "com.justdataplease.boringtravel.compat.JavaFormat.oneDecimal(",
    ),
    // Route tracking: the engine's days, and the next day's check from a moment in epoch milliseconds and a zone by its
    // id (the same arithmetic as java.time's, through the engine's startOfDayMs).
    "tracking/TrackingPlan.kt" to listOf(
        "import java.time.Duration" to "// Duration: milliseconds here.",
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate",
        "import java.time.ZonedDateTime" to "import com.justdataplease.boringtravel.time.localDateAt\nimport com.justdataplease.boringtravel.time.startOfDayMs",
        "fun msUntilNextDay(now: ZonedDateTime, after: Duration = Duration.ofMinutes(5)): Long {" to
            "fun msUntilNextDay(nowMs: Long, zoneId: String?, afterMs: Long = 5 * 60_000L): Long {",
        "val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plus(after)" to
            "val next = startOfDayMs(localDateAt(nowMs, zoneId).plusDays(1), zoneId) + afterMs",
        "return max(60_000L, Duration.between(now, next).toMillis())" to "return max(60_000L, next - nowMs)",
    ),
    // The widgets' words: the engine's days, ordered by their epoch day.
    "widget/WidgetModel.kt" to listOf(
        "import java.time.LocalDate" to "import com.justdataplease.boringtravel.time.LocalDate\nimport com.justdataplease.boringtravel.compat.isAfter",
        ".minByOrNull { it.startDate!! }" to ".minByOrNull { it.startDate!!.toEpochDay() }",
    ),
) + aiRewrites + siteRewrites + clipRewrites + bookRewrites + communityRewrites + togetherRewrites + shareRewrites

/** A rule that names a whole import line replaces only that line; any other rule replaces its text where it stands. */
fun rewrite(line: String, rules: List<Pair<String, String>>): String = rules.fold(line) { text, (old, new) ->
    if (old.startsWith("import ")) (if (text.trim() == old) new else text) else text.replace(old, new)
}

val sharedSources = tasks.register<Sync>("sharedSources") {
    into(layout.buildDirectory.dir("sharedSources"))
    // Gradle can't see a change to the rewriting rules; raise this when one changes, so the copy runs again.
    inputs.property("rules", 36)
    from(androidMain.resolve("java")) {
        sharedFiles.filterNot { it in rewrites }.forEach { include("$pkg/$it") }
    }
    rewrites.forEach { (file, rules) ->
        from(androidMain.resolve("java")) {
            include("$pkg/$file")
            filter { line: String -> rewrite(line, rules) }
        }
    }
    doLast {
        val root = destinationDir.resolve(pkg)
        val missing = sharedFiles.filterNot { root.resolve(it).isFile }
        check(missing.isEmpty()) { "Not in the Android app any more: $missing" }
        // A rule that no longer finds its line means the Android file changed: look at it again.
        rewrites.forEach { (file, rules) ->
            val text = root.resolve(file).readText()
            val stale = rules.filter { (_, new) -> new !in text }.map { it.first }
            check(stale.isEmpty()) { "$file: these lines are not there to rewrite any more: $stale" }
        }
        // The functions cut out (`cuts`), each replaced by a note.
        cuts.forEach { (file, list) ->
            val f = root.resolve(file)
            var text = f.readText().replace("\r\n", "\n")
            list.forEach { (start, end) ->
                val from = text.indexOf(start)
                check(from >= 0) { "$file: this is not there to cut out any more: ${start.lines().first { it.isNotBlank() }}" }
                val to = text.indexOf(end, from + start.length)
                check(to >= 0) { "$file: the end of ${start.lines().first { it.isNotBlank() }} is not there" }
                val name = start.lines().last { it.isNotBlank() }.trim()
                text = text.substring(0, from) + "// Cut out on the way in, written again in this app: $name …\n" + text.substring(to + end.length)
            }
            f.writeText(text)
        }
    }
}

// Android names its fonts R.font.<file>; the shared files keep doing so here, through a small R written from the
// same folder: each font file's number, and the file behind each number.
val fontIds = tasks.register("fontIds") {
    val fonts = androidMain.resolve("res/font")
    val out = layout.buildDirectory.dir("fontIds")
    inputs.dir(fonts)
    outputs.dir(out)
    doLast {
        val names = fonts.listFiles { file -> file.extension == "ttf" }.orEmpty().map { it.nameWithoutExtension }.sorted()
        val file = out.get().asFile.resolve("$pkg/R.kt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.justdataplease.boringtravel")
            appendLine()
            appendLine("/** Android's R for the files shared with the Android app: written by the build from its res/font folder. */")
            appendLine("object R {")
            appendLine("    object font {")
            names.forEachIndexed { index, name -> appendLine("        const val $name = ${index + 1}") }
            appendLine("    }")
            appendLine()
            appendLine("    /** The font file behind each number. */")
            appendLine("    val fontFiles: Map<Int, String> = mapOf(")
            names.forEachIndexed { index, name -> appendLine("        ${index + 1} to \"$name.ttf\",") }
            appendLine("    )")
            appendLine("}")
        })
    }
}

/*
 * This build's Firebase ids for the built-in AI planner (the Android app's BuildConfig.FIREBASE_*, app/build.gradle.kts):
 * environment variables first, then the git-ignored local.properties beside this file; empty by default, and the
 * planner then says it isn't set up. Written as a small Kotlin object under build/, never into git. The app id is the
 * owner's Firebase iPhone app's.
 */
val localProps: Properties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { text -> load(StringReader(text)) }
}
fun localValue(key: String, env: String): String =
    (providers.environmentVariable(env).orNull ?: localProps.getProperty(key)).orEmpty().trim()

val firebaseIds = tasks.register("firebaseIds") {
    val values = linkedMapOf(
        "PROJECT_ID" to localValue("firebase.projectId", "BORINGTRAVEL_FIREBASE_PROJECT_ID"),
        "APP_ID" to localValue("firebase.appId", "BORINGTRAVEL_FIREBASE_APP_ID"),
        "API_KEY" to localValue("firebase.apiKey", "BORINGTRAVEL_FIREBASE_API_KEY"),
        "AI_MODEL" to localValue("firebase.aiModel", "BORINGTRAVEL_FIREBASE_AI_MODEL"),
    )
    inputs.properties(values)
    val out = layout.buildDirectory.dir("firebaseIds")
    outputs.dir(out)
    doLast {
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = out.get().asFile.resolve("$pkg/firebase/FirebaseIds.kt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.justdataplease.boringtravel.firebase")
            appendLine()
            appendLine("/** This build's Firebase ids (local.properties or BORINGTRAVEL_FIREBASE_*): written by the build, empty without them. */")
            appendLine("internal object FirebaseIds {")
            values.forEach { (name, value) -> appendLine("    const val $name = ${quoted(value)}") }
            appendLine("}")
        })
    }
}

/*
 * This build's sign-in (round 23), from the same places as the Firebase ids: `google.iosClientId`
 * (BORINGTRAVEL_GOOGLE_IOS_CLIENT_ID), the owner's Google OAuth client of type iOS for com.justdataplease.boringtravel,
 * which Google's sign-in page, Drive's backups and every Drive button use (Android needs none: Google knows its app by
 * its signing key); and `apple.signIn` (BORINGTRAVEL_APPLE_SIGN_IN) "true" once the app's Apple capability and Firebase's
 * Apple provider are set up (Sign in with Apple signs in to the owner's Firebase project, which also revokes Apple's
 * token when the account is deleted). Empty by default: then Welcome, Me and Backup & sync say Google sign-in isn't set
 * up in this build, and Sign in with Apple isn't offered. Since round 24 also `drive.apiKey`
 * (BORINGTRAVEL_DRIVE_API_KEY): the owner's Google Cloud API key restricted to the Drive API and this app's bundle id,
 * which reads community posts' pictures and clips from their posters' Drives by link (Android's PublicDrive key, sent with
 * the X-Ios-Bundle-Identifier header); without it they come from Google's public download address, as Android's build
 * without a key reads them.
 */
val accountIds = tasks.register("accountIds") {
    val values = linkedMapOf(
        "GOOGLE_IOS_CLIENT_ID" to localValue("google.iosClientId", "BORINGTRAVEL_GOOGLE_IOS_CLIENT_ID"),
        "APPLE_SIGN_IN" to localValue("apple.signIn", "BORINGTRAVEL_APPLE_SIGN_IN"),
        "DRIVE_API_KEY" to localValue("drive.apiKey", "BORINGTRAVEL_DRIVE_API_KEY"),
    )
    inputs.properties(values)
    val out = layout.buildDirectory.dir("accountIds")
    outputs.dir(out)
    doLast {
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = out.get().asFile.resolve("$pkg/google/AccountIds.kt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.justdataplease.boringtravel.google")
            appendLine()
            appendLine("/** This build's sign-in (local.properties or BORINGTRAVEL_GOOGLE_* and BORINGTRAVEL_APPLE_*): written by the build, empty without them. */")
            appendLine("internal object AccountIds {")
            values.forEach { (name, value) -> appendLine("    const val $name = ${quoted(value)}") }
            appendLine("}")
        })
    }
}

/*
 * The Android app's versionName, read from its build file (`versionName = "1.21.1"` in app/build.gradle.kts): what Me ›
 * Version shows on both phones, and what the app writes with its name (a backup's manifest, an .ics file, what it asks
 * the web with). iosApp/project.yml's MARKETING_VERSION is the same number (VersionTest checks the two agree). Android's
 * debug build has no suffix, so neither has this.
 */
fun androidVersionName(): String {
    // androidMain is app/src/main: the build file is two folders up.
    val script = androidMain.resolve("../../build.gradle.kts").readText()
    return Regex("""\bversionName\s*=\s*"([^"]+)"""").find(script)?.groupValues?.get(1)
        ?: error("No versionName in the Android app's build.gradle.kts")
}

val appVersion = tasks.register("appVersion") {
    val name = androidVersionName()
    inputs.property("versionName", name)
    val out = layout.buildDirectory.dir("appVersion")
    outputs.dir(out)
    doLast {
        val file = out.get().asFile.resolve("$pkg/ios/AppVersion.kt")
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.justdataplease.boringtravel.ios")
            appendLine()
            appendLine("/** The Android app's versionName (its app/build.gradle.kts), written by the build: Me › Version on both phones. */")
            appendLine("internal object AppVersion {")
            appendLine("    const val NAME = \"$name\"")
            appendLine("}")
        })
    }
}

// The fonts themselves, read by the app at start, with their licences (the OFL asks for them to ship with the fonts, as
// they do in the Android app), and the tag tree plan items take their tags from.
val androidResources = tasks.register<Sync>("androidResources") {
    into(layout.buildDirectory.dir("androidResources"))
    from(androidMain.resolve("res/font")) { into("files/fonts") }
    from(androidMain.resolve("assets/licenses")) {
        include("*-OFL.txt")
        into("files/licenses")
    }
    from(androidMain.resolve("assets/tags")) { into("files/tags") }
    // The demo's photos (Wikimedia Commons, freely licensed; their credits are a note in the demo's plan) and where
    // each came from.
    from(androidMain.resolve("assets/demo")) { into("files/demo") }
    from(androidMain.resolve("assets/licenses")) {
        include("demo-photos.txt")
        into("files/licenses")
    }
    // The countries' outlines (Natural Earth, public domain), for a place's country without the network.
    from(androidMain.resolve("assets/geo")) {
        include("countries.json")
        into("files/geo")
    }
    // The trip video's eight bundled tracks (CC0, the Android app's own assets/music, the same files): what the
    // editor's Audio lists and plays under the preview (round 19) and the export mixes (round 20).
    from(androidMain.resolve("assets/music")) {
        include("*.m4a")
        into("files/music")
    }
    // What only this app has.
    from("src/commonMain/composeResources")
}

/*
 * The Android app's own tests of the rules this app writes again for iPhone (links, place search and naming, the place
 * page, link previews, Wikipedia photos, Google Maps links and lists), run on the desktop against this app's code: the
 * same cases and the same recorded answers, read from the Android app at build time (nothing copied into git). Their
 * package becomes this app's, and their coroutine test runner runBlocking.
 */
val androidTests = listOf(
    "links/LinkFillTest.kt", "links/LinkPlacesTest.kt", "links/LinkPreviewTest.kt", "links/LinkRenewTest.kt",
    "links/LinkUrlsTest.kt", "links/MapsLinksTest.kt", "links/MapsListsTest.kt", "links/MetaTagsTest.kt",
    "links/PlacePhotosTest.kt", "links/SharedLinksTest.kt", "links/WikiCreditsTest.kt", "links/WikiPhotosTest.kt",
    "places/PhotonParserTest.kt", "places/PlaceNamesTest.kt", "places/TripPlacesTest.kt", "ui/place/PlaceGroupingTest.kt",
    // A plan item's journal notes and a note written about it (the second parity round), and the km words beside them.
    "ui/trip/ItemNotesTest.kt",
)

/*
 * The Android app's own tests of Copy from a link (all but the live ones), in their own package against the site files
 * read in above, on virtual time as on Android: their java.time is compat's (the engine's days stay java.time's on the
 * desktop), jsoup's names Ksoup's, the link rules this app's, and the folders JUnit makes for them compat's files.
 */
val androidSiteTests: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    fun links(name: String) = "import com.justdataplease.boringtravel.links.$name" to "import com.justdataplease.boringtravel.ios.$name"
    val rules = listOf(
        "import java.time.Instant" to "import $c.Instant",
        "import java.time.ZoneOffset" to "import $c.ZoneOffset",
        "import java.time.ZoneId" to "import $c.ZoneId\nimport $c.atTimeIn",
        "import java.util.Locale" to "import $c.Locale",
        "import java.io.File" to "import $c.File",
        "import java.io.InputStream" to "import $c.InputStream",
        "import org.junit.rules.TemporaryFolder" to "import org.junit.rules.TemporaryFolder\nimport $c.byteInputStream",
        "import org.jsoup.Jsoup" to "import com.fleeksoft.ksoup.Ksoup as Jsoup",
        "import okhttp3.HttpUrl.Companion.toHttpUrl" to "import $c.toHttpUrl",
        links("LinkHttpException"), links("MapsList"), links("MapsListException"), links("MapsListPlace"), links("MapsLists"),
        "java.time.ZoneOffset.UTC" to "$c.ZoneOffset.UTC",
        "tmp.newFolder(\"pictures\")" to "$c.File(tmp.newFolder(\"pictures\").path)",
        "tmp.newFolder()" to "$c.File(tmp.newFolder().path)",
        "tmp.root.resolve(\"inline\")" to "$c.File(tmp.root.resolve(\"inline\").path)",
        ".atTime(12, 0).atZone(zone).toInstant()" to ".atTimeIn(12, 0, zone)",
    )
    listOf(
        "FeedsTest.kt", "GoogleDocsTest.kt", "HtmlBlocksTest.kt", "InlinePicturesTest.kt", "SiteArticlesTest.kt", "SiteImporterTest.kt",
        "SiteLinksTest.kt", "SiteNotesTest.kt", "SiteOnePostTest.kt", "SiteOrganizerGuardsTest.kt", "SiteOrganizerJoinTest.kt",
        "SiteOrganizerTest.kt", "SitePartsTest.kt", "SitePlacesTest.kt", "SiteReaderMoreTest.kt", "SiteReaderTest.kt",
        "SiteSectionsTest.kt", "StyledHtmlTest.kt", "TumblrApiTest.kt",
    ).associate { "site/$it" to rules }
}

/*
 * The Android app's own tests of the trip video (round 18), in their own packages against the clip files read in
 * above: their zones and Locale are compat's (the engine's days stay java.time's on the desktop), this app's travel
 * rules (ios/PlanTravel.kt) stand for Android's ui/trip ones, and the example plans they build come from this app's
 * copies of the same tables (desktopTest's data/SampleTripsForClipTests.kt). Since round 19 also the editor's tracks
 * (TrackLayoutTest, against the read-in ui/clip/TrackLayout.kt); the bundled music's catalogue and settings run as
 * they are (androidTestsAsTheyAre). Since round 20 also RidesTest (every vehicle's rides, with the read-in RideChoices).
 * Not here: ClipFilesTest (Android's java.nio moves; this app's parts and moves are clip/ClipVideoExport.kt's, with their
 * own tests), MapStylesTest (it runs since round 9).
 */
val androidClipTests: Map<String, List<Pair<String, String>>> = run {
    val c = "com.justdataplease.boringtravel.compat"
    val rules = listOf(
        "import java.time.ZoneId" to "import $c.ZoneId",
        "import java.util.Locale" to "import $c.Locale",
        "import com.justdataplease.boringtravel.ui.trip.travel" to "import com.justdataplease.boringtravel.ios.travel",
        "import com.justdataplease.boringtravel.ui.trip.LegEstimate" to "import com.justdataplease.boringtravel.ios.LegEstimate",
        "java.util.TreeMap<" to "$c.TreeMap<",
    )
    listOf(
        "CalmCameraTest.kt", "ClipBeatsTest.kt", "ClipDayCardsTest.kt", "ClipDesignTest.kt", "ClipEditsTest.kt", "ClipGlobeTest.kt",
        "ClipHelpersTest.kt", "ClipLengthTest.kt", "ClipLooksTest.kt", "ClipPicksTest.kt", "ClipPlanTagsTest.kt", "ClipPlanTest.kt",
        "ClipRound3Test.kt", "ClipRound4Test.kt", "ClipScenesTest.kt", "ClipTimingTest.kt", "ClipVideosTest.kt", "ClipZoomTest.kt",
        "EditorTextsAndMusicStartTest.kt", "ExportProgressTest.kt", "FollowCameraTest.kt", "HeadingTest.kt", "LegKmTest.kt",
        "LegVehiclePlanTest.kt", "PaperVehicle3DTest.kt", "PlaceMediaTest.kt", "PlannedReplayTest.kt", "ReplayProjectionTest.kt",
        "RouteLegsTest.kt", "SpriteFitTest.kt", "SteadyCameraTest.kt", "StopStaysTest.kt", "TravellerEditsTest.kt", "TravellerTest.kt",
        "VehiclePaletteTest.kt", "WalkCycleTest.kt", "music/MusicMixTest.kt", "RidesTest.kt",
    ).associate { "clip/$it" to rules } + mapOf(
        "ui/clip/PreviewMathTest.kt" to rules,
        // Its moments are java.time's at a compat zone (desktopTest's clip/ClipTestShims.kt, in the clip package).
        "ui/clip/TrackLayoutTest.kt" to listOf("import java.time.ZoneId" to "import $c.ZoneId\nimport com.justdataplease.boringtravel.clip.atZone") + rules,
    )
}

/*
 * The Android app's own tests of the book (round 21), in its package against the book files read in above: their
 * Locale is compat's (BookFacts takes compat's), their days java.time's (the engine's days are java.time's on the desktop).
 * BookSamplesTest writes sample books only when BT_BOOK_SAMPLES names a folder, as on Android.
 */
val androidBookTests: Map<String, List<Pair<String, String>>> = listOf(
    "AlbumPagesTest.kt", "BookFixtures.kt", "BookHtmlTest.kt", "BookJsonTest.kt", "BookLayoutTest.kt", "BookMathTest.kt",
    "BookSamplesTest.kt", "FreeEditTest.kt", "FreeMathTest.kt", "JournalLayoutPagesTest.kt", "LayersTest.kt",
).associate { name ->
    "book/$name" to listOf(
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
        "java.util.Locale.UK" to "com.justdataplease.boringtravel.compat.Locale.UK",
    )
}

/*
 * The Android app's own tests of the community (round 24), in its package against the files read in above, with its
 * in-memory repository (FakeCommunityRepository): links, the Following feed's merged pages, follows and their keeper,
 * the leaderboard and the hearts, the avatar, a trip as a post carries it, Copy this trip, usernames. Their Locale is
 * compat's (the feed's texts take compat's), their days java.time's (the engine's days are java.time's on the desktop);
 * the usernames' test reads the Android app's own Firestore rules.
 */
val androidCommunityTests: Map<String, List<Pair<String, String>>> = run {
    val rules = androidMain.resolve("../../../firebase/firestore.rules").normalize().invariantSeparatorsPath
    listOf(
        "CommunityLinksTest.kt", "FakeCommunityRepository.kt", "FollowingFeedTest.kt", "FollowKeeperTest.kt", "FollowsTest.kt",
        "LeaderboardTest.kt", "ProfileAvatarTest.kt", "SharedTripsTest.kt", "TripCopyTest.kt", "UsernamesTest.kt",
    ).associate { name ->
        "community/$name" to listOf(
            "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
            "java.io.File(\"../firebase/firestore.rules\")" to "java.io.File(\"$rules\")",
        )
    } + run {
        // Round 25, posting: the draft's rules, the upload and an update of a trip post. Their File and their uploader's
        // zone are compat's (PostDraft and PostUploader take compat's here); the links are kept under the app's files
        // (MediaFiles: in memory here, where Android's test makes a folder).
        val file = "import java.io.File" to "import com.justdataplease.boringtravel.compat.File"
        val zone = "import java.time.ZoneOffset" to "import com.justdataplease.boringtravel.compat.ZoneOffset"
        mapOf(
            "community/PostDraftTest.kt" to listOf(file, "java.util.Locale.UK" to "com.justdataplease.boringtravel.compat.Locale.UK"),
            "community/PostUploaderTest.kt" to listOf(file, zone),
            "community/PostSyncTest.kt" to listOf(
                file, zone,
                "        val dir = Files.createTempDirectory(\"links\").toFile()" to "        val files = com.justdataplease.boringtravel.ios.MemoryMediaFiles()",
                "        val file = File(dir, \"community/post-links.json\")" to "        val file = \"community/post-links.json\"",
                "PostLinks(file)" to "PostLinks(files, file)",
                "        assertTrue(file.isFile)" to "        assertTrue(files.read(file) != null)",
                "        dir.deleteRecursively()" to "        // The files are in memory: nothing to delete.",
            ),
        )
    }
}

/*
 * The Android app's own tests of Travel together (round 26), in their own packages against the files read in above and
 * this app's together/Together.kt (its schedule's rule): whole conversations between phones over a fake Drive and
 * mailbox (TogetherEngineTest), the merge, the file's checks and its gzip read back with Java's own GZIPInputStream
 * (TogetherMergeTest), the link, the mailbox's rules, the schedule and the send queue, the sheets' texts. Their days are
 * java.time's (the engine's days are java.time's on the desktop); the engine's photo files are compat's File over the
 * test's own folders.
 */
val androidTogetherTests: Map<String, List<Pair<String, String>>> = run {
    val f = "com.justdataplease.boringtravel.compat.File"
    mapOf(
        "together/TogetherEngineTest.kt" to listOf(
            "            override fun file(media: Media) = File(dir, media.localPath)" to "            override fun file(media: Media) = $f(File(dir, media.localPath).path)",
            "            override fun smallCopy(media: Media) = File(dir, media.localPath).takeIf { it.isFile }.also { smallCopies += media.id }" to
                "            override fun smallCopy(media: Media) = File(dir, media.localPath).takeIf { it.isFile }.also { smallCopies += media.id }?.let { $f(it.path) }",
            "override suspend fun upload(folderId: String, file: File, name: String, mime: String): String? {" to
                "override suspend fun upload(folderId: String, file: $f, name: String, mime: String): String? {",
            "override suspend fun download(fileId: String, target: File, maxBytes: Long) {" to
                "override suspend fun download(fileId: String, target: $f, maxBytes: Long) {",
        ),
        "together/TogetherMergeTest.kt" to emptyList(),
        "together/TogetherScheduleTest.kt" to emptyList(),
        "together/TripMailTest.kt" to emptyList(),
        "together/ShareLinkTest.kt" to emptyList(),
        "ui/together/TogetherTextTest.kt" to emptyList(),
    )
}

/*
 * The Android app's own test of Add to plan's rules (round 27: the trips offered, the trip, day and kind chosen first, the
 * item a Maps place, a short link and a site become), in its package against ShareTargets read in above, with this app's
 * link rules for Android's. Its days are java.time's (the engine's days are java.time's on the desktop).
 */
val androidShareTests: Map<String, List<Pair<String, String>>> = run {
    fun links(name: String) = "import com.justdataplease.boringtravel.links.$name" to "import com.justdataplease.boringtravel.ios.$name"
    mapOf("ui/share/ShareTargetsTest.kt" to listOf(links("LinkPlaces"), links("LinkPreview"), links("SharedLink")))
}

/*
 * Round 28: the drawn scenes' picking (English and Greek words, then the country, the spot, a hash: art/Scenes.kt, read
 * in) with this app's sceneFor (ios/DrawnScenes.kt), and the brand mark's numbers, colours and SVG (art/PlaneMark.kt)
 * against the launcher icon's script in the Android app's docs.
 */
val androidArtTests: Map<String, List<Pair<String, String>>> = run {
    val brand = androidMain.resolve("../../../docs/brand").normalize().invariantSeparatorsPath
    mapOf(
        "art/ScenePickTest.kt" to listOf("import com.justdataplease.boringtravel.ui.components.sceneFor" to "import com.justdataplease.boringtravel.ios.sceneFor"),
        "art/PlaneMarkTest.kt" to listOf(
            "File(\"../docs/brand/" to "File(\"$brand/",
            "File(\"src/main/res/drawable\")" to "File(\"${androidMain.invariantSeparatorsPath}/res/drawable\")",
        ),
    )
}

fun testRewrite(line: String): String = when {
    line.startsWith("package com.justdataplease.boringtravel.") ->
        "package com.justdataplease.boringtravel.ios\n\nimport com.justdataplease.boringtravel.places.*"
    line.trim() == "import com.justdataplease.boringtravel.ui.trip.ItemNotes" -> "// ItemNotes: this app's own, in this package."
    line.trim() == "import kotlinx.coroutines.test.runTest" -> "import kotlinx.coroutines.runBlocking as runTest"
    // The one rule that differs by design: the User-Agent names the iPhone (Android: "(Android; +…)").
    "\"BoringTravel/1.0.0 (Android; +" in line -> line.replace("(Android; +", "(iPhone; +")
    else -> line
}

/*
 * The Android app's tests of the rules read in above as they are (maps and routes): they keep their own packages, as
 * the files they test do. Where one passes a java.time zone, desktopTest's AndroidTestShims gives the call its zone id.
 */
val androidTestsAsTheyAre = listOf(
    "MapDataTest.kt", "SpikeFilterTest.kt", "StopDetectorTest.kt", "places/VisitMatcherTest.kt",
    "ui/map/LegSheetModelTest.kt", "clip/MapStylesTest.kt",
    "speech/DictationTextTest.kt", "speech/DictationRecoveryTest.kt", "checkin/DailyCheckInTest.kt",
    // Your world: the countries' facts and outlines, the globe's maths, its pins and its clipping, the world worked out
    // from the rows (world/WorldModel.kt here) and the share card's lines.
    "CountriesTest.kt", "geo/CountryInfoTest.kt", "globe/GlobeMathTest.kt", "globe/PinLayoutTest.kt", "globe/RingClipperTest.kt",
    "world/WorldModelTest.kt", "world/ShareCardTest.kt",
    // A restore puts the phone back as the backup was (backup/BackupMerge.kt here).
    "backup/BackupMergeTest.kt",
    // Route tracking's rules (its manifest check reads the Android app's own manifest) and the widgets' words, looks,
    // paper and the Quick bar's layout (widget/PaperGrid.kt here is a copy: Android's sits in a file of android.graphics).
    "TrackingPlanTest.kt",
    "widget/WidgetTextTest.kt", "widget/WidgetLookTest.kt", "widget/QuickBarLayoutTest.kt", "widget/PaperGridTest.kt",
    // The trip video's bundled music (round 19): the eight tracks, their files (the same ones this app ships, read in by
    // androidResources) and lengths, and the remembered choice in the "clip_music" settings file.
    "clip/music/MusicCatalogTest.kt", "clip/music/MusicPrefsTest.kt",
    // Me › Your traveller & rides and the Route tool's rows for a person (round 20, ui/traveller read in).
    "ui/traveller/TravellerChoicesTest.kt",
    // The trip's saved clips' index (round 20, data/SavedClips.kt here; desktopTest's data/SavedClipsShims.kt gives its
    // calls their Java folders).
    "data/SavedClipsTest.kt",
    // Edit trip › Photos in notes (the second parity round: data/settings/TripPhotos.kt read in).
    "data/TripPhotoStylesTest.kt",
    // Drive's queries, metadata, answers and errors (round 23: google/DriveJson.kt read in).
    "google/DriveJsonTest.kt",
    // A note's photo and video types, a video's limits (720p, its bit rate by length, 30 minutes, 240 MB as it is) and
    // its date and place tags (the second parity round: data/repo/MediaTypes.kt here).
    "data/repo/MediaTypesTest.kt",
)

/*
 * The Android app's tests of the export files read in above, in their own package: their Locale is compat's, and the
 * Word file's test hands TripDocx this app's file and stream types (compat/JavaIo.kt; desktopTest's ExportTestShims).
 */
val androidExportTests: Map<String, List<Pair<String, String>>> = listOf(
    "ExportFilesTest.kt", "ExportFixtures.kt", "PageBreakExportTest.kt", "PhotoLayoutExportTest.kt", "PhotoRowsTest.kt",
    "RouteSvgTest.kt", "TripBookTest.kt", "TripHtmlTest.kt", "TripTextTest.kt", "TripDocxTest.kt",
).associate { name ->
    "export/$name" to listOf(
        "import java.util.Locale" to "import com.justdataplease.boringtravel.compat.Locale",
        "import java.io.ByteArrayOutputStream" to "import com.justdataplease.boringtravel.compat.ByteArrayOutputStream",
        "TripDocx.Photo(f, " to "TripDocx.Photo(com.justdataplease.boringtravel.compat.File(f.path), ",
        "ExportOptions(), it, { m -> photos[m.id] }, Locale.UK)" to "ExportOptions(), it.asCompat(), { m -> photos[m.id] }, Locale.UK)",
    )
}

/*
 * The Android app's own tests of the AI planner (all but the live ones), in their own packages against the files read
 * in above and this app's ai/ files: this app's links, places and travel rules for the Android app's, compat's
 * ByteArrayInputStream, OkHttp's header check written out, and the Claude request's messages read from this app's JSON
 * where the Android test reads the SDK's (ClaudeAgainstSdkTest compares the two). Not here: ClaudeRequestsTest and
 * ClaudeKeyApiStreamTest (the SDK itself: ClaudeOnDesktopTest instead), OwnKeyPrivacyTest (Android's backup rules files).
 */
val androidAiTests: Map<String, List<Pair<String, String>>> = run {
    fun ios(pkg: String, name: String) = "import com.justdataplease.boringtravel.$pkg.$name" to "import com.justdataplease.boringtravel.ios.$name"
    val plain = listOf(
        "ai/ClarificationParserTest.kt", "ai/DraftApplierTest.kt", "ai/DraftEditsTest.kt", "ai/DraftLimitsTest.kt", "ai/DraftSchemaTest.kt",
        "ai/PlannerAllowanceTest.kt", "ai/PlannerErrorRetryTest.kt", "ai/PlannerPromptTest.kt", "ai/TripDraftParserTest.kt",
        "ai/own/GeminiRestTest.kt", "ai/own/JsonSchemasTest.kt", "ai/own/OpenAiRestTest.kt", "ai/own/OpenRouterRestTest.kt",
        "ai/own/OwnAiModelsTest.kt", "ai/tools/AgentToolsTest.kt", "ai/tools/TravelBriefTest.kt", "ui/ai/AttachmentsTest.kt",
        // The report's mail text (ui/ai/AiReport.kt here: the same object, the mail sent through the phone).
        "ai/AiReportTest.kt",
    ).associateWith { emptyList<Pair<String, String>>() }
    plain + mapOf(
        "ai/ItineraryImportTest.kt" to listOf("import java.io.ByteArrayInputStream" to "import com.justdataplease.boringtravel.compat.ByteArrayInputStream"),
        "ai/PictureReadingTest.kt" to listOf(
            "import com.anthropic.core.jsonMapper" to "// jsonMapper: the request is this app's JSON here (ai/own/ClaudeKeyApi.kt).",
            "val message = Json.parseToJsonElement(jsonMapper().writeValueAsString(params.messages().single())).jsonObject" to
                "val message = params.body.getValue(\"messages\").jsonArray.single().jsonObject",
        ),
        "ai/own/OwnKeyStoreTest.kt" to listOf(
            // OkHttp's header value check: a tab or printable ASCII only.
            "okhttp3.Headers.Builder().add(\"Authorization\", \"Bearer \$cleaned\")" to "check(\"Bearer \$cleaned\".all { it == '\\t' || it in ' '..'~' })",
        ),
        "ai/tools/MapsListToolsTest.kt" to listOf(ios("links", "MapsList"), ios("links", "MapsListPlace")),
        "ai/tools/PlacesToolsTest.kt" to listOf(ios("links", "LinkPlaces"), ios("links", "MapsPlace"), ios("places", "LooseQueries")),
        "ai/tools/PlannerToolsTest.kt" to listOf(ios("clip", "RouteLegs"), ios("ui.trip", "travel")),
        // read_link's page, with Copy from a link's readers (read in since round 17).
        "ai/tools/LinkReaderTest.kt" to listOf(ios("links", "LinkHttpException")),
    )
}

/*
 * The Android app's export, book and doc files exactly as they are (java.time, java.io, String.format), compiled for
 * the desktop's tests in a package of their own (…androidref…): what the Android app writes for a trip, to compare
 * this app's text, Word file and print HTML with, byte for byte.
 */
val androidReferenceFiles = sharedFiles.filter { it.startsWith("export/") || it.startsWith("book/") || it == "doc/DocRender.kt" } +
    // The Claude requests as Anthropic's Java SDK builds them (on the desktop tests' classpath), next to this app's own.
    "ai/own/ClaudeKeyApi.kt"

fun referenceRewrite(line: String): String = when {
    line == "package com.justdataplease.boringtravel.ai.own" ->
        "package com.justdataplease.boringtravel.androidref.ai.own\n\nimport com.justdataplease.boringtravel.ai.own.*"
    line == "package com.justdataplease.boringtravel.doc" -> "package com.justdataplease.boringtravel.androidref.doc\n\nimport com.justdataplease.boringtravel.doc.*"
    // Since round 21 the whole book is here (BookModel.kt with it), in the book's own package of the reference.
    line.startsWith("package com.justdataplease.boringtravel.") -> line.replace("package com.justdataplease.boringtravel.", "package com.justdataplease.boringtravel.androidref.")
    else -> line.replace("com.justdataplease.boringtravel.export.", "com.justdataplease.boringtravel.androidref.export.")
        .replace("com.justdataplease.boringtravel.book.", "com.justdataplease.boringtravel.androidref.book.")
        .replace("com.justdataplease.boringtravel.doc.DocRender", "com.justdataplease.boringtravel.androidref.doc.DocRender")
}

val androidReferenceSources = tasks.register<Sync>("androidReferenceSources") {
    into(layout.buildDirectory.dir("androidReference"))
    inputs.property("rules", 4)
    from(androidMain.resolve("java/$pkg")) {
        androidReferenceFiles.forEach { include(it) }
        filter { line: String -> referenceRewrite(line) }
        into("$pkg/androidref")
    }
}

val androidTestSources = tasks.register<Sync>("androidTestSources") {
    into(layout.buildDirectory.dir("androidTests"))
    inputs.property("rules", 21)
    (androidExportTests + androidAiTests + androidSiteTests + androidClipTests + androidBookTests + androidCommunityTests + androidTogetherTests + androidShareTests + androidArtTests).forEach { (file, rules) ->
        from(androidMain.parentFile.resolve("test/java/$pkg")) {
            include(file)
            filter { line: String -> rewrite(line, rules) }
            into(pkg)
        }
    }
    from(androidMain.parentFile.resolve("test/java/$pkg")) {
        androidTests.forEach { include(it) }
        filter { line: String -> testRewrite(line) }
        into("$pkg/ios/android")
    }
    from(androidMain.parentFile.resolve("test/java/$pkg")) {
        androidTestsAsTheyAre.forEach { include(it) }
        // They read the Android app's assets and manifest from its own folder (unit tests run there); here from where it is.
        filter { line: String ->
            line.replace("File(\"src/main/assets/", "File(\"${androidMain.invariantSeparatorsPath}/assets/")
                .replace("File(\"src/main/assets\")", "File(\"${androidMain.invariantSeparatorsPath}/assets\")")
                .replace("File(\"src/main/AndroidManifest.xml\")", "File(\"${androidMain.invariantSeparatorsPath}/AndroidManifest.xml\")")
        }
        into(pkg)
    }
    doLast {
        val missing = androidTests.filterNot { destinationDir.resolve("$pkg/ios/android/$it").isFile } +
            androidTestsAsTheyAre.filterNot { destinationDir.resolve("$pkg/$it").isFile } +
            (androidExportTests + androidAiTests + androidSiteTests + androidClipTests + androidBookTests + androidCommunityTests + androidTogetherTests + androidShareTests + androidArtTests).keys.filterNot { destinationDir.resolve("$pkg/$it").isFile }
        check(missing.isEmpty()) { "Not in the Android app's tests any more: $missing" }
    }
}

kotlin {
    // compat/JavaNet.kt: Java's exceptions on the desktop, the app's own on iPhone (expect classes, as the engine's).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    // Windows, macOS and Linux: runs the same screens in a window and writes the review screenshots.
    jvm("desktop")

    // The iPhone and the Simulator on Apple-chip Macs; these compile only on macOS.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BoringTravelApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(sharedSources)
            kotlin.srcDir(fontIds)
            kotlin.srcDir(firebaseIds)
            kotlin.srcDir(accountIds)
            kotlin.srcDir(appVersion)
            dependencies {
                // The engine both phones run on: the trip, plan and journal rows, their rules, the trip ideas, the dates.
                implementation(project(":shared"))
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
                // The system's Back as navigation events (ios/Back.kt): on iPhone the swipe from the left edge, which
                // Compose's UIKit layer turns into one. The version Compose 1.12.2 itself is built with.
                implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                // The plan's press-and-hold dragging: the Android app's own library (it has iPhone builds), so a row
                // lifts, moves and drops the same on both phones.
                implementation(libs.reorderable)
                // The answers of the place search, Wikipedia and Google Maps lists, and the link previews kept.
                implementation(libs.kotlinx.serialization.json)
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                // Copy from a link's HTML reader: Ksoup, jsoup ported to Kotlin Multiplatform with jsoup's names (the
                // Android app reads with jsoup 1.24, which has no iPhone build). 0.2.6 is built with Kotlin 2.3.10; its
                // own dependencies (fleeksoft io and charset, Touchlab's stately) are plain Kotlin Multiplatform too, and
                // the charset library also decodes pages in their own character set (site/SiteWeb.kt).
                implementation("com.fleeksoft.ksoup:ksoup:$ksoupVersion")
            }
        }
        // Tests of the code read in from Android that run on both the desktop's JVM (desktopTest) and the iPhone
        // Simulator (iosSimulatorArm64Test): Kotlin/Native's regular expressions and text functions differ from Java's
        // in places and fail only when the code runs (src/commonTest/…/smoke). kotlin.test and the coroutines' test
        // runner only: no JUnit, nothing of Java.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-java:$ktorVersion")
            }
        }
        // The tests drive the screens on the desktop (taps, typing, what is on screen) and read what was kept the way
        // the Android app's restore does (the engine's BackupJson).
        named("desktopTest") {
            kotlin.srcDir(androidTestSources)
            kotlin.srcDir(androidReferenceSources)
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-test:$composeVersion")
                implementation(libs.kotlinx.serialization.json)
                // The Android app's Copy from a link tests run on virtual time (runTest, the test scheduler), as on Android.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                // Anthropic's Java SDK, the Android app's: its requests compared with this app's (ClaudeAgainstSdkTest).
                implementation(libs.anthropic.java)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.justdataplease.boringtravel.ios.resources"
    customDirectory("commonMain", layout.dir(androidResources.map { it.destinationDir }))
}

compose.desktop {
    application {
        mainClass = "com.justdataplease.boringtravel.ios.PreviewKt"
    }
}

// The globe's idle spin never ends, and a UI test waits for the screen to settle: the tests run with Reduce Motion on.
tasks.withType<Test>().configureEach {
    systemProperty("boringtravel.reduceMotion", "true")
}

// The shared tests on the iPhone Simulator (only on a Mac): the device CI picks (`-PsimulatorDevice=<udid>`, the newest
// iPhone its Xcode has), else Kotlin's own default.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("simulatorDevice").orNull?.takeIf { it.isNotBlank() }?.let { device.set(it) }
}

// Renders each screen to a PNG without a window, for review: `gradlew :composeApp:screenshots` writes
// composeApp/build/screenshots (or `-Pshots=<folder>`).
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Writes a PNG of every screen at iPhone size."
    val desktop = kotlin.targets.getByName("desktop").compilations.getByName("main") as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
    classpath = files(desktop.output.allOutputs, desktop.runtimeDependencyFiles)
    mainClass.set("com.justdataplease.boringtravel.ios.ScreenshotsKt")
    val out = providers.gradleProperty("shots").orElse(layout.buildDirectory.dir("screenshots").map { it.asFile.path })
    // `-PshotsOnly=clip`: only the trip video's pictures (they take a while to draw); `-PshotsOnly=book`: only the book's.
    args(listOfNotNull(out.get(), providers.gradleProperty("shotsOnly").orNull))
    systemProperty("java.awt.headless", "true")
}

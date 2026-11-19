package com.justdataplease.boringtravel.geo

import java.util.IllformedLocaleException
import java.util.Locale

/** The seven continents as travellers count them; [center] is where the globe turns to show one. */
enum class Continent(val label: String, val centerLat: Double, val centerLng: Double) {
    Europe("Europe", 50.0, 15.0),
    Asia("Asia", 34.0, 88.0),
    Africa("Africa", 3.0, 19.0),
    NorthAmerica("North America", 38.0, -98.0),
    SouthAmerica("South America", -18.0, -60.0),
    Oceania("Oceania", -20.0, 150.0),
    Antarctica("Antarctica", -78.0, 20.0),
}

/**
 * Facts about countries that need no network: continent, where a flag pin sits, the flag emoji and the name in the
 * phone's language. Codes are ISO 3166-1 alpha-2 plus Kosovo ("XK"); the two Natural Earth codes without one
 * ("CYN" Northern Cyprus, "SOL" Somaliland) count with the country they belong to under the UN ([key]).
 */
object CountryInfo {
    /** "% of the world" counts the 193 UN members plus the two observer states (Vatican, Palestine). */
    const val WORLD_COUNT = 195

    // Sovereign states first (they make the 195), then territories, per continent. Cyprus counts in Europe, Russia
    // too, Turkey and the Caucasus in Asia; Antarctica holds the southern islands with no one living there.
    private val sovereign: Map<Continent, String> = mapOf(
        Continent.Africa to "DZ AO BJ BW BF BI CV CM CF TD KM CG CD CI DJ EG GQ ER SZ ET GA GM GH GN GW KE LS LR LY MG MW ML " +
            "MR MU MA MZ NA NE NG RW ST SN SC SL SO ZA SS SD TZ TG TN UG ZM ZW",
        Continent.Asia to "AF AM AZ BH BD BT BN KH CN GE IN ID IR IQ IL JP JO KZ KW KG LA LB MY MV MN MM NP KP OM PK PH QA " +
            "KR SA SG LK SY TJ TH TL TR TM AE UZ VN YE PS",
        Continent.Europe to "AL AD AT BY BE BA BG HR CY CZ DK EE FI FR DE GR HU IS IE IT LV LI LT LU MT MD MC ME NL MK NO PL " +
            "PT RO RU SM RS SK SI ES SE CH UA GB VA",
        Continent.NorthAmerica to "AG BS BB BZ CA CR CU DM DO SV GD GT HT HN JM MX NI PA KN LC VC TT US",
        Continent.SouthAmerica to "AR BO BR CL CO EC GY PY PE SR UY VE",
        Continent.Oceania to "AU FJ KI MH FM NR NZ PW PG WS SB TO TV VU",
    )

    private val territories: Map<Continent, String> = mapOf(
        Continent.Africa to "EH RE YT SH IO",
        Continent.Asia to "HK MO TW",
        Continent.Europe to "AX FO GI GG IM JE SJ XK",
        Continent.NorthAmerica to "AI AW BL BM BQ VG KY CW GL GP MQ MS PR MF PM SX TC VI",
        Continent.SouthAmerica to "FK GF",
        Continent.Oceania to "AS CK PF GU NC NU NF MP PN TK WF UM CC CX",
        Continent.Antarctica to "AQ BV GS HM TF",
    )

    private fun codes(s: String) = s.split(' ').filter { it.isNotBlank() }

    /** The 195 states "% of the world" is counted against. */
    val worldStates: Set<String> = sovereign.values.flatMap(::codes).toSet()

    private val continents: Map<String, Continent> = buildMap {
        for ((c, s) in sovereign) codes(s).forEach { put(it, c) }
        for ((c, s) in territories) codes(s).forEach { put(it, c) }
    }

    /** Every code this table knows (249 ISO codes and Kosovo). */
    val allCodes: Set<String> get() = continents.keys

    /** States counted per continent, for "6 of 45". */
    fun statesIn(continent: Continent): Int = sovereign[continent]?.let(::codes)?.size ?: 0

    /**
     * Where a country's flag pin sits (lat, lng): Natural Earth's own label points for the 175 countries in the
     * bundled outlines (public domain; Equatorial Guinea, New Zealand and Trinidad moved inside the 1:110m shapes),
     * hand-placed points for the small places 1:110m leaves out.
     */
    private val labelText =
        "AD:42.55,1.58;AE:23.47,54.55;AF:34.16,66.5;AG:17.08,-61.8;AI:18.22,-63.05;AL:40.65,20.11;AM:40.46,44.8;" +
        "AO:-12.18,17.98;AQ:-79.84,35.89;AR:-33.5,-64.17;AS:-14.3,-170.7;AT:47.52,14.13;AU:-24.13,134.05;" +
        "AW:12.52,-69.97;AX:60.2,19.95;AZ:40.4,47.21;BA:44.09,18.07;BB:13.17,-59.55;BD:24.21,89.68;BE:50.79,4.8;" +
        "BF:12.67,-1.36;BG:42.51,25.16;BH:26.03,50.55;BI:-3.33,29.92;BJ:10.32,2.35;BL:17.9,-62.83;BM:32.32,-64.76;" +
        "BN:4.45,114.55;BO:-16.67,-64.59;BQ:12.18,-68.25;BR:-12.1,-49.56;BS:26.4,-77.15;BT:27.54,90.04;" +
        "BV:-54.42,3.36;BW:-22.1,24.18;BY:53.82,28.42;BZ:17.2,-88.71;CA:60.32,-101.91;CC:-12.16,96.87;CD:-1.86,23.46;" +
        "CF:6.99,20.91;CG:0.14,15.9;CH:46.72,7.46;CI:7.49,-5.57;CK:-21.24,-159.78;CL:-38.15,-72.32;CM:4.59,12.47;" +
        "CN:32.5,106.34;CO:3.37,-73.17;CR:10.07,-84.08;CU:21.33,-77.98;CV:15.95,-23.95;CW:12.17,-68.98;" +
        "CX:-10.49,105.62;CY:34.91,33.08;CZ:49.88,15.38;DE:50.96,9.68;DJ:11.98,42.5;DK:55.97,9.02;DM:15.42,-61.35;" +
        "DO:19.1,-70.65;DZ:27.4,2.81;EC:-1.26,-78.19;EE:58.72,25.87;EG:26.19,29.45;EH:23.97,-12.63;ER:15.79,38.29;" +
        "ES:40.09,-3.46;ET:8.03,39.09;FI:63.25,27.28;FJ:-17.83,177.98;FK:-51.61,-58.74;FM:6.88,158.22;FO:62,-6.79;" +
        "FR:46.7,2.55;GA:-0.44,11.84;GB:54.4,-2.12;GD:12.12,-61.68;GE:41.87,43.74;GF:4,-53;GG:49.45,-2.58;" +
        "GH:7.72,-1.04;GI:36.14,-5.35;GL:74.32,-39.34;GM:13.64,-15;GN:10.62,-10.02;GP:16.25,-61.58;GQ:1.6,10.4;" +
        "GR:39.49,21.73;GS:-54.43,-36.59;GT:14.98,-90.5;GU:13.44,144.79;GW:12.16,-14.52;GY:5.12,-58.94;" +
        "HK:22.32,114.17;HM:-53.08,73.5;HN:14.79,-86.89;HR:45.81,16.37;HT:19.26,-72.22;HU:47.09,19.45;" +
        "ID:-0.95,101.89;IE:53.08,-7.8;IL:30.91,34.85;IM:54.24,-4.55;IN:22.69,79.36;IO:-7.33,72.42;IQ:33.09,43.26;" +
        "IR:32.17,54.93;IS:64.78,-18.67;IT:44.73,11.08;JE:49.21,-2.13;JM:18.14,-77.32;JO:30.81,36.38;JP:36.14,138.44;" +
        "KE:0.55,37.91;KG:41.67,74.53;KH:12.65,104.5;KI:1.42,173.03;KM:-11.88,43.87;KN:17.3,-62.72;KP:39.89,126.44;" +
        "KR:36.38,128.13;KW:29.41,47.31;KY:19.31,-81.25;KZ:49.05,68.69;LA:19.43,102.53;LB:34.13,35.99;" +
        "LC:13.91,-60.98;LI:47.16,9.55;LK:7.58,80.7;LR:6.45,-9.46;LS:-29.48,28.25;LT:55.1,24.09;LU:49.73,6.08;" +
        "LV:57.07,25.46;LY:26.64,18.01;MA:31.65,-7.19;MC:43.74,7.42;MD:47.43,28.49;ME:42.8,19.14;MF:18.07,-63.05;" +
        "MG:-18.63,46.7;MH:7.13,171.18;MK:41.56,21.56;ML:18.69,-2.04;MM:21.57,95.8;MN:46,104.15;MO:22.2,113.55;" +
        "MP:15.18,145.75;MQ:14.64,-61.02;MR:19.59,-9.74;MS:16.74,-62.19;MT:35.92,14.41;MU:-20.28,57.55;MV:3.2,73.22;" +
        "MW:-13.39,33.61;MX:23.92,-102.29;MY:2.53,113.84;MZ:-13.94,37.84;NA:-20.58,17.11;NC:-21.06,165.08;" +
        "NE:17.45,9.5;NF:-29.04,167.95;NG:9.44,7.5;NI:12.67,-85.07;NL:52.42,5.61;NO:61.36,9.68;NP:28.3,83.64;" +
        "NR:-0.52,166.93;NU:-19.05,-169.87;NZ:-42.5,171.9;OM:22.12,57.34;PA:8.72,-80.35;PE:-12.98,-72.9;" +
        "PF:-17.68,-149.41;PG:-5.7,143.91;PH:11.2,122.47;PK:29.33,68.55;PL:51.99,19.49;PM:46.94,-56.27;" +
        "PN:-25.07,-130.1;PR:18.23,-66.48;PS:32.05,35.29;PT:39.61,-8.27;PW:7.51,134.58;PY:-21.67,-60.15;" +
        "QA:25.24,51.14;RE:-21.12,55.54;RO:45.73,24.97;RS:44.19,20.79;RU:58.25,44.69;RW:-1.9,30.1;SA:23.81,44.7;" +
        "SB:-8.03,159.17;SC:-4.68,55.49;SD:16.33,29.26;SE:65.86,19.02;SG:1.35,103.82;SH:-15.96,-5.71;SI:46.06,14.92;" +
        "SJ:78.22,15.65;SK:48.73,19.05;SL:8.62,-11.76;SM:43.94,12.46;SN:15.14,-14.78;SO:3.57,45.19;SR:4.14,-55.91;" +
        "SS:7.23,30.39;ST:0.19,6.61;SV:13.69,-88.89;SX:18.04,-63.07;SY:35.01,38.28;SZ:-26.53,31.47;TC:21.69,-71.8;" +
        "TD:15.14,18.65;TF:-49.3,69.12;TG:8.81,1.06;TH:15.46,101.07;TJ:38.2,72.59;TK:-9.2,-171.85;TL:-8.8,125.85;" +
        "TM:39.86,58.68;TN:33.69,9.01;TO:-21.18,-175.2;TR:39.35,34.51;TT:10.45,-61.25;TV:-8.52,179.2;TW:23.65,120.87;" +
        "TZ:-6.05,34.96;UA:49.72,32.14;UG:1.97,32.95;UM:19.28,166.65;US:39.54,-97.48;UY:-32.96,-55.97;UZ:41.69,64.01;" +
        "VA:41.9,12.45;VC:13.25,-61.2;VE:7.18,-64.6;VG:18.42,-64.64;VI:18.34,-64.9;VN:21.72,105.39;VU:-15.37,166.91;" +
        "WF:-13.77,-177.16;WS:-13.76,-172.1;XK:42.59,20.86;YE:15.33,45.87;YT:-12.83,45.17;ZA:-29.71,23.67;" +
        "ZM:-14.66,26.4;ZW:-18.91,29.93;"

    private val labels: Map<String, Pair<Double, Double>> by lazy {
        labelText.split(';').filter { it.isNotBlank() }.associate { item ->
            val (code, rest) = item.split(':')
            val (lat, lng) = rest.split(',')
            code to (lat.toDouble() to lng.toDouble())
        }
    }

    /**
     * The code a country is counted under: trimmed, upper case, with Northern Cyprus folded into Cyprus and
     * Somaliland into Somalia. Null for anything that isn't a two-letter code.
     */
    fun key(code: String?): String? {
        val c = code?.trim()?.uppercase() ?: return null
        return when (c) {
            "CYN" -> "CY"
            "SOL" -> "SO"
            else -> c.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
        }
    }

    fun continentOf(code: String?): Continent? = key(code)?.let { continents[it] }

    /** Latitude and longitude of the flag pin, or null for a code the table doesn't know. */
    fun labelPoint(code: String?): Pair<Double, Double>? = key(code)?.let { labels[it] }

    /** The flag as an emoji (two regional-indicator letters), or null when [code] isn't a two-letter code. */
    fun flagEmoji(code: String?): String? {
        val c = code?.trim()?.uppercase() ?: return null
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        val sb = StringBuilder(4)
        for (ch in c) sb.appendCodePoint(REGIONAL_A + (ch - 'A'))
        return sb.toString()
    }

    /** Whole percent of the 195 states among [codes] (already keys), at least 1 when there is any. */
    fun worldPercent(codes: Collection<String>): Int {
        val n = codes.count { it in worldStates }
        if (n == 0) return 0
        return Math.round(100.0 * n / WORLD_COUNT).toInt().coerceIn(1, 100)
    }

    /** The country's name in [locale] (the phone's language by default); the code itself when Java doesn't know it. */
    fun name(code: String, locale: Locale = Locale.getDefault()): String {
        val c = code.trim().uppercase()
        when (c) {
            "CYN" -> return "Northern Cyprus"
            "SOL" -> return "Somaliland"
        }
        return try {
            Locale.Builder().setRegion(c).build().getDisplayCountry(locale).takeIf { it.isNotBlank() && it != c } ?: c
        } catch (_: IllformedLocaleException) {
            c
        }
    }

    private const val REGIONAL_A = 0x1F1E6
}

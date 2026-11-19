package com.justdataplease.boringtravel.places

/**
 * Looser forms of an address the map search found nothing for. Google Maps links often name a place by its full
 * address ("Mollafenari, Elizi, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul, Turkey"), and Photon finds nothing with the
 * postcode, the "No:" or the "Fatih/İstanbul" in it; "Bab-ı Ali Cd. 27, Fatih, İstanbul, Turkey" it finds. So: the
 * address cleaned, then its leading parts dropped while a numbered street is left. Never down to a bare town: a
 * restaurant pinned on a city centre is worse than no pin. Pure and unit tested ([of]); [search] runs them.
 */
object LooseQueries {
    /** Most extra searches for one place. */
    const val MAX = 3

    private val NUMBER_MARK = Regex("(?i)\\b(?:no|nr|nº|n°)\\s*[:.]?\\s*(?=\\d)")
    private val POSTCODE = Regex("(?<![\\w-])\\d{4,6}(?![\\w-])")
    private val SPACES = Regex("\\s+")

    fun of(query: String): List<String> {
        val parts = query.replace('/', ',').replace(NUMBER_MARK, "").replace(POSTCODE, " ")
            .split(',').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        val out = LinkedHashSet<String>()
        out += parts.joinToString(", ")
        for (drop in 1 until parts.size - 1) {
            val rest = parts.drop(drop)
            if (rest.size >= 3 && rest.first().any(Char::isDigit)) out += rest.joinToString(", ")
        }
        out.remove(query.trim())
        return out.take(MAX)
    }

    /**
     * [find] for [query]; nothing found, its looser forms in turn until one finds a place within a day trip of
     * [anchors] (anywhere without anchors); then, near a trip only, the name at its front ([names]: "Pandeli
     * Restaurant, Spice Bazaar, Istanbul" → "Pandeli Restaurant", "Pandeli"), kept only for a venue of that name.
     */
    suspend fun search(query: String, anchors: List<LatLng>, find: suspend (String) -> List<PlaceResult>): List<PlaceResult> {
        val found = find(query)
        if (found.isNotEmpty()) return found
        fun near(p: PlaceResult) = anchors.isEmpty() || NearTrip.distanceM(p, anchors)!! <= NearTrip.DAY_TRIP_M
        for (q in of(query)) {
            val hits = find(q).filter(::near)
            if (hits.isNotEmpty()) return hits
        }
        if (anchors.isEmpty()) return emptyList()
        val front = query.split(',').first().trim()
        for (n in names(front).filter { normal(it) != normal(query) }) {
            val hits = find(n).filter { near(it) && !it.area && sameName(it.name, front) }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    /** [name], then without the words that only say what it is ("Lhardy Restaurante" → "Lhardy"). */
    fun names(name: String): List<String> =
        listOf(name.trim(), bare(name)).filter { it.length >= 3 }.distinctBy(::normal)

    /** [name] without the words that only say what it is: restaurant, hotel, café, museum … */
    fun bare(name: String): String = name.trim().split(SPACES).filter { normal(it) !in KIND_WORDS }.joinToString(" ")

    /**
     * The same name: equal once accents, case and punctuation go, or the shorter one's words inside the longer one and
     * at least half of them ("Restaurante Botín" and "Botín"; not "Four days in Porto" and "Porto").
     */
    fun sameName(a: String, b: String): Boolean {
        val x = normal(a).split(' ').filter { it.isNotEmpty() }
        val y = normal(b).split(' ').filter { it.isNotEmpty() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.size <= y.size) x to y else y to x
        if (short.joinToString("").length < 4 || short.size * 2 < long.size) return false
        return long.windowed(short.size).any { it == short }
    }

    /** Lower case, no accents, words only. */
    fun normal(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").replace(SPACES, " ").trim()

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val KIND_WORDS = setOf(
        "restaurant", "restaurante", "ristorante", "restaurang", "hotel", "hostal", "hostel", "cafe", "bar", "taverna",
        "tavern", "trattoria", "brasserie", "bistro", "bistrot", "pub", "museum", "museo", "musee", "official",
    )
}

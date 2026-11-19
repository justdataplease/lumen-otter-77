package com.justdataplease.boringtravel.tripcal

/**
 * Which of the theme's trip colours each trip gets (an index; ui/tripcal/TripPalette turns it into a colour). A trip
 * keeps its colour: it comes from its id, so adding, moving or deleting another trip never repaints it. Only where
 * two trips overlap or follow each other within a day, and would share a colour, the later one takes the next free
 * colour, so the two never read as one. Pure.
 */
object TripColors {
    /** Colours in every theme's trip palette. */
    const val SIZE = 6

    /** The colour a trip gets on its own: FNV-1a over its id, the same on every phone and every run. */
    fun preferred(id: String, size: Int = SIZE): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in id) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return Math.floorMod(h, size)
    }

    /**
     * The colour of each trip in [trips]: its own ([preferred]) unless a trip it overlaps or touches (within a day)
     * already has that one; then the next colour none of those has (back to its own when all are taken).
     */
    fun assign(trips: List<TripSpan>, size: Int = SIZE): Map<String, Int> {
        val ordered = trips.sortedWith(compareBy<TripSpan>({ it.start }, { it.id }))
        val out = LinkedHashMap<String, Int>()
        for ((i, t) in ordered.withIndex()) {
            val own = preferred(t.id, size)
            val taken = HashSet<Int>()
            for (j in 0 until i) {
                val o = ordered[j]
                if (touches(o, t)) out[o.id]?.let(taken::add)
            }
            var c = own
            if (taken.size < size) while (c in taken) c = (c + 1) % size
            out[t.id] = c
        }
        return out
    }

    /** Overlapping, or one ends the day before (or the day) the other starts. */
    fun touches(a: TripSpan, b: TripSpan): Boolean =
        !a.end.plusDays(1).isBefore(b.start) && !b.end.plusDays(1).isBefore(a.start)
}

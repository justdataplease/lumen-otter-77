package com.justdataplease.boringtravel.tracking

import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.geo.Geo

/** Where a stop came from. A stop the traveller adds or edits is [MANUAL] and is never changed by the detector. */
object StopSource {
    const val AUTO = "auto"
    const val MANUAL = "manual"
}

/**
 * Finds the places the traveller stayed a while (dwell clusters: within [Rules.radiusM] for at least
 * [Rules.minStayMs]) and turns them into suggested stops. Works on sparse points too: with Low or Balanced the phone
 * sends nothing while it stays put, so a long gap between two points at the same spot is a stay, and a stay lasts
 * until the next point elsewhere, less the time it takes to get there.
 */
object StopDetector {
    data class Rules(
        val radiusM: Double = 150.0,
        val minStayMs: Long = 20 * 60_000L,
        /** Leaving speed assumed when the next point is far away (about 30 km/h). */
        val leaveSpeedMps: Double = 8.3,
        /** One stray point inside a stay is skipped when the next point is back within this time. */
        val strayMs: Long = 15 * 60_000L,
        /** Two stays at the same spot with a gap shorter than this are one stay. */
        val joinGapMs: Long = 30 * 60_000L,
        /** An earlier suggestion this close, overlapping in time, is the same stay (updated, keeps its id). */
        val sameStayM: Double = 300.0,
        /** A traveller's own stop this close, overlapping in time, already covers the stay. */
        val ownStopM: Double = 1_000.0,
        val overlapSlackMs: Long = 30 * 60_000L,
    )

    /** A stay found in the points. [leftAt] null = still there. */
    data class Dwell(val lat: Double, val lng: Double, val arrivedAt: Long, val leftAt: Long?, val points: Int)

    /**
     * Stays in [points] (already spike-filtered). [ongoingUntil] = now, while the trip is being recorded: the last
     * cluster then lasts until now and stays open.
     */
    fun detect(points: List<TrackPoint>, ongoingUntil: Long? = null, rules: Rules = Rules()): List<Dwell> {
        val p = points.sortedBy { it.time }
        val found = ArrayList<Dwell>()
        var i = 0
        while (i < p.size) {
            var sumLat = p[i].lat
            var sumLng = p[i].lng
            var count = 1
            var last = i
            var j = i + 1
            while (j < p.size) {
                val cLat = sumLat / count
                val cLng = sumLng / count
                if (Geo.distanceM(cLat, cLng, p[j].lat, p[j].lng) <= rules.radiusM) {
                    sumLat += p[j].lat; sumLng += p[j].lng; count++; last = j; j++
                } else if (j + 1 < p.size && p[j + 1].time - p[last].time <= rules.strayMs &&
                    Geo.distanceM(cLat, cLng, p[j + 1].lat, p[j + 1].lng) <= rules.radiusM
                ) {
                    j++ // one stray fix, back inside right after
                } else break
            }
            val lat = sumLat / count
            val lng = sumLng / count
            val start = p[i].time
            var end = p[last].time
            var open = false
            val next = p.getOrNull(j)
            if (next != null) {
                val travelMs = (Geo.distanceM(lat, lng, next.lat, next.lng) / rules.leaveSpeedMps * 1000).toLong()
                end = maxOf(end, next.time - travelMs)
            } else if (ongoingUntil != null && ongoingUntil >= end) {
                end = ongoingUntil
                open = true
            }
            if (end - start >= rules.minStayMs) found += Dwell(lat, lng, start, if (open) null else end, count)
            i = j
        }
        return join(found, rules)
    }

    /** Joins back-to-back stays at the same spot (a short walk out and back is one stay). */
    private fun join(dwells: List<Dwell>, rules: Rules): List<Dwell> {
        val out = ArrayList<Dwell>()
        for (d in dwells) {
            val prev = out.lastOrNull()
            val prevEnd = prev?.leftAt
            if (prev != null && prevEnd != null && d.arrivedAt - prevEnd <= rules.joinGapMs &&
                Geo.distanceM(prev.lat, prev.lng, d.lat, d.lng) <= rules.radiusM
            ) {
                val n = prev.points + d.points
                out[out.size - 1] = Dwell(
                    (prev.lat * prev.points + d.lat * d.points) / n, (prev.lng * prev.points + d.lng * d.points) / n,
                    prev.arrivedAt, d.leftAt, n,
                )
            } else out += d
        }
        return out
    }

    /**
     * The stops to save for [dwells] next to the trip's [existing] stops (deleted ones included). Never touches the
     * traveller's own stops; a stay they deleted or already marked themselves is not suggested again; an earlier
     * suggestion for the same stay is updated in place (same id, name kept). Returns only rows that changed.
     */
    fun merge(
        existing: List<Stop>,
        dwells: List<Dwell>,
        tripId: String,
        now: Long,
        newId: () -> String,
        rules: Rules = Rules(),
    ): List<Stop> {
        val out = ArrayList<Stop>()
        val claimed = HashSet<String>()
        for (d in dwells) {
            val dEnd = d.leftAt ?: Long.MAX_VALUE
            fun overlaps(s: Stop): Boolean {
                val sEnd = s.leftAt ?: if (s.source == StopSource.AUTO) Long.MAX_VALUE else s.arrivedAt
                return s.arrivedAt <= dEnd.saturatingPlus(rules.overlapSlackMs) && d.arrivedAt <= sEnd.saturatingPlus(rules.overlapSlackMs)
            }
            fun dist(s: Stop) = Geo.distanceM(s.lat, s.lng, d.lat, d.lng)

            val covered = existing.any { s ->
                when {
                    s.deletedAt != null -> overlaps(s) && dist(s) <= rules.sameStayM
                    s.source != StopSource.AUTO -> overlaps(s) && dist(s) <= rules.ownStopM
                    else -> false
                }
            }
            if (covered) continue

            val match = existing
                .filter { it.deletedAt == null && it.source == StopSource.AUTO && it.id !in claimed && overlaps(it) && dist(it) <= rules.sameStayM }
                .minByOrNull { dist(it) }
            if (match != null) {
                claimed += match.id
                val moved = dist(match) > 50
                val updated = match.copy(
                    arrivedAt = d.arrivedAt, leftAt = d.leftAt,
                    lat = if (moved) d.lat else match.lat, lng = if (moved) d.lng else match.lng,
                )
                if (updated != match) out += updated.copy(updatedAt = now)
            } else {
                out += Stop(
                    id = newId(), tripId = tripId, arrivedAt = d.arrivedAt, leftAt = d.leftAt, lat = d.lat, lng = d.lng,
                    source = StopSource.AUTO, createdAt = now, updatedAt = now,
                )
            }
        }
        return out
    }

    private fun Long.saturatingPlus(x: Long): Long = if (this > Long.MAX_VALUE - x) Long.MAX_VALUE else this + x
}

package com.justdataplease.boringtravel.geo

/**
 * A circle the traveller keeps private (home, say). Points inside it are dropped before anything leaves the phone:
 * sync, exports and clips call [PrivacyZone.outside] on their copy; the raw points on the phone stay as recorded.
 */
data class PrivacyZone(val lat: Double, val lng: Double, val radiusM: Double) {
    fun contains(lat: Double, lng: Double): Boolean = Geo.distanceM(this.lat, this.lng, lat, lng) <= radiusM

    companion object {
        /** The items of [items] outside every zone in [zones]; [lat] and [lng] read an item's position. */
        fun <T> outside(items: List<T>, zones: List<PrivacyZone>, lat: (T) -> Double, lng: (T) -> Double): List<T> {
            if (zones.isEmpty()) return items
            val boxes = zones.map { BBox(it.lat, it.lng, it.lat, it.lng).expand(it.radiusM) }
            return items.filter { item ->
                val a = lat(item)
                val b = lng(item)
                zones.indices.none { i -> boxes[i].contains(a, b) && zones[i].contains(a, b) }
            }
        }
    }
}

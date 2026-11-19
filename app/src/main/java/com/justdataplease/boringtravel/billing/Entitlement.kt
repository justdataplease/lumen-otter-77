package com.justdataplease.boringtravel.billing

/** What Play said about one product in one answer. */
enum class PaymentState { Purchased, Pending, Other }

data class Evidence(val productId: String, val state: PaymentState)

data class Ownership(val owned: Boolean, val pending: Boolean)

/**
 * Decides ownership of [productId] from Play's answer. A purchased record grants it; when the query for this
 * product's type was complete and nothing purchased came back, ownership is revoked; an incomplete answer (offline,
 * a failed query) keeps the cached value, so a bad connection never takes Pro away.
 */
fun reconcile(productId: String, cached: Boolean, evidence: List<Evidence>, queryComplete: Boolean): Ownership {
    val mine = evidence.filter { it.productId == productId }
    val purchased = mine.any { it.state == PaymentState.Purchased }
    val pending = !purchased && mine.any { it.state == PaymentState.Pending }
    val owned = when {
        purchased -> true
        queryComplete -> false
        else -> cached
    }
    return Ownership(owned, pending)
}

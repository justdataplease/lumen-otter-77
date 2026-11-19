package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.billing.Evidence
import com.justdataplease.boringtravel.billing.Ownership
import com.justdataplease.boringtravel.billing.PaymentState
import com.justdataplease.boringtravel.billing.reconcile
import org.junit.Assert.assertEquals
import org.junit.Test

class EntitlementTest {
    @Test fun purchaseGrants() =
        assertEquals(Ownership(true, false), reconcile("pro", false, listOf(Evidence("pro", PaymentState.Purchased)), queryComplete = false))

    @Test fun completeEmptyAnswerRevokes() =
        assertEquals(Ownership(false, false), reconcile("pro", true, emptyList(), queryComplete = true))

    @Test fun failedQueryKeepsCachedPro() =
        assertEquals(Ownership(true, false), reconcile("pro", true, emptyList(), queryComplete = false))

    @Test fun pendingIsNotOwned() =
        assertEquals(Ownership(false, true), reconcile("pro_life", false, listOf(Evidence("pro_life", PaymentState.Pending)), queryComplete = true))

    @Test fun otherProductsDontCount() =
        assertEquals(Ownership(false, false), reconcile("pro", false, listOf(Evidence("pro_life", PaymentState.Purchased)), queryComplete = true))
}

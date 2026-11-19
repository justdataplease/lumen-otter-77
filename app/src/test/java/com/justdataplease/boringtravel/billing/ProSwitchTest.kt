package com.justdataplease.boringtravel.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ProBilling.PRO_FOR_SALE] is the one switch for Pro. While it is false (the free Play launch) everything is open and
 * the manifest drops the billing permission, so Play sees an app that sells nothing; once it is true the permission
 * must come back. Flipping the switch without the manifest line fails here.
 */
class ProSwitchTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val billingRemoved = Regex("""<uses-permission\s+android:name="com\.android\.vending\.BILLING"\s+tools:node="remove"\s*/>""")

    @Test fun manifestFollowsTheSwitch() =
        assertEquals(
            "com.android.vending.BILLING must be removed exactly while Pro isn't for sale",
            !ProBilling.PRO_FOR_SALE, billingRemoved.containsMatchIn(manifest),
        )

    @Test fun everythingOpenWhileNotForSale() {
        assertEquals(!ProBilling.PRO_FOR_SALE, ProBilling.EVERYTHING_UNLOCKED)
        if (!ProBilling.PRO_FOR_SALE) assertTrue(ProBilling.State().isPro)
    }

    @Test fun clipsKeepTheMarkUnlessProIsSoldAndOwned() {
        // The free launch opens everything but keeps the mark on exported clips, even with Pretend Pro.
        if (!ProBilling.PRO_FOR_SALE) {
            assertFalse(ProBilling.State().markOff)
            assertFalse(ProBilling.State(yearlyOwned = true, lifeOwned = true, pretend = true).markOff)
        } else {
            assertFalse(ProBilling.State().markOff)
            assertTrue(ProBilling.State(lifeOwned = true).markOff)
        }
    }

    @Test fun noAdvertisingId() =
        assertTrue(Regex("""com\.google\.android\.gms\.permission\.AD_ID"\s+tools:node="remove"""").containsMatchIn(manifest))

    @Test fun microphoneIsOptional() {
        val mic = Regex("""<uses-feature\s+android:name="android\.hardware\.microphone"\s+android:required="false"\s*/>""")
        assertTrue(mic.containsMatchIn(manifest))
        assertFalse(manifest.contains("android.permission.CAMERA\""))
    }
}

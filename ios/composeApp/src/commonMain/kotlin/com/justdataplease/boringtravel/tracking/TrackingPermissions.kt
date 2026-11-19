package com.justdataplease.boringtravel.tracking

/**
 * The steps route tracking asks for, in the Android app's names (its tracking/TrackingPermissions.kt `Step`, which the
 * Android app's TrackingPlan.missing, read in here, names too). The rest of Android's file asks Android for them; on
 * iPhone the screens ask Core Location and UserNotifications ([com.justdataplease.boringtravel.ios.IosTrackingPlan]):
 * Precise is location While Using with Precise Location on, Background is Always, Notifications the route notes.
 */
object TrackingPermissions {
    /** One thing to ask for, in the order the permission flow asks. */
    enum class Step { Precise, Background, Notifications }
}

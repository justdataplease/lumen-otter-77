package com.justdataplease.boringtravel.widget

import android.content.Context
import android.content.Intent
import com.justdataplease.boringtravel.MainActivity
import com.justdataplease.boringtravel.community.CommunityLinks

/** Quick actions from the widgets and launcher shortcuts, handled by MainActivity → AppNav. */
object Deeplink {
    const val EXTRA = "com.justdataplease.boringtravel.ACTION"

    /** A new entry for today in today's trip, ready to type. */
    const val WRITE = "write"

    /** The system camera; the photo becomes a new entry opened in Write. */
    const val CAMERA = "camera"

    /** Older widget buttons sent "photo"; it now means the camera too. */
    const val PHOTO = "photo"
    const val VOICE = "voice"
    const val NEW_TRIP = "new_trip"

    /** Today's trip (or the app as it was when there is none). */
    const val TODAY = "today"

    private const val PREFIX = "com.justdataplease.boringtravel."
    private val known = setOf(WRITE, CAMERA, PHOTO, VOICE, NEW_TRIP, TODAY)

    fun intent(context: Context, action: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(PREFIX + action)
        .putExtra(EXTRA, action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** A community post's link opened with the app ([CommunityLinks]): "post:<id>". */
    const val POST = "post:"

    /** The action carried by an intent from a widget button or launcher shortcut, or a post's link, if any. */
    fun of(intent: Intent?): String? = intent?.getStringExtra(EXTRA)?.takeIf { it in known }
        ?: intent?.action?.removePrefix(PREFIX)?.takeIf { it in known }
        ?: intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(CommunityLinks::find)?.let { POST + it }

    /** The post id of a [POST] action, or null. */
    fun postOf(action: String): String? = if (action.startsWith(POST)) action.removePrefix(POST).takeIf { it.isNotEmpty() } else null
}

package com.justdataplease.boringtravel.widget

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.justdataplease.boringtravel.R

/** Long-press launcher shortcuts (Write, Take a photo, Voice note, New trip), published at runtime so debug builds work too. */
object Shortcuts {
    fun publish(context: Context) {
        fun shortcut(id: String, label: String, icon: Int) = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Deeplink.intent(context, id))
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut(Deeplink.WRITE, context.getString(R.string.shortcut_write), R.drawable.ic_shortcut_write),
                    shortcut(Deeplink.CAMERA, context.getString(R.string.shortcut_camera), R.drawable.ic_shortcut_camera),
                    shortcut(Deeplink.VOICE, context.getString(R.string.shortcut_voice), R.drawable.ic_shortcut_voice),
                    shortcut(Deeplink.NEW_TRIP, context.getString(R.string.shortcut_new_trip), R.drawable.ic_shortcut_trip),
                ),
            )
        }
    }
}

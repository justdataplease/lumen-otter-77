package com.justdataplease.boringtravel.google

import android.content.Context
import androidx.core.content.edit

/**
 * The app's Drive folder ids on this phone ([FolderMemory]), in their own small preferences file: ids by path, no
 * account, token or other data. An id another account can't open is simply looked up again by name.
 */
class DriveFolderMemory(context: Context) : FolderMemory {
    private val prefs = context.applicationContext.getSharedPreferences("drive_folders", Context.MODE_PRIVATE)

    override fun get(path: String): String? = prefs.getString(path, null)

    override fun put(path: String, id: String?) {
        if (prefs.getString(path, null) == id) return
        prefs.edit { if (id == null) remove(path) else putString(path, id) }
    }
}

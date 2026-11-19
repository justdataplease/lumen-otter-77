package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.SettingsFile

/**
 * Android's SharedPreferences as the AI planner's daily count uses them (ai/PlannerAllowance.kt PlannerUsage): a small
 * named file of strings and numbers, here kept in this phone's [SettingsFile] under "<name>.<key>". Those keys are this
 * phone's own: a backup never reads them (AppSettings.backupPreferences names the keys it writes).
 */
class SharedPreferences(private val file: SettingsFile, private val name: String) {
    private fun key(k: String) = "$name.$k"

    fun getInt(key: String, defValue: Int): Int = file.read(key(key))?.toIntOrNull() ?: defValue

    fun getString(key: String, defValue: String?): String? = file.read(key(key)) ?: defValue

    fun edit(): Editor = Editor()

    /** Changes written together on [apply]. */
    inner class Editor internal constructor() {
        private val changes = LinkedHashMap<String, String?>()

        fun putString(key: String, value: String?): Editor = apply { changes[key] = value }

        fun putInt(key: String, value: Int): Editor = apply { changes[key] = value.toString() }

        fun apply() = changes.forEach { (k, v) -> file.write(key(k), v) }
    }
}

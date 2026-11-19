package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.justdataplease.boringtravel.engine.TripFile
import java.io.File
import java.util.Properties

/**
 * The iPhone app's screens in a desktop window, at an iPhone 17 Pro Max's size in points: `gradlew :composeApp:run`.
 * The trips made in the window are kept in composeApp/build/preview/trips.json, the settings in settings.properties
 * and the notes' photos and videos in media/ beside it, so they are there on the next run.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "BoringTravel (iPhone screens)",
        state = rememberWindowState(width = 440.dp, height = 956.dp),
    ) {
        // The trip's pages are drawn by a stand-in for WebKit (DesktopPrint.kt); there is no printer.
        val media = remember { desktopMediaKit(File("build/preview")) }
        // The trip video's editor: silent music, a placeholder microphone and drawn videos (DesktopClipDevice.kt).
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPagePrinter provides remember { DesktopPagePrinter() },
            LocalClipDevice provides remember { desktopClipDevice(media.files) },
        ) { App(
            DiskTripFile(File("build/preview/trips.json")), DiskSettingsFile(File("build/preview/settings.properties")), PREVIEW_VERSION,
            media,
            web = { photos -> desktopWeb(File("build/preview"), PREVIEW_VERSION, photos) },
            // No calendars on the desktop: a phone's calendars in memory, so the Calendar sheet can be tried.
            calendar = remember { com.justdataplease.boringtravel.calendar.MemoryPhoneCalendar() },
            // Me › AI's keys in a file of their own beside the settings (the iPhone keeps them in its Keychain).
            ai = remember { PlannerPhone.ofBuild(DiskKeyValues(File("build/preview/ai-keys.properties"))) },
        ) }
    }
}

/** What Me shows as the version on the desktop: the Android app's versionName, as on the iPhone. */
const val PREVIEW_VERSION = AppVersion.NAME

/** The trips as a text file on the desktop. */
class DiskTripFile(private val file: File) : TripFile {
    override fun read(): String? = file.takeIf { it.isFile }?.readText()

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

/** Me › AI's keys and choices as a properties file on the desktop (never a backup's; never printed). */
class DiskKeyValues(private val file: File) : com.justdataplease.boringtravel.ai.own.KeyValues {
    private val values = Properties().apply { if (file.isFile) file.reader().use { load(it) } }

    override fun get(name: String): String? = values.getProperty(name)

    override fun put(name: String, value: String?) {
        if (value == null) values.remove(name) else values.setProperty(name, value)
        write()
    }

    override fun clear() {
        values.clear()
        write()
    }

    private fun write() {
        file.parentFile?.mkdirs()
        file.writer().use { values.store(it, "BoringTravel (iPhone screens): Me › AI") }
    }
}

/** The settings as a properties file on the desktop, written again on every change. */
class DiskSettingsFile(private val file: File) : SettingsFile {
    private val values = Properties().apply { if (file.isFile) file.reader().use { load(it) } }

    override fun read(key: String): String? = values.getProperty(key)

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.writer().use { values.store(it, "BoringTravel (iPhone screens): settings") }
    }
}

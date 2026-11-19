package com.justdataplease.boringtravel.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.ios.resources.Res

/*
 * What lets the Android app's Fonts.kt compile here unchanged. It names each bundled font as Android does,
 * `Font(R.font.caveat, …)`: R is written by the build from the Android font folder, and this file gives that call its
 * meaning on iPhone.
 */

/** The bundled font files, read once at start so that every look's faces are ready before the first screen. */
object BtFontFiles {
    private val files = HashMap<Int, ByteArray>()

    suspend fun load() {
        for ((id, name) in R.fontFiles) if (id !in files) files[id] = Res.readBytes("files/fonts/$name")
        BtRoboto.load()
    }

    fun data(resId: Int): ByteArray = files[resId] ?: error("Font $resId is not read yet: BtFontFiles.load() comes first")

    /** The font file [name] ("caveat.ttf") once read, for the printed pages; null when the app has none by that name. */
    fun file(name: String): ByteArray? = R.fontFiles.entries.firstOrNull { it.value == name }?.let { files[it.key] }
}

/**
 * Roboto, the face Android draws in wherever it uses the system font (Me › Fonts › Text's "Phone (Roboto)", the default;
 * Material's own words; the trip video's and the globe's fallbacks): Google's own Roboto 2.138 for Android (the
 * googlefonts/roboto-2 release, Apache 2.0, its licence in files/licenses/roboto-LICENSE.txt), bundled because an
 * iPhone's own face is San Francisco. The weights the Android app draws: Regular, Italic, Medium, Bold and Bold Italic.
 * Its SemiBold (600: titles, rows, buttons) is Medium here, as Android 10 and 11 draw it from their static Roboto
 * (Android 12 and later have a variable Roboto with a true 600, which roboto-2 hasn't). Read at start with the others.
 */
object BtRoboto {
    /** The five files, under files/roboto. */
    val FILES = listOf("Roboto-Regular.ttf", "Roboto-Italic.ttf", "Roboto-Medium.ttf", "Roboto-Bold.ttf", "Roboto-BoldItalic.ttf")

    private val files = HashMap<String, ByteArray>()

    suspend fun load() {
        for (name in FILES) if (name !in files) files[name] = Res.readBytes("files/roboto/$name")
    }

    /** The bytes of [name] once read; null before [load]. */
    fun data(name: String): ByteArray? = files[name]

    private fun face(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal): Font = androidx.compose.ui.text.platform.Font(
        identity = "roboto-$name-${weight.weight}",
        getData = { files[name] ?: error("Roboto is not read yet: BtFontFiles.load() comes first") },
        weight = weight,
        style = style,
    )

    /** Roboto as one family, each weight Android draws on its file. */
    val family: FontFamily by lazy {
        FontFamily(
            face("Roboto-Regular.ttf", FontWeight.Normal),
            face("Roboto-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
            face("Roboto-Medium.ttf", FontWeight.Medium),
            face("Roboto-Medium.ttf", FontWeight.SemiBold),
            face("Roboto-Bold.ttf", FontWeight.Bold),
            face("Roboto-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
        )
    }
}

/** Android's `Font(resId, …)`: the same font file, at the same weight and variation, from this app's own files. */
fun Font(
    resId: Int,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    variationSettings: FontVariation.Settings = FontVariation.Settings(weight, style),
): Font = androidx.compose.ui.text.platform.Font(
    // One name per file, weight and variation: the text engine keeps each loaded face under it.
    identity = "bt-$resId-${weight.weight}-$style-" + variationSettings.settings.joinToString("-") { "${it.axisName}${it.toVariationValue(null)}" },
    getData = { BtFontFiles.data(resId) },
    weight = weight,
    style = style,
    variationSettings = variationSettings,
)

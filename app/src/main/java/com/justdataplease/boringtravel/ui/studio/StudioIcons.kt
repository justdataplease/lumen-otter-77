package com.justdataplease.boringtravel.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.ui.icons.BtIcons

/**
 * Tool icons the editors need beyond [BtIcons], drawn the same way (24 × 24, stroke 1.8, round caps and joins);
 * paths from the studio mockups.
 */
object StudioIcons {
    /** A photo swapped for another. */
    val Replace: ImageVector by lazy { line("Replace", "M4,4 H20 V20 H4 Z M8,12 H16 M13,9 L16,12 L13,15") }

    /** Crop marks. */
    val Crop: ImageVector by lazy { line("Crop", "M6,2 V18 H22 M2,6 H18 V22") }

    /** A print's border. */
    val Frame: ImageVector by lazy { line("Frame", "M3,3 H21 V21 H3 Z M7,7 H17 V17 H7 Z") }

    /** A sheet of paper with a folded corner: the page's background. */
    val Paper: ImageVector by lazy { line("Paper", "M6,3 H15 L19,7 V21 H6 Z M15,3 V7 H19") }

    /** A sticker with a peeled corner. */
    val Sticker: ImageVector by lazy { line("Sticker", "M4,4 H16 L20,8 V20 H4 Z M16,4 V8 H20") }

    /** A drop of ink: colour. */
    val Colour: ImageVector by lazy { line("Colour", "M12,3 C12,3 18,9.5 18,14 A6,6 0 0 1 6,14 C6,9.5 12,3 12,3 Z") }

    /** Shown. */
    val Eye: ImageVector by lazy { line("Eye", "M2,12 C2,12 6,5 12,5 C18,5 22,12 22,12 C22,12 18,19 12,19 C6,19 2,12 2,12 Z M9,12 A3,3 0 1 0 15,12 A3,3 0 1 0 9,12 Z") }

    /** Hidden. */
    val EyeOff: ImageVector by lazy { line("EyeOff", "M2,12 C2,12 6,5 12,5 C18,5 22,12 22,12 C22,12 18,19 12,19 C6,19 2,12 2,12 Z M9,12 A3,3 0 1 0 15,12 A3,3 0 1 0 9,12 Z M4,4 L20,20") }

    // The trip video editor's tools and tracks (Editor mockup).

    /** A clock: a scene's timing. */
    val Timing: ImageVector by lazy { line("Timing", "M12 20a8 8 0 1 1 0-16a8 8 0 1 1 0 16M12 8v4l3 2") }

    /** A magnifier with a plus: how close the camera goes. */
    val ZoomIn: ImageVector by lazy { line("ZoomIn", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M21 21l-5-5M8 11h6M11 8v6") }

    /** A gauge: the clip's speed. */
    val Speed: ImageVector by lazy { line("Speed", "M12 20a8 8 0 1 1 8-8M12 12l4-4") }

    /** A folded map. */
    val FoldedMap: ImageVector by lazy { line("FoldedMap", "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2zM9 4v14M15 6v14") }

    /** A winding route between two dots. */
    val Path: ImageVector by lazy { line("Path", "M5 19c4-1 3-6 7-7s3-6 7-7M5 19h.01M19 5h.01") }

    /** A T: words on the video. */
    val Words: ImageVector by lazy { line("Words", "M5 6h14M12 6v13") }

    /** The Stats tool: three bars rising from a base line. */
    val Stats: ImageVector by lazy { line("Stats", "M4 20h16M7 16v-4M12 16V8M17 16V5") }

    /** A picture. */
    val Picture: ImageVector by lazy { line("Picture", "M3 5h18v14H3zM9 10a1 1 0 1 0 0 .01M21 16l-5-5-8 8") }

    /** Two notes: music. */
    val Music: ImageVector by lazy { line("Music", "M9 18V5l11-2v13M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6M17 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6") }

    /** A microphone: a voice line. */
    val Voice: ImageVector by lazy { line("Voice", "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0zM5 11a7 7 0 0 0 14 0M12 18v3") }

    /** A video camera. */
    val Video: ImageVector by lazy { line("Video", "M3 6h13v12H3zM16 10l5-3v10l-5-3z") }

    /** A title card. */
    val Card: ImageVector by lazy { line("Card", "M4 5h16v14H4zM8 10h8M8 14h5") }

    /** Someone walking: the trip video's traveller (the walk glyph of the old flat vehicles). */
    val Traveller: ImageVector by lazy {
        line(
            "Traveller",
            "M12.0,4.2 a2.0,2.0 0 1,0 4.0,0 a2.0,2.0 0 1,0 -4.0,0 M13.2,7.6 L11.6,13.4 M11.6,13.4 L9.4,17.2 L7,20.4 " +
                "M11.6,13.4 L14.2,16.6 L14.6,21 M12.8,9 L15.8,11.6 M12.8,9 L9.6,11.2",
        )
    }

    /** Four corners pointing out: full screen. */
    val FullScreen: ImageVector by lazy { line("FullScreen", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5") }

    private fun line(name: String, d: String): ImageVector =
        ImageVector.Builder(name = "StudioIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = BtIcons.STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()
}

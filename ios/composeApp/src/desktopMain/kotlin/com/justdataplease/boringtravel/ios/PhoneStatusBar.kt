package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.compat.SystemBars

/**
 * An iPhone's status bar drawn over the pictures (an iPhone 17 Pro Max's: 9:41, the Dynamic Island, the signal, Wi-Fi
 * and the battery), its icons dark or light as the app sets them ([SystemBars]: the theme's, a studio's), so each
 * picture shows the status bar the phone would. In [landscape] iPhone hides the status bar: only the island is drawn,
 * at the left side.
 */
@Composable
fun PhoneStatusBar(landscape: Boolean = false) {
    if (landscape) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = 11.dp).size(width = 37.dp, height = 126.dp).background(Color.Black, RoundedCornerShape(19.dp)))
        }
        return
    }
    val ink = if (SystemBars.lightStatusBars) Color.Black else Color.White
    Box(Modifier.fillMaxWidth().height(54.dp)) {
        Text("9:41", Modifier.align(Alignment.CenterStart).padding(start = 56.dp), color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.align(Alignment.TopCenter).offset(y = 11.dp).size(width = 126.dp, height = 37.dp).background(Color.Black, RoundedCornerShape(19.dp)))
        Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 34.dp).size(width = 76.dp, height = 13.dp)) {
            val u = size.height / 13f
            // The signal: four bars.
            for (i in 0 until 4) {
                val h = (4f + i * 3f) * u
                drawRoundRect(ink, Offset(i * 4.6f * u, size.height - h), Size(3f * u, h), CornerRadius(1f * u))
            }
            // Wi-Fi: three arcs.
            val c = Offset(29f * u, size.height)
            for (r in listOf(4f, 8f, 12f)) drawArc(ink, 225f, 90f, useCenter = r == 4f, topLeft = c - Offset(r * u, r * u), size = Size(2 * r * u, 2 * r * u), style = if (r == 4f) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.2f * u))
            // The battery.
            drawRoundRect(ink.copy(alpha = 0.4f), Offset(46f * u, 0f), Size(26f * u, size.height), CornerRadius(4f * u), style = Stroke(1.2f * u))
            drawRoundRect(ink, Offset(48f * u, 2f * u), Size(22f * u, size.height - 4f * u), CornerRadius(2.5f * u))
            drawRoundRect(ink.copy(alpha = 0.4f), Offset(73f * u, 4.5f * u), Size(2f * u, 4f * u), CornerRadius(1f * u))
        }
    }
}

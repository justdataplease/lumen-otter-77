package com.justdataplease.boringtravel.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.justdataplease.boringtravel.art.PlaneMarkBitmap
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.ui.theme.BtColors
import com.justdataplease.boringtravel.ui.theme.planeMark
import com.justdataplease.boringtravel.ui.theme.BtLooks
import kotlin.math.roundToInt

/**
 * "Today" home-screen widget: the trip happening now (or today's date) and three quick buttons — Write, Photo
 * (the camera), Voice. Drawn in the app's current look.
 */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.graph
        val first = WidgetUpdates.load(graph)
        provideContent {
            val updates = remember { WidgetUpdates.flow(graph) }
            val info by updates.collectAsState(first)
            Content(info)
        }
    }

    @Composable
    private fun Content(s: TodayInfo) {
        val c = BtLooks.of(s.look).colors
        val wide = LocalSize.current.width >= WIDE.width
        val density = LocalContext.current.resources.displayMetrics.density
        // The logo on the theme's yellow (Glance shows bitmaps, not Canvas drawings).
        val mark = remember(s.look, density) { PlaneMarkBitmap.bitmap((28 * density).roundToInt(), c.planeMark()) }
        Column(
            GlanceModifier.fillMaxSize().background(ColorProvider(c.surface)).cornerRadius(22.dp).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Image(ImageProvider(mark), null, GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(s.trip?.title ?: "Today", maxLines = 1, style = TextStyle(color = ColorProvider(c.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text(WidgetText.todayLine(s), maxLines = 1, style = TextStyle(color = ColorProvider(c.muted), fontSize = 12.sp))
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth()) {
                Button("Write", c, primary = true, action = Deeplink.WRITE, modifier = GlanceModifier.defaultWeight())
                if (wide) {
                    Spacer(GlanceModifier.width(8.dp))
                    Button("Photo", c, primary = false, action = Deeplink.CAMERA, modifier = GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.width(8.dp))
                Button("Voice", c, primary = false, action = Deeplink.VOICE, modifier = GlanceModifier.defaultWeight())
            }
        }
    }

    @Composable
    private fun Button(label: String, c: BtColors, primary: Boolean, action: String, modifier: GlanceModifier) {
        val context = LocalContext.current
        Box(
            modifier.height(40.dp).cornerRadius(20.dp).background(ColorProvider(if (primary) c.primary else c.chip))
                .clickable(actionStartActivity(Deeplink.intent(context, action))),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(color = ColorProvider(if (primary) c.onPrimary else c.ink), fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }

    companion object {
        val SMALL = DpSize(150.dp, 100.dp)
        val WIDE = DpSize(250.dp, 100.dp)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

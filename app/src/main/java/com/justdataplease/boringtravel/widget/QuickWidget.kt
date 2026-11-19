package com.justdataplease.boringtravel.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import com.justdataplease.boringtravel.R
import com.justdataplease.boringtravel.graph
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.BUTTON
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.GAP
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.MARK
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.MARK_GAP
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.PAD
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.SHADOW_X
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.SHADOW_Y
import com.justdataplease.boringtravel.art.PlaneMarkBitmap
import kotlin.math.roundToInt

/**
 * "Quick bar": a horizontal bar on the look's paper with the logo, today's trip ("Day 4 · Naxos") and the quick
 * buttons — Note (a new entry, ready to type), Camera (the system camera, then the photo in a new entry) and, when
 * there is room, Voice. Sized exactly ([SizeMode.Exact]) so the paper is drawn at the size it is shown; see
 * [QuickBarLayout] for what shows at each width. The 2×1 "Quick bar, small" is the same widget at its narrowest.
 */
open class QuickWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.graph
        val first = WidgetUpdates.load(graph)
        provideContent {
            val updates = remember { WidgetUpdates.flow(graph) }
            val info by updates.collectAsState(first)
            QuickBar(info)
        }
    }
}

/** The 2×1 entry in the widget picker; the bar's narrow layout. */
class QuickSmallWidget : QuickWidget()

class QuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickWidget()
}

class QuickSmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickSmallWidget()
}

@Composable
private fun QuickBar(info: TodayInfo) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    val layout = QuickBarLayout.of(size.width.value, size.height.value)
    val look = WidgetLook.of(info.look)
    val bar = remember(look, layout, density) { WidgetArt.bar(look, layout.width, layout.barHeight, density) }
    val note = remember(look, layout.noteWidth, density) { WidgetArt.note(look, layout.noteWidth, BUTTON, density) }
    val round = remember(look, density) { WidgetArt.round(look, BUTTON, density) }
    val mark = remember(look, density) { PlaneMarkBitmap.bitmap((MARK * density).roundToInt(), look.mark) }
    val line = WidgetText.context(info)

    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            GlanceModifier
                .fillMaxWidth()
                .height(layout.barHeight.dp)
                .background(ImageProvider(bar), ContentScale.FillBounds)
                .appWidgetBackground()
                .clickable(actionStartActivity(Deeplink.intent(context, Deeplink.TODAY))),
        ) {
            Row(
                GlanceModifier.fillMaxSize().padding(start = layout.startPad.dp, end = (PAD + SHADOW_X).dp, bottom = SHADOW_Y.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = if (layout.context) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                if (layout.mark) {
                    Image(ImageProvider(mark), contentDescription = null, modifier = GlanceModifier.size(MARK.dp))
                    Spacer(GlanceModifier.width(MARK_GAP.dp))
                }
                if (layout.context) {
                    Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line.primary,
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(look.ink), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        )
                        Text(
                            line.secondary,
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(look.muted), fontSize = 12.sp),
                        )
                    }
                    Spacer(GlanceModifier.width(GAP.dp))
                }
                NoteButton(look, note, layout.noteWidth)
                Spacer(GlanceModifier.width(GAP.dp))
                RoundButton(look, round, R.drawable.ic_widget_camera, context.getString(R.string.widget_camera), Deeplink.CAMERA)
                if (layout.voice) {
                    Spacer(GlanceModifier.width(GAP.dp))
                    RoundButton(look, round, R.drawable.ic_widget_mic, context.getString(R.string.widget_voice), Deeplink.VOICE)
                }
            }
        }
    }
}

@Composable
private fun NoteButton(look: WidgetLook, art: android.graphics.Bitmap, widthDp: Float) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .width(widthDp.dp)
            .height(BUTTON.dp)
            .background(ImageProvider(art), ContentScale.FillBounds)
            .clickable(actionStartActivity(Deeplink.intent(context, Deeplink.WRITE))),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                ImageProvider(R.drawable.ic_widget_note),
                contentDescription = null,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(ColorProvider(look.onNote)),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(context.getString(R.string.widget_note), maxLines = 1, style = TextStyle(color = ColorProvider(look.onNote), fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun RoundButton(look: WidgetLook, art: android.graphics.Bitmap, icon: Int, label: String, action: String) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .size(BUTTON.dp)
            .background(ImageProvider(art), ContentScale.FillBounds)
            .clickable(actionStartActivity(Deeplink.intent(context, action))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(ColorProvider(look.onButton)),
        )
    }
}

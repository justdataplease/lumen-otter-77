package com.justdataplease.boringtravel.ui.me

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.coroutines.launch

/**
 * Me › Theme and Fonts (the boards' Me screen): the five themes as small swatch cards, each drawn on its own paper
 * with its own text face, the picked one ringed in its route colour; then two font choices as chips: titles and
 * dates ("Theme's own" first) and text (the phone's Roboto first).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSection(graph: AppGraph, modifier: Modifier = Modifier) {
    val current by graph.settings.look.collectAsStateWithLifecycle(initialValue = Look.Default)
    val fonts by graph.settings.fontSet.collectAsStateWithLifecycle(initialValue = FontSet.LooksOwn)
    val text by graph.settings.textFont.collectAsStateWithLifecycle(initialValue = TextFont.Default)
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Theme")
        Look.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { look ->
                    ThemeCard(look, look == current, Modifier.weight(1f)) { scope.launch { graph.settings.setLook(look) } }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SectionLabel("Titles and dates", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            FontSet.entries.forEach { set ->
                BtChip(set.label, set == fonts, onClick = { scope.launch { graph.settings.setFontSet(set) } })
            }
        }
        SectionLabel("Text", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TextFont.entries.forEach { font ->
                BtChip(font.label, font == text, onClick = { scope.launch { graph.settings.setTextFont(font) } })
            }
        }
    }
}

/** One theme as on the boards (`.s-lk`): its swatch, then its name in its own face on its own paper. */
@Composable
private fun ThemeCard(look: Look, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = BtLooks.of(look)
    val ring = t.colors.route
    Column(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .paper(t)
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                // `outline: 2.5px solid var(--route); outline-offset: 2px` round the swatch only.
                .drawWithContent {
                    drawContent()
                    if (selected) {
                        val w = 2.5.dp.toPx()
                        val o = 2.dp.toPx() + w / 2
                        drawRoundRect(
                            ring, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                            cornerRadius = CornerRadius(10.dp.toPx() + o), style = Stroke(w),
                        )
                    }
                }
                .clip(RoundedCornerShape(10.dp))
                .drawBehind { swatch(look) }
                .border(1.dp, Color(0x1F000000), RoundedCornerShape(10.dp)),
        )
        Text(
            look.label,
            style = Bt.text.fine.copy(fontFamily = t.type.body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) t.colors.ink else t.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The boards' swatches (`.lk-pop` …): each theme's paper and colours as bands. */
private fun DrawScope.swatch(look: Look) {
    fun bands(vararg stops: Pair<Float, Color>) {
        var from = 0f
        for ((to, c) in stops) {
            drawRect(c, Offset(0f, size.height * from), Size(size.width, size.height * (to - from)))
            from = to
        }
    }
    when (look) {
        Look.PostcardPop -> bands(0.62f to Color(0xFFFFF6E5), 0.72f to Color(0xFFFF5A3C), 1f to Color(0xFF1D1B3A))
        Look.Trail -> bands(0.30f to Color(0xFFF27A54), 0.72f to Color(0xFF3D2448), 0.80f to Color(0xFFF6C445), 1f to Color(0xFF24132C))
        Look.StickerBook -> {
            bands(0.60f to Color(0xFFFCFCF9), 0.72f to Color(0xFFFFE066), 1f to Color(0xFFFCFCF9))
            val step = 8.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(Color(0x401E2A5A), 1.dp.toPx(), Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        Look.Sketchbook -> {
            bands(1f to Color(0xFFFCFCF9))
            val step = 10.dp.toPx()
            var y = 9.dp.toPx()
            while (y < size.height) {
                drawRect(Color(0x405A606E), Offset(0f, y), Size(size.width, 1.dp.toPx()))
                y += step
            }
        }
        Look.Market -> bands(0.60f to Color(0xFFEFE5D3), 0.70f to Color(0xFFFF7A3D), 1f to Color(0xFFEFE5D3))
    }
}

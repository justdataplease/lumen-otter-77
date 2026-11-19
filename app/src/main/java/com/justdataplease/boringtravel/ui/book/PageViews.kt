package com.justdataplease.boringtravel.ui.book

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.CropMath
import com.justdataplease.boringtravel.book.DecoEl
import com.justdataplease.boringtravel.book.El
import com.justdataplease.boringtravel.book.FlagsEl
import com.justdataplease.boringtravel.book.FreeKind
import com.justdataplease.boringtravel.book.FlowEl
import com.justdataplease.boringtravel.book.LinesEl
import com.justdataplease.boringtravel.book.PageBg
import com.justdataplease.boringtravel.book.PagePlan
import com.justdataplease.boringtravel.book.PhotoEl
import com.justdataplease.boringtravel.book.RouteEl
import com.justdataplease.boringtravel.book.RuleEl
import com.justdataplease.boringtravel.book.StampEl
import com.justdataplease.boringtravel.book.TextEl
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.ui.components.MediaImage
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt
import com.justdataplease.boringtravel.book.Box as MmBox

/** CSS pixels per mm at the WebView's default scale (1 CSS px = 1 dp). */
const val CSS_PX_PER_MM = 96.0 / 25.4

private fun hex(s: String): Color = Color(android.graphics.Color.parseColor(s))

/**
 * An element being dragged, as a change from where the page's HTML has it: moved by ([dx], [dy]) mm, turned to [rot]
 * degrees and stretched by [sx] × [sy] around its centre.
 */
data class LiveMove(val id: String, val dx: Double, val dy: Double, val rot: Double, val sx: Double, val sy: Double)

/** What a preview WebView last showed. */
private class PreviewState(var html: String? = null, var moved: String? = null)

/** Script that shows [m] on the element with `data-fe` = its id (the page keeps its own transform to put back). */
internal fun moveScript(m: LiveMove): String {
    fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.3f", v)
    return "(function(){var e=document.querySelector('[data-fe=\"${m.id}\"]');if(!e)return;" +
        "if(e.getAttribute('data-t0')===null){e.setAttribute('data-t0',e.style.transform||'');e.setAttribute('data-o0',e.style.transformOrigin||'');}" +
        "e.style.transformOrigin='50% 50%';e.style.transform='translate(${n(m.dx)}mm,${n(m.dy)}mm) rotate(${n(m.rot)}deg) scale(${n(m.sx)},${n(m.sy)})';})();"
}

internal fun resetScript(id: String): String =
    "(function(){var e=document.querySelector('[data-fe=\"$id\"]');if(!e||e.getAttribute('data-t0')===null)return;" +
        "e.style.transform=e.getAttribute('data-t0');e.style.transformOrigin=e.getAttribute('data-o0');e.removeAttribute('data-t0');e.removeAttribute('data-o0');})();"

/**
 * The page exactly as it prints: the page HTML in a WebView (no network, only the bundled fonts and the prepared
 * photos; the page has no scripts of its own). Touches go to the overlays above it, not the WebView. While an element
 * is dragged ([live]) the editor moves it here directly, so the page follows the finger before it is laid out again.
 */
@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
@Composable
fun PagePreview(html: String, allowedDir: File, paper: Color, modifier: Modifier = Modifier, live: LiveMove? = null) {
    val allowed = allowedDir.absolutePath
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Only the editor's own move scripts run (evaluateJavascript); the page HTML has none and no network.
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.argb((paper.alpha * 255).roundToInt(), (paper.red * 255).roundToInt(), (paper.green * 255).roundToInt(), (paper.blue * 255).roundToInt()))
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (request.isForMainFrame || ExportFiles.isAllowedUrl(url, allowed)) return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
        },
        update = { web ->
            val st = (web.tag as? PreviewState) ?: PreviewState().also { web.tag = it }
            if (st.html != html) {
                st.html = html
                st.moved = null
                web.loadDataWithBaseURL("file://$allowed/", html, "text/html", "utf-8", null)
            }
            if (live != null) {
                st.moved?.takeIf { it != live.id }?.let { web.evaluateJavascript(resetScript(it), null) }
                st.moved = live.id
                web.evaluateJavascript(moveScript(live), null)
            } else {
                st.moved?.let { web.evaluateJavascript(resetScript(it), null) }
                st.moved = null
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/**
 * A small drawing of a page from its [plan]: the paper, the photos where they sit (tilted), text as lines, stamps,
 * route and flags as marks. Laid out from the same geometry as the PDF.
 */
@Composable
fun PageThumb(plan: PagePlan, layout: BookLayout, media: Map<String, Media>, modifier: Modifier = Modifier, dim: Boolean = false) {
    val look = layout.pages.look
    val f = layout.spec.format
    val paper = hex(look.paperColor)
    val ink = hex(look.ink)
    val red = hex(look.route)
    val bg = plan.bg
    val bgColor = bg?.takeIf { it.kind == PageBg.COLOR }?.color?.let(::hex)
    BoxWithConstraints(modifier.aspectRatio(f.aspect.toFloat()).shadow(2.dp, RectangleShape).background(bgColor ?: paper).clipToBounds()) {
        val k = maxWidth / f.widthMm.toFloat()
        fun Modifier.at(b: MmBox): Modifier = this.offset(k * b.x.toFloat(), k * b.y.toFloat()).size(k * b.w.toFloat(), k * b.h.toFloat())
        bg?.takeIf { it.kind == PageBg.PHOTO }?.photo?.let { s ->
            media[s.mediaId]?.let { m -> CroppedPhoto(m, f.aspect, s.crop, Modifier.fillMaxSize()) }
            if (bg.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bg.dim.toFloat())))
        }
        // Drawn in the page's order: what lies under the photos, the photos, then what lies on them.
        val lastPhoto = plan.els.indexOfLast { it is PhotoEl }
        val under = if (lastPhoto < 0) plan.els else plan.els.subList(0, lastPhoto)
        val over = if (lastPhoto < 0) emptyList() else plan.els.subList(lastPhoto + 1, plan.els.size)
        val drawEls: DrawScope.(List<El>) -> Unit = { list ->
            val s = size.width / f.widthMm.toFloat()
            list.forEach { el ->
                when (el) {
                    is FlowEl -> {
                        val rows = (el.rows).coerceAtMost(60)
                        val filled = plan.page.paras.isNotEmpty()
                        if (filled) for (c in 0 until el.cols) for (r in 0 until rows step 2) {
                            val y = (el.box.y + (r + 0.6) * layout.pages.grid.line).toFloat() * s
                            val x = (el.box.x + c * (el.colWidth + el.gutter)).toFloat() * s
                            val w = (el.colWidth * if (r % 6 == 4) 0.6 else 0.94).toFloat() * s
                            drawLine(ink.copy(alpha = 0.22f), Offset(x, y), Offset(x + w, y), 1.2f * s.coerceAtMost(3f))
                        }
                    }
                    is TextEl -> if (el.text.isNotBlank()) rotate(el.angle.toFloat(), Offset(el.box.cx.toFloat() * s, el.box.cy.toFloat() * s)) {
                        val h = (el.pt * 0.25 * if (el.role.startsWith("t") || el.role.contains("title") || el.role == "stat") 1.0 else 0.7).toFloat() * s
                        val w = minOf(el.box.w, el.text.length * el.pt * 0.18).toFloat() * s
                        val x = when (el.align) { "center" -> (el.box.cx.toFloat() * s - w / 2); "right" -> (el.box.right.toFloat() * s - w); else -> el.box.x.toFloat() * s }
                        val y = (el.box.y + el.box.h * 0.55).toFloat() * s
                        if (el.mark) drawRect(hex(look.highlighter).copy(alpha = 0.8f), Offset(x, y - h * 0.1f), androidx.compose.ui.geometry.Size(w, h * 0.6f))
                        val tint = el.color?.let(::hex) ?: ink.copy(alpha = if (el.face == layout.pages.fonts.mono) 0.35f else 0.75f)
                        drawRect(tint, Offset(x, y - h / 2), androidx.compose.ui.geometry.Size(w, h * 0.5f))
                    }
                    is StampEl -> {
                        val c = Offset(el.box.cx.toFloat() * s, el.box.cy.toFloat() * s)
                        val r = el.box.w.toFloat() / 2 * s
                        drawCircle(hex(look.red), r * 0.92f, c, style = Stroke(width = r * 0.1f))
                        drawCircle(hex(look.red), r * 0.66f, c, style = Stroke(width = r * 0.04f))
                    }
                    is RouteEl -> {
                        val b = el.box
                        val p = Path().apply {
                            moveTo((b.x + b.w * 0.1).toFloat() * s, (b.y + b.h * 0.7).toFloat() * s)
                            quadraticTo((b.x + b.w * 0.35).toFloat() * s, (b.y + b.h * 0.1).toFloat() * s, (b.x + b.w * 0.55).toFloat() * s, (b.y + b.h * 0.5).toFloat() * s)
                            quadraticTo((b.x + b.w * 0.75).toFloat() * s, (b.y + b.h * 0.9).toFloat() * s, (b.x + b.w * 0.9).toFloat() * s, (b.y + b.h * 0.3).toFloat() * s)
                        }
                        drawPath(p, red, style = Stroke(width = 0.7f * s, cap = StrokeCap.Round))
                    }
                    is FlagsEl -> el.codes.take(6).forEachIndexed { i, _ ->
                        val w = el.height * 1.5
                        val x = when (el.align) { "center" -> el.box.cx - el.codes.size.coerceAtMost(6) * (w + 1.3) / 2; "right" -> el.box.right - el.codes.size.coerceAtMost(6) * (w + 1.3); else -> el.box.x } + i * (w + 1.3)
                        drawRect(Color(0xFF6C86B4), Offset(x.toFloat() * s, el.box.y.toFloat() * s), androidx.compose.ui.geometry.Size(w.toFloat() * s, el.height.toFloat() * s))
                    }
                    is RuleEl -> drawLine(ink.copy(alpha = 0.2f), Offset(el.box.x.toFloat() * s, el.box.y.toFloat() * s), Offset(el.box.right.toFloat() * s, el.box.y.toFloat() * s), 1f)
                    is LinesEl -> {
                        var y = el.box.y + el.pitch
                        while (y < el.box.bottom) {
                            drawLine(ink.copy(alpha = 0.12f), Offset(el.box.x.toFloat() * s, y.toFloat() * s), Offset(el.box.right.toFloat() * s, y.toFloat() * s), 1f)
                            y += el.pitch
                        }
                    }
                    is DecoEl -> rotate(el.angle.toFloat(), Offset(el.box.cx.toFloat() * s, el.box.cy.toFloat() * s)) {
                        val tl = Offset(el.box.x.toFloat() * s, el.box.y.toFloat() * s)
                        val sz = androidx.compose.ui.geometry.Size(el.box.w.toFloat() * s, el.box.h.toFloat() * s)
                        val color = el.color?.let(::hex)
                        when (el.kind) {
                            FreeKind.TAPE -> drawRect((color ?: Color(0xFFE6D7A8)).copy(alpha = 0.85f), tl, sz)
                            FreeKind.SHAPE -> {
                                el.fill?.let { f -> if (el.style == "oval") drawOval(hex(f), tl, sz) else drawRect(hex(f), tl, sz) }
                                if (el.stroke > 0 && color != null) {
                                    val st = Stroke(width = (el.stroke * s).toFloat().coerceAtLeast(1f))
                                    if (el.style == "oval") drawOval(color, tl, sz, style = st) else drawRect(color, tl, sz, style = st)
                                }
                            }
                            FreeKind.LINE -> drawLine(color ?: ink, Offset(tl.x, tl.y + sz.height / 2), Offset(tl.x + sz.width, tl.y + sz.height / 2), (el.stroke * s).toFloat().coerceAtLeast(1f))
                            FreeKind.CORNERS -> {
                                val c = color ?: Color(0xFF2B2B2B)
                                val k = (minOf(el.box.w, el.box.h) * 0.2).coerceIn(3.0, 12.0).toFloat() * s
                                listOf(tl, Offset(tl.x + sz.width - k, tl.y), Offset(tl.x + sz.width - k, tl.y + sz.height - k), Offset(tl.x, tl.y + sz.height - k))
                                    .forEach { drawRect(c, it, androidx.compose.ui.geometry.Size(k, k)) }
                            }
                            FreeKind.SHADE -> drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), tl.y, tl.y + sz.height), tl, sz)
                        }
                    }
                    is PhotoEl -> Unit
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) { drawEls(under) }
        plan.photos.forEach { p ->
            val framed = (p.style ?: if (layout.pages.look.prints && !p.bleed) "print" else "flat") == "print"
            val m = media[p.mediaId]
            Box(
                Modifier.at(p.frame).rotate(p.tilt.toFloat())
                    .then(if (framed) Modifier.shadow(1.dp, RectangleShape).background(Color.White) else Modifier)
                    .then(if (m == null) Modifier.border(1.dp, ink.copy(alpha = 0.3f)) else Modifier),
            ) {
                if (m != null) {
                    val inner = MmBox(p.image.x - p.frame.x, p.image.y - p.frame.y, p.image.w, p.image.h)
                    CroppedPhoto(m, p.image.aspect, p.crop, Modifier.at(inner))
                }
            }
        }
        if (over.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { drawEls(over) }
        if (dim) Box(Modifier.fillMaxSize().background(paper.copy(alpha = 0.6f)))
    }
}

/** A photo showing the part [crop] picks for a frame of [aspect]. */
@Composable
fun CroppedPhoto(m: Media, aspect: Double, crop: com.justdataplease.boringtravel.book.Crop, modifier: Modifier) {
    val v = CropMath.visible(m.width, m.height, aspect, crop)
    Box(modifier.clipToBounds()) {
        // A video on a page is its first frame, as printed: no play mark.
        Layout(content = { MediaImage(m, Modifier, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, playBadge = false) }) { measurables, constraints ->
            val fw = constraints.maxWidth
            val fh = constraints.maxHeight
            val w = (fw / v.w).roundToInt().coerceAtLeast(1)
            val h = (fh / v.h).roundToInt().coerceAtLeast(1)
            val placeable = measurables.first().measure(Constraints.fixed(w, h))
            layout(fw, fh) { placeable.place(-(v.x * w).roundToInt(), -(v.y * h).roundToInt()) }
        }
    }
}

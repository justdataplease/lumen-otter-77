package com.justdataplease.boringtravel.ui.export

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The pages of a finished PDF as pictures, for the share screen's preview: the very file that is then shared, saved
 * or printed. [PdfRenderer] draws one page at a time, so calls take turns. Small pictures are kept in a cache of a
 * fixed size; bitmaps are left to the garbage collector rather than recycled, as one may still be on screen.
 */
internal class PdfPages private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val count: Int = renderer.pageCount

    /** Width / height of the first page (all pages share the paper). */
    val aspect: Float = if (count == 0) 0.707f else renderer.openPage(0).use { it.width.toFloat() / it.height.coerceAtLeast(1) }

    private val lock = Any()
    private var closed = false
    private val small = object : LruCache<Int, Bitmap>(SMALL_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    /** Page [index] drawn [widthPx] wide (at most [MAX_PX]); null once closed or when it can't be drawn. */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed || index !in 0 until count) null else runCatching {
            renderer.openPage(index).use { page ->
                val w = widthPx.coerceIn(64, MAX_PX)
                val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                createBitmap(w, h).also {
                    it.eraseColor(AColor.WHITE) // where the page draws nothing, paper is white
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    /** A small picture of page [index], from the cache when it has one. */
    fun thumb(index: Int, widthPx: Int): Bitmap? = small.get(index) ?: render(index, widthPx)?.also { small.put(index, it) }

    fun close() {
        synchronized(lock) { if (!closed) shut() }
    }

    private fun shut() {
        closed = true
        small.evictAll()
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        /** Biggest page picture, for the full-screen view. */
        const val MAX_PX = 1600
        private const val SMALL_CACHE_BYTES = 24 * 1024 * 1024

        fun open(file: File): PdfPages? {
            val fd = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() ?: return null
            return runCatching { PdfPages(fd, PdfRenderer(fd)) }.getOrElse { runCatching { fd.close() }; null }
        }
    }
}

/** The pages side by side, small, drawn as they scroll in; a tap opens one big. */
@Composable
internal fun PageStrip(pages: PdfPages, height: Dp, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val thumbW = height * pages.aspect
    val px = with(LocalDensity.current) { thumbW.roundToPx() }
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pages.count, key = { it }) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bitmap by produceState<ImageBitmap?>(null, pages, i) { value = withContext(Dispatchers.IO) { pages.thumb(i, px) }?.asImageBitmap() }
                PagePaper(bitmap, "Page ${i + 1} of ${pages.count}", Modifier.height(height).aspectRatio(pages.aspect).clickable(role = Role.Button, onClickLabel = "Open page ${i + 1}") { onOpen(i) })
                Text("${i + 1}", Modifier.padding(top = 4.dp), style = Bt.text.fine, color = Bt.colors.muted)
            }
        }
    }
}

/** A page on screen: white paper with a soft shadow, its picture once drawn. */
@Composable
private fun PagePaper(bitmap: ImageBitmap?, description: String, modifier: Modifier) {
    Box(modifier.shadow(3.dp).background(Color.White).border(0.5.dp, Color(0x22000000))) {
        if (bitmap != null) Image(bitmap, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The pages full screen on a dark ground, swiped through from [start]; "3 / 12" at the bottom, back or × closes. */
@Composable
internal fun PageViewer(pages: PdfPages, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (pages.count - 1).coerceAtLeast(0))) { pages.count }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF26282B))) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = 44.dp)) {
                val px = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(PdfPages.MAX_PX)
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, key = { it }) { i ->
                    val bitmap by produceState<ImageBitmap?>(null, pages, i, px) { value = withContext(Dispatchers.IO) { pages.render(i, px) }?.asImageBitmap() }
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        PagePaper(bitmap, "Page ${i + 1} of ${pages.count}", Modifier.fillMaxWidth().aspectRatio(pages.aspect))
                    }
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(44.dp).clip(CircleShape)
                    .background(Color(0x66000000)).clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(BtIcons.Close, "Close", Modifier.size(22.dp), tint = Color.White) }
            Text(
                "${pager.currentPage + 1} / ${pages.count}",
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
                    .clip(CircleShape).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                style = Bt.text.mono, color = Color.White,
            )
        }
    }
}

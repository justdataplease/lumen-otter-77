package com.justdataplease.boringtravel.ui.book

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.book.AlbumEdit
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPage
import com.justdataplease.boringtravel.book.Slot
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.export.ExportException
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.export.ExportProgress
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioIconButton
import com.justdataplease.boringtravel.ui.studio.StudioMainButton
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioRoundButton
import com.justdataplease.boringtravel.ui.studio.StudioScreen
import com.justdataplease.boringtravel.ui.studio.StudioSheet
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.studio.StudioTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import com.justdataplease.boringtravel.google.DriveFile
import java.io.File

/** Sheets over the whole book (the PDF, the book's look, a new page from the overview). */
private sealed interface BookSheetKind {
    data object Make : BookSheetKind
    data object Look : BookSheetKind
    data class AddAfter(val index: Int) : BookSheetKind
    data object PhotoPages : BookSheetKind
}

/**
 * Make a book, in the dark studio: every page as a thumbnail (tap to open, hold and drag to move), and one page large
 * to arrange by hand ([PageEditor]: select, move, resize, turn, hide, reorder, add, restyle); the book's look and the
 * print-ready PDF.
 */
@Composable
fun BookScreen(graph: AppGraph, actions: AppActions, tripId: String) {
    val vm: BookViewModel = viewModel(key = "book-$tripId") { BookViewModel(graph, tripId) }
    // Every photo grid of the book gets "From phone": the phone's own picker, the photos joining the trip.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_FROM_PHONE)) { uris -> vm.addFromPhone(uris) }
    val fromPhone = remember(picker) { { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    CompositionLocalProvider(LocalAddFromPhone provides fromPhone) { BookEditor(graph, actions, tripId, vm) }
}

/** The most photos one visit to the phone's picker brings in. */
private const val MAX_FROM_PHONE = 30

@Composable
private fun BookEditor(graph: AppGraph, actions: AppActions, tripId: String, vm: BookViewModel) {
    val s by vm.state.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var sheet by remember { mutableStateOf<BookSheetKind?>(null) }
    val layout = remember(s.spec, s.facts) { vm.layout(s) }
    val printed = remember(s.doc, layout) { layout?.printed(s.doc).orEmpty() }
    val media = remember(s.trip) { s.trip?.media.orEmpty().filter { it.deletedAt == null }.associateBy { it.id } }
    // By the time taken; the ones just brought in from the phone first, so they are found at once.
    val tripPhotos = remember(media, s.fresh) {
        s.fresh.mapNotNull { media[it] } + media.values.filter { it.id !in s.fresh }.sortedBy { it.takenAt ?: it.createdAt }
    }

    /** A panel closes (its visit is one undo step). */
    fun setTool(t: Tool?) {
        vm.endLive()
        tool = t
    }
    /** Another selection closes the open panel. */
    fun select(id: String?) {
        if (id != selected) setTool(null)
        selected = id
    }
    fun openPage(id: String?) {
        select(null)
        setTool(null)
        open = id
    }
    LaunchedEffect(open) { selected = null }
    BackHandler(open != null) {
        when {
            tool != null -> setTool(null)
            selected != null -> select(null)
            else -> openPage(null)
        }
    }

    StudioScreen {
        // The app frame already stops above the gesture bar (AppNav paints it dark for the studio).
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val l = layout
            val page = open?.let { s.doc.page(it) }
            when {
                s.loading || s.error != null || l == null -> {
                    StudioTopBar("Book", null, onClose = actions::back, closeLabel = "Back")
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (s.error != null) {
                            Text("Couldn't make the book", style = StudioText.panelTitle)
                            Spacer(Modifier.height(6.dp))
                            StudioNote(s.error.orEmpty())
                        } else {
                            CircularProgressIndicator(color = StudioColors.yellow)
                            Spacer(Modifier.height(12.dp))
                            StudioNote("Laying out the pages")
                        }
                    }
                }
                page == null -> PagesOverview(
                    state = s,
                    layout = l,
                    printed = printed,
                    media = media,
                    onClose = actions::back,
                    onOpen = ::openPage,
                    onMove = { from, to -> vm.edit(reflowAll = true) { BookEdit.move(it, from, to) } },
                    onAdd = { sheet = BookSheetKind.AddAfter(s.doc.pages.lastIndex - 1) },
                    onAddPhotos = { sheet = BookSheetKind.PhotoPages },
                    onLook = { sheet = BookSheetKind.Look },
                    onPdf = { sheet = BookSheetKind.Make },
                    onUndo = vm::undo,
                    onRedo = vm::redo,
                )
                else -> PageEditor(
                    graph = graph,
                    vm = vm,
                    state = s,
                    layout = l,
                    pageId = page.id,
                    printed = printed,
                    media = media,
                    photos = tripPhotos,
                    selected = selected,
                    tool = tool,
                    onSelect = ::select,
                    onTool = ::setTool,
                    onOpen = ::openPage,
                    onPdf = { sheet = BookSheetKind.Make },
                )
            }
        }
    }

    val l = layout ?: return
    when (val k = sheet) {
        null -> Unit
        BookSheetKind.Make -> MakeSheet(graph, l, s, printed.size, onDismiss = { sheet = null })
        BookSheetKind.Look -> StudioSheet("Book look", onDismiss = { sheet = null }) {
            LookContent(
                s.spec, s.doc.padTo4, tripPhotos,
                onSpec = vm::setSpec,
                onPad = { on -> vm.edit { it.copy(padTo4 = on) } },
                onReset = { sheet = null; openPage(null); vm.resetLayout() },
            )
        }
        BookSheetKind.PhotoPages -> StudioSheet("Add photo pages", onDismiss = { sheet = null }) {
            var picked by remember { mutableStateOf(emptyList<String>()) }
            var choosing by remember { mutableStateOf(false) }
            var onlyNew by remember { mutableStateOf(true) }
            val used = remember(s.doc) { s.doc.pages.flatMapTo(HashSet()) { it.mediaIds } }
            if (!choosing) StudioChip("Only photos not in the book", onlyNew, { onlyNew = !onlyNew })
            else StudioChip("Back to the photos", false, { choosing = false })
            AlbumPagesPicker(l, tripPhotos, used, 340.dp, picked, { picked = it }, choosing, { choosing = it }, onlyNew) { ids, a ->
                sheet = null
                // Before the back cover, after the last day.
                val at = AlbumEdit.beforeBack(s.doc)
                val day = s.doc.pages.getOrNull(at - 1)?.day
                val added = AlbumEdit.pages(ids.map { Slot(it) }, a, day, null, vm::newElementId)
                vm.edit(reflowAll = true) { AlbumEdit.insertAt(it, AlbumEdit.beforeBack(it), added) }
                added.firstOrNull()?.let { openPage(it.id) }
            }
        }
        is BookSheetKind.AddAfter -> {
            val day = s.doc.pages.getOrNull(k.index)?.day
            StudioSheet("Add a page", onDismiss = { sheet = null }) {
                TemplateGrid(
                    null, l, media, key = day,
                    preview = { t -> l.pages.plan(BookEdit.blank(t, "preview", day), k.index + 2) },
                    onPick = { t ->
                        sheet = null
                        val page = vm.newPage(t, day)
                        vm.edit(reflowAll = true) { BookEdit.insert(it, k.index + 1, page) }
                        openPage(page.id)
                    },
                )
            }
        }
    }
}

/** Every page, in order, on the dark studio. Tap to open; hold and drag to move. */
@Composable
private fun PagesOverview(
    state: BookViewModel.State,
    layout: BookLayout,
    printed: List<BookPage>,
    media: Map<String, Media>,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onAddPhotos: () -> Unit,
    onLook: () -> Unit,
    onPdf: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val pages = state.doc.pages
    val pad = BookLayout.padding(pages.count { layout.isVisible(it, state.doc) }).takeIf { state.doc.padTo4 } ?: 0
    Column(Modifier.fillMaxSize()) {
        StudioTopBar(
            title = layout.facts.trip.title.ifBlank { "Book" },
            subtitle = "Book · ${printed.size} pages · ${layout.spec.format.label}",
            onClose = onClose,
            closeLabel = "Back",
        ) {
            StudioRoundButton(BtIcons.Palette, "Book look", onLook)
            StudioMainButton("PDF", onPdf, enabled = !state.busy)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        StudioNote("Tap a page to change it. Hold and drag to move it.")
                        if (pad > 0) StudioNote("$pad notes ${if (pad == 1) "page" else "pages"} added for binding.")
                    }
                    StudioIconButton(BtIcons.Undo, "Undo", onUndo, enabled = state.canUndo)
                    StudioIconButton(BtIcons.Redo, "Redo", onRedo, enabled = state.canRedo)
                }
                PagesGrid(pages, printed, layout, media, onOpen, onMove, onAdd, onAddPhotos)
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = StudioColors.yellow, trackColor = Color.Transparent)
        }
    }
}

/** All pages, in order. Tap to open; hold and drag to move. */
@Composable
private fun PagesGrid(
    pages: List<BookPage>,
    printed: List<BookPage>,
    layout: BookLayout,
    media: Map<String, Media>,
    onOpen: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var order by remember(pages) { mutableStateOf(pages.map { it.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableIntStateOf(-1) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val byId = remember(pages) { pages.associateBy { it.id } }
    val numbers = remember(printed) { printed.mapIndexed { i, p -> p.id to i + 1 }.toMap() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp).pointerInput(pages) {
            fun keyAt(p: Offset): String? = grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                p.x >= info.offset.x && p.x <= info.offset.x + info.size.width && p.y >= info.offset.y && p.y <= info.offset.y + info.size.height
            }?.key as? String
            detectDragGesturesAfterLongPress(
                onDragStart = { p ->
                    val key = keyAt(p)?.takeIf { it in byId } ?: return@detectDragGesturesAfterLongPress
                    dragging = key
                    start = order.indexOf(key)
                    pointer = p
                },
                onDrag = { change, amount ->
                    val key = dragging ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    pointer += amount
                    val target = keyAt(pointer)
                    if (target != null && target != key && target in byId) {
                        val list = order.toMutableList()
                        val to = list.indexOf(target)
                        list.remove(key)
                        list.add(to, key)
                        order = list
                    }
                    val edge = with(density) { 56.dp.toPx() }
                    val h = grid.layoutInfo.viewportSize.height
                    if (pointer.y > h - edge) scope.launch { grid.scrollBy(18f) } else if (pointer.y < edge) scope.launch { grid.scrollBy(-18f) }
                },
                onDragEnd = {
                    val key = dragging
                    val to = key?.let { order.indexOf(it) } ?: -1
                    if (key != null && start >= 0 && to >= 0 && to != start) onMove(start, to)
                    dragging = null
                },
                onDragCancel = { dragging = null; order = pages.map { it.id } },
            )
        },
    ) {
        items(order, key = { it }) { id ->
            val page = byId[id] ?: return@items
            val n = numbers[id]
            val plan = remember(page, layout, n) { layout.pages.plan(page, n ?: 1) }
            val lifted = id == dragging
            Column(
                Modifier
                    .graphicsLayer { if (lifted) { scaleX = 1.06f; scaleY = 1.06f; alpha = 0.9f } }
                    .clickable(role = Role.Button, onClickLabel = "Open page") { onOpen(id) }
                    .semantics { contentDescription = "Page ${n ?: "hidden"}, ${page.template?.label ?: "page"}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageThumb(plan, layout, media, Modifier.fillMaxWidth().then(if (lifted) Modifier.border(2.dp, StudioColors.yellow) else Modifier), dim = n == null)
                Text(
                    if (n == null) "Hidden" else "$n · ${shortLabel(page, layout)}",
                    Modifier.padding(top = 6.dp),
                    style = StudioText.sub,
                    color = StudioColors.soft,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "add") { AddTile(layout, BtIcons.Add, "Add a page", onAdd) }
        item(key = "add-photos") { AddTile(layout, BtIcons.Photo, "Photo pages", onAddPhotos, coral = true) }
    }
}

/** A dashed page to tap: a new page, or new photo pages. */
@Composable
private fun AddTile(layout: BookLayout, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, coral: Boolean = false) {
    Column(Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(layout.spec.format.aspect.toFloat()).clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    drawRoundRect(
                        if (coral) StudioColors.coral else StudioColors.muted, cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (coral) StudioColors.coral else StudioColors.soft) }
        Text(label, Modifier.padding(top = 6.dp), style = StudioText.sub, color = StudioColors.soft)
    }
}

private fun shortLabel(p: BookPage, layout: BookLayout): String = when (p.template) {
    Template.DayOpener -> layout.facts.day(p.day)?.number?.let { "${layout.facts.labels.day} $it" } ?: "Day"
    Template.Cover -> "Cover"
    Template.BackCover -> "Back"
    Template.TitlePage -> "Title"
    Template.PhotoFull, Template.PhotoFullCaption, Template.Photo, Template.PhotoTwo, Template.PhotoTwoSide,
    Template.PhotoThree, Template.PhotoThreeUp, Template.PhotoFour, Template.Album -> "Photos"
    Template.PhotoText, Template.PhotoTextSide, Template.Text -> "Text"
    Template.Map -> "Map"
    Template.Stats -> "Numbers"
    Template.Notes -> "Notes"
    null -> "Page"
}

/**
 * The PDF: save to the phone, share the file, print, or send to Drive and then send its link (owner, 2026-11-09, on
 * sharing: "send full video or google drive link?", "same with the book").
 */
@Composable
private fun MakeSheet(graph: AppGraph, layout: BookLayout, s: BookViewModel.State, pages: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var made by remember(s.doc, s.spec) { mutableStateOf<File?>(null) }
    // The PDF in Drive, once sent there, and its link anyone can open, once asked for.
    var inDrive by remember(s.doc, s.spec) { mutableStateOf<DriveFile?>(null) }
    var link by remember(s.doc, s.spec) { mutableStateOf<String?>(null) }
    val title = layout.facts.trip.title.ifBlank { "Trip" }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = made
        if (uri != null && f != null) scope.launch {
            notice = try { graph.books.copyTo(f, uri); "Saved." } catch (e: Exception) { "Couldn't save it there. Try another place." }
        }
    }

    fun run(work: suspend (File) -> Unit) {
        if (job?.isActive == true) return
        notice = null
        job = scope.launch {
            progress = ExportProgress("Getting ready")
            try {
                val f = made ?: graph.books.pdf(layout, s.doc, title) { progress = it }.also { made = it }
                progress = null
                work(f)
            } catch (e: TimeoutCancellationException) {
                notice = "The pages took too long. Try smaller photos or fewer pages."
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveNotConnectedException) {
                notice = "Google Drive isn't connected. Connect it in Me › Backup & sync."
            } catch (e: ExportException) {
                notice = e.message
            } catch (e: OutOfMemoryError) {
                notice = "Too many large photos for one go. Try a smaller size."
            } catch (e: Exception) {
                notice = "Couldn't make the PDF. Try again."
            } finally {
                progress = null
            }
        }
    }

    StudioSheet("PDF", onDismiss) {
        val spec = layout.spec
        Column {
            Text("$pages pages · ${spec.format.label} · ${spec.format.sizeText}", style = StudioText.item)
            StudioNote(
                listOfNotNull(
                    if (spec.bleed) "3 mm bleed and crop marks" else null,
                    "photos at print quality",
                    "fonts embedded",
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            )
        }
        val idle = progress == null
        StudioButton("Save to phone", { run { saver.launch(ExportFiles.fileName("$title book", "pdf")) } }, Modifier.fillMaxWidth(), icon = BtIcons.Download, primary = true, enabled = idle)
        StudioButton("Share PDF", { run { Sharing.share(context, it, title) } }, Modifier.fillMaxWidth(), icon = BtIcons.Share, enabled = idle)
        StudioButton("Print", {
            val activity = context.findActivity()
            run { f -> if (activity != null) graph.books.print(activity, f, title, layout.pages.sheet) }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Print, enabled = idle)
        StudioButton("Send to Drive", {
            run { f ->
                graph.export.requireDrive()
                val file = graph.export.savePdfToDrive(f, "$title book") { progress = it }
                inDrive = file
                notice = "Saved to Drive › BoringTravel › Exports › ${file.name}"
            }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Cloud, enabled = idle)
        inDrive?.let { file ->
            StudioButton("Send Drive link", {
                if (job?.isActive != true) job = scope.launch {
                    try {
                        val url = link ?: graph.drive.shareLink(file).also { link = it }
                        Sharing.shareLink(context, url, "$title book")
                        notice = "Anyone with the link can open the book; it stays in your Drive."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notice = "Couldn't make the link. Try again."
                    }
                }
            }, Modifier.fillMaxWidth(), icon = BtIcons.Share, enabled = idle)
        }
        progress?.let { p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.text, Modifier.weight(1f), style = StudioText.item)
                    Text("Cancel", Modifier.clickable(role = Role.Button) { job?.cancel() }.padding(8.dp), style = StudioText.chip, color = StudioColors.yellow)
                }
                Spacer(Modifier.height(8.dp))
                val f = p.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = StudioColors.yellow, trackColor = StudioColors.raised)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = StudioColors.yellow, trackColor = StudioColors.raised)
            }
        }
        notice?.let { StudioNote(it) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.book.AlbumEdit
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.FreeEl
import com.justdataplease.boringtravel.book.Slot
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.ui.book.AlbumPagesPicker
import com.justdataplease.boringtravel.ui.book.BookViewModel
import com.justdataplease.boringtravel.ui.book.LocalAddFromPhone
import com.justdataplease.boringtravel.ui.book.LookContent
import com.justdataplease.boringtravel.ui.book.PageEditor
import com.justdataplease.boringtravel.ui.book.PagesOverview
import com.justdataplease.boringtravel.ui.book.TemplateGrid
import com.justdataplease.boringtravel.ui.book.Tool
import com.justdataplease.boringtravel.ui.book.importLoosePhotos
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.studio.StudioButton
import com.justdataplease.boringtravel.ui.studio.StudioChip
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioScreen
import com.justdataplease.boringtravel.ui.studio.StudioSheet
import com.justdataplease.boringtravel.ui.studio.StudioText
import com.justdataplease.boringtravel.ui.studio.StudioTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/*
 * Make a book, in the dark studio (the Android app's ui/book/BookScreen.kt: BookScreen, BookEditor and MakeSheet, cut
 * out of the read-in file because they reach Android's view-model factory, launchers, Activity and Drive; the overview
 * of all pages, the sheets and the thumbnails are Android's own files, read in). Every page as a thumbnail (tap to open,
 * hold and drag to move), the book's look, new pages and photo pages, and the print-ready PDF. A page opens in Android's
 * own page editor (ui/book/PageEditor.kt with its canvas, tools and layers, read in since round 22) on the same
 * view-model: select, move with snapping, resize, turn, hide, reorder, add, restyle; the page under it is WebKit's on
 * iPhone, moved live through evaluateJavaScript while the finger moves.
 */

/** Sheets over the whole book (the PDF, the book's look, a new page from the overview). */
private sealed interface BookSheetKind {
    data object Make : BookSheetKind
    data object Look : BookSheetKind
    data class AddAfter(val index: Int) : BookSheetKind
    data object PhotoPages : BookSheetKind
}

/** What the book opens with, for previews and tests: a sheet over all pages, or a page large. */
enum class BookStart { Look, AddPage, PhotoPages, Pdf, Page, Drive }

/**
 * What the page opened at the start ([BookScreen]'s pageAtStart) has selected and which tool is open, for previews and
 * tests: [select] picks an element's id among the page's elements as the editor lists them (bottom first), or none.
 */
class EditorStart internal constructor(
    internal val select: (List<FreeEl>) -> String? = { null },
    internal val tool: Tool? = null,
)

/** The most photos one visit to the phone's picker brings in (the Android app's MAX_FROM_PHONE). */
private const val MAX_FROM_PHONE = 30

/**
 * Trip [tripId]'s book: the trip store [book], the Share screen's style a new book takes ([settings]' print theme).
 * [onChanged]: photos brought in from the phone joined the trip. [startWith] and [pageAtStart] (a page's index) open a
 * sheet or a page at once, [editorAtStart] what that page has selected and open, for previews and tests.
 */
@Composable
fun BookScreen(
    book: TripBook,
    tripId: String,
    settings: AppSettings,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    startWith: BookStart? = null,
    pageAtStart: Int? = null,
    editorAtStart: EditorStart? = null,
) {
    val media = LocalMediaKit.current
    val printer = LocalPagePrinter.current
    val appScope = LocalAppScope.current
    val scope = rememberCoroutineScope()
    val books = remember(book, media, printer) { BookExports(book, media.files, media.frames, printer) }
    val importer = remember(media) { MediaImporter(media.files) }
    val vm = remember(books, tripId) {
        BookViewModel(
            books, tripId, printTheme = { settings.printTheme }, scope = scope, appScope = appScope,
            import = { picked -> importLoosePhotos(picked, tripId, importer) { book.addMedia(it) }.also { if (it.isNotEmpty()) onChanged() } },
        )
    }
    // Leaving keeps the last edit (Android: the view-model's onCleared).
    DisposableEffect(vm) { onDispose { vm.close() } }
    // Every photo grid of the book gets "From phone": the phone's own picker, the photos joining the trip.
    val fromPhone = remember(media, vm) { { media.picker.pickPhotos(MAX_FROM_PHONE) { picked -> vm.addFromPhone(picked) } } }
    CompositionLocalProvider(LocalAddFromPhone provides fromPhone) { BookEditor(vm, onBack, startWith, pageAtStart, editorAtStart) }
}

@Composable
private fun BookEditor(vm: BookViewModel, onBack: () -> Unit, startWith: BookStart?, pageAtStart: Int?, editorAtStart: EditorStart?) {
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
    // A page opens with nothing selected; the page opened at the start with what previews and tests ask for.
    val startPicks = remember { arrayOf(editorAtStart) }
    LaunchedEffect(open) {
        selected = null
        val ask = startPicks[0] ?: return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val page = open?.let { s.doc.page(it) } ?: return@LaunchedEffect
        startPicks[0] = null
        selected = ask.select(l.pages.free.elements(page, l.number(s.doc, page.id)))
        tool = ask.tool
    }
    // The system's Back (iPhone: the swipe from the left edge), as Android's BookScreen's BackHandler: in the page editor
    // it closes the open tool, then lets the selection go, then goes to all pages; on all pages the app's Back leaves.
    BackHandler(open != null) {
        when {
            tool != null -> setTool(null)
            selected != null -> select(null)
            else -> openPage(null)
        }
    }

    // Previews and tests: a sheet or a page once the book is laid out.
    LaunchedEffect(startWith, pageAtStart, layout != null) {
        if (layout == null) return@LaunchedEffect
        pageAtStart?.let { i -> s.doc.pages.getOrNull(i)?.let { open = it.id } }
        sheet = when (startWith) {
            BookStart.Look -> BookSheetKind.Look
            BookStart.AddPage -> BookSheetKind.AddAfter(s.doc.pages.lastIndex - 1)
            BookStart.PhotoPages -> BookSheetKind.PhotoPages
            BookStart.Pdf, BookStart.Drive -> BookSheetKind.Make
            BookStart.Page, null -> null
        }
    }

    StudioScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val l = layout
            val page = open?.let { s.doc.page(it) }
            when {
                s.loading || s.error != null || l == null -> {
                    StudioTopBar("Book", null, onClose = onBack, closeLabel = "Back")
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
                page == null -> Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    PagesOverview(
                        state = s,
                        layout = l,
                        printed = printed,
                        media = media,
                        onClose = onBack,
                        onOpen = ::openPage,
                        onMove = { from, to -> vm.edit(reflowAll = true) { BookEdit.move(it, from, to) } },
                        onAdd = { sheet = BookSheetKind.AddAfter(s.doc.pages.lastIndex - 1) },
                        onAddPhotos = { sheet = BookSheetKind.PhotoPages },
                        onLook = { sheet = BookSheetKind.Look },
                        onPdf = { sheet = BookSheetKind.Make },
                        onUndo = vm::undo,
                        onRedo = vm::redo,
                    )
                }
                // The page editor stops above the home indicator (Android: the app frame stops above the gesture bar).
                else -> Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    PageEditor(
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
    }

    val l = layout ?: return
    when (val k = sheet) {
        null -> Unit
        BookSheetKind.Make -> MakeSheet(vm, l, s, printed.size, onDismiss = { sheet = null }, sendAtStart = startWith == BookStart.Drive)
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

/**
 * The PDF: save to the phone, share the file, print, or send to Drive and then send its link (the Android app's
 * MakeSheet; its owner, on sharing: "send full video or google drive link?", "same with the book"). Made once until the
 * book changes. Without Drive: "Google Drive isn't connected. Connect it in Me › Backup & sync."
 */
@Composable
private fun MakeSheet(vm: BookViewModel, layout: BookLayout, s: BookViewModel.State, pages: Int, onDismiss: () -> Unit, sendAtStart: Boolean = false) {
    val scope = rememberCoroutineScope()
    val phone = LocalPhone.current
    val files = LocalMediaKit.current.files
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var made by remember(s.doc, s.spec) { mutableStateOf<String?>(null) }
    // The PDF in Drive, once sent there, and its link anyone can open, once asked for.
    var inDrive by remember(s.doc, s.spec) { mutableStateOf<com.justdataplease.boringtravel.google.DriveFile?>(null) }
    var link by remember(s.doc, s.spec) { mutableStateOf<String?>(null) }
    val drive = com.justdataplease.boringtravel.google.LocalGoogle.current?.drive
    val title = layout.facts.trip.title.ifBlank { "Trip" }

    fun run(work: suspend (String) -> Unit) {
        if (job?.isActive == true) return
        notice = null
        job = scope.launch {
            progress = ExportProgress("Getting ready")
            try {
                val f = made ?: vm.books.pdf(layout, s.doc, title) { progress = it }.also { made = it }
                progress = null
                work(f)
            } catch (e: TimeoutCancellationException) {
                notice = "The pages took too long. Try smaller photos or fewer pages."
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.justdataplease.boringtravel.google.DriveNotConnectedException) {
                notice = "Google Drive isn't connected. Connect it in Me › Backup & sync."
            } catch (e: ExportException) {
                notice = e.message
            } catch (e: Throwable) {
                // The JVM's OutOfMemoryError (the desktop); an iPhone out of memory ends the app.
                notice = if (e::class.simpleName == "OutOfMemoryError") "Too many large photos for one go. Try a smaller size." else "Couldn't make the PDF. Try again."
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
        StudioButton("Save to phone", {
            run { f -> phone.exportFile(f) { done -> if (done) notice = "Saved." } }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Download, primary = true, enabled = idle)
        StudioButton("Share PDF", {
            run { f -> files.read(f)?.let { phone.shareFile(f.substringAfterLast('/'), "application/pdf", it, title) } }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Share, enabled = idle)
        StudioButton("Print", {
            run { f -> if (!vm.books.print(f, title)) notice = "Couldn't open the print dialog." }
        }, Modifier.fillMaxWidth(), icon = BtIcons.Print, enabled = idle)
        fun sendToDrive() = run { f ->
            val d = drive ?: throw com.justdataplease.boringtravel.google.DriveNotConnectedException()
            val file = TripExports.savePdfToDrive(d, f, "$title book") { progress = it }
            inDrive = file
            notice = "Saved to Drive › BoringTravel › Exports › ${file.name}"
        }
        // Previews: the sheet opened with the PDF on its way to Drive.
        LaunchedEffect(sendAtStart) { if (sendAtStart) sendToDrive() }
        StudioButton("Send to Drive", { sendToDrive() }, Modifier.fillMaxWidth(), icon = BtIcons.Cloud, enabled = idle)
        inDrive?.let { file ->
            StudioButton("Send Drive link", {
                if (job?.isActive != true) job = scope.launch {
                    try {
                        val url = link ?: (drive ?: throw com.justdataplease.boringtravel.google.DriveNotConnectedException()).shareLink(file).also { link = it }
                        phone.share("$title book", url)
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

/** "<Trip> book.pdf" (the Android app's file name for the book's PDF). */
fun bookFileName(title: String): String = ExportFiles.fileName("${title.ifBlank { "Trip" }} book", "pdf")

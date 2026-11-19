package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justdataplease.boringtravel.compat.DateTimeFormatter
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.PhotoSize
import com.justdataplease.boringtravel.export.TripDocx
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtRadioRow
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btBorder
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * A trip's ⋮ › Share (the Android app's ui/export/ExportScreen.kt, DayRangeSheet.kt and PdfPreview.kt): on top the line
 * to the book, then the real pages, the PDF for the options on screen made in the background and shown page by page,
 * made again 0.7 s after an option changes. Under it the style (Notebook or Minimal), the days, photo size, paper, the
 * notebook's paper and the plan, each remembered. Last, only what to make, as outlined buttons: PDF, Text, Google Docs,
 * Word; picking one opens a sheet with what to do with it (Save to phone, Print, Share; Copy for text). Google Docs
 * needs the Google account (checklist round 23): it says Drive isn't connected, as Android does when it isn't.
 */

/** A message under the buttons: a result, a problem in plain words, and what can be done next. */
private data class Notice(val title: String, val body: String? = null, val actions: List<Pair<String, () -> Unit>> = emptyList())

/** One thing to do with what was just made (Save to phone, Print, Share…), as a tile with its icon. */
private data class Way(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** What was made ([title], a line about it) and what can be done with it: the sheet that opens after a format is picked. */
private data class Ways(val title: String, val body: String?, val items: List<Way>)

/** The PDF made for [options]: previewed, then shared, saved or printed. [handedOut] once another app has it. */
private class MadePdf(val path: String, val options: ExportOptions, val title: String) {
    var handedOut = false
}

/** How long the options must stay put before the pages are made again. */
private const val PREVIEW_PAUSE_MS = 700L

/** The longest text handed over as a message (the Android app's Sharing.MAX_TEXT_CHARS); longer goes as a file. */
private const val MAX_TEXT_CHARS = 100_000

/** What the Share screen opens with, for previews: the days' sheet, a format's sheet, or the first page big. */
enum class ShareStart { Days, Pdf, Text, Word, Page, Docs }

@Composable
fun ShareScreen(
    book: TripBook, tripId: String, settings: AppSettings, revision: Int, onBack: () -> Unit, onSay: (String) -> Unit,
    startWith: ShareStart? = null,
    onMakeBook: () -> Unit = {},
) {
    val media = LocalMediaKit.current
    val printer = LocalPagePrinter.current
    val phone = LocalPhone.current
    val copy = rememberCopy()
    val files = media.files
    val exports = remember(book, media, printer) { TripExports(book, files, media.frames, printer) }
    val scope = rememberCoroutineScope()
    val trip = remember(book, tripId, revision) { book.trip(tripId) }

    // The last choices made here are kept (and travel in backups).
    val saved = remember { settings.printChoices }
    var picked by remember { mutableStateOf<PrintTheme?>(null) }
    var pickedSize by remember { mutableStateOf<PhotoSize?>(null) }
    var pickedPaper by remember { mutableStateOf<Paper?>(null) }
    var pickedPlan by remember { mutableStateOf<Boolean?>(null) }
    var pickedRuled by remember { mutableStateOf<Boolean?>(null) }
    // Medium by default: more photos to a page.
    val photoSize = pickedSize ?: PhotoSize.entries.firstOrNull { it.id == saved.photoSize } ?: PhotoSize.Medium
    val paper = pickedPaper ?: Paper.entries.firstOrNull { it.id == saved.paper } ?: Paper.default()
    val includePlan = pickedPlan ?: saved.plan ?: false
    val ruled = pickedRuled ?: saved.ruled ?: false
    fun keep(c: PrintChoices) = settings.setPrintChoices(c)
    var from by remember { mutableStateOf<LocalDate?>(null) }
    var to by remember { mutableStateOf<LocalDate?>(null) }
    var choosingDays by remember { mutableStateOf(false) }
    // Only Notebook and Minimal here; a style saved before (Postcard, Scrapbook) opens as Notebook.
    val theme = PrintTheme.forShare(picked ?: settings.printTheme)
    val options = ExportOptions(theme, paper, photoSize, includePlan, from, to, ruled)

    // ------------------------------------------------------------ the preview

    var made by remember { mutableStateOf<MadePdf?>(null) }
    var making by remember { mutableStateOf<ExportProgress?>(null) }
    var previewProblem by remember { mutableStateOf<Notice?>(null) }
    var stoppedFor by remember { mutableStateOf<ExportOptions?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(options, trip != null, retries, stoppedFor) {
        if (trip == null || made?.options == options || stoppedFor == options) return@LaunchedEffect
        previewProblem = null
        making = ExportProgress("Making the pages")
        try {
            if (made != null) delay(PREVIEW_PAUSE_MS)
            val loaded = exports.load(tripId)
            val path = exports.pdf(loaded, options) { making = it }
            val old = made
            made = MadePdf(path, options, exports.title(loaded, options))
            if (old != null && !old.handedOut) exports.discard(old.path)
        } catch (e: TimeoutCancellationException) {
            previewProblem = problem(e, "Couldn't make the pages.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            previewProblem = problem(e, "Couldn't make the pages.")
        } finally {
            making = null
        }
    }
    // A preview no other app was given goes with the screen.
    val latestMade by rememberUpdatedState(made)
    DisposableEffect(Unit) {
        onDispose { latestMade?.takeIf { !it.handedOut }?.let { exports.discard(it.path) } }
    }
    val ready = made?.takeIf { it.options == options && making == null }
    var opened by remember { mutableStateOf<PdfPages?>(null) }
    var openedFor by remember { mutableStateOf<String?>(null) }
    val readyPath = ready?.path
    LaunchedEffect(readyPath) {
        opened?.close()
        opened = null
        openedFor = null
        if (readyPath == null) return@LaunchedEffect
        opened = try {
            printer.open(files, readyPath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        openedFor = readyPath
    }
    val opening = readyPath != null && openedFor != readyPath
    val pages = opened.takeIf { !opening }
    // Between an option change and the new pages starting (or showing): already "making", so the top never blinks empty.
    val shownProgress = making ?: ExportProgress("Making the pages").takeIf {
        opening || (trip != null && ready == null && previewProblem == null && stoppedFor != options)
    }

    // ------------------------------------------------------------ the other ways out

    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var ways by remember { mutableStateOf<Ways?>(null) }
    val busy = progress != null
    val google = com.justdataplease.boringtravel.google.LocalGoogle.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var docsStarts by remember { mutableIntStateOf(0) }

    /** Google Docs needs Drive: connected here in one go, then the save goes on by itself (Android's rememberDriveConnect). */
    fun connectDrive() {
        val g = google
        if (g == null) {
            notice = Notice("Google Drive isn't connected.", "Google Docs saves the document in your Drive.")
            return
        }
        scope.launch {
            when (val step = g.connectDrive()) {
                com.justdataplease.boringtravel.google.DriveStep.Connected -> docsStarts++
                is com.justdataplease.boringtravel.google.DriveStep.Failed -> notice = Notice(step.message)
                com.justdataplease.boringtravel.google.DriveStep.Cancelled -> notice = Notice("Google Drive isn't connected.", "Google Docs saves the document in your Drive.")
            }
        }
    }

    /** Runs one action; failures become a [Notice] in plain words. */
    fun launchAction(failure: String, work: suspend () -> Unit) {
        if (job?.isActive == true) return
        notice = null
        job = scope.launch {
            progress = ExportProgress("Getting ready")
            try {
                work()
            } catch (e: com.justdataplease.boringtravel.google.DriveNotConnectedException) {
                notice = Notice("Google Drive isn't connected.", null, if (google?.signInConfigured == true) listOf("Connect" to { connectDrive() }) else emptyList())
            } catch (e: TimeoutCancellationException) {
                notice = problem(e, failure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice = problem(e, failure)
            } finally {
                progress = null
            }
        }
    }

    /** The Files app's save sheet with the file at [path] (Android: its "create document" picker). */
    fun saveToPhone(path: String) = phone.exportFile(path) { done -> if (done) notice = Notice("Saved.") }

    /** The share sheet with the file at [path], [title] its subject (Android's Sharing.share). */
    fun shareFile(path: String, mime: String, title: String) {
        val bytes = files.read(path) ?: return
        phone.shareFile(path.substringAfterLast('/'), mime, bytes, title)
    }

    fun printPdf() {
        val r = made?.takeIf { it.options == options && making == null } ?: return
        r.handedOut = true
        if (!printer.print(files, r.path, r.title)) notice = Notice("Couldn't open the print dialog.")
    }

    /** The PDF is the one on screen: what to do with it. */
    fun pdfWays(r: MadePdf): Ways = Ways(
        "PDF", r.title,
        listOf(
            Way("Save to phone", BtIcons.Download) { r.handedOut = true; saveToPhone(r.path) },
            Way("Print", BtIcons.Print, ::printPdf),
            Way("Share", BtIcons.Share) { r.handedOut = true; shareFile(r.path, "application/pdf", r.title) },
        ),
    )

    fun makeText() {
        launchAction("Couldn't make the text.") {
            val loaded = exports.load(tripId)
            val title = exports.title(loaded, options)
            val text = exports.text(loaded, options)
            val path = exports.textFile(loaded, options, text)
            val short = text.length <= MAX_TEXT_CHARS
            ways = Ways(
                "Text", title,
                buildList {
                    add(Way("Save to phone", BtIcons.Download) { saveToPhone(path) })
                    if (short) add(Way("Copy", BtIcons.Duplicate) { copy(text); notice = Notice("Copied.") })
                    add(Way("Share", BtIcons.Share) { if (short) phone.share(title, text) else shareFile(path, "text/plain", title) })
                },
            )
        }
    }

    fun makeWord() {
        launchAction("Couldn't make the Word file.") {
            val loaded = exports.load(tripId)
            val path = exports.docx(loaded, options) { progress = it }
            val title = exports.title(loaded, options)
            ways = Ways(
                "Word file", title,
                listOf(
                    Way("Save to phone", BtIcons.Download) { saveToPhone(path) },
                    Way("Share", BtIcons.Share) { shareFile(path, TripDocx.MIME, title) },
                ),
            )
        }
    }

    fun shareDocLink(doc: com.justdataplease.boringtravel.google.DriveFile, title: String, saved: Notice) = launchAction("Couldn't make the link.") {
        phone.share(title, TripExports.shareLink(google?.drive, doc))
        notice = saved.copy(body = "Anyone with the link can open it. It stays in your Google Drive.")
    }

    /** The trip as a Google Doc in Drive › BoringTravel › Exports, then Open and Share link (Android's saveDocs). */
    fun saveDocs() {
        launchAction("Couldn't save to Google Docs.") {
            val loaded = exports.load(tripId)
            val doc = exports.saveToDocs(loaded, options, google?.drive) { progress = it }
            val url = google?.drive?.openUrl(doc) ?: "https://drive.google.com/open?id=${doc.id}"
            val title = exports.title(loaded, options)
            val saved = Notice("Saved to Google Docs.", "In Drive › BoringTravel › Exports.")
            notice = saved
            ways = Ways(
                "Saved to Google Docs", "In Drive › BoringTravel › Exports.",
                listOf(
                    Way("Open", BtIcons.Document) { if (runCatching { uri.openUri(url) }.isFailure) notice = saved.copy(body = "No app on this phone opens the link.") },
                    Way("Share link", BtIcons.Link) { shareDocLink(doc, title, saved) },
                ),
            )
        }
    }

    /** Google Docs saves into the traveller's Drive: connected first when it isn't, then saved (Android's startDocs). */
    fun startDocs() {
        if (job?.isActive == true) return
        scope.launch {
            if (google?.drive?.isConnected() == true) saveDocs() else connectDrive()
        }
    }
    LaunchedEffect(docsStarts) { if (docsStarts > 0) saveDocs() }

    LaunchedEffect(startWith) {
        when (startWith) {
            ShareStart.Days -> choosingDays = true
            ShareStart.Text -> makeText()
            ShareStart.Word -> makeWord()
            ShareStart.Docs -> startDocs()
            else -> Unit
        }
    }
    LaunchedEffect(startWith, ready, pages) {
        if (startWith == ShareStart.Pdf && ready != null && pages != null) ways = pdfWays(ready)
        if (startWith == ShareStart.Page && pages != null) viewing = 0
    }

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Share", onBack = onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                BookLine(enabled = trip != null, onClick = onMakeBook)
                Preview(
                    pages = pages,
                    making = shownProgress,
                    problem = previewProblem,
                    stopped = stoppedFor == options,
                    unreadable = ready != null && !opening && pages == null,
                    onOpen = { viewing = it },
                    onCancel = { stoppedFor = options },
                    onRetry = { stoppedFor = null; retries++ },
                )

                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Style", Modifier.padding(top = 10.dp), style = Bt.text.sub)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrintTheme.SHARE.forEach { t ->
                            BtChip(t.label, t == theme, onClick = {
                                picked = t
                                settings.printTheme = t
                            })
                        }
                    }
                    BtDivider()
                    val start = trip?.startDate
                    val end = trip?.endDate
                    val canChoose = start != null && end != null && end.toEpochDay() > start.toEpochDay()
                    OptionRow("Pages", onClick = if (canChoose) ({ choosingDays = true }) else null) {
                        Text(pagesText(trip?.days, start, from, to), style = Bt.text.item)
                        if (canChoose) Icon(BtIcons.Forward, null, tint = Bt.colors.muted)
                    }
                    OptionRow("Photos") {
                        PhotoSize.entries.forEach { s -> BtChip(s.label, s == photoSize, onClick = { pickedSize = s; keep(PrintChoices(photoSize = s.id)) }) }
                    }
                    OptionRow("Paper") {
                        Paper.entries.forEach { p -> BtChip(p.label, p == paper, onClick = { pickedPaper = p; keep(PrintChoices(paper = p.id)) }) }
                    }
                    if (theme == PrintTheme.Notebook) {
                        OptionRow("Notebook paper") {
                            BtChip("Dotted", !ruled, onClick = { pickedRuled = false; keep(PrintChoices(ruled = false)) })
                            BtChip("Ruled", ruled, onClick = { pickedRuled = true; keep(PrintChoices(ruled = true)) })
                        }
                    }
                    val setPlan = { on: Boolean -> pickedPlan = on; keep(PrintChoices(plan = on)) }
                    OptionRow("Include the plan", onClick = { setPlan(!includePlan) }) {
                        val c = Bt.colors
                        Switch(
                            checked = includePlan,
                            onCheckedChange = setPlan,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.onPrimary, checkedTrackColor = c.primary, checkedBorderColor = c.primary,
                                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.chip, uncheckedBorderColor = c.line,
                            ),
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val idle = !busy && trip != null
                    // Only what to make, all outlined; what to do with it is asked next ([WaysSheet]).
                    Eyebrow("Format")
                    ButtonPair(
                        { m -> BtGhostButton("PDF", onClick = { ready?.let { ways = pdfWays(it) } }, modifier = m, icon = BtIcons.Document, enabled = idle && ready != null) },
                        { m -> BtGhostButton("Text", onClick = ::makeText, modifier = m, icon = BtIcons.Text, enabled = idle) },
                    )
                    ButtonPair(
                        { m -> BtGhostButton("Google Docs", onClick = ::startDocs, modifier = m, icon = BtIcons.Cloud, enabled = idle) },
                        { m -> BtGhostButton("Word", onClick = ::makeWord, modifier = m, icon = BtIcons.Document, enabled = idle) },
                    )
                    progress?.let { p -> ProgressCard(p, onCancel = { job?.cancel() }) }
                    notice?.let { n -> NoticeCard(n, onClose = { notice = null }) }
                    if (trip != null && progress == null && notice == null) {
                        Text("Nothing leaves the phone until you save, print or share it.", style = Bt.text.fine)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    val s = trip?.startDate
    val e = trip?.endDate
    if (choosingDays && s != null && e != null) {
        DayRangeSheet(
            start = s, end = e, from = from, to = to,
            onDone = { a, b -> from = a; to = b; choosingDays = false },
            onDismiss = { choosingDays = false },
        )
    }
    val open = viewing
    if (open != null && pages != null) PageViewer(pages, open, onClose = { viewing = null })
    ways?.let { w -> WaysSheet(w, onClose = { ways = null }) }
}

/** "Want to customize more? Make a book" on top: the book lays the pages out by hand. */
@Composable
private fun BookLine(enabled: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(BtIcons.Journal, null, Modifier.size(18.dp), tint = c.muted)
        Text("Want to customize more?", Modifier.weight(1f, fill = false), style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Make a book", style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline), color = c.accentInk, maxLines = 1)
    }
}

/** After a format is picked: what was made, and what to do with it as outlined tiles, each an icon over its name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaysSheet(w: Ways, onClose: () -> Unit) {
    val c = Bt.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.surface,
        contentColor = c.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.line) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(w.title, style = Bt.text.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (w.body != null) Text(w.body, Modifier.padding(top = 2.dp), style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                w.items.forEach { way ->
                    val shape = btControlShape()
                    Column(
                        Modifier.weight(1f).heightIn(min = 92.dp).clip(shape).btBorder(Bt.tokens.skin.ghostBorder, shape)
                            .clickable(role = Role.Button) { onClose(); way.onClick() }
                            .padding(horizontal = 8.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Icon(way.icon, null, Modifier.size(26.dp), tint = c.ink)
                        Text(way.label, style = Bt.text.button, color = c.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The top of the screen: the pages, or how far along they are, or what went wrong. Always the same height. */
@Composable
private fun Preview(
    pages: PdfPages?,
    making: ExportProgress?,
    problem: Notice?,
    stopped: Boolean,
    unreadable: Boolean,
    onOpen: (Int) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val height = 210.dp
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Eyebrow(
            if (pages != null && making == null) "Preview · ${pages.count} ${if (pages.count == 1) "page" else "pages"}" else "Preview",
            Modifier.padding(start = 20.dp, bottom = 2.dp),
        )
        Box(Modifier.fillMaxWidth().heightIn(min = height + 30.dp), contentAlignment = Alignment.Center) {
            when {
                making != null -> Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                    ProgressCard(making, onCancel)
                }
                problem != null -> Message(problem.title, problem.body, "Try again", onRetry)
                stopped -> Message("Stopped.", null, "Make the pages", onRetry)
                unreadable -> Message("Couldn't show the pages here.", "Share, save and print still work.")
                pages != null && pages.count > 0 -> Column {
                    PageStrip(pages, height, onOpen)
                    Text("Tap a page to see it bigger.", Modifier.padding(start = 20.dp, top = 2.dp), style = Bt.text.fine, color = Bt.colors.muted)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String?, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        BtCard(Modifier.fillMaxWidth()) {
            Text(title, style = Bt.text.item)
            if (body != null) Text(body, style = Bt.text.sub)
            if (action != null && onAction != null) BtButton(action, onClick = onAction, modifier = Modifier.padding(top = 10.dp), small = true)
        }
    }
}

/** Two buttons side by side, equal widths. */
@Composable
private fun ButtonPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

/** An option: its name on the left, the choices on the right (scrolling sideways when they don't fit). */
@Composable
private fun OptionRow(label: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.padding(end = 12.dp), style = Bt.text.sub)
            // Right-aligned when the choices fit, scrolling when they don't.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
        BtDivider()
    }
}

@Composable
private fun ProgressCard(p: ExportProgress, onCancel: () -> Unit) {
    val c = Bt.colors
    BtCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.text, Modifier.weight(1f), style = Bt.text.item)
            Text("Cancel", Modifier.clickable(role = Role.Button, onClick = onCancel).padding(8.dp), style = Bt.text.label.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk)
        }
        Spacer(Modifier.height(8.dp))
        val f = p.fraction
        if (f != null) {
            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = c.route, trackColor = c.line)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.route, trackColor = c.line)
        }
    }
}

@Composable
private fun NoticeCard(n: Notice, onClose: () -> Unit) {
    BtCard(Modifier.fillMaxWidth(), onClick = onClose) {
        Text(n.title, style = Bt.text.item)
        if (n.body != null) Text(n.body, style = Bt.text.sub)
        if (n.actions.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                n.actions.forEachIndexed { i, (label, act) ->
                    if (i == 0) BtButton(label, onClick = act, small = true) else BtGhostButton(label, onClick = act)
                }
            }
        }
    }
}

/** A failure in plain words. */
private fun problem(e: Throwable, failure: String): Notice = when (e) {
    is TimeoutCancellationException -> Notice("The pages took too long to load.", "Try fewer days or smaller photos.")
    is ExportException -> Notice(e.message ?: failure)
    else -> Notice(failure, "Try again. If it keeps happening, choose fewer days.")
}

/** "Whole trip · 10 days", "Days 2–5 · 4 days", "Day 4 · Sat 19 Jun". */
private fun pagesText(tripDays: Int?, start: LocalDate?, from: LocalDate?, to: LocalDate?): String {
    if (start == null || (from == null && to == null)) {
        return if (tripDays != null) "Whole trip · $tripDays ${if (tripDays == 1) "day" else "days"}" else "Whole trip"
    }
    val a = from ?: start
    val b = to ?: a
    val na = a.toEpochDay() - start.toEpochDay() + 1
    val nb = b.toEpochDay() - start.toEpochDay() + 1
    return if (a == b) "Day $na · ${DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(a)}"
    else "Days $na–$nb · ${nb - na + 1} days"
}

/** Pages: the whole trip, or a first and last day picked on a slider. Null [from]/[to] = whole trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayRangeSheet(
    start: LocalDate,
    end: LocalDate,
    from: LocalDate?,
    to: LocalDate?,
    onDone: (from: LocalDate?, to: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Bt.colors
    val days = (end.toEpochDay() - start.toEpochDay() + 1).toInt().coerceAtLeast(1)
    var whole by remember { mutableStateOf(from == null && to == null) }
    var range by remember {
        val a = from?.let { (it.toEpochDay() - start.toEpochDay()).toFloat() } ?: 0f
        val b = to?.let { (it.toEpochDay() - start.toEpochDay()).toFloat() } ?: (days - 1).toFloat()
        mutableStateOf(a.coerceIn(0f, (days - 1).toFloat())..b.coerceIn(0f, (days - 1).toFloat()))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pages", style = Bt.text.title)
            BtRadioRow("Whole trip", if (days == 1) "1 day" else "All $days days", whole, onClick = { whole = true })
            if (days > 1) BtRadioRow("Choose days", "Pick the first and the last day.", !whole, onClick = { whole = false })
            if (!whole && days > 1) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it.start.roundToInt().toFloat()..it.endInclusive.roundToInt().toFloat() },
                    valueRange = 0f..(days - 1).toFloat(),
                    steps = (days - 2).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = c.primary,
                        activeTrackColor = c.primary,
                        inactiveTrackColor = c.line,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                val a = start.plusDays(range.start.roundToInt().toLong())
                val b = start.plusDays(range.endInclusive.roundToInt().toLong())
                Text(dayLabel(start, a) + if (a != b) "  –  " + dayLabel(start, b) else "", style = Bt.text.mono)
            }
            BtButton(
                "Done",
                onClick = {
                    if (whole || days == 1) onDone(null, null)
                    else onDone(start.plusDays(range.start.roundToInt().toLong()), start.plusDays(range.endInclusive.roundToInt().toLong()))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

private fun dayLabel(start: LocalDate, d: LocalDate) =
    "Day ${d.toEpochDay() - start.toEpochDay() + 1} · ${DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(d)}"

/** The pages side by side, small, drawn as they scroll in; a tap opens one big. */
@Composable
private fun PageStrip(pages: PdfPages, height: Dp, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val thumbW = height * pages.aspect
    val px = with(LocalDensity.current) { thumbW.roundToPx() }
    val thumbs = remember(pages) { HashMap<Int, ImageBitmap>() }
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(pages.count, key = { it }) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bitmap by produceState(thumbs[i], pages, i) { value = thumbs[i] ?: pages.render(i, px)?.also { thumbs[i] = it } }
                PagePaper(
                    bitmap, "Page ${i + 1} of ${pages.count}",
                    Modifier.height(height).aspectRatio(pages.aspect).clickable(role = Role.Button, onClickLabel = "Open page ${i + 1}") { onOpen(i) },
                )
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

/** The pages full screen on a dark ground, swiped through from [start]; "3 / 12" at the bottom, × closes. */
@Composable
private fun PageViewer(pages: PdfPages, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (pages.count - 1).coerceAtLeast(0))) { pages.count }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF26282B))) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = 44.dp)) {
                val px = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(PdfPages.MAX_PX)
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, key = { it }) { i ->
                    val bitmap by produceState<ImageBitmap?>(null, pages, i, px) { value = pages.render(i, px) }
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

package com.justdataplease.boringtravel.ui.export

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PdfFileWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.settings.PrintChoices
import com.justdataplease.boringtravel.export.ExportException
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.ExportProgress
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.PhotoSize
import com.justdataplease.boringtravel.export.Sharing
import com.justdataplease.boringtravel.export.TripDocx
import com.justdataplease.boringtravel.google.DriveFile
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.DriveStep
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.components.btBorder
import com.justdataplease.boringtravel.ui.components.btControlShape
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.onboarding.rememberDriveConnect
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A message under the buttons: a result, a problem in plain words, and what can be done next. */
private data class Notice(val title: String, val body: String? = null, val actions: List<Pair<String, () -> Unit>> = emptyList())

/** One thing to do with what was just made (Save to phone, Print, Share…), as a tile with its icon. */
private data class Way(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** What was made ([title], a line about it) and what can be done with it: the sheet that opens after a format is picked. */
private data class Ways(val title: String, val body: String?, val items: List<Way>)

/** The PDF made for [options]: previewed, then shared, saved or printed. [handedOut] once another app has it. */
private class MadePdf(val file: File, val options: ExportOptions, val title: String) {
    var handedOut = false
}

/** How long the options must stay put before the pages are made again. */
private const val PREVIEW_PAUSE_MS = 700L

/**
 * Share (was Print; owner, 2026-11-12). On top one line to the book ("want to customize more? create a book"), then
 * the real pages: the PDF for the options on screen is made in the background and shown page by page, made again when
 * an option changes. Under it the style (Notebook or Minimal), the days, photo size, paper and the plan; the choices
 * are remembered. Last, only what to make, as plain outlined buttons: PDF, Text, Google Docs, Word (owner,
 * 2026-11-14: "keep it simple black outline … at the bottom only keep the type"). Picking one opens a sheet with what
 * to do with it, as icon tiles: Save to phone, Print, Share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ExportScreen(graph: AppGraph, actions: AppActions, tripId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = graph.export
    val trip by remember(tripId) { graph.trips.observeTrip(tripId) }.collectAsState(initial = null)
    val savedTheme by graph.settings.printTheme.collectAsState(initial = null)

    // The last choices made here are kept (and travel in backups); null until they are read.
    val savedChoices by graph.settings.printChoices.collectAsState(initial = null)
    var picked by rememberSaveable { mutableStateOf<PrintTheme?>(null) }
    var pickedSize by rememberSaveable { mutableStateOf<PhotoSize?>(null) }
    var pickedPaper by rememberSaveable { mutableStateOf<Paper?>(null) }
    var pickedPlan by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pickedRuled by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Medium by default: more photos to a page (owner, 2026-11-12: "more compact auto layout of the images").
    val photoSize = pickedSize ?: PhotoSize.entries.firstOrNull { it.id == savedChoices?.photoSize } ?: PhotoSize.Medium
    val paper = pickedPaper ?: Paper.entries.firstOrNull { it.id == savedChoices?.paper } ?: Paper.default()
    val includePlan = pickedPlan ?: savedChoices?.plan ?: false
    val ruled = pickedRuled ?: savedChoices?.ruled ?: false
    fun keep(change: (PrintChoices) -> PrintChoices) {
        scope.launch { graph.settings.setPrintChoices(change(PrintChoices())) }
    }
    var from by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var to by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var choosingDays by remember { mutableStateOf(false) }
    // The saved style and choices are read first, so the first pages aren't made with others. Only Notebook and
    // Minimal here (owner, 2026-11-14); a style saved before opens as Notebook.
    val theme = (picked ?: savedTheme)?.let(PrintTheme::forShare)
    val options = theme?.takeIf { savedChoices != null }?.let { ExportOptions(it, paper, photoSize, includePlan, from, to, ruled) }

    // ------------------------------------------------------------ the preview

    var made by remember { mutableStateOf<MadePdf?>(null) }
    var making by remember { mutableStateOf<ExportProgress?>(null) }
    var previewProblem by remember { mutableStateOf<Notice?>(null) }
    var stoppedFor by remember { mutableStateOf<ExportOptions?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    // Some phones can't write a PDF file from the WebView: then there's no preview and Print opens the system's own.
    var canWritePdf by remember { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(options, trip != null, retries, stoppedFor, canWritePdf) {
        val o = options ?: return@LaunchedEffect
        if (trip == null || !canWritePdf || made?.options == o || stoppedFor == o) return@LaunchedEffect
        previewProblem = null
        making = ExportProgress("Making the pages")
        try {
            if (made != null) delay(PREVIEW_PAUSE_MS)
            val book = exporter.load(tripId)
            val file = exporter.pdf(context, book, o) { making = it }
            val old = made
            made = MadePdf(file, o, exporter.title(book, o))
            if (old != null && !old.handedOut) exporter.discard(old.file)
        } catch (e: TimeoutCancellationException) {
            previewProblem = problem(e, "Couldn't make the pages.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            canWritePdf = false
        } catch (e: Throwable) {
            previewProblem = problem(e, "Couldn't make the pages.")
        } finally {
            making = null
        }
    }
    // A preview no other app was given goes with the screen.
    val latestMade by rememberUpdatedState(made)
    DisposableEffect(Unit) {
        onDispose { latestMade?.takeIf { !it.handedOut }?.let { m -> runCatching { m.file.delete(); m.file.parentFile?.delete() } } }
    }
    val ready = made?.takeIf { it.options == options && making == null }
    // The finished PDF is opened off the main thread (reading it grows with its size) and closed there too (a close
    // waits for a page still being drawn). [openedFor] is the file [opened] belongs to, also when it couldn't be read.
    var opened by remember { mutableStateOf<PdfPages?>(null) }
    var openedFor by remember { mutableStateOf<File?>(null) }
    val readyFile = ready?.file
    LaunchedEffect(readyFile) {
        val old = opened
        opened = null
        openedFor = null
        if (old != null) graph.scope.launch(Dispatchers.IO) { old.close() }
        if (readyFile == null) return@LaunchedEffect
        val opening = graph.scope.async(Dispatchers.IO) { PdfPages.open(readyFile) }
        try {
            opened = opening.await()
            openedFor = readyFile
        } catch (e: CancellationException) {
            // Left or changed while it opened: what was opened is closed again.
            opening.invokeOnCompletion { if (it == null) runCatching { opening.getCompleted()?.close() } }
            throw e
        }
    }
    val latestOpened by rememberUpdatedState(opened)
    DisposableEffect(Unit) { onDispose { latestOpened?.let { p -> graph.scope.launch(Dispatchers.IO) { p.close() } } } }
    val opening = readyFile != null && openedFor != readyFile
    val pages = opened.takeIf { !opening }
    // Between an option change and the new pages starting (or showing): already "making", so the top never blinks empty.
    val shownProgress = making ?: ExportProgress("Making the pages").takeIf {
        opening || (options != null && trip != null && ready == null && canWritePdf && previewProblem == null && stoppedFor != options)
    }

    // ------------------------------------------------------------ the other ways out

    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var docx by remember { mutableStateOf<File?>(null) }
    var textFile by remember { mutableStateOf<File?>(null) }
    // The sheet after a format is picked: what to do with what was made.
    var ways by remember { mutableStateOf<Ways?>(null) }
    var docsAfterConnect by remember { mutableStateOf(false) }
    var docsStarts by remember { mutableIntStateOf(0) }
    val busy = progress != null

    // Google Docs needs Drive: connected here in one go, then the save goes on by itself.
    val connectDrive = rememberDriveConnect(graph) { step ->
        when (step) {
            DriveStep.Connected -> if (docsAfterConnect) { docsAfterConnect = false; docsStarts++ }
            is DriveStep.Failed -> { docsAfterConnect = false; notice = Notice(step.message) }
            DriveStep.Cancelled -> { docsAfterConnect = false; notice = Notice("Google Drive isn't connected.", "Google Docs saves the document in your Drive.") }
            is DriveStep.NeedsConsent -> Unit
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
            } catch (e: DriveNotConnectedException) {
                notice = Notice("Google Drive isn't connected.", null, listOf("Connect" to { docsAfterConnect = true; connectDrive() }))
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

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = made?.file
        if (uri != null && f != null) launchAction("Couldn't save the PDF there.") { exporter.copyTo(f, uri); notice = Notice("Saved.") }
    }
    val saveDocx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TripDocx.MIME)) { uri ->
        val f = docx
        if (uri != null && f != null) launchAction("Couldn't save the Word file there.") { exporter.copyTo(f, uri); notice = Notice("Saved.") }
    }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val f = textFile
        if (uri != null && f != null) launchAction("Couldn't save the text there.") { exporter.copyTo(f, uri); notice = Notice("Saved.") }
    }

    fun shareDocLink(doc: DriveFile, title: String, saved: Notice) = launchAction("Couldn't make the link.") {
        Sharing.shareLink(context, exporter.shareLink(doc), title)
        notice = saved.copy(body = "Anyone with the link can open it. It stays in your Google Drive.")
    }

    fun saveDocs() {
        val o = options ?: return
        launchAction("Couldn't save to Google Docs.") {
            val book = exporter.load(tripId)
            val doc = exporter.saveToDocs(book, o) { progress = it }
            val url = exporter.openUrl(doc)
            val title = exporter.title(book, o)
            val saved = Notice("Saved to Google Docs.", "In Drive › BoringTravel › Exports.")
            notice = saved
            ways = Ways(
                "Saved to Google Docs", "In Drive › BoringTravel › Exports.",
                listOf(
                    Way("Open", BtIcons.Document) { if (!Sharing.openUrl(context, url)) notice = saved.copy(body = "No app on this phone opens the link.") },
                    Way("Share link", BtIcons.Link) { shareDocLink(doc, title, saved) },
                ),
            )
        }
    }
    fun startDocs() {
        if (job?.isActive != true) scope.launch {
            if (exporter.driveConnected()) saveDocs() else { docsAfterConnect = true; connectDrive() }
        }
    }

    fun printPdf() {
        val activity = context.findActivity() ?: return
        val o = options
        val r = made?.takeIf { it.options == o && making == null }
        if (r != null) {
            r.handedOut = true
            runCatching { exporter.printPdf(activity, r.file, r.title, r.options.paper) }
                .onFailure { notice = Notice("Couldn't open the print dialog.") }
        } else if (!canWritePdf && o != null) {
            launchAction("Couldn't open the print dialog.") {
                exporter.print(activity, exporter.load(tripId), o) { progress = it }
            }
        }
    }

    /** The PDF is the one on screen: what to do with it. A phone that can't write the file only prints. */
    fun pdfWays(r: MadePdf?): Ways = Ways(
        "PDF", r?.title,
        buildList {
            if (r != null) add(Way("Save to phone", BtIcons.Download) { savePdf.launch(r.file.name) })
            add(Way("Print", BtIcons.Print, ::printPdf))
            if (r != null) add(Way("Share", BtIcons.Share) { r.handedOut = true; Sharing.share(context, r.file, r.title) })
        },
    )

    fun makeText() {
        val o = options ?: return
        launchAction("Couldn't make the text.") {
            val book = exporter.load(tripId)
            val title = exporter.title(book, o)
            val text = exporter.text(book, o)
            val file = exporter.textFile(book, o, text)
            textFile = file
            val short = text.length <= Sharing.MAX_TEXT_CHARS
            ways = Ways(
                "Text", title,
                buildList {
                    add(Way("Save to phone", BtIcons.Download) { saveText.launch(file.name) })
                    if (short) add(Way("Copy", BtIcons.Duplicate) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
                        notice = Notice("Copied.")
                    })
                    add(Way("Share", BtIcons.Share) { if (short) Sharing.shareText(context, text, title) else Sharing.share(context, file, title, "text/plain") })
                },
            )
        }
    }

    fun makeWord() {
        val o = options ?: return
        launchAction("Couldn't make the Word file.") {
            val book = exporter.load(tripId)
            val file = exporter.docx(book, o) { progress = it }
            docx = file
            val title = exporter.title(book, o)
            ways = Ways(
                "Word file", title,
                listOf(
                    Way("Save to phone", BtIcons.Download) { saveDocx.launch(file.name) },
                    Way("Share", BtIcons.Share) { Sharing.share(context, file, title, TripDocx.MIME) },
                ),
            )
        }
    }
    LaunchedEffect(docsStarts) { if (docsStarts > 0) saveDocs() }

    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Share", onBack = actions::back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                BookLine(enabled = trip != null, onClick = { actions.book(tripId) })
                Preview(
                    pages = pages,
                    making = shownProgress,
                    problem = previewProblem,
                    stopped = options != null && stoppedFor == options,
                    canWritePdf = canWritePdf,
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
                                scope.launch { graph.settings.setPrintTheme(t) }
                            })
                        }
                    }
                    BtDivider()
                    val t = trip
                    val start = t?.startDate
                    val end = t?.endDate
                    val canChoose = start != null && end != null && !end.isBefore(start) && start != end
                    OptionRow("Pages", onClick = if (canChoose) ({ choosingDays = true }) else null) {
                        Text(pagesText(t?.days, start, from, to), style = Bt.text.item)
                        if (canChoose) Icon(BtIcons.Forward, null, tint = Bt.colors.muted)
                    }
                    OptionRow("Photos") {
                        PhotoSize.entries.forEach { s -> BtChip(s.label, s == photoSize, onClick = { pickedSize = s; keep { it.copy(photoSize = s.id) } }) }
                    }
                    OptionRow("Paper") {
                        Paper.entries.forEach { p -> BtChip(p.label, p == paper, onClick = { pickedPaper = p; keep { it.copy(paper = p.id) } }) }
                    }
                    if (theme == PrintTheme.Notebook) {
                        OptionRow("Notebook paper") {
                            BtChip("Dotted", !ruled, onClick = { pickedRuled = false; keep { it.copy(ruled = false) } })
                            BtChip("Ruled", ruled, onClick = { pickedRuled = true; keep { it.copy(ruled = true) } })
                        }
                    }
                    val setPlan = { on: Boolean -> pickedPlan = on; keep { it.copy(plan = on) } }
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
                    val idle = !busy && trip != null && options != null
                    // Only what to make, all outlined; what to do with it is asked next ([WaysSheet]).
                    Eyebrow("Format")
                    ButtonPair(
                        { m -> BtGhostButton("PDF", onClick = { ways = pdfWays(ready) }, modifier = m, icon = BtIcons.Document, enabled = idle && (ready != null || !canWritePdf)) },
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

    val t = trip
    if (choosingDays && t?.startDate != null && t.endDate != null) {
        DayRangeSheet(
            start = t.startDate!!,
            end = t.endDate!!,
            from = from,
            to = to,
            onDone = { a, b -> from = a; to = b; choosingDays = false },
            onDismiss = { choosingDays = false },
        )
    }
    val open = viewing
    if (open != null && pages != null) PageViewer(pages, open, onClose = { viewing = null })
    ways?.let { w -> WaysSheet(w, onClose = { ways = null }) }
}

/** "Want to customize more? Make a book" on top (owner, 2026-11-14): the book lays the pages out by hand. */
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
    canWritePdf: Boolean,
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
                !canWritePdf -> Message("This phone can't show the pages here.", "Print opens them in its own preview.")
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
    is PdfFileWriter.PdfWriteException -> Notice(failure, "Try again, or choose fewer days or smaller photos.")
    is OutOfMemoryError -> Notice("Too many photos for one go.", "Choose fewer days or smaller photos.")
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.speech.DictationText
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtChip
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtHeader
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.SectionLabel
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont

/** The app's public pages (the Android app's ui/me/AboutApp.kt). */
object AboutApp {
    const val PRIVACY_URL = "https://justdataplease.com/apps/boringtravel/privacy.html"
}

/**
 * Me, as the Android app's Me screen (ui/me/MeScreen.kt) shows it, with the rows this phone can keep: "Your space",
 * the account (since round 23: Google, with Sign in with Apple beside it), the theme and the fonts; Voice notes, Import trips from a link (a Google Maps list here), AI, Trips look, Plan view,
 * Place photos and Demo mode; Backup & sync (Google Drive and backup files); then the
 * privacy policy and the version; since round 20 Your traveller & rides (the Android app's own row and sheet). Left
 * out until this phone has what they need: Community profile (the community) and Pro. [version] is the app's version; [onDemo] turns demo mode on (adding the examples) or off;
 * [onImportFromLink] opens Copy from a link; [onBackup] opens Backup & sync; [onAi] opens Me › AI; [onTracking] opens
 * Me › Route tracking (since round 10).
 */
@Composable
fun MeScreen(
    settings: AppSettings, version: String, onDemo: (Boolean) -> Unit = { settings.demoMode = it }, onImportFromLink: () -> Unit = {},
    voiceAtStart: Boolean = false,
    onBackup: () -> Unit = {},
    onAi: () -> Unit = {},
    onTracking: () -> Unit = {},
    deleteAtStart: Boolean = false,
    signOutAtStart: Boolean = false,
    /** Community profile (round 25): make or change the traveller's community profile. */
    onCommunityProfile: () -> Unit = {},
) {
    val uri = LocalUriHandler.current
    val toast = LocalToast.current
    // Me › AI's line follows a key saved or removed there.
    val ownAi = LocalPlannerKit.current?.ownAi?.state?.collectAsState()?.value
    val phoneLanguage = LocalSpeech.current.phoneLanguage()
    var showVoice by rememberSaveable { mutableStateOf(voiceAtStart) }
    BtScreen(statusScrim = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BtHeader("Your space", eyebrow = "BoringTravel")
            // The account (since round 23): Google, or Sign in with Apple beside it; Drive's state; Delete account.
            com.justdataplease.boringtravel.ui.components.Eyebrow("Account", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            AccountCard(settings, Modifier.padding(horizontal = 20.dp), deleteAtStart = deleteAtStart, signOutAtStart = signOutAtStart)
            ThemeSection(settings, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            BtCard(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), padding = PaddingValues(horizontal = 16.dp)) {
                // Community profile first, in a build with the community (Android's MeScreen).
                val community = com.justdataplease.boringtravel.community.LocalCommunity.current
                if (community?.available == true) {
                    val profile by community.profile.collectAsState()
                    MeRow("Community profile", profile?.let { "@${it.username}" } ?: "Username, name and picture", onCommunityProfile)
                }
                // The language dictation listens for.
                MeRow("Voice notes", settings.dictationLanguage?.let { DictationText.languageName(it, phoneLanguage) } ?: "Phone language") { showVoice = true }
                // The level for every trip ("Off" until one is picked: nothing is asked at the first start).
                MeRow("Route tracking", settings.trackingLevel.label, onTracking)
                // Backups: "Off" until Google Drive is connected, then the last backup ("22:00" today, else "5 Nov").
                MeRow(
                    "Backup & sync",
                    if (!settings.driveConnected) "Off" else settings.lastBackupAt?.let { com.justdataplease.boringtravel.backup.BackupText.short(it, com.justdataplease.boringtravel.time.nowMs()) } ?: "Google Drive",
                    onBackup,
                )
                // Copy from a link, here because it is used rarely.
                MeRow("Import trips from a link", "A blog, a Google Doc or a Google Maps list", onImportFromLink)
                // Where the AI planner's answers come from: the built-in planner or the traveller's own key.
                MeRow("AI", ownAi?.active?.let { "Your ${it.label} key · ${ownAi.modelOrDefault(it)}" } ?: "Built-in planner", onAi)
                // Who walks and how the vehicles look in every trip video (the Android app's own row and sheet, read in).
                com.justdataplease.boringtravel.ui.me.TravellerSetting(remember(settings) { TravellerGraph(settings) })
                // Me › Trips look: Tickets (the default) or Pictures.
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Trips look", style = Bt.text.item)
                    BtSegmented(
                        TripsLook.entries.map { it.label }, settings.tripsLook.ordinal,
                        onSelect = { i -> settings.tripsLook = TripsLook.entries[i] },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                BtDivider()
                // The same choice as the switch on a trip's Plan tab.
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Plan view", style = Bt.text.item)
                    BtSegmented(
                        PlanView.entries.map { it.label }, settings.planView.ordinal,
                        onSelect = { i -> settings.planView = PlanView.entries[i] },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                BtDivider()
                // Off: the app never asks Wikipedia (plan items with a place, Google Maps links).
                BtSwitchRow(
                    "Place photos", "Pictures from Wikipedia for places in your plans", settings.placePhotos,
                    onChange = { settings.placePhotos = it },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                BtDivider()
                // On adds the example trips when they are missing; off shows only yours.
                BtSwitchRow(
                    "Demo mode", "Shows the example trips instead of yours. Yours stay as they are.", settings.demoMode,
                    onChange = onDemo,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            BtCard(Modifier.padding(20.dp), padding = PaddingValues(horizontal = 16.dp)) {
                MeRow("Privacy policy", "What stays on this phone and what goes where") { openPrivacyPolicy(uri, toast) }
                // About is only the version and opens nothing (the Android app's, since 2026-11-14).
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Version", style = Bt.text.item, maxLines = 1)
                    Text(version, style = Bt.text.sub, maxLines = 1)
                }
            }
            Text(
                "Boring travel, when everything can be interesting.",
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), style = Bt.text.fine,
            )
        }
    }
    if (showVoice) VoiceSheet(settings, phoneLanguage, onDismiss = { showVoice = false })
}

/** Me › Voice notes (the Android app's ui/me/VoiceSheet.kt): the language dictation listens for, the phone's by default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(settings: AppSettings, phoneLanguage: String, onDismiss: () -> Unit) {
    val languages = listOf(
        null to "Phone language · ${DictationText.languageName(phoneLanguage, phoneLanguage)}",
        "en-US" to "English", "el-GR" to "Ελληνικά", "es-AR" to "Español",
        "fr-FR" to "Français", "de-DE" to "Deutsch",
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Voice notes", style = Bt.text.h2)
            Text("Choose the language you speak in notes and planner chat.", style = Bt.text.sub)
            languages.forEach { (tag, label) ->
                val chosen = settings.dictationLanguage == tag
                Text(
                    label,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (chosen) Bt.colors.chipOn else Bt.colors.chip)
                        .selectable(chosen, role = Role.RadioButton) {
                            settings.dictationLanguage = tag
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    style = Bt.text.label, color = if (chosen) Bt.colors.onChipOn else Bt.colors.ink,
                )
            }
            Text(
                "Uses your phone's speech service. Offline recognition depends on the language models installed; otherwise an internet connection may be needed.",
                style = Bt.text.fine,
            )
        }
    }
}

@Composable
private fun MeRow(title: String, value: String, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = Bt.text.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(BtIcons.Forward, null, Modifier.size(20.dp), tint = Bt.colors.muted)
        }
        BtDivider()
    }
}

/**
 * Me › Theme and Fonts (the Android app's ui/me/ThemePicker.kt): the five themes as small swatch cards, each drawn on
 * its own paper with its own text face, the picked one ringed in its route colour; then two font choices as chips:
 * titles and dates ("Theme's own" first) and text (the phone's own first). A choice is kept at once ([AppSettings]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSection(settings: AppSettings, modifier: Modifier = Modifier) {
    val current = settings.look
    val fonts = settings.fontSet
    val text = settings.textFont
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Theme")
        Look.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { look ->
                    ThemeCard(look, look == current, Modifier.weight(1f)) { settings.look = look }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SectionLabel("Titles and dates", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            FontSet.entries.forEach { set ->
                BtChip(set.label, set == fonts, onClick = { settings.fontSet = set })
            }
        }
        SectionLabel("Text", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            TextFont.entries.forEach { font ->
                BtChip(textFontLabel(font), font == text, onClick = { settings.textFont = font })
            }
        }
    }
}

/**
 * A text font's name: the Android app's, "Phone (Roboto)" too, since the phone's font here is the bundled Roboto
 * (ui/theme/FontFiles.kt BtRoboto), the same face as on Android.
 */
fun textFontLabel(font: TextFont): String = font.label

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

/**
 * Opens the privacy policy in the browser; a phone that can't says its address with Android's long toast (the Android
 * app's openPrivacyPolicy).
 */
fun openPrivacyPolicy(uri: androidx.compose.ui.platform.UriHandler, toast: (String, Boolean) -> Unit) {
    if (runCatching { uri.openUri(AboutApp.PRIVACY_URL) }.isFailure) toast(AboutApp.PRIVACY_URL, true)
}

/** A quiet "Privacy policy" link (Welcome; the Android app's PrivacyLink). */
@Composable
fun PrivacyLink(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val toast = LocalToast.current
    Text(
        "Privacy policy",
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { openPrivacyPolicy(uri, toast) }.padding(vertical = 14.dp),
        style = Bt.text.sub,
        color = Bt.colors.accentInk,
    )
}

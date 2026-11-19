package com.justdataplease.boringtravel.ui.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.billing.ProBilling
import com.justdataplease.boringtravel.data.settings.PlanView
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.speech.DictationText
import com.justdataplease.boringtravel.ui.components.BtDivider
import com.justdataplease.boringtravel.ui.components.BtCard
import com.justdataplease.boringtravel.ui.components.BtHeader
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtSegmented
import com.justdataplease.boringtravel.ui.components.BtSwitchRow
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch
import java.util.Locale

/** Me: account, theme and fonts, tracking, backup & sync, AI, your traveller, trips look, plan view, place photos, demo mode, Pro (when for sale), the privacy policy and the version. */
@Composable
fun MeScreen(graph: AppGraph, actions: AppActions) {
    val level by graph.settings.trackingLevel.collectAsStateWithLifecycle(initialValue = TrackingLevel.Off)
    val driveConnected by graph.settings.driveConnected.collectAsStateWithLifecycle(initialValue = false)
    val lastBackupAt by graph.settings.lastBackupAt.collectAsStateWithLifecycle(initialValue = null)
    val voiceLanguage by graph.settings.dictationLanguage.collectAsStateWithLifecycle(initialValue = null)
    val displayLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    var showVoice by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val demoMode by graph.settings.demoMode.collectAsStateWithLifecycle(initialValue = false)
    val placePhotos by graph.settings.placePhotos.collectAsStateWithLifecycle(initialValue = true)
    val planView by graph.settings.planView.collectAsStateWithLifecycle(initialValue = PlanView.List)
    val ownAi by graph.ownAi.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val backupValue = if (!driveConnected) "Off" else lastBackupAt?.let { BackupText.short(it, System.currentTimeMillis()) } ?: "Google Drive"
    BtScreen(statusScrim = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BtHeader("Your space", eyebrow = "BoringTravel")
            Eyebrow("Account", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            AccountCard(graph, Modifier.padding(horizontal = 20.dp))
            ThemeSection(graph, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            BtCard(Modifier.padding(20.dp), padding = PaddingValues(horizontal = 16.dp)) {
                // No "Your world" row here (owner, 2026-11-14: "remove my world from me settings"): the Map tab shows it under the globe.
                if (graph.community.available) {
                    val profile by graph.community.profile.collectAsStateWithLifecycle()
                    MeRow("Community profile", profile?.let { "@${it.username}" } ?: "Username, name and picture", actions::editCommunityProfile)
                }
                MeRow("Voice notes", voiceLanguage?.let { DictationText.languageName(it, displayLocale) } ?: "Phone language") { showVoice = true }
                MeRow("Route tracking", level.label, actions::trackingSettings)
                MeRow("Backup & sync", backupValue, actions::backupSettings)
                // Copy from a link, here because it is used rarely (owner, 15 Nov 2026: "from settings, it happens rarer").
                MeRow("Import trips from a link", "A blog, a Google Doc or a Google Maps list") { actions.copySite() }
                MeRow("AI", ownAi.active?.let { "Your ${it.label} key · ${ownAi.modelOrDefault(it)}" } ?: "Built-in planner", actions::aiSettings)
                TravellerSetting(graph)
                TripsLookSetting(graph)
                BtDivider()
                // The same choice as the switch on a trip's Plan tab.
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Plan view", style = Bt.text.item)
                    BtSegmented(
                        PlanView.entries.map { it.label }, planView.ordinal,
                        onSelect = { i -> scope.launch { graph.settings.setPlanView(PlanView.entries[i]) } },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                BtDivider()
                // Off: the app never asks Wikipedia (plan items with a place, Google Maps links).
                BtSwitchRow(
                    "Place photos", "Pictures from Wikipedia for places in your plans", placePhotos,
                    onChange = { on -> scope.launch { graph.settings.setPlacePhotos(on) } },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                BtDivider()
                // Always here, in every build: on adds the example trips when they are missing; off shows only yours.
                BtSwitchRow(
                    "Demo mode", "Shows the example trips instead of yours. Yours stay as they are.", demoMode,
                    onChange = { on -> scope.launch { graph.demo.set(on) } },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                // Pro is hidden until it goes on sale (ProBilling.PRO_FOR_SALE); the free launch has everything open.
                if (ProBilling.PRO_FOR_SALE) {
                    BtDivider()
                    MeRow("BoringTravel Pro", "AI planner, clips", actions::paywall)
                }
            }
            BtCard(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), padding = PaddingValues(horizontal = 16.dp)) {
                MeRow("Privacy policy", "What stays on this phone and what goes where") { openPrivacyPolicy(context) }
                // About is only the version and opens nothing (owner, 2026-11-14: "remove from about, keep only version,
                // don't open at all"). Map and photo credits stay where the maps and photos are.
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Version", style = Bt.text.item, maxLines = 1)
                    Text(BuildConfig.VERSION_NAME, style = Bt.text.sub, maxLines = 1)
                }
            }
            Text(
                "Boring travel, when everything can be interesting.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = Bt.text.fine,
            )
        }
    }
    if (showVoice) VoiceSheet(graph, voiceLanguage, onDismiss = { showVoice = false })
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

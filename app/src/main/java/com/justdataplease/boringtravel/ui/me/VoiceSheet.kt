package com.justdataplease.boringtravel.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.speech.DictationText
import com.justdataplease.boringtravel.ui.theme.Bt
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheet(graph: AppGraph, selected: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val phone = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    val languages = listOf(
        null to "Phone language · ${DictationText.languageName(phone.toLanguageTag(), phone)}",
        "en-US" to "English", "el-GR" to "Ελληνικά", "es-AR" to "Español",
        "fr-FR" to "Français", "de-DE" to "Deutsch",
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bt.colors.surface,
        contentColor = Bt.colors.ink,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice notes", style = Bt.text.h2)
            Text("Choose the language you speak in notes and planner chat.", style = Bt.text.sub)
            languages.forEach { (tag, label) ->
                val chosen = selected == tag
                Text(label, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (chosen) Bt.colors.chipOn else Bt.colors.chip)
                    .selectable(chosen, role = Role.RadioButton) {
                        scope.launch { graph.settings.setDictationLanguage(tag); onDismiss() }
                    }.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = Bt.text.label, color = if (chosen) Bt.colors.onChipOn else Bt.colors.ink)
            }
            Text("Uses your phone's speech service. Offline recognition depends on the language models installed; otherwise an internet connection may be needed.", style = Bt.text.fine)
        }
    }
}

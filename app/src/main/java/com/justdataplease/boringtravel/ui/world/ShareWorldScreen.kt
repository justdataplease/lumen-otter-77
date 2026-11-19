package com.justdataplease.boringtravel.ui.world

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.world.ShareCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share your world: the 9:16 card in the current look, then the share sheet or Pictures. */
@Composable
fun ShareWorldScreen(graph: AppGraph, actions: AppActions) {
    val context = LocalContext.current
    val world by graph.world.world.collectAsState()
    val tokens = Bt.tokens
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val card by produceState<Bitmap?>(null, world, tokens) {
        val w = world ?: return@produceState
        val geometry = graph.world.geometry()
        value = withContext(Dispatchers.Default) { ShareCard.render(context.applicationContext, tokens, w, geometry) }
    }
    BtScreen {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            BtTopBar("Share", onBack = actions::back)
            Eyebrow("Share your world", Modifier.padding(start = 20.dp, bottom = 6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                val bmp = card
                val shape = RoundedCornerShape(14.dp)
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "Your world card: ${ShareCard.headline(world?.countries ?: 0)}",
                        modifier = Modifier.fillMaxHeight().aspectRatio(9f / 16f).shadow(12.dp, shape).clip(shape),
                    )
                }
            }
            note?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp), style = Bt.text.fine) }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtButton(
                    "Share",
                    icon = BtIcons.Share,
                    enabled = card != null,
                    onClick = {
                        val bmp = card ?: return@BtButton
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { ShareCard.writeForSharing(context, bmp) }
                            runCatching { context.startActivity(ShareCard.shareIntent(context, file)) }
                                .onFailure { note = "No app to share with." }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                BtGhostButton(
                    "Save image",
                    icon = BtIcons.Download,
                    enabled = card != null,
                    onClick = {
                        val bmp = card ?: return@BtGhostButton
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { ShareCard.saveToPictures(context, bmp) }
                            note = if (uri != null) "Saved to Pictures/BoringTravel." else "Couldn't save the image."
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

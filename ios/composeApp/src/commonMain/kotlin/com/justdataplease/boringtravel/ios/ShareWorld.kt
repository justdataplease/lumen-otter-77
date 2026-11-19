package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.Eyebrow
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.world.ShareCard

/** Where "Save image" puts the card on iPhone: the photo library (Android's says Pictures/BoringTravel). */
const val SAVED_TO_PHOTOS = "Saved to Photos."

/**
 * Share your world (the Android app's ui/world/ShareWorldScreen.kt): the 9:16 card in the current look, then the share
 * sheet or the photo library.
 */
@Composable
fun ShareWorldScreen(book: TripBook, today: LocalDate, revision: Int, demoMode: Boolean, onBack: () -> Unit) {
    val world = rememberWorld(book, revision, demoMode, today)
    val tokens = Bt.tokens
    val phone = LocalPhone.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val faces = ShareCard.Faces(Bt.text.h1, Bt.text.body, Bt.text.mono)
    var note by remember { mutableStateOf<String?>(null) }
    val card by produceState<ImageBitmap?>(null, world, tokens) {
        val geometry = CountryOutlines.geometry()
        value = ShareCard.render(tokens, world, geometry, measurer, faces)
    }
    BtScreen {
        Column(Modifier.fillMaxSize()) {
            BtTopBar("Share", onBack = onBack)
            Eyebrow("Share your world", Modifier.padding(start = 20.dp, bottom = 6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                val bmp = card
                val shape = RoundedCornerShape(14.dp)
                if (bmp != null) {
                    Image(
                        bmp,
                        contentDescription = "Your world card: ${ShareCard.headline(world.countries)}",
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
                        // No subject: Android's ShareCard.shareIntent gives none.
                        if (!phone.shareFile("boringtravel-world.png", "image/png", ShareCard.png(bmp), "")) note = "No app to share with."
                    },
                    modifier = Modifier.weight(1f),
                )
                BtGhostButton(
                    "Save image",
                    icon = BtIcons.Download,
                    enabled = card != null,
                    onClick = {
                        val bmp = card ?: return@BtGhostButton
                        phone.saveImage(ShareCard.png(bmp)) { ok -> note = if (ok) SAVED_TO_PHOTOS else "Couldn't save the image." }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

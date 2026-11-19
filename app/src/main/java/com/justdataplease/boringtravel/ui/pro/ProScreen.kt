package com.justdataplease.boringtravel.ui.pro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.billing.ProBilling
import com.justdataplease.boringtravel.ui.components.BrandMark
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.BtScreen
import com.justdataplease.boringtravel.ui.components.BtTopBar
import com.justdataplease.boringtravel.ui.components.H1
import com.justdataplease.boringtravel.ui.components.ProTag
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt

private enum class Plan { Yearly, Life }

/** BoringTravel Pro: what it adds, the two ways to buy (Play's prices only), restore and manage. */
@Composable
fun ProScreen(graph: AppGraph, actions: AppActions) {
    // The free launch has no Pro offer: a stray route here just goes back (AppActions.paywall never opens it).
    if (!ProBilling.PRO_FOR_SALE) {
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    val context = LocalContext.current
    val billing = graph.billing
    val state by billing.state.collectAsState()
    var plan by remember { mutableStateOf(Plan.Life) }
    var note by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { billing.connect(); onDispose { } }

    BtScreen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            BtTopBar("BoringTravel Pro", onBack = actions::back)
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark(size = 48.dp)
                    ProTag()
                }
                H1("More time for the trip")
                if (state.isPro) {
                    Text("Pro is on. Thank you.", style = Bt.text.body)
                } else {
                    Text("Get help turning ideas into a plan, and make longer travel films to share.", style = Bt.text.sub)
                }
                Feature(BtIcons.Sparkle, "AI planner", "Paste rough notes or chat, and get days, places and times you can edit.")
                Feature(BtIcons.Movie, "Trip clips without the mark", "No BoringTravel mark, and clips up to 60 s.")
                if (state.isPro) {
                    BtGhostButton("Plan a trip from notes", modifier = Modifier.fillMaxWidth(), icon = BtIcons.Sparkle, onClick = { actions.planner(null) })
                }

                if (!state.isPro) {
                    Offer("For life", state.lifePrice?.let { "$it once" }, plan == Plan.Life) { plan = Plan.Life }
                    Offer("Yearly", state.yearlyPrice?.let { "$it a year" }, plan == Plan.Yearly) { plan = Plan.Yearly }
                    BtButton(
                        "Get Pro", modifier = Modifier.fillMaxWidth(), enabled = state.status == ProBilling.Status.Ready,
                        onClick = {
                            val activity = context.findActivity() ?: return@BtButton
                            val started = if (plan == Plan.Life) billing.buyLife(activity) else billing.buyYearly(activity)
                            if (!started) note = "Google Play couldn't start the purchase. Try again in a moment."
                        },
                    )
                    if (state.status == ProBilling.Status.Unavailable) {
                        Text("Prices aren't available right now. Check that Google Play is signed in, then try again.", style = Bt.text.fine)
                    }
                    if (state.pending) Text("Your purchase is waiting for payment. Pro turns on when Google Play confirms it.", style = Bt.text.fine)
                }
                BtGhostButton("Restore purchases", modifier = Modifier.fillMaxWidth(), onClick = {
                    billing.restore { owned ->
                        note = when (owned) {
                            true -> "Pro restored."
                            false -> "No Pro purchase found on this Google account."
                            null -> "Google Play didn't answer. Try again in a moment."
                        }
                    }
                })
                if (state.yearlyOwned) {
                    BtGhostButton("Manage subscription", modifier = Modifier.fillMaxWidth(), onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProBilling.manageUrl(context.packageName))))
                    })
                }
                note?.let { Text(it, style = Bt.text.sub) }
                Text("Payments go through Google Play. The yearly plan renews until you cancel it in Google Play.", style = Bt.text.fine)
                if (BuildConfig.DEBUG) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pretend Pro (debug builds)", Modifier.weight(1f), style = Bt.text.sub)
                        Switch(state.pretend, onCheckedChange = billing::setPretend)
                    }
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Bt.colors.chip), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Bt.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(body, style = Bt.text.sub)
        }
    }
}

@Composable
private fun Offer(title: String, price: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Bt.colors
    val shape = RoundedCornerShape(Bt.tokens.radius)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.route else c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Bt.text.item)
            Text(price ?: "Price from Google Play", style = Bt.text.sub)
        }
        if (selected) Icon(BtIcons.Check, null, tint = c.route)
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

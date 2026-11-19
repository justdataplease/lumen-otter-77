package com.justdataplease.boringtravel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.justdataplease.boringtravel.ui.nav.AppNav
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.links.ShareRoute
import com.justdataplease.boringtravel.links.SharedLink
import com.justdataplease.boringtravel.links.SharedLinks
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.TextFont
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.widget.Deeplink
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A quick action from the widget or a launcher shortcut, waiting for navigation to handle it. */
    private val pendingAction = mutableStateOf<String?>(null)
    private val pendingImport = mutableStateOf<SharedItinerary?>(null)

    /** A link shared from another app (Google Maps, a browser) for Add to plan, or [ShareRoute.NoLink]. */
    private val pendingPlace = mutableStateOf<ShareRoute?>(null)

    /** A shared trip's link (opened from a chat or the browser, or shared in as text), for Join a trip. */
    private val pendingJoin = mutableStateOf<ShareLink?>(null)

    /**
     * Where a camera closed without a photo goes back to: the pending action came from outside the app (the home
     * screen), so it leaves again — [LEAVE_FINISH] when the tap started the app, [LEAVE_BACK] when the app was in the
     * background — or [LEAVE_STAY] when the app was already on screen.
     */
    private var leave = LEAVE_STAY

    /** Out of sight since the last onStop (onNewIntent comes before onResume, after onStart). */
    private var outOfSight = true

    override fun onResume() {
        super.onResume()
        outOfSight = false
    }

    override fun onStop() {
        super.onStop()
        outOfSight = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Deeplink.of(intent)?.let {
            leave = if (outOfSight) LEAVE_BACK else LEAVE_STAY
            pendingAction.value = it
        }
        receiveShare(intent)
        receiveJoin(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingAction.value = Deeplink.of(intent)
            leave = if (pendingAction.value != null) LEAVE_FINISH else LEAVE_STAY
        } else {
            leave = savedInstanceState.getInt(KEY_LEAVE, LEAVE_STAY)
        }
        if (savedInstanceState == null) {
            receiveShare(intent)
            receiveJoin(intent)
        } else {
            pendingJoin.value = savedInstanceState.getString(KEY_JOIN)?.let(ShareLink::find)
            val text = savedInstanceState.getString("itineraryText")
            val uri = savedInstanceState.getString("itineraryUri")
            val pictures = savedInstanceState.getStringArrayList("itineraryPictures").orEmpty()
            pendingImport.value = if (text != null || uri != null || pictures.isNotEmpty()) SharedItinerary(text, uri, pictures) else null
            val placeUrl = savedInstanceState.getString(KEY_PLACE_URL)
            pendingPlace.value = when {
                placeUrl != null -> ShareRoute.Place(SharedLink(placeUrl, savedInstanceState.getString(KEY_PLACE_NAME)))
                savedInstanceState.getBoolean(KEY_NO_LINK) -> ShareRoute.NoLink
                else -> null
            }
        }
        val graph = graph
        // Shared trips catch up with the other members whenever the app opens.
        if (savedInstanceState == null) graph.scope.launch { runCatching { graph.together.syncAll() } }
        setContent {
            // Read the look, fonts and onboarding state once before drawing, so the first frame is already right.
            val start by produceState<Triple<Look, Pair<FontSet, TextFont>, Boolean>?>(null) {
                value = combine(graph.settings.look, graph.settings.fontSet, graph.settings.textFont, graph.settings.onboardingDone) { l, f, t, d ->
                    Triple(l, f to t, d)
                }.first()
            }
            val look by produceState<Look?>(null) { graph.settings.look.collect { value = it } }
            val fonts by produceState<FontSet?>(null) { graph.settings.fontSet.collect { value = it } }
            val textFont by produceState<TextFont?>(null) { graph.settings.textFont.collect { value = it } }
            val current = look ?: start?.first
            val currentFonts = fonts ?: start?.second?.first
            val currentText = textFont ?: start?.second?.second
            if (current == null || currentFonts == null || currentText == null || start == null) {
                Box(Modifier.fillMaxSize().background(com.justdataplease.boringtravel.ui.theme.BtLooks.of(com.justdataplease.boringtravel.ui.theme.Look.Default).colors.bg))
            } else {
                BoringTravelTheme(current, currentFonts, currentText) {
                    SystemBars(current)
                    AppNav(
                        graph,
                        onboardingDone = start!!.third,
                        pendingAction = pendingAction.value,
                        actionFromOutside = leave != LEAVE_STAY,
                        onActionHandled = { pendingAction.value = null },
                        onLeave = {
                            when (leave) {
                                LEAVE_FINISH -> finish()
                                LEAVE_BACK -> moveTaskToBack(true)
                            }
                            leave = LEAVE_STAY
                        },
                        pendingImport = pendingImport.value,
                        onImportHandled = { pendingImport.value = null },
                        pendingPlace = pendingPlace.value,
                        onPlaceHandled = { pendingPlace.value = null },
                        pendingJoin = pendingJoin.value,
                        onJoinHandled = { pendingJoin.value = null },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingImport.value?.let { share ->
            outState.putString("itineraryText", share.text)
            outState.putString("itineraryUri", share.uri)
            if (share.pictures.isNotEmpty()) outState.putStringArrayList("itineraryPictures", ArrayList(share.pictures))
        }
        when (val place = pendingPlace.value) {
            is ShareRoute.Place -> {
                outState.putString(KEY_PLACE_URL, place.link.url)
                outState.putString(KEY_PLACE_NAME, place.link.name)
            }
            ShareRoute.NoLink -> outState.putBoolean(KEY_NO_LINK, true)
            else -> Unit
        }
        pendingJoin.value?.let { outState.putString(KEY_JOIN, it.url) }
        outState.putInt(KEY_LEAVE, leave)
        super.onSaveInstanceState(outState)
    }

    /**
     * A text shared into the app: a link (Google Maps, a browser) opens Add to plan; a file, a Google Docs link or a
     * longer text goes to the AI planner's import as before; a few words without a link say there is nothing to add.
     */
    private fun receiveShare(intent: Intent) {
        if (receivePictures(intent)) return
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        // One extra character retains an explicit too-large error instead of silently losing itinerary details.
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(ItineraryImport.MAX_CHARS + 1)?.toString()
        // A trip invite shared in from a chat joins the trip instead of going to the plan.
        ShareLink.find(text)?.let {
            pendingJoin.value = it
            return
        }
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.takeIf { it.scheme == "content" }
        when (val route = SharedLinks.route(text, subject, hasFile = uri != null)) {
            null -> Unit
            ShareRoute.Itinerary -> pendingImport.value = SharedItinerary(text, uri?.toString())
            else -> pendingPlace.value = route
        }
    }

    /**
     * Pictures shared into the app (Plan from picture: a screenshot of a list, a guide page, a ticket) go to the AI
     * planner, which reads the places and plans in them ([com.justdataplease.boringtravel.ai.PictureReading]).
     */
    private fun receivePictures(intent: Intent): Boolean {
        if (intent.type?.startsWith("image/") != true) return false
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return false
        }.filter { it.scheme == "content" }.take(com.justdataplease.boringtravel.ai.PictureReading.MAX_PICTURES)
        if (uris.isNotEmpty()) pendingImport.value = SharedItinerary(pictures = uris.map { it.toString() })
        return true
    }

    /** A join link opened with the app (https://justdataplease.com/apps/boringtravel/join#… or boringtravel://join#…). */
    private fun receiveJoin(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        ShareLink.find(intent.dataString)?.let { pendingJoin.value = it }
    }

    @Composable
    private fun SystemBars(look: Look) {
        val bg = Bt.colors.bg
        LaunchedEffect(look) {
            val style = if (look.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            window.decorView.setBackgroundColor(android.graphics.Color.argb((bg.alpha * 255).toInt(), (bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt()))
        }
    }

    private companion object {
        const val KEY_LEAVE = "leaveOnCancel"
        const val KEY_PLACE_URL = "sharedPlaceUrl"
        const val KEY_PLACE_NAME = "sharedPlaceName"
        const val KEY_NO_LINK = "sharedNoLink"
        const val KEY_JOIN = "pendingJoin"
        const val LEAVE_STAY = 0
        const val LEAVE_FINISH = 1
        const val LEAVE_BACK = 2
    }
}

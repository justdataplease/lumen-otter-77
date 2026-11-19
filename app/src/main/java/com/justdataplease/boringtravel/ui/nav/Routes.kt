package com.justdataplease.boringtravel.ui.nav

import kotlinx.serialization.Serializable

// Type-safe navigation routes. Top-level tabs first, then pushed screens. Outside a trip the bar is Trips · Calendar ·
// Map · Me; inside one (TripDetail) it is the trip's own Plan · Journal · Map · Media (see TripNav).

@Serializable data object Welcome
@Serializable data object TripsHome

/** All trips by date (ui/tripcal). */
@Serializable data object CalendarHome
@Serializable data object MapHome
@Serializable data object MeHome

/**
 * One trip, in trip mode. [tab] = plan | journal | map | media (see [TripTab]); null = the tab last used on this trip,
 * else Plan. [day] = the plan's day to show (epoch day, or "ideas").
 */
@Serializable data class TripDetail(val tripId: String, val tab: String? = null, val day: String? = null)

/** Entries written when no trip was on (a card at the end of Trips opens them). */
@Serializable data object LooseNotes

/** New trip when [tripId] is null; [start] (epoch day) is a new trip's first day, picked on the Calendar tab. */
@Serializable data class EditTrip(val tripId: String? = null, val start: String? = null)

/**
 * The editor: a new entry (optionally in a trip) or an existing one; [dictate] starts the microphone; [attachPhotos]
 * opens the photo picker; [fromCamera] is an entry just made from a camera shot (caret under the photo, place filled in);
 * [planItemId] a new entry about that plan item (its place and day filled in, see ItemNotes.newEntry).
 */
@Serializable data class Write(
    val entryId: String? = null,
    val tripId: String? = null,
    val dictate: Boolean = false,
    val attachPhotos: Boolean = false,
    val fromCamera: Boolean = false,
    val planItemId: String? = null,
)

@Serializable data class EntryView(val entryId: String)
@Serializable data class Clip(val tripId: String)
@Serializable data class Export(val tripId: String)

/** The book editor for a trip (Make a book). */
@Serializable data class BookEditor(val tripId: String)
@Serializable data object TrackingSettings
@Serializable data object BackupSettings

/** Me › AI: built-in planner or the traveller's own Gemini, Claude, ChatGPT or OpenRouter key. */
@Serializable data object AiSettings
@Serializable data object Paywall

/** Your world (stats and flags), its share card, and the list of countries to add by hand (Wave 3J). */
@Serializable data object YourWorld
@Serializable data object ShareWorld
@Serializable data object PlacesBeen

/**
 * Trip ideas: ready-made day-by-day programs (programs/ProgramLibrary). [destinationId] opens straight on that
 * destination's two questions and preview; null starts with the list of destinations.
 */
@Serializable data class TripIdeas(val destinationId: String? = null)

/** The AI planner (Pro) for a trip, or for a new trip when [tripId] is null; [chat] opens on Chat instead of Notes. */
/** [start]: a [PlannerStart] step it opens at (New trip's choices), once. */
@Serializable data class Planner(val tripId: String? = null, val chat: Boolean = false, val start: String? = null)

/** Where the AI planner opens from New trip (owner, 18 Nov 2026): the questionnaire, the photo picker or Add a link. */
object PlannerStart {
    const val QUESTIONS = "questions"
    const val PICTURES = "pictures"
    const val LINK = "link"
}

/**
 * Copy from a link: a blog's posts and pictures as notes, or a Google Maps list's places, in new trips or one the
 * traveller has; [address] filled in (from the AI planner or a share), [tripId] the trip it was opened from, offered
 * as where everything goes.
 */
@Serializable data class CopySite(val address: String? = null, val tripId: String? = null)

/**
 * A place in a trip (arriving somewhere new), around a plan item ([itemId]) or a saved stop ([stopId]). The page shows
 * only what the trip's own plan, stops and photos say.
 */
@Serializable data class Place(val tripId: String, val itemId: String? = null, val stopId: String? = null)

/** The community: Latest and Top (the leaderboard). */
@Serializable data object CommunityHome

/** One community post. */
@Serializable data class CommunityPost(val postId: String)

/** Someone's community profile (their posts, likes received). */
@Serializable data class CommunityProfile(val uid: String)

/** Someone's followers ([followers]) or the people they follow, as two tabs. */
@Serializable data class CommunityFollows(val uid: String, val followers: Boolean = true)

/** Make or change the signed-in traveller's community profile; their blocked people. */
@Serializable data object CommunityProfileEdit

/**
 * Post to the community from trip [tripId]: [kind] trip | clip | album; [clipId] a saved clip to post; [mediaIds] photos
 * picked for an album (comma-separated media ids).
 */
@Serializable data class CommunityCompose(val tripId: String, val kind: String = "trip", val clipId: String? = null, val mediaIds: String? = null)

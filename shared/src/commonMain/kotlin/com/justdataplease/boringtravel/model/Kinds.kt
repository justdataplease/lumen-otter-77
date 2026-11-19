package com.justdataplease.boringtravel.model

/** What a plan item is; stored by [id] so names can change. */
enum class PlanKind(val id: String, val label: String) {
    Place("place", "Place"),
    Food("food", "Food"),
    Stay("stay", "Stay"),
    Transport("transport", "Getting there"),
    Activity("activity", "Activity"),
    Note("note", "Note");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Place
    }
}

/**
 * Where a plan item came from: typed by hand, drafted by the AI planner, imported (Tripadvisor later), taken from
 * an event in the phone's calendar (its `externalId` is then "cal:<eventId>/<day>"), or from a trip idea (a ready-made
 * program, `externalId` "<programId>:<day>:<index>", day 0 = its ideas; see programs/ProgramTrips), or copied from a
 * community post (`externalId` "community:<postId>:<index>"; see community/TripCopy).
 */
enum class ItemSource(val id: String) {
    Manual("manual"), Ai("ai"), Import("import"), Calendar("calendar"), Program("program"), Community("community");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Manual
    }
}

/**
 * Route tracking settings (owner: "very low or low, 3 settings"): how often and how precisely the route records, and so
 * the battery it takes. In the first Play version every level records through the location foreground service with its
 * notification (tracking/TrackingPlan.BACKGROUND_RECORDING off); with background recording on, Low and Balanced run
 * without it (updates delivered in batches to a receiver) and only Detailed ([usesForegroundService]) needs it.
 * Intervals are targets; Android may batch or delay updates. [maxDelayMs] lets the system batch.
 */
enum class TrackingLevel(
    val id: String,
    val label: String,
    val blurb: String,
    val intervalMs: Long,
    val minDistanceM: Float,
    val maxDelayMs: Long,
    val usesForegroundService: Boolean,
) {
    Off("off", "Off", "You add places yourself.", 0, 0f, 0, false),
    Low("low", "Low", "A point about every 30 min. Lightest on battery.", 30 * 60_000L, 500f, 60 * 60_000L, false),
    Balanced("balanced", "Balanced", "About every 10 min while you move.", 10 * 60_000L, 150f, 30 * 60_000L, false),
    Detailed("detailed", "Detailed", "Every few seconds while you move. Best for clips; uses the most battery.", 15_000L, 15f, 60_000L, true);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Balanced
    }
}

/** Vehicles for trip clips (and the guess per route segment, by speed). */
enum class Vehicle(val id: String, val label: String) {
    Auto("auto", "Auto"),
    Walk("walk", "Walk"),
    Bike("bike", "Bike"),
    Car("car", "Car"),
    Train("train", "Train"),
    Ferry("ferry", "Ferry"),
    Plane("plane", "Plane");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * Print themes for PDF export and books (design canvas, board "Print themes for PDF"). Notebook matches the default
 * look and is the proposed default (2E follow-up); a saved choice is kept. The trip's Share screen offers [SHARE]
 * only (owner, 2026-11-14: "keep only Notebook and Minimal"): there Minimal is the Notebook on plain white; the book
 * keeps all four with its own pages.
 */
enum class PrintTheme(val id: String, val label: String, val blurb: String) {
    Notebook("notebook", "Notebook", "Dotted paper, ink, taped photos."),
    Postcard("postcard", "Postcard", "Classic. One big photo a day."),
    Scrapbook("scrapbook", "Scrapbook", "Tilted photos, tape, handwriting."),
    Minimal("minimal", "Minimal", "Photo grid, facts, lots of white.");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Notebook

        /** The styles of the trip's Share screen. */
        val SHARE: List<PrintTheme> = listOf(Notebook, Minimal)

        /** [theme] for the Share screen: itself when it is offered there, else Notebook. */
        fun forShare(theme: PrintTheme): PrintTheme = if (theme in SHARE) theme else Notebook
    }
}

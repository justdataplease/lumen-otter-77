package com.justdataplease.boringtravel

import android.app.Application
import com.justdataplease.boringtravel.widget.Shortcuts
import com.justdataplease.boringtravel.widget.WidgetUpdates
import kotlinx.coroutines.launch

class BoringTravelApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // The examples (debug builds) and demo mode: chosen once, the demo kept around today.
        graph.demo.start()
        WidgetUpdates.keepUpdated(graph)
        // A call into the launcher: not on the main thread of every start (alarms and receivers start the app too).
        graph.scope.launch { Shortcuts.publish(this@BoringTravelApp) }
        // Route tracking follows today's trip and the chosen level from here on.
        graph.tracking.start()
        graph.calendar.startAutomaticUpdates()
        // Shared trips send each change to the other members, wherever in the app it is made (off the main thread:
        // it builds the Google and Drive objects).
        graph.scope.launch { graph.together.start() }
        // Trip posts are updated by hand only: any update 1.19.0 queued is dropped (nothing starts Firebase here).
        graph.postSync.start()
    }
}

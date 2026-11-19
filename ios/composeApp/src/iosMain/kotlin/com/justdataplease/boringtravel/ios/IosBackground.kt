@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * [BackgroundWork] on iPhone. Always UIApplication's background task: the app asks for time when a long piece of work
 * starts and gives it back when it is over; when the phone's time runs out first (its expiration handler), the task is
 * ended there, as Apple asks, and the work goes on once the app is back on screen.
 *
 * Since iOS 26 also a continued processing task ([ContinuedWork], the second parity round; the task itself in Swift): work the traveller started
 * with a tap (Export's Create video, Copy from a link's Read and Copy) is handed to BGTaskScheduler as a
 * BGContinuedProcessingTaskRequest, which the system runs at once or refuses (the Fail strategy: then only the
 * background task stands, as before). While the system runs it, it shows the work's name and its progress (the task's
 * NSProgress, from [BackgroundTicket.progressBackgroundWork]) and lets the work go on away from the screen until it is
 * done, as Android's goes on; the traveller or the system can stop it there (its expiration handler), and then the
 * work waits for the screen as before. Only type-checked on Windows: whether the phone's video encoder keeps writing
 * away from the screen under such a task needs a real iPhone on iOS 26.
 */
class IosBackgroundWork : BackgroundWork {
    override fun beginBackgroundWork(name: String): BackgroundTicket {
        val app = UIApplication.sharedApplication
        var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        var ended = false
        val end = {
            if (!ended) {
                ended = true
                if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
            }
        }
        task = app.beginBackgroundTaskWithName(name, expirationHandler = end)
        val continued = ContinuedWork.start(name)
        return object : BackgroundTicket {
            override fun endBackgroundWork() {
                end()
                continued?.finish()
            }

            override fun progressBackgroundWork(done: Float) {
                continued?.progress(done)
            }

            override val keepsGoingAway: Boolean get() = continued?.running == true
        }
    }
}

/**
 * iOS 26's continued processing tasks, done in Swift (iosApp/BoringTravel/ContinuedWorkBridge.swift): the classes exist
 * from iOS 26 only, Swift links them weakly behind `#available`, and a Kotlin reference to them would keep the app from
 * launching on iOS 16-25. Swift registers each identifier's launch handler once, submits the request (the Fail
 * strategy), keeps the task's progress and ends it; Kotlin says when, and hears when the system runs or stops it.
 */
interface NativeContinuedWork {
    /** Hands the work [identifier] (named [title]) to the system to run now; false when this iPhone has none or it refused. */
    fun startContinuedWork(identifier: String, title: String, listener: ContinuedWorkListener): Boolean

    fun progressContinuedWork(identifier: String, done: Double)

    fun finishContinuedWork(identifier: String)
}

/** What Swift tells Kotlin about a continued task, on the main thread. */
interface ContinuedWorkListener {
    /** The system runs it now: the work may go on away from the screen. */
    fun continuedWorkRunning(identifier: String)

    /** The traveller or the system stopped it: the work waits for the screen again. */
    fun continuedWorkStopped(identifier: String)
}

/** Swift's continued tasks (BoringTravelApp.swift hands them to [startBoringTravel]); null before that, or in tests. */
internal var nativeContinuedWork: NativeContinuedWork? = null

/** The export's and Copy from a link's continued tasks, one identifier per kind (the Info.plist's BGTaskSchedulerPermittedIdentifiers). */
private object ContinuedWork {
    private val ids = mapOf(
        ClipExporter.TASK_NAME to "com.justdataplease.boringtravel.continued.export",
        COPY_WORK_NAME to "com.justdataplease.boringtravel.continued.copy",
    )

    /** The work [name] handed to the system to run now; null when this iPhone has none or the system refused it. */
    fun start(name: String): Continued? {
        val id = ids[name] ?: return null
        val native = nativeContinuedWork ?: return null
        if (IosApp.tour) return null
        val work = Continued(native, id)
        return if (native.startContinuedWork(id, name, work)) work else null
    }

    /** One piece of work the system may run as a continued task. Main thread. */
    class Continued(private val native: NativeContinuedWork, private val id: String) : ContinuedWorkListener {
        private var finished = false

        /** The system runs it now (the task launched and hasn't been stopped). */
        var running = false
            private set

        override fun continuedWorkRunning(identifier: String) {
            if (!finished) running = true
        }

        override fun continuedWorkStopped(identifier: String) {
            running = false
        }

        fun progress(fraction: Float) {
            if (!finished) native.progressContinuedWork(id, fraction.coerceIn(0f, 1f).toDouble())
        }

        fun finish() {
            if (finished) return
            finished = true
            running = false
            native.finishContinuedWork(id)
        }
    }
}

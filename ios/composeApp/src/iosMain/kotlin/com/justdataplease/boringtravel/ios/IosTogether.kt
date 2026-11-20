@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.together.TogetherRuns
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Travel together's background turn (round 26; Android's periodic WorkManager job "together-sync" every 3 h with a
 * connection, and its sends' retries): a BGAppRefreshTask, asked for 3 h after each turn while a trip is shared (sooner,
 * 15 minutes, while a change waits to be sent), run when iOS gives the app time (best effort: iOS decides by how the
 * phone is used). Its identifier is in the Info.plist's BGTaskSchedulerPermittedIdentifiers (iosApp/project.yml); the
 * `fetch` background mode is already declared for route tracking's nightly check.
 */
internal object IosTogetherRuns : TogetherRuns {
    const val SYNC = "com.justdataplease.boringtravel.together.sync"
    private var registered = false

    /** Before the app has finished starting (iOS's rule): [work] for the task. */
    fun register(work: suspend () -> Unit) {
        if (registered) return
        registered = true
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(SYNC, usingQueue = null) { task -> task?.let { run(it, work) } }
    }

    /** One turn on the main thread; the phone's time running out cancels it. The next is asked for by the turn itself. */
    private fun run(task: BGTask, work: suspend () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            val job = MainScope().launch {
                try {
                    work()
                } finally {
                    task.setTaskCompletedWithSuccess(true)
                }
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    override fun planCatchUp(atMs: Long?) {
        val scheduler = BGTaskScheduler.sharedScheduler
        scheduler.cancelTaskRequestWithIdentifier(SYNC)
        if (atMs == null) return
        // The Simulator has no background tasks: the request is refused there, quietly.
        val request = BGAppRefreshTaskRequest(SYNC)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)
        scheduler.submitTaskRequest(request, null)
    }
}

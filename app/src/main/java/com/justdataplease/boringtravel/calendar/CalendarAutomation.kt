package com.justdataplease.boringtravel.calendar

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Observes local commits only while a connection exists. No calendar polling or foreground service. */
internal class CalendarAutomation(private val graph: AppGraph, private val sync: CalendarSync, private val links: CalendarLinks) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        graph.scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) graph.scope.launch { recover() }
            })
        }
        graph.scope.launch {
            links.observeBindings().map { bindings -> bindings.values.any { it.link.autoUpdate } }
                .distinctUntilChanged()
                .onEach { CalendarWork.recovery(graph.app, it) }
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) throw cause
                    delay(30_000)
                    true
                }.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                // Local writes may finish after the UI backgrounds. Queue them while this process still lives;
                // the periodic recovery job covers process death between a DB commit and the DataStore enqueue.
                combine(graph.trips.observeTrips(), graph.db.planItems().observeAllLive()) { _, _ -> Unit }
                    .debounce(500)
                    .onEach { sync.reconcileAutomatic() }
                    .retryWhen { cause, _ ->
                        if (cause is CancellationException) throw cause
                        delay(30_000)
                        true
                    }.collect { }
            }
        }
        graph.scope.launch { recover() }
    }

    /** Storage/provider startup failures must not crash the application. Durable work retries later. */
    private suspend fun recover() {
        try { sync.reconcileAutomatic(resume = true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* ON_START and periodic recovery try again. */ }
    }
}

internal object CalendarWork {
    private const val RECOVERY = "calendar-update-recovery"
    const val TRIP = "tripId"

    fun enqueue(context: Context, tripId: String) {
        val request = OneTimeWorkRequestBuilder<CalendarUpdateWorker>()
            .setInputData(workDataOf(TRIP to tripId))
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Append preserves a newer edit arriving just as running work finishes. Every job reads the newest
        // durable pending fingerprint, so intermediate edits collapse without cancelling an in-flight write.
        WorkManager.getInstance(context).enqueueUniqueWork("calendar-trip-$tripId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun recovery(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork(RECOVERY); return }
        work.enqueueUniquePeriodicWork(RECOVERY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarRecoveryWorker>(15, TimeUnit.MINUTES).build())
    }
}

class CalendarUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trip = inputData.getString(CalendarWork.TRIP) ?: return Result.failure()
        return try {
            when (applicationContext.graph.calendar.runAutomatic(trip)) {
                AutomaticCalendarResult.Done, AutomaticCalendarResult.Paused -> Result.success()
                AutomaticCalendarResult.Retry -> if (runAttemptCount < 4) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 4) Result.retry() else Result.failure() }
    }
}

class CalendarRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.graph.calendar.reconcileAutomatic(resume = true)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }
}

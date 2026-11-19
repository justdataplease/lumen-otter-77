package com.justdataplease.boringtravel.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The daily Drive backup. Skips quietly when backups are off, Drive needs the owner, or one ran in the last hours. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val settings = graph.settings
        if (!settings.autoBackup.first() || !settings.driveConnected.first()) return Result.success()
        val last = settings.lastBackupAt.first() ?: 0L
        if (BackupSchedule.isRecent(last, System.currentTimeMillis())) return Result.success()
        return try {
            // A phone that has never backed up asks about the backup in Drive first (ui/me/RestoreOffer, when the app
            // is opened): until then nothing is uploaded from it.
            if (settings.lastBackupAt.first() == null && !settings.restoreAsked.first()) {
                val inDrive = graph.backups.latest()
                if (BackupSafety.waitForRestoreAnswer(null, false, inDrive != null && BackupNames.hasData(inDrive))) return Result.success()
                settings.setRestoreAsked(true)
            }
            graph.backups.backupNow()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveNotConnectedException) {
            Result.success() // Google wants the owner to confirm; Trips and Me › Backup & sync say so.
        } catch (_: NothingToBackUpException) {
            Result.success()
        } catch (_: Exception) {
            // Tried again a few times today (with a growing wait), and again tomorrow whatever happens.
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

/** When the daily backup runs: once a day around 22:00, on Wi‑Fi when asked, never on a low battery. */
object BackupSchedule {
    const val WORK_NAME = "drive-backup-daily"
    const val HOUR = 22

    /** A backup this recent (a "Back up now", say) makes the daily one skip. */
    const val RECENT_MS = 18 * 60 * 60 * 1000L

    /** A corrected phone clock must not suppress backups until a future timestamp is reached. */
    fun isRecent(last: Long, now: Long): Boolean = last > 0 && now >= last && now - last < RECENT_MS

    fun apply(context: Context, on: Boolean, wifiOnly: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!on) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(firstDelay(ZonedDateTime.now()).toMinutes(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Time until the next [hour]:00 (tomorrow's when it has passed today). */
    fun firstDelay(now: ZonedDateTime, hour: Int = HOUR): Duration {
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}

package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupTransactionsTest {
    @Test fun anEditMadeWhileTheRestoreWaitedIsInTheCopyKeptForUndo() = runTest {
        val lock = Mutex()
        var row = Trip("t", "Before edit", createdAt = 1, updatedAt = 1)
        val incoming = Snapshot(trips = listOf(row.copy(title = "Backup", updatedAt = 2)))
        val finishEdit = CompletableDeferred<Unit>()
        val editor = launch {
            lock.withLock {
                finishEdit.await()
                row = row.copy(title = "Confirmed while downloading", updatedAt = 3)
            }
        }
        runCurrent()
        var readInsideLock = false
        var kept: Snapshot? = null
        val restore = async {
            BackupTransactions.replace(incoming, emptySet(),
                transaction = { work -> lock.withLock { work() } },
                read = { readInsideLock = lock.isLocked; Snapshot(trips = listOf(row)) },
                keep = { kept = it },
                write = { row = it.trips.single() },
            )
        }
        runCurrent()
        assertFalse(readInsideLock)
        finishEdit.complete(Unit)
        editor.join()
        restore.await()
        assertTrue(readInsideLock)
        // The phone is the backup now, and the edit is what Undo brings back.
        assertEquals("Backup", row.title)
        assertEquals("Confirmed while downloading", kept?.trips?.single()?.title)
    }

    @Test fun aPhoneThatCouldNotBeKeptIsNotChanged() = runTest {
        var wrote = false
        val failure = runCatching {
            BackupTransactions.replace(sampleSnapshot(), emptySet(),
                transaction = { work -> work() },
                read = { Snapshot(trips = listOf(Trip("mine", "Mine", createdAt = 1, updatedAt = 1))) },
                keep = { throw IOException("No space left") },
                write = { wrote = true },
            )
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(wrote)
    }

    @Test fun aPhoneAlreadyLikeTheBackupIsNeitherKeptNorWritten() = runTest {
        var touched = false
        val plan = BackupTransactions.replace(sampleSnapshot(), emptySet(),
            transaction = { work -> work() },
            read = { sampleSnapshot().let { s -> s.copy(trackPoints = s.trackPoints.mapIndexed { i, p -> p.copy(id = i + 1L) }) } },
            keep = { touched = true },
            write = { touched = true },
        )
        assertTrue(plan.isEmpty)
        assertFalse(touched)
    }

    @Test fun cancellationWhileWaitingForTransactionNeverReadsOrWrites() = runTest {
        val lock = Mutex(locked = true)
        var readOrWrite = false
        val restore = launch {
            BackupTransactions.replace(sampleSnapshot(), emptySet(),
                transaction = { work -> lock.withLock { work() } },
                read = { readOrWrite = true; Snapshot() },
                keep = { readOrWrite = true },
                write = { readOrWrite = true },
            )
        }
        runCurrent()
        restore.cancelAndJoin()
        lock.unlock()
        assertFalse(readOrWrite)
    }
}

package com.justdataplease.boringtravel.ui.editor

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Assign revisions when the UI captures a draft, before background jobs can run out of order. */
internal class DraftSaveOrder {
    private val sequence = AtomicLong()
    private val lock = Mutex()
    private var completed = 0L

    fun next(): Long = sequence.incrementAndGet()

    suspend fun save(revision: Long, write: suspend () -> Unit) = lock.withLock {
        if (revision <= completed) return@withLock
        write()
        completed = revision
    }
}

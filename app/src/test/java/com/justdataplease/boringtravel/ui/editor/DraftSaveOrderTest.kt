package com.justdataplease.boringtravel.ui.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftSaveOrderTest {
    @Test fun lateAutosaveCannotRemoveAnAttachedPhotoFromTheFinalDraft() = runTest {
        val order = DraftSaveOrder()
        val textOnly = order.next()
        val withPhoto = order.next()
        var stored = ""
        order.save(withPhoto) { stored = "note + photo + caption" }
        // A Default dispatcher job carrying an older snapshot reaches the database last.
        order.save(textOnly) { stored = "note" }
        assertEquals("note + photo + caption", stored)
    }

    @Test fun concurrentImportSavesWaitForTheCurrentWriteThenKeepTheNewest() = runTest {
        val order = DraftSaveOrder()
        val first = order.next()
        val second = order.next()
        val third = order.next()
        val releaseFirst = CompletableDeferred<Unit>()
        var stored = ""
        launch { order.save(first) { releaseFirst.await(); stored = "first photo" } }
        runCurrent()
        launch { order.save(third) { stored = "three photos" } }
        launch { order.save(second) { stored = "two photos" } }
        runCurrent()
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals("three photos", stored)
    }

    @Test fun discardedEmptyDraftCannotBeResurrectedByPendingAutosave() = runTest {
        val order = DraftSaveOrder()
        val old = order.next()
        val deleted = order.next()
        var exists = true
        order.save(deleted) { exists = false }
        order.save(old) { exists = true }
        assertEquals(false, exists)
    }
}

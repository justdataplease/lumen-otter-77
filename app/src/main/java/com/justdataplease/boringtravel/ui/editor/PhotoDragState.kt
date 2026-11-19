package com.justdataplease.boringtravel.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.PhotoPlacement
import com.justdataplease.boringtravel.doc.draggedPhotoPosition
import com.justdataplease.boringtravel.doc.horizontalPosition

/** Only the photo's Move handle starts this gesture; the rest of the page scrolls normally. */
internal class PhotoDragState(private val vm: WriteViewModel, private val scroll: ScrollState) {
    val bounds = mutableMapOf<String, Rect>()
    var viewport = Rect.Zero
    var key by mutableStateOf<String?>(null)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var beforeKey by mutableStateOf<String?>(null)
        private set
    var pointerY = 0f
        private set
    private var photo: EdItem.Photo? = null
    private var original: Block.Photo? = null
    private var start = Offset.Zero
    private var initialScroll = 0
    private var columnWidth = 0f
    private var envelope = 1f

    fun begin(item: EdItem.Photo, pointer: Offset, width: Float, aspect: Float) {
        photo = item
        original = item.block
        key = item.key
        start = pointer
        pointerY = pointer.y
        columnWidth = width
        envelope = PhotoPlacement.of(item.block, aspect).envelope
        initialScroll = scroll.value
        offsetY = 0f
        updateTarget()
    }

    fun move(pointer: Offset) {
        val item = photo ?: return
        val from = original ?: return
        pointerY = pointer.y
        item.block = item.block.copy(position = draggedPhotoPosition(from.horizontalPosition(), pointer.x - start.x, columnWidth, envelope))
        afterScroll()
    }

    fun afterScroll() {
        if (key == null) return
        offsetY = pointerY - start.y + scroll.value - initialScroll
        updateTarget()
    }

    private fun updateTarget() {
        beforeKey = vm.items.firstOrNull { item ->
            item.key != key && bounds[item.key]?.let { pointerY < it.center.y } == true
        }?.key
    }

    fun finish() {
        val moving = key
        val target = beforeKey
        clear()
        if (moving != null) vm.moveBefore(moving, target)
    }

    fun cancel() {
        original?.let { photo?.block = it }
        clear()
    }

    private fun clear() {
        key = null
        offsetY = 0f
        beforeKey = null
        photo = null
        original = null
    }
}

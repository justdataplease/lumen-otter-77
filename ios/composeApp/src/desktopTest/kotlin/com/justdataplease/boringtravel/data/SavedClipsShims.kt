package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.ios.DiskMediaFiles
import java.io.File

/*
 * The Android app's SavedClipsTest (read in as it is) hands SavedClipIndex Java's folders; this app's index works on the
 * app's files by path. These give the test's calls their folder: the index reads and writes the same files through
 * DiskMediaFiles rooted at the folder's parent.
 */

fun SavedClipIndex.video(dir: File, id: String): File = File(dir, File(video(id, "x")).name)

fun SavedClipIndex.meta(dir: File, id: String): File = File(dir, File(meta(id, "x")).name)

fun SavedClipIndex.thumb(dir: File, id: String): File = File(dir, File(thumb(id, "x")).name)

fun SavedClipIndex.list(dir: File): List<SavedClip> = list(DiskMediaFiles(dir.absoluteFile.parentFile), dir.name)

fun SavedClipIndex.delete(dir: File, id: String) = delete(DiskMediaFiles(dir.absoluteFile.parentFile), id, dir.name)

fun SavedClipIndex.deleteTrip(dir: File, tripId: String): List<String> = deleteTrip(DiskMediaFiles(dir.absoluteFile.parentFile), tripId, dir.name)

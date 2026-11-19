package com.justdataplease.boringtravel.backup

import java.io.InputStream
import java.io.OutputStream

/*
 * The Android app's BackupMergeTest (read in as it is) writes and reads a Drive backup through Java's streams; here
 * the same two files go through this app's own zip ([ZipWriter], [ZipReader]) and codec.
 */

/** A Drive backup: manifest.json and data.json, nothing else (the Android app's BackupCodec.write). */
fun BackupCodec.write(manifest: BackupManifest, data: BackupData, out: OutputStream) {
    val zip = ZipWriter({ b, off, len -> out.write(b, off, len) })
    val (manifestBytes, dataBytes) = rows(manifest, data)
    zip.add(MANIFEST, manifestBytes)
    zip.add(DATA, dataBytes)
    zip.finish()
}

/** Reads a Drive backup (format 1), with every reader's checks (the Android app's BackupCodec.read). */
fun BackupCodec.read(input: InputStream): Pair<BackupManifest, BackupData> {
    val bytes = input.readBytes()
    val zip = BackupArchive.reader(bytes.size.toLong()) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
    val manifest = zip.entries.firstOrNull { it.name == MANIFEST }?.let { zip.bytesOf(it, MAX_SMALL_BYTES).decodeToString() }
    val data = zip.entries.firstOrNull { it.name == DATA }?.let { zip.bytesOf(it, MAX_DATA_BYTES) }
    return parse(manifest, data, FORMAT)
}

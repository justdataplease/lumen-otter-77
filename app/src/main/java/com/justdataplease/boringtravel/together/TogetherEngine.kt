package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.backup.toTrip
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.MediaTypes
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** This phone's rows (Room in the app, a map in tests). */
interface TogetherRows {
    /** The trip's own row, deleted or not; null when it isn't on this phone. */
    suspend fun trip(tripId: String): Trip?

    suspend fun rows(tripId: String): TripRows

    /**
     * Runs [compute] on the trip's current rows and the ids among [ids] that belong to other trips, and writes the
     * result, all in one transaction (an edit made meanwhile is never overwritten by an older file).
     */
    suspend fun merge(tripId: String, ids: Set<String>, compute: (TripRows, Set<String>) -> TogetherPlan): TogetherPlan

    /** Records where photo [expected]'s small copy is, if the row is still [expected]; stamped as a change. */
    suspend fun setSharedFile(expected: Media, fileId: String): Boolean

    /** Forgets photo [expected]'s small copy [fileId] (deleted, or its folder is gone) if the row still names it; no new stamp. */
    suspend fun clearSharedFile(expected: Media, fileId: String): Boolean
}

/** The owner's own Drive (drive.file): only their folder and files. */
interface OwnDrive {
    suspend fun connected(): Boolean

    /** Makes BoringTravel/Shared/<name>, link-shared; returns its id. */
    suspend fun sharedFolder(name: String): String

    /** A new link-shared file in [folderId]; returns its id, or null when the folder is gone. */
    suspend fun create(folderId: String, name: String, content: ByteArray): String?

    /** Replaces file [fileId]'s content; false when the file is gone. */
    suspend fun replace(fileId: String, content: ByteArray): Boolean

    /** Uploads a photo's small copy or a video ([mime]) into [folderId]; returns its id, or null when the folder is gone. */
    suspend fun upload(folderId: String, file: File, name: String, mime: String): String?

    /** Deletes one of the owner's files (gone already counts as done). */
    suspend fun delete(fileId: String)

    /** Takes a file or folder off its link: only the owner can open it again. */
    suspend fun unshare(id: String)
}

/** Other members' files, read by link. Throws [com.justdataplease.boringtravel.google.NotSharedException] when gone. */
interface LinkReader {
    /** Drive's checksum of file [fileId], without its content; null when Drive can't tell (then read it). */
    suspend fun checksum(fileId: String): String?
    suspend fun read(fileId: String, maxBytes: Long): ByteArray
    suspend fun download(fileId: String, target: File, maxBytes: Long)
}

/** Photo files on this phone. */
interface PhotoFiles {
    /** The photo's file on this phone ([Media.localPath]). */
    fun file(media: Media): File

    /** A small JPEG copy of one of the owner's photos to share; null when it can't be read. */
    fun smallCopy(media: Media): File?
}

class TogetherException(message: String) : IOException(message)

/**
 * Shared trips: each member keeps their copy of the trip in their own Drive and reads the others' copies by link (see
 * PLAN.md › Travel together). One sync reads every other member's file that changed (Drive's checksum first, so an
 * unchanged file isn't downloaded), merges it ([TogetherMerge]), sends the owner's new photos and takes deleted ones
 * out of Drive, writes the owner's file when the trip changed, and fetches the others' photos.
 *
 * Plain Kotlin over the interfaces above, so whole conversations between phones run in unit tests.
 */
class TogetherEngine(
    private val rows: TogetherRows,
    private val own: OwnDrive,
    private val links: LinkReader,
    private val photos: PhotoFiles,
    private val store: StateStore,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val notShared: (Throwable) -> Boolean = { it is com.justdataplease.boringtravel.google.NotSharedException },
    /**
     * True when big files wait for Wi-Fi now: the owner's "Only on Wi-Fi" is on and this network is metered. A video
     * over [WAIT_FOR_WIFI_BYTES] then neither goes up nor comes down; the next sync on Wi-Fi takes it.
     */
    private val bigFilesWait: suspend () -> Boolean = { false },
) {
    /** Where shared trips' state is kept (a JSON file in the app, memory in tests). */
    interface StateStore {
        suspend fun read(): TogetherState
        suspend fun write(state: TogetherState)
    }

    private val lock = Mutex()
    private val _state = MutableStateFlow(TogetherState())
    val state: StateFlow<TogetherState> = _state.asStateFlow()
    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()
    private val _photos = MutableStateFlow(0)

    /** Goes up each time other members' photos arrive, so the screens showing them load them again. */
    val photosVersion: StateFlow<Int> = _photos.asStateFlow()
    private var loaded = false

    /** Photo downloads that failed, by shared file id: how often, and when to try again (while the app runs). */
    private val misses = HashMap<String, Miss>()

    /** Other members' videos' sizes by media id, from the files read while the app runs ([SharedPhoto.bytes]). */
    private val videoBytes = HashMap<String, Long>()

    private class Miss(val count: Int, val retryAt: Long)

    private suspend fun current(): TogetherState {
        if (!loaded) {
            _state.value = store.read()
            loaded = true
        }
        return _state.value
    }

    private suspend fun save(state: TogetherState) {
        store.write(state)
        _state.value = state
    }

    /** Saves [s] at once: a folder or file just made in Drive must not be forgotten when a later step fails. */
    private suspend fun keep(s: SharedTrip): SharedTrip {
        val st = current()
        if (st.of(s.tripId) != s) save(st.with(s))
        return s
    }

    suspend fun load(): TogetherState = lock.withLock { current() }

    suspend fun isShared(tripId: String): Boolean = load().of(tripId) != null

    /** The state as JSON, for backups (null when no trip is or was shared). */
    suspend fun exportState(): String? = load().takeIf { !it.isEmpty }?.let { STATE_JSON.encodeToString(TogetherState.serializer(), it) }

    /**
     * Takes back shared trips from a backup; a trip this phone already tracks keeps its own state. A trip that isn't on
     * this phone, or was deleted here (left), only leaves who this phone was in it, for joining again.
     */
    suspend fun importState(json: String) = lock.withLock {
        val saved = runCatching { STATE_JSON.decodeFromString(TogetherState.serializer(), json) }.getOrNull() ?: return@withLock
        var st = current()
        for (t in saved.trips) {
            if (st.of(t.tripId) != null) continue
            val trip = rows.trip(t.tripId)
            st = if (trip == null || trip.deletedAt != null) {
                st.copy(former = mapOf(t.tripId to t.me) + st.former)
            } else {
                // Posted to the mailbox again once (cheap), in case the entry went while this phone was away.
                st.with(t.copy(lastRead = emptyMap(), seen = emptyMap(), lastWritten = null, lastRows = null, mailed = null, mailedAt = 0))
            }
        }
        st = st.copy(former = (saved.former + st.former).filterKeys { st.of(it) == null })
        save(st)
    }

    /**
     * Starts sharing trip [tripId] (one of the owner's): makes the folder and file in their Drive and returns the
     * invite link. Already shared: the same link again.
     */
    suspend fun share(tripId: String, myName: String, tripTitle: String): ShareLink = lock.withLock {
        val st = current()
        val s = st.of(tripId) ?: SharedTrip(tripId, st.former[tripId] ?: newId(), ShareCodec.cleanName(myName), joinedAt = clock())
        val published = publish(s.copy(myName = ShareCodec.cleanName(myName).ifBlank { s.myName }), rows.rows(tripId), tripTitle)
        save(current().with(published).let { it.copy(former = it.former - tripId) })
        link(published, tripTitle)
    }

    /** The link this phone gives out for [tripId] (to invite, or to send back after joining); null before it has a file. */
    suspend fun linkFor(tripId: String, tripTitle: String): ShareLink? = load().of(tripId)?.let { s -> s.fileId?.let { link(s, tripTitle) } }

    private fun link(s: SharedTrip, title: String) = ShareLink(s.tripId, requireNotNull(s.fileId), s.me, s.myName, title)

    /** What [link] means on this phone. */
    sealed interface Opened {
        /** A new trip: joined, its rows merged in. */
        data class Joined(val tripId: String) : Opened

        /** A new member of a trip already shared here. */
        data class Added(val tripId: String, val name: String) : Opened

        /** Nothing new (a link already known, or this phone's own). */
        data class Known(val tripId: String, val own: Boolean = false) : Opened
    }

    /**
     * Opens a join link: joins the trip, or adds the member who sent it to a trip already shared here. Reads their
     * file first, so a dead link changes nothing. Joining a trip left before carries on as the same member.
     */
    suspend fun open(link: ShareLink, myName: String): Opened = lock.withLock {
        val st = current()
        val existing = st.of(link.tripId)
        if (existing != null) return@withLock addLocked(st, existing, link)
        val local = rows.trip(link.tripId)
        if (local != null && local.deletedAt == null) {
            throw TogetherException("This trip is already on this phone.")
        }
        val file = readMember(link)
        val s = SharedTrip(
            tripId = link.tripId, me = st.former[link.tripId] ?: newId(), myName = ShareCodec.cleanName(myName),
            members = listOf(Member(link.memberId, ShareCodec.cleanName(link.name), link.fileId)), joinedAt = clock(),
        )
        // A trip left before and joined again comes back: its leaving was only on this phone.
        mergeFile(s, file, revive = true)
        save(st.with(s).copy(former = st.former - link.tripId))
        Opened.Joined(link.tripId)
    }

    /**
     * Adds the member [link] names to its trip when that trip is shared here: an address from the trip's mailbox
     * ([TogetherMail]). Null when the trip isn't shared on this phone: this never joins a trip.
     */
    suspend fun add(link: ShareLink): Opened? = lock.withLock {
        val st = current()
        st.of(link.tripId)?.let { addLocked(st, it, link) }
    }

    /** [link]'s member into [existing], after reading their file: a link sent back, or an address from the mailbox. */
    private suspend fun addLocked(st: TogetherState, existing: SharedTrip, link: ShareLink): Opened {
        if (link.memberId == existing.me) return Opened.Known(link.tripId, own = true)
        val known = existing.members.firstOrNull { it.id == link.memberId }
        if (known?.fileId == link.fileId && !known.left) return Opened.Known(link.tripId)
        if (known == null && existing.members.size >= ShareCodec.MAX_MEMBERS - 1) {
            throw TogetherException("This trip already has ${ShareCodec.MAX_MEMBERS} people, the most a shared trip takes.")
        }
        val file = readMember(link)
        // A mailbox address has no name: the one in their own file.
        val written = file.members.firstOrNull { it.id == file.writer }?.name.orEmpty()
        val name = ShareCodec.cleanName(link.name).ifBlank { ShareCodec.cleanName(written) }.ifBlank { known?.name.orEmpty() }
        val member = Member(link.memberId, name, link.fileId)
        val updated = existing.copy(
            members = existing.members.filter { it.id != member.id } + member,
            unreachable = existing.unreachable - member.id,
            // Their (new) file is read in full at the next sync.
            lastRead = existing.lastRead - member.id,
            seen = existing.seen - member.id,
            listed = existing.listed || TogetherMerge.lists(file, existing.me, existing.fileId),
        )
        save(st.with(updated))
        return Opened.Added(link.tripId, member.name)
    }

    /** Records what this phone posted to [tripId]'s mailbox, and when ([SharedTrip.mailed]). */
    suspend fun mailed(tripId: String, posted: String?, at: Long) = lock.withLock {
        val st = current()
        st.of(tripId)?.let { if (it.mailed != posted || it.mailedAt != at) save(st.with(it.copy(mailed = posted, mailedAt = at))) }
        Unit
    }

    /**
     * One sync of [tripId]: read the others, merge, send new photos and this phone's file (when Drive is connected),
     * fetch the others' photos. Returns true when anything on this phone changed. A trip deleted here without leaving
     * (or one a restore brought back after leaving) is left now.
     */
    suspend fun sync(tripId: String, tripTitle: String = ""): Boolean = lock.withLock {
        var s = current().of(tripId) ?: return@withLock false
        val trip = rows.trip(tripId)
        if (trip == null || trip.deletedAt != null) {
            leaveLocked(s)
            return@withLock false
        }
        _status.value = _status.value + (tripId to SyncStatus.Working)
        try {
            var changed = false
            val lastRead = s.lastRead.toMutableMap()
            val seen = s.seen.toMutableMap()
            val unreachable = s.unreachable.toMutableSet()
            var members = s.members
            var listed = s.listed
            // A member's copy that couldn't be read for another reason than its link: said after the sync.
            var trouble: String? = null
            for (m in s.members) {
                val fileId = m.fileId ?: continue
                if (m.left) continue
                val sum = try {
                    links.checksum(fileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (notShared(e)) unreachable += m.id else trouble = e.readable("Couldn't read a member's copy. Try again.")
                    continue
                }
                // Drive says the file is the one merged last time: nothing to download.
                if (sum != null && seen[m.id] == sum) {
                    unreachable -= m.id
                    continue
                }
                val bytes = try {
                    links.read(fileId, ShareCodec.MAX_GZIP_BYTES)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (notShared(e)) unreachable += m.id else trouble = e.readable("Couldn't read a member's copy. Try again.")
                    continue
                }
                unreachable -= m.id
                val hash = sha(bytes)
                if (lastRead[m.id] == hash && listed) {
                    sum?.let { seen[m.id] = it }
                    continue
                }
                val file = try {
                    ShareCodec.decode(bytes)
                } catch (e: ShareFormatException) {
                    trouble = e.message
                    continue
                }
                if (file.trip.id != tripId || file.writer != m.id) continue
                if (TogetherMerge.lists(file, s.me, s.fileId)) listed = true
                if (lastRead[m.id] != hash) {
                    if (mergeFile(s, file, revive = false)) changed = true
                    members = TogetherMerge.members(members, file, s.me)
                    lastRead[m.id] = hash
                }
                sum?.let { seen[m.id] = it }
            }
            s = keep(s.copy(members = members, lastRead = lastRead, seen = seen, unreachable = unreachable.toList(), listed = listed))
            var r = rows.rows(tripId)
            var ownTrouble: Exception? = null
            if (own.connected()) {
                try {
                    val (sent, touched) = sendPhotos(s, r, tripTitle)
                    s = sent
                    if (touched) r = rows.rows(tripId)
                    s = publish(s, r, tripTitle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The owner's Drive (full, access expired, the connection): the others' photos still come in.
                    ownTrouble = e
                    s = current().of(tripId) ?: s
                }
            }
            if (fetchPhotos(r)) changed = true
            ownTrouble?.let { throw it }
            s = keep(s.copy(syncedAt = clock()))
            _status.value = _status.value + (tripId to (trouble?.let { SyncStatus.Failed(it) } ?: SyncStatus.Done(s.syncedAt, changed)))
            changed
        } catch (e: CancellationException) {
            _status.value = _status.value + (tripId to SyncStatus.Idle)
            throw e
        } catch (e: Exception) {
            _status.value = _status.value + (tripId to SyncStatus.Failed(e.readable("Couldn't sync. Try again.")))
            throw e
        }
    }

    /**
     * Leaves [tripId] on this phone: the owner's file says so one last time, without the trip, and their folder is
     * taken off its link (when Drive is connected); then the trip stops syncing here. The others keep the trip; what
     * the owner added stays in it.
     */
    suspend fun leave(tripId: String) = lock.withLock {
        current().of(tripId)?.let { leaveLocked(it) }
        Unit
    }

    private suspend fun leaveLocked(s: SharedTrip) {
        val told = sayGoodbye(s)
        val st = current().leaving(s.tripId)
        // Drive couldn't be told now: remembered, so the copy doesn't stay readable by link ([finishLeaving]).
        val waiting = s.copy(members = emptyList(), lastRead = emptyMap(), seen = emptyMap(), unreachable = emptyList())
        save(if (told) st else st.copy(unfinished = st.unfinished.filter { it.tripId != s.tripId } + waiting))
        _status.value = _status.value - s.tripId
    }

    /**
     * The owner's file says "left" one last time, without the trip, and their folder comes off its link. False when
     * Drive couldn't be told (offline, or not connected).
     */
    private suspend fun sayGoodbye(s: SharedTrip): Boolean {
        val fileId = s.fileId ?: return true
        return try {
            if (!own.connected()) return false
            own.replace(fileId, ShareCodec.encode(TogetherMerge.farewell(s.tripId, Member(s.me, s.myName, fileId), clock())))
            // The photos' copies stop being readable by link; the file keeps its own link so the others see "Left".
            s.folderId?.let { own.unshare(it) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Finishes the leavings Drive hasn't heard of yet. What still can't be told waits for the next time. */
    suspend fun finishLeaving() = lock.withLock {
        // A trip joined again since is in use: nothing to take back there.
        val done = current().unfinished.filter { current().of(it.tripId) != null || sayGoodbye(it) }
        if (done.isNotEmpty()) save(current().let { st -> st.copy(unfinished = st.unfinished - done.toSet()) })
    }

    /** Merges one member's file; true when rows changed. [revive]: an earlier leaving on this phone is undone. */
    private suspend fun mergeFile(s: SharedTrip, file: ShareFile, revive: Boolean): Boolean {
        file.photos.forEach { if (it.bytes > 0) videoBytes[it.id] = it.bytes }
        val ids = (file.planItems.map { it.id } + file.entries.map { it.id } + file.photos.map { it.id }).toSet()
        val plan = rows.merge(s.tripId, ids) { local, elsewhere ->
            val p = TogetherMerge.plan(local, file, s.me, elsewhere)
            val back = local.trip?.takeIf { revive && it.deletedAt != null }
            if (back == null) p else {
                // The trip as the others have it now (they went on changing it); only the leaving is undone.
                val theirs = p.trip ?: file.trip.toTrip().copy(trackingLevel = back.trackingLevel, createdAt = back.createdAt)
                p.copy(trip = theirs.copy(deletedAt = null, updatedAt = maxOf(back.updatedAt, file.trip.updatedAt) + 1))
            }
        }
        return !plan.isEmpty
    }

    /** Reads the file a join link points at and checks it is that member's copy of that trip. */
    private suspend fun readMember(link: ShareLink): ShareFile {
        val bytes = try {
            links.read(link.fileId, ShareCodec.MAX_GZIP_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (notShared(e)) throw TogetherException("This link doesn't work any more. Ask for a new one.")
            throw e
        }
        val file = ShareCodec.decode(bytes)
        if (file.trip.id != link.tripId || file.writer != link.memberId) throw TogetherException("This link doesn't match its trip.")
        if (file.members.firstOrNull { it.id == file.writer }?.left == true) {
            throw TogetherException("${link.name.ifBlank { "They" }} left this trip. Ask someone still in it for a link.")
        }
        return file
    }

    /**
     * Makes the owner's folder and file if needed and writes the file when the trip changed: an unchanged
     * fingerprint skips even the encoding, an unchanged file skips the upload.
     */
    private suspend fun publish(s0: SharedTrip, r0: TripRows, title: String): SharedTrip {
        var s = s0
        var r = r0
        if (r.trip == null) throw TogetherException("This trip isn't on this phone.")
        if (!own.connected()) throw TogetherException("Connect Google Drive to share this trip.")
        if (s.fileId != null && s.folderId != null && s.lastRows == TogetherMerge.fingerprint(r, s.everyone())) return s
        if (s.folderId == null) {
            s = newFolder(s, title, r)
            r = rows.rows(s.tripId)
        }
        val existing = s.fileId
        if (existing != null) {
            val hash = sha(encode(r, s, writtenAt = 0))
            if (hash == s.lastWritten) return keep(s.copy(lastRows = TogetherMerge.fingerprint(r, s.everyone())))
            if (own.replace(existing, encode(r, s, clock()))) {
                return keep(s.copy(lastWritten = hash, lastRows = TogetherMerge.fingerprint(r, s.everyone())))
            }
        }
        // A first file (or one deleted from Drive): made, then written again so it names itself for the others.
        var made = own.create(requireNotNull(s.folderId), ShareCodec.FILE_NAME, encode(r, s, clock()))
        if (made == null) {
            s = folderGone(s, title, r)
            made = own.create(requireNotNull(s.folderId), ShareCodec.FILE_NAME, encode(r, s, clock()))
                ?: throw TogetherException("Couldn't save your copy of the trip to Google Drive. Try again.")
        }
        // The others know this phone's old file at best: until one of them lists the new one, send the link again.
        s = keep(s.copy(fileId = made, lastWritten = null, lastRows = null, listed = false))
        val hash = sha(encode(r, s, writtenAt = 0))
        if (own.replace(made, encode(r, s, clock()))) s = keep(s.copy(lastWritten = hash, lastRows = TogetherMerge.fingerprint(r, s.everyone())))
        return s
    }

    private fun encode(r: TripRows, s: SharedTrip, writtenAt: Long) =
        ShareCodec.encode(TogetherMerge.file(r, s.me, s.everyone(), writtenAt, ::sizeOf))

    /** A video's size: its file here, else what its author's file said. */
    private fun sizeOf(m: Media): Long = photos.file(m).length().takeIf { it > 0 } ?: videoBytes[m.id] ?: 0

    /**
     * Uploads the owner's photos of the trip that have no shared copy yet (a photo as a small JPEG, a video as its own
     * compressed file), and deletes the copies of the ones they deleted. Returns the trip and whether any row changed.
     */
    private suspend fun sendPhotos(s0: SharedTrip, r: TripRows, title: String): Pair<SharedTrip, Boolean> {
        var s = s0
        var touched = false
        for (m in r.media) {
            val fileId = m.sharedFileId ?: continue
            if (m.authorId != null || m.deletedAt == null) continue
            own.delete(fileId)
            if (rows.clearSharedFile(m, fileId)) touched = true
        }
        if (s.folderId == null) {
            s = newFolder(s, title, r)
            touched = true
        }
        var pending = unsent(if (touched) rows.rows(s.tripId) else r)
        var renewed = false
        var wait: Boolean? = null
        var i = 0
        while (i < pending.size) {
            val m = pending[i++]
            val id = if (m.isVideo) {
                val video = photos.file(m).takeIf { it.isFile && it.length() in 1..MAX_VIDEO_BYTES } ?: continue
                // A big video waits for Wi-Fi when the owner asked for that; it stays unsent and goes with a later sync.
                if (video.length() > WAIT_FOR_WIFI_BYTES && (wait ?: bigFilesWait().also { wait = it })) continue
                own.upload(requireNotNull(s.folderId), video, "${m.id}.${MediaTypes.extension(m.mime)}", m.mime)
            } else {
                val small = photos.smallCopy(m) ?: continue
                own.upload(requireNotNull(s.folderId), small, "${m.id}.jpg", MediaTypes.JPEG)
            }
            if (id == null) {
                // The folder was deleted from Drive: a new one, and every photo goes into it again.
                if (renewed) throw TogetherException("Couldn't save your photos to Google Drive. Try again.")
                renewed = true
                s = folderGone(s, title, r)
                touched = true
                pending = unsent(rows.rows(s.tripId))
                i = 0
                continue
            }
            rows.setSharedFile(m, id)
            touched = true
        }
        return s to touched
    }

    private fun unsent(r: TripRows) =
        r.media.filter { it.authorId == null && it.sharedFileId == null && it.deletedAt == null && (it.mime.startsWith("image/") || it.isVideo) }

    /**
     * The owner's folder is gone from Drive (deleted there): a new one, saved at once. The file and the photos' copies
     * went with the old folder, so they are made again.
     */
    private suspend fun folderGone(s: SharedTrip, title: String, r: TripRows): SharedTrip =
        newFolder(s.copy(fileId = null, lastWritten = null, lastRows = null, listed = false), title, r)

    /**
     * A new folder for the owner's copy, saved at once (the first, or one after leaving and joining again). None of
     * the owner's photos has a copy in it yet, so each forgets the copy it had and goes up again.
     */
    private suspend fun newFolder(s: SharedTrip, title: String, r: TripRows): SharedTrip {
        val fresh = keep(s.copy(folderId = own.sharedFolder(folderName(title, r))))
        for (m in rows.rows(s.tripId).media) {
            val fileId = m.sharedFileId ?: continue
            if (m.authorId == null) rows.clearSharedFile(m, fileId)
        }
        return fresh
    }

    private fun folderName(title: String, r: TripRows) = folderName(title.ifBlank { r.trip?.title.orEmpty() })

    /**
     * Downloads the photos this phone doesn't have yet: the other members', and the owner's own that came back
     * through the trip (a phone recovered from a backup older than them). One that keeps failing waits longer each time.
     */
    private suspend fun fetchPhotos(r: TripRows): Boolean {
        var any = false
        var wait: Boolean? = null
        for (m in r.media) {
            val id = m.sharedFileId ?: continue
            if (m.deletedAt != null) continue
            // The owner's own photo with its original here (it has a checksum) is never replaced by the small copy.
            if (m.authorId == null && m.sha256 != null) continue
            val target = photos.file(m)
            if (target.isFile && target.length() > 0) continue
            // A video over WAIT_FOR_WIFI_BYTES (or of a size this phone hasn't read yet) waits for Wi-Fi when asked to.
            if (m.isVideo && (videoBytes[m.id] ?: Long.MAX_VALUE) > WAIT_FOR_WIFI_BYTES && (wait ?: bigFilesWait().also { wait = it })) continue
            val now = clock()
            val miss = misses[id]
            if (miss != null && now < miss.retryAt) continue
            try {
                links.download(id, target, if (m.isVideo) MAX_VIDEO_BYTES else MAX_PHOTO_BYTES)
                misses.remove(id)
                any = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val count = (miss?.count ?: 0) + 1
                misses[id] = Miss(count, now + retryWait(count))
            }
        }
        if (any) _photos.value = _photos.value + 1
        return any
    }

    companion object {
        const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

        /** A video's copy: about 22 MB a minute, about 240 MB at most ([VideoLimits]); never more than a backup holds. */
        const val MAX_VIDEO_BYTES = VideoLimits.HARD_MAX_BYTES

        /** With "Only on Wi-Fi" on, a video bigger than this (about a minute) waits for Wi-Fi both ways. */
        const val WAIT_FOR_WIFI_BYTES = 25L * 1024 * 1024

        /** A photo that couldn't be downloaded waits a minute, then twice as long each time, up to six hours. */
        const val MISS_FIRST_MS = 60_000L
        const val MISS_MAX_MS = 6 * 3_600_000L

        private val STATE_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun folderName(title: String): String = title.replace(Regex("[\\p{Cntrl}/\\\\]+"), " ").trim().take(60).ifBlank { "Trip" }

        fun retryWait(failures: Int): Long = minOf(MISS_FIRST_MS shl (failures - 1).coerceIn(0, 20), MISS_MAX_MS)

        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

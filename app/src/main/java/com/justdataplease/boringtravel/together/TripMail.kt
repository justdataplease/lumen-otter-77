package com.justdataplease.boringtravel.together

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/** One member's address in a shared trip's mailbox: who posted it (Firebase user), their member id, their copy's Drive file. */
data class MailEntry(val uid: String, val member: String, val fileId: String)

/** The trips' mailboxes (Firestore in the app, a map in tests). Every call needs a signed-in [uid]. */
interface MailService {
    /** The signed-in Firebase user's id, or null: nothing is posted or read then. */
    val uid: StateFlow<String?>

    /** Puts [entry] into mailbox [box] as its poster's one entry there (replacing the one before), with the server's time. */
    suspend fun post(box: String, entry: MailEntry)

    /** Takes the signed-in user's entry out of [box] (gone already counts as done). */
    suspend fun remove(box: String)

    /** [box]'s entries now (at most [TripMail.MAX_ENTRIES]). */
    suspend fun read(box: String): List<MailEntry>

    /** [box]'s entries, again each time one changes, while collected. */
    fun watch(box: String): Flow<List<MailEntry>>
}

/**
 * A shared trip's mailbox: where each member keeps their copy of the trip, so a friend who joins by link reaches the
 * others by themselves (until 1.12.0 they had to send their own link back). In Firestore at
 * `tripMail/{box}/members/{uid}`, per member: their member id, the Drive file id of their copy and a time. Nothing of
 * the trip itself (see android-boringtravel/firebase/README.md). An address is trusted only as far as the file it names proves
 * to be that member's copy of the trip: [TogetherEngine.add] reads it first, as for a link.
 *
 * The box is a hash of the trip's id: whoever holds a link to the trip (or one of its files) can work it out, nobody
 * else can, and the trip's id itself never reaches Firestore. Old links lead to it too, as they carry the trip's id.
 * Plain Kotlin, unit-tested.
 */
object TripMail {
    const val COLLECTION = "tripMail"
    const val MEMBERS = "members"

    /** At most this many entries are read from a box (the rules refuse a bigger read). */
    const val MAX_ENTRIES = 50

    /** How long a posted copy may go unread by the others before "Send your link" is offered after all. */
    const val READ_WAIT_MS = 5 * 60_000L

    /** The app's trip ids are random UUIDs (36 characters); a shorter id could be guessed, so it gets no mailbox. */
    const val MIN_TRIP_ID = 20

    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val FILE = Regex("[A-Za-z0-9_-]{5,200}")

    /** The mailbox of trip [tripId] (64 hex characters), or null for an id that is too short to stay secret. */
    fun box(tripId: String): String? {
        if (tripId.length < MIN_TRIP_ID || !ID.matches(tripId)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("BoringTravel trip mail 1:$tripId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** What [SharedTrip.mailed] holds once [fileId] is posted as [uid]: a new account or a new copy posts again. */
    fun posted(uid: String, fileId: String): String = "$uid $fileId"

    /** True when [s]'s current copy is in its mailbox (as whichever account posted it). */
    fun isPosted(s: SharedTrip): Boolean = s.fileId != null && s.mailed?.substringAfter(' ', "") == s.fileId

    /** An entry as Firestore holds it, or null when it isn't one the app writes (a damaged or foreign document). */
    fun parse(uid: String, member: Any?, fileId: Any?): MailEntry? {
        if (!ID.matches(uid) || member !is String || fileId !is String) return null
        if (!ID.matches(member) || !FILE.matches(fileId)) return null
        return MailEntry(uid, member, fileId)
    }

    /** One entry of one trip, as [newcomers] remembers the ones whose file wasn't theirs. */
    fun key(tripId: String, e: MailEntry): String = "$tripId ${e.member} ${e.fileId}"

    /**
     * The entries that bring someone to [s]: members this phone doesn't know yet, and members whose copy it lost (they
     * left, it can't be reached, or they had none) at a new address. Never this phone itself, one entry per member, never
     * more new members than the trip has room for. [refused]: [key]s whose file was read and wasn't theirs.
     */
    fun newcomers(s: SharedTrip, entries: List<MailEntry>, refused: Set<String>): List<MailEntry> {
        val known = s.members.associateBy { it.id }
        val picked = LinkedHashMap<String, MailEntry>()
        for (e in entries) {
            if (e.member == s.me || e.member in picked || key(s.tripId, e) in refused) continue
            val m = known[e.member]
            if (m != null && (m.fileId == e.fileId || !(m.left || m.fileId == null || m.id in s.unreachable))) continue
            picked[e.member] = e
        }
        // The trip takes ShareCodec.MAX_MEMBERS people, this phone included.
        val room = (ShareCodec.MAX_MEMBERS - 1 - s.members.size).coerceAtLeast(0)
        val (back, fresh) = picked.values.partition { it.member in known }
        return back + fresh.take(room)
    }

    /**
     * Whether to offer "Send your link": this phone has a copy that no other member reads yet, and the mailbox can't be
     * counted on to tell them: it isn't posted (not signed in, or Firestore failed), none of the others is in the
     * mailbox (an older app, or not signed in: they never read it), or they still haven't picked it up [READ_WAIT_MS]
     * after it was posted. [inBox]: the member ids last read in the mailbox, null when it wasn't read.
     */
    fun offerLink(s: SharedTrip, inBox: Set<String>?, now: Long): Boolean {
        if (s.fileId == null || !s.needsLinkBack) return false
        if (!isPosted(s)) return true
        if (inBox != null && s.members.none { !it.left && it.id in inBox }) return true
        return now - s.mailedAt > READ_WAIT_MS
    }
}

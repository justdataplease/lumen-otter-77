package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.firebase.FirestoreException
import com.justdataplease.boringtravel.firebase.FirestoreRest
import com.justdataplease.boringtravel.firebase.FsQuery
import com.justdataplease.boringtravel.firebase.FsTime
import com.justdataplease.boringtravel.firebase.FsWrites
import com.justdataplease.boringtravel.firebase.ServerTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * The trips' mailboxes in Firestore ([TripMail]), over its REST API where Android uses the SDK (round 26; the Android
 * app's together/FirestoreTripMail.kt written again): `tripMail/{box}/members/{uid}` = `{member, fileId, at}`, the time
 * the server's. Paths and rules: android-boringtravel/firebase/firestore.rules (a box is read at most
 * [TripMail.MAX_ENTRIES] at a time, as the rules want; each person writes only their own entry). [uid] is the signed-in
 * Firebase account (the community's).
 *
 * Android watches a box with the SDK's listener (one read per member when the trip opens, then only what changes).
 * The REST API has no listener, so [watch] reads the box once and then asks every [pollMs] for its newest entry only
 * (one document): a new member, or a member's copy at a new address, makes it read the box again.
 */
class FirestoreTripMail(
    private val fs: FirestoreRest,
    override val uid: StateFlow<String?>,
    private val pollMs: Long = Together.OPEN_EVERY_MS,
) : MailService {
    private fun box(box: String) = "${TripMail.COLLECTION}/$box"

    override suspend fun post(box: String, entry: MailEntry) {
        fs.commit(listOf(FsWrites.set(fs.name("${box(box)}/${TripMail.MEMBERS}/${entry.uid}"), mapOf("member" to entry.member, "fileId" to entry.fileId, "at" to ServerTime))))
    }

    override suspend fun remove(box: String) {
        val me = uid.value ?: return
        fs.commit(listOf(FsWrites.delete(fs.name("${box(box)}/${TripMail.MEMBERS}/$me"))))
    }

    override suspend fun read(box: String): List<MailEntry> =
        fs.query(box(box), FsQuery(TripMail.MEMBERS, limit = TripMail.MAX_ENTRIES)).mapNotNull { TripMail.parse(it.id, it.fields["member"], it.fields["fileId"]) }

    /** The box's newest entry: who posted it and when (null: an empty box). */
    private suspend fun newest(box: String): Pair<String, Long>? =
        fs.query(box(box), FsQuery(TripMail.MEMBERS, orders = listOf(FsQuery.Order("at", descending = true)), limit = 1)).firstOrNull()
            ?.let { it.id to ((it.fields["at"] as? FsTime)?.ms ?: 0L) }

    override fun watch(box: String): Flow<List<MailEntry>> = flow {
        emit(read(box))
        var last = newest(box)
        while (true) {
            delay(pollMs)
            val now = try {
                newest(box)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirestoreException) {
                // Refused (the rules, the account): the box is left until the trip opens again, as Android's listener.
                if (e.status == "UNAVAILABLE" || e.code >= 500) continue else throw e
            } catch (_: Exception) {
                // No connection for a moment: the SDK's listener reconnects; so does this.
                continue
            }
            if (now != last) {
                last = now
                emit(read(box))
            }
        }
    }
}

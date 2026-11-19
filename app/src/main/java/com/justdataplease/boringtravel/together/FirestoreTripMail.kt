package com.justdataplease.boringtravel.together

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** What the trips' mailboxes need from the app: Firestore, and a Firebase sign-in with the app's Google account. */
class MailAccess(
    val service: MailService,
    /** Signs in to Firebase over [activity]; [quiet] never shows Google's sheet. True when signed in. */
    val signIn: suspend (activity: Context, quiet: Boolean) -> Boolean,
)

/**
 * The trips' mailboxes in Firestore ([TripMail]): `tripMail/{box}/members/{uid}` = `{member, fileId, at}`. Paths and
 * rules: android-boringtravel/firebase. Reads are bounded to [TripMail.MAX_ENTRIES], as the rules want.
 */
class FirestoreTripMail(private val db: FirebaseFirestore, override val uid: StateFlow<String?>) : MailService {
    private fun members(box: String) = db.collection(TripMail.COLLECTION).document(box).collection(TripMail.MEMBERS)

    override suspend fun post(box: String, entry: MailEntry) {
        members(box).document(entry.uid)
            .set(mapOf("member" to entry.member, "fileId" to entry.fileId, "at" to FieldValue.serverTimestamp()))
            .await()
    }

    override suspend fun remove(box: String) {
        val me = uid.value ?: return
        members(box).document(me).delete().await()
    }

    override suspend fun read(box: String): List<MailEntry> =
        entries(members(box).limit(TripMail.MAX_ENTRIES.toLong()).get().await())

    override fun watch(box: String): Flow<List<MailEntry>> = callbackFlow {
        val registration = members(box).limit(TripMail.MAX_ENTRIES.toLong()).addSnapshotListener { snap, error ->
            when {
                error != null -> close(error)
                snap != null -> trySend(entries(snap))
            }
        }
        awaitClose { registration.remove() }
    }

    private fun entries(snap: QuerySnapshot): List<MailEntry> =
        snap.documents.mapNotNull { TripMail.parse(it.id, it.get("member"), it.get("fileId")) }
}

/**
 * The mailboxes over the community's Firebase (the same project and the same Google sign-in), or null: no Firebase in
 * this build, or a debug build on the community emulators (shared trips need the real Drive, so they keep to links).
 * The signed-in user comes straight from Firebase Auth: the community itself is made only to sign in, so a shared trip
 * never has it read the profile at start.
 */
fun firestoreMail(graph: AppGraph): MailAccess? {
    if (BuildConfig.DEBUG && BuildConfig.COMMUNITY_EMULATOR_HOST.isNotBlank()) return null
    val firebase = FirebaseSetup.app(graph.app) ?: return null
    val auth = FirebaseAuth.getInstance(firebase)
    val uid = MutableStateFlow(auth.currentUser?.uid)
    auth.addAuthStateListener { uid.value = it.currentUser?.uid }
    return MailAccess(FirestoreTripMail(FirebaseFirestore.getInstance(firebase), uid.asStateFlow())) { activity, quiet ->
        graph.community.signIn(activity, quiet) == null
    }
}

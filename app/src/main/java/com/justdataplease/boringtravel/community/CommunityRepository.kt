package com.justdataplease.boringtravel.community

import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** The signed-in community user (Firebase Auth, signed in with the Google account). */
data class CommunityUser(val uid: String, val email: String? = null, val name: String? = null)

/**
 * The community's words and numbers: profiles, usernames, posts, likes, reports and blocks. [FirestoreCommunity] is
 * the real one; tests use a fake. Every call needs a signed-in [user] (the rules let nobody else read or write).
 */
interface CommunityRepository {
    val user: StateFlow<CommunityUser?>

    suspend fun profile(uid: String): Profile?

    /** Who holds [username] (its uid), or null when it is free. */
    suspend fun usernameOwner(username: String): String?

    /**
     * Creates or changes the signed-in user's profile. A new [username] is claimed (it fails with
     * [UsernameTakenException] when someone has it) and the old one let go; their posts show the new one (any post
     * still under an older name is fixed on every save, and a failure there never fails the save).
     */
    suspend fun saveProfile(username: String, name: String, bio: String, avatar: String?): Profile

    /** Newest posts first. [after] is the previous page's [Page.next]. */
    suspend fun latest(after: Any?, limit: Int = CommunityLimits.PAGE): Page<Post>

    /** Most liked first: posted in [month] ("2026-11") for [TopRange.Month], or of all time. */
    suspend fun top(range: TopRange, month: String, after: Any?, limit: Int = CommunityLimits.PAGE): Page<Post>

    /** Someone's posts, newest first. */
    suspend fun postsBy(uid: String, limit: Int = 100): List<Post>

    suspend fun post(id: String): Post?

    /** The trip JSON beside post [postId] (see [SharedTrips]), or null when it has none. */
    suspend fun trip(postId: String): String?

    /** A fresh post id, made before uploading so a retry never posts twice. */
    fun newPostId(): String

    /** Saves [post] (its id from [newPostId]) with its trip; likes start at 0 and the time is the server's. */
    suspend fun createPost(post: Post, tripJson: String?): Post

    /**
     * The poster's [post] again with what its trip holds now (since 1.19.0): countries, days, km, first month,
     * pictures, a Drive folder when it had none, and the trip beside it ([tripJson]); the words, likes, posting month
     * and times stay as they are.
     */
    suspend fun updatePost(post: Post, tripJson: String?): Post

    /**
     * Deletes the signed-in user's [post], its trip and its likes, reading from the server (offline it fails at once).
     * Its Drive folder is the caller's to delete.
     */
    suspend fun deletePost(post: Post)

    /**
     * Whether the signed-in user liked each of [postIds]. An id is left out when its look-up failed (offline): the
     * heart is then not known yet, and asked again later.
     */
    suspend fun liked(postIds: Collection<String>): Map<String, Boolean>

    /** Likes or unlikes [postId]: the like and the post's count change together (the rules check both). */
    suspend fun setLiked(postId: String, liked: Boolean)

    /** Reports a post ([postId]) or a profile (null) of user [target]. */
    suspend fun report(postId: String?, target: String, reason: ReportReason)

    suspend fun blocked(): List<BlockedUser>

    /** Blocks [user]; the follows between the two end too, both ways (see [Follows]). */
    suspend fun block(user: BlockedUser)
    suspend fun unblock(uid: String)

    /** Whether the signed-in user follows [uid]. */
    suspend fun isFollowing(uid: String): Boolean

    /**
     * Follows [uid] (whose username is [username] now) as [myUsername]: their followers list and the signed-in user's
     * following list change together (the rules check both, and both usernames against the profiles).
     */
    suspend fun follow(uid: String, username: String, myUsername: String)

    /** Stops following [uid]: both lists change together. */
    suspend fun unfollow(uid: String)

    /** How many follow [uid] and how many [uid] follows, counted by the server (offline it fails). */
    suspend fun followCounts(uid: String): FollowCounts

    /** Who follows [uid], newest first. [after] is the previous page's [Page.next]. */
    suspend fun followers(uid: String, after: Any?, limit: Int = Follows.PAGE): Page<FollowLink>

    /** Whom [uid] follows, newest first. */
    suspend fun following(uid: String, after: Any?, limit: Int = Follows.PAGE): Page<FollowLink>

    /** Posts of [uids] (1 to [Follows.IN_LIMIT] of them), newest first. */
    suspend fun postsOf(uids: List<String>, after: Any?, limit: Int = CommunityLimits.PAGE): Page<Post>

    /**
     * Deletes everything of the signed-in user: the likes they gave, their blocks, their follows both ways, their posts
     * (trips and likes too), their profile and username. Reads from the server only, so offline it fails before
     * deleting anything. Returns their posts' Drive folders, for the caller to delete.
     */
    suspend fun deleteAll(): List<String>
}

/** The community can't be reached in this build (no Firebase project in it). */
class CommunityUnavailableException : IllegalStateException("The community isn't available in this build.")

class NotSignedInException : IllegalStateException("Sign in with Google to use the community.")

class NoProfileException : IllegalStateException("Make your community profile first.")

class UsernameTakenException(val username: String) : IllegalStateException("@$username is taken. Try another.")

/** The server said no (rules): usually an old app or a change someone else made first. */
class CommunityRefusedException(message: String = "The community didn't accept that. Try again.") : IOException(message)

/** No connection, or the server is busy. */
class CommunityOfflineException(message: String = "No internet connection. Check it and try again.") : IOException(message)

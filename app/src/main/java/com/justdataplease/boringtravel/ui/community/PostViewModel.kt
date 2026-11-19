package com.justdataplease.boringtravel.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.clip.PlannedReplay
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.Profile
import com.justdataplease.boringtravel.community.ReportReason
import com.justdataplease.boringtravel.community.SharedItem
import com.justdataplease.boringtravel.community.SharedTrip
import com.justdataplease.boringtravel.community.SharedTrips
import com.justdataplease.boringtravel.community.TripCopy
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.ui.map.MapData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** A clip on its way to the screen: downloaded once from the poster's Drive into the cache, then played. */
sealed interface ClipLoad {
    data object Idle : ClipLoad
    data object Loading : ClipLoad
    data class Ready(val file: File) : ClipLoad
    data class Failed(val message: String) : ClipLoad
}

/** One plan item as the post page lists it: its day number (0 = an idea), the shared item and the same as a plan row. */
data class SharedRow(val day: Int, val shared: SharedItem, val item: PlanItem)

data class PostUi(
    val post: Post? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val error: String? = null,
    val author: Profile? = null,
    val trip: SharedTrip? = null,
    /** The plan's items in order, paired with their plan-row form (kind badges and place photos). */
    val rows: List<SharedRow> = emptyList(),
    /** The trip's planned places on a map (read-only), when it has any. */
    val map: MapData? = null,
    /** The trip couldn't be read (an app too old for it, or damaged). */
    val tripMissing: Boolean = false,
)

/** One post: the post, its author, its trip (plan, notes, map) and its clip; Copy this trip, report, block, delete. */
class PostViewModel(private val graph: AppGraph, private val postId: String) : ViewModel() {
    private val community = graph.community
    private val _state = MutableStateFlow(PostUi())
    val state: StateFlow<PostUi> = _state.asStateFlow()
    private val _clip = MutableStateFlow<ClipLoad>(ClipLoad.Idle)
    val clip: StateFlow<ClipLoad> = _clip.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val post = community.repo.post(postId)
                if (post == null) {
                    _state.value = PostUi(loading = false, gone = true)
                    return@launch
                }
                _state.update { it.copy(post = post, loading = false) }
                community.seedLikes(listOf(post))
                val author = runCatching { community.repo.profile(post.uid) }.getOrNull()
                _state.update { it.copy(author = author) }
                if (post.hasTrip) {
                    val json = community.repo.trip(post.id)
                    val shown = withContext(Dispatchers.Default) {
                        SharedTrips.decode(json)?.let { t ->
                            // The plan as plan rows once: the list's kind badges and the map both use it.
                            val built = TripCopy.build(t, post.title, post.id, ROW_START, now = 0)
                            Triple(t, t.items.zip(built.items) { s, i -> SharedRow(s.day, s, i) }, planMap(built))
                        }
                    }
                    _state.update {
                        it.copy(trip = shown?.first, rows = shown?.second.orEmpty(), map = shown?.third, tripMissing = shown == null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = communityMessage(e)) }
            }
        }
    }

    /** The planned places and the dashed route through them, as the trip map draws a plan. */
    private fun planMap(built: TripCopy.Built): MapData? {
        val plans = MapData.plans(built.items)
        if (plans.isEmpty()) return null
        val planned = runCatching { PlannedReplay.build(built.trip, built.items).waypoints.map { GeoPoint(it.lat, it.lng) } }.getOrDefault(emptyList())
        return MapData(plans = plans, planned = planned)
    }

    fun loadClip() {
        val id = _state.value.post?.clipFileId ?: return
        if (_clip.value is ClipLoad.Loading || _clip.value is ClipLoad.Ready) return
        _clip.value = ClipLoad.Loading
        viewModelScope.launch {
            _clip.value = try {
                ClipLoad.Ready(community.clipFile(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClipLoad.Failed(communityMessage(e, "Couldn't load the clip. Try again."))
            }
        }
    }

    fun clipShown() {
        if (_clip.value is ClipLoad.Ready) _clip.value = ClipLoad.Idle
    }

    /** The plan as a trip of the viewer's own, starting [start] (null for a plan of ideas). Returns its id. */
    suspend fun copyTrip(start: LocalDate?): String {
        val post = _state.value.post ?: error("No post")
        val trip = _state.value.trip ?: error("No trip")
        val built = TripCopy.build(trip, post.title, post.id, start, now())
        return withContext(NonCancellable) { graph.trips.createWithPlan(built.trip, built.items).id }
    }

    /** True when sent, false while it waits for a connection. */
    suspend fun report(reason: ReportReason): Boolean {
        val post = _state.value.post ?: return true
        return community.report(post.id, post.uid, reason)
    }

    /** True when the server has it, false while it waits for a connection (hidden at once either way). */
    suspend fun block(): Boolean {
        val post = _state.value.post ?: return true
        return community.block(post.uid, post.username)
    }

    suspend fun unblock() {
        val post = _state.value.post ?: return
        community.unblock(post.uid)
    }

    /** Deletes the viewer's own post; false when its Drive folder stayed (Drive not connected on this phone). */
    suspend fun delete(): Boolean {
        val post = _state.value.post ?: return true
        return withContext(NonCancellable) { community.deletePost(post) }
    }

    private companion object {
        /** Any fixed day: the plan rows only need their day numbers apart. */
        val ROW_START: LocalDate = LocalDate.of(2000, 1, 1)
    }
}

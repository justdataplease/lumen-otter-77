package com.justdataplease.boringtravel.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.community.Post
import com.justdataplease.boringtravel.community.PostDraft
import com.justdataplease.boringtravel.community.PostDrafts
import com.justdataplease.boringtravel.community.PostKind
import com.justdataplease.boringtravel.community.PostSource
import com.justdataplease.boringtravel.community.PostSyncs
import com.justdataplease.boringtravel.community.postSource
import com.justdataplease.boringtravel.community.UploadProgress
import com.justdataplease.boringtravel.community.UploadState
import com.justdataplease.boringtravel.data.SavedClips
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.ui.nav.CommunityCompose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Posting, as the composer shows it. */
sealed interface UploadUi {
    data object Idle : UploadUi
    data class Running(val progress: UploadProgress) : UploadUi
    data class Failed(val message: String) : UploadUi
}

/**
 * Post to community from one trip: what is on the phone ([PostSource]), what the traveller picks ([PostDraft]) and the
 * upload, which keeps where it got to so Try again carries on. Leaving before it is posted deletes what went to Drive.
 */
class ComposeViewModel(private val graph: AppGraph, private val route: CommunityCompose) : ViewModel() {
    private val community = graph.community
    val clips: SavedClips = SavedClips.of(graph.app)

    /** Made before uploading, so a retry never posts twice; the preview's drawn picture uses it too. */
    val postId: String = runCatching { community.repo.newPostId() }.getOrElse { "draft" }

    private val _source = MutableStateFlow<PostSource?>(null)
    val source: StateFlow<PostSource?> = _source.asStateFlow()
    private val _draft = MutableStateFlow<PostDraft?>(null)
    val draft: StateFlow<PostDraft?> = _draft.asStateFlow()
    private val _missing = MutableStateFlow(false)
    /** The trip isn't on this phone (deleted). */
    val missing: StateFlow<Boolean> = _missing.asStateFlow()
    private val _upload = MutableStateFlow<UploadUi>(UploadUi.Idle)
    val upload: StateFlow<UploadUi> = _upload.asStateFlow()

    private val state = UploadState()
    private var job: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val source = postSource(graph, route.tripId)
        if (source == null) {
            _missing.value = true
            return
        }
        _source.value = source
        _draft.value = PostDrafts.start(source, route.kind, route.clipId, route.mediaIds?.split(',')?.filter { it.isNotBlank() }.orEmpty())
    }

    /** Changes the draft (not while it uploads). */
    fun edit(change: (PostSource, PostDraft) -> PostDraft) {
        val s = _source.value ?: return
        val d = _draft.value ?: return
        if (_upload.value is UploadUi.Running) return
        _draft.value = change(s, d)
    }

    /** Why it can't be posted yet (the draft only; sign-in, profile and Drive are the screen's to say). */
    fun problem(s: PostSource, d: PostDraft): String? = PostDrafts.problem(s, d) { clips.video(it) }

    /** Posts; [onPosted] gets the post once it is in the community. */
    fun post(onPosted: (Post) -> Unit) {
        val s = _source.value ?: return
        val d = _draft.value ?: return
        if (_upload.value is UploadUi.Running || problem(s, d) != null) return
        val plan = PostDrafts.plan(postId, s, d) { clips.video(it).takeIf { f -> f.isFile } }
        _upload.value = UploadUi.Running(UploadProgress("Getting ready", 0, 1))
        job = viewModelScope.launch {
            try {
                val post = community.post(plan, state) { p -> _upload.value = UploadUi.Running(p) }
                // A trip post remembers its trip, so the trip's ⋮ › Community post can update it (only when asked).
                if (d.kind == PostKind.Trip) graph.postSync.linked(PostSyncs.link(route.tripId, post, d, s, state, plan, now()))
                _upload.value = UploadUi.Idle
                onPosted(post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _upload.value = UploadUi.Failed(communityMessage(e, "Couldn't post. Try again."))
            }
        }
    }

    /** Stops an upload on its way; what went to Drive is deleted. */
    fun stop() {
        job?.cancel()
        job = null
        _upload.value = UploadUi.Idle
        discard()
    }

    private fun discard() {
        if (state.posted != null || state.folderId == null) return
        graph.scope.launch { runCatching { community.discard(state) } }
    }

    override fun onCleared() {
        job?.cancel()
        discard()
    }
}

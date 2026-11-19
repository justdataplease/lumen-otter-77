package com.justdataplease.boringtravel.community

/**
 * A post's link, for Share on the post page: `https://justdataplease.com/apps/boringtravel/post/#<postId>`. The id sits
 * after `#`, so the website (one page that opens the app or points to it) never receives it; the app, opened with the
 * link, shows the post. Pure (CommunityLinksTest).
 */
object CommunityLinks {
    const val WEB = "https://justdataplease.com/apps/boringtravel/post/"

    private val FOUND = Regex("""https://(?:www\.)?justdataplease\.com/apps/boringtravel/post/?[#?](?:id=)?([A-Za-z0-9_-]{1,64})""")

    fun url(postId: String): String {
        require(Post.isId(postId)) { "Invalid post id" }
        return "$WEB#$postId"
    }

    /** The post id of the first post link in [text] (a link opened with the app, a pasted line), or null. */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return FOUND.find(text)?.groupValues?.get(1)
    }

    /** What Share sends: "Patagonia · @alexa on BoringTravel" and the link. */
    fun shareText(post: Post): String = "${post.title} · @${post.username} on BoringTravel\n${url(post.id)}"
}

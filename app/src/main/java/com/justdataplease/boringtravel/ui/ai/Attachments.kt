package com.justdataplease.boringtravel.ui.ai

import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.PictureReading
import com.justdataplease.boringtravel.links.LinkUrls

/**
 * What the traveller attached to the message they are writing (owner, 18 Nov 2026: "pictures, link, everything as an
 * attachment … actually just append to the context of the message"): up to [PictureReading.MAX_PICTURES] pictures
 * (content addresses from the photo picker or a share) and up to [MAX_LINKS] links. On Send, [message] adds the links
 * and what the pictures say to the words typed; the chat shows the words, the pictures' count and the links' [domains].
 * Pure (AttachmentsTest).
 */
data class Attachments(val pictures: List<String> = emptyList(), val links: List<String> = emptyList()) {
    val isEmpty: Boolean get() = pictures.isEmpty() && links.isEmpty()

    /** [added] after the pictures already here, each once, up to [PictureReading.MAX_PICTURES]. */
    fun withPictures(added: List<String>): Attachments = copy(pictures = (pictures + added).distinct().take(PictureReading.MAX_PICTURES))

    /** [link] (already made safe, see [link]) at the end, once; past [MAX_LINKS] it isn't added. */
    fun withLink(link: String): Attachments = if (link in links || links.size >= MAX_LINKS) this else copy(links = links + link)

    fun withoutPicture(picture: String): Attachments = copy(pictures = pictures - picture)
    fun withoutLink(link: String): Attachments = copy(links = links - link)

    /** The links as a sent message shows them: their domains ("maps.app.goo.gl"). */
    val domains: List<String> get() = links.map(LinkUrls::domain)

    /**
     * The message the planner reads: [typed], then the links one per line, then [read] (what the pictures say, as
     * [PictureReading.message] writes it; null before they are read), at most [ItineraryImport.MAX_CHARS].
     */
    fun message(typed: String, read: String?): String =
        listOfNotNull(typed.trim().ifEmpty { null }, links.joinToString("\n").ifEmpty { null }, read?.trim()?.ifEmpty { null })
            .joinToString("\n\n").take(ItineraryImport.MAX_CHARS)

    companion object {
        const val MAX_LINKS = 5

        /** The link in what was typed or pasted in Add a link: its first web address, made safe; null when there is none. */
        fun link(input: String): String? = LinkUrls.normalize(input)
    }
}

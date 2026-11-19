package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.model.PrintTheme
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/*
 * A trip laid out as a printed book: fixed pages, each made from a template, with the book's own copies of the
 * trip's texts and photo choices. Editing the book never changes the journal. Pure data, unit-tested on the JVM.
 *
 * Stored as JSON in `books.pages` ([BookJson]); every object keeps the fields this version doesn't know in `extra`,
 * so a book saved by a newer app survives a round trip through an older one.
 */

/** Trim sizes the book can be printed at. */
enum class BookFormat(val id: String, val label: String, val widthMm: Double, val heightMm: Double) {
    A5("a5", "A5", 148.0, 210.0),
    Square("sq21", "21 × 21 cm", 210.0, 210.0),
    A4("a4", "A4", 210.0, 297.0),
    Letter("letter", "US Letter", 215.9, 279.4);

    val aspect: Double get() = widthMm / heightMm

    /** "148 × 210 mm". */
    val sizeText: String get() = "${mm(widthMm)} × ${mm(heightMm)} mm"

    companion object {
        fun of(id: String?): BookFormat = entries.firstOrNull { it.id == id } ?: A5

        private fun mm(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)
    }
}

/** What the paper looks like: dots (Notebook), quiet rules (Notebook 2) or plain. Notes pages use it in every theme. */
enum class BookPaper(val id: String, val label: String) {
    Dots("dots", "Dotted"),
    Lines("lines", "Ruled"),
    Plain("plain", "Plain");

    companion object {
        fun of(id: String?): BookPaper = entries.firstOrNull { it.id == id } ?: Dots
    }
}

/**
 * Page templates. [photos] is how many photo slots it has, [text] whether it carries flowing text. Stored by [id];
 * an id this version doesn't know is kept and printed as a blank page. An [Album] page's arrangement is its own
 * ([BookPage.album], one of [AlbumLayouts]), and so is its number of photos ([BookPage.spots]).
 */
enum class Template(val id: String, val label: String, val photos: Int, val text: Boolean) {
    Cover("cover", "Cover", 1, false),
    TitlePage("title", "Title page", 0, false),
    DayOpener("day", "Day opener", 0, false),
    PhotoFull("photo-full", "Full-page photo", 1, false),
    PhotoFullCaption("photo-full-cap", "Full page with caption", 1, false),
    Photo("photo", "Photo and caption", 1, false),
    PhotoTwo("photo-2", "Two photos", 2, false),
    PhotoTwoSide("photo-2s", "Two side by side", 2, false),
    PhotoThree("photo-3", "Three photos", 3, false),
    PhotoThreeUp("photo-3up", "Three up", 3, false),
    PhotoFour("photo-4", "Grid of four", 4, false),
    PhotoText("photo-text", "Photo and text", 1, true),
    PhotoTextSide("photo-col", "Photo and text column", 1, true),
    Text("text", "Text", 0, true),
    Album("album", "Photo album", 0, false),
    Map("map", "Map", 0, false),
    Stats("stats", "Trip in numbers", 0, false),
    Notes("notes", "Notes page", 0, false),
    BackCover("back", "Back cover", 0, false);

    companion object {
        fun of(id: String?): Template? = entries.firstOrNull { it.id == id }

        /** What a page can be switched to in the editor (the cover and back cover stay what they are). */
        val choosable: List<Template> = listOf(
            PhotoFull, PhotoFullCaption, Photo, PhotoTwo, PhotoTwoSide, PhotoThree, PhotoThreeUp, PhotoFour,
            PhotoText, PhotoTextSide, Text, DayOpener, Map, Stats, Notes, TitlePage,
        )
    }
}

/**
 * Pan and zoom of a photo inside its frame: [zoom] ≥ 1 (1 = the whole photo that fits the frame's shape), [cx]/[cy]
 * the centre of what shows, as fractions of the photo. See [CropMath].
 */
data class Crop(val zoom: Double = 1.0, val cx: Double = 0.5, val cy: Double = 0.5) {
    val isDefault: Boolean get() = zoom == 1.0 && cx == 0.5 && cy == 0.5
}

/** One photo spot on a page. [tilt] null = the theme's own angle; [caption] is the book's copy. */
data class Slot(
    val mediaId: String? = null,
    val crop: Crop = Crop(),
    val tilt: Double? = null,
    val caption: String = "",
    val extra: JsonObject = EMPTY,
)

/** Kinds of text in a page's flow. Kept as strings, so a kind from a newer version prints as a paragraph. */
object ParaKind {
    const val P = "p"
    const val HEADING = "h"
    const val QUOTE = "q"
    const val BULLET = "ul"
    const val NUMBER = "ol"
    const val CHECK = "check"
    const val DONE = "done"
    const val PLACE = "place"

    /** An entry's title (display face) and its time · place line (mono). */
    const val TITLE = "title"
    const val META = "meta"
}

/**
 * A paragraph of text in the book: [html] is the small sanitized subset journal text uses (escaped plain text for
 * titles and lines). [entry] is the journal entry it came from (hide/show, editing); [cont] marks the second half of
 * a paragraph split across pages (no indent; joined again when the text is laid out anew).
 */
data class Para(
    val kind: String,
    val html: String,
    val entry: String? = null,
    val cont: Boolean = false,
    val extra: JsonObject = EMPTY,
)

/**
 * One page. [templateId] picks the [Template]; [slots] are its photos in order; [title]/[subtitle] are the cover's,
 * title page's, day opener's or map's own words; [paras] the flowing text (text templates); [entryId] the entry a
 * photo or text page belongs to; [day] the trip day it is about; [start] marks the first page of a run of text pages
 * (a continuation page has `start = false` and takes the overflow of the pages before it).
 *
 * [els] are elements placed by hand, bottom to top. On a template page they sit on top of the template; a [free] page
 * is drawn from [els] alone (its template's own layout was turned into elements when it was first changed by hand,
 * and [slots] then only keeps photos the template had no spot for). [bg] is under everything (null = the paper).
 * [album] is an album page's arrangement ([AlbumLayouts] id); photos beyond its spots stay in [slots], unseen, until
 * the page gets more spots or they go to a page of their own.
 */
data class BookPage(
    val id: String,
    val templateId: String,
    val slots: List<Slot> = emptyList(),
    val title: String? = null,
    val subtitle: String? = null,
    val paras: List<Para> = emptyList(),
    val entryId: String? = null,
    val day: LocalDate? = null,
    val start: Boolean = true,
    val free: Boolean = false,
    val els: List<FreeEl> = emptyList(),
    val bg: PageBg? = null,
    val album: String? = null,
    val extra: JsonObject = EMPTY,
) {
    val template: Template? get() = Template.of(templateId)

    /** An album page's arrangement; an id this version doesn't know falls back to the usual one for its photos. */
    val albumLayout: AlbumLayout?
        get() = if (template != Template.Album) null
        else AlbumLayouts.of(album) ?: AlbumLayouts.fitting(slots.count { it.mediaId != null }.coerceIn(1, AlbumLayouts.max))

    /** How many photo spots the page's layout has. */
    val spots: Int get() = albumLayout?.count ?: template?.photos ?: 0

    fun el(id: String): FreeEl? = els.firstOrNull { it.id == id }

    /** Every photo on the page: the template's spots, the placed photos and the background. */
    val mediaIds: List<String>
        get() = (slots.mapNotNull { it.mediaId } + els.mapNotNull { it.photo?.mediaId } + listOfNotNull(bg?.photo?.mediaId)).distinct()

    /** The photo in slot [i], or an empty slot. */
    fun slot(i: Int): Slot = slots.getOrNull(i) ?: Slot()

    fun withSlot(i: Int, slot: Slot): BookPage {
        val list = slots.toMutableList()
        while (list.size <= i) list += Slot()
        list[i] = slot
        return copy(slots = list)
    }
}

/** The pages plus the choices stored with them. Format, theme, fonts and bleed live in their own columns ([BookSpec]). */
data class BookDoc(
    val pages: List<BookPage> = emptyList(),
    val paper: BookPaper = BookPaper.Dots,
    val padTo4: Boolean = true,
    /** Journal entries left out of the book (their pages and text are kept, so showing them again restores them). */
    val hidden: Set<String> = emptySet(),
    val extra: JsonObject = EMPTY,
) {
    fun page(id: String): BookPage? = pages.firstOrNull { it.id == id }
    fun indexOf(id: String): Int = pages.indexOfFirst { it.id == id }

    companion object {
        /** 2: pages can carry elements placed by hand ([BookPage.els], [BookPage.free]) and a background. */
        const val VERSION = 2
    }
}

/** Everything that decides how the pages print: trim size, theme, fonts, paper, bleed. */
data class BookSpec(
    val format: BookFormat = BookFormat.A5,
    val theme: PrintTheme = PrintTheme.Notebook,
    val fonts: BookFonts = BookFonts.Theme,
    val paper: BookPaper = BookPaper.Dots,
    val bleed: Boolean = false,
) {
    /** The fonts actually used ("Theme's own" resolved). */
    val faces: BookFonts get() = fonts.resolve(theme)
}

internal val EMPTY = JsonObject(emptyMap())

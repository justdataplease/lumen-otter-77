package com.justdataplease.boringtravel.doc

import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.album.AlbumLayouts

/**
 * Photo layouts in the journal ([Block.Album]): which arrangement a set of photos gets, how it is drawn, and the
 * edits the editor's layout sheet makes. Pure; each edit returns a new block.
 */
object PhotoAlbums {
    /** Space between two photos of a layout, as a fraction of the column's width (print and the app alike). */
    const val GUTTER = 0.018

    /** One arrangement drawn across the column: [spots] in its order (null: an empty spot), from photo [first] on. */
    data class Grid(val layout: AlbumLayout, val spots: List<String?>, val first: Int)

    /**
     * The arrangement [b] is drawn in: its own when this version knows it and it has a spot for every photo, else the
     * usual one for exactly that many photos, else the smallest group that holds them all. Null when no single
     * arrangement holds them (more photos than [AlbumLayouts.max]).
     */
    fun layoutOf(b: Block.Album): AlbumLayout? {
        val n = b.mediaIds.size.coerceAtLeast(1)
        return AlbumLayouts.of(b.layoutId)?.takeIf { it.count >= n } ?: AlbumLayouts.exact(n) ?: AlbumLayouts.fitting(n)
    }

    /**
     * What to draw for [b], top to bottom: its arrangement with the photos in their spots (and the empty spots after
     * them), or, when no one arrangement holds them all, full arrangements one under the other ([AlbumLayouts.split]).
     */
    fun grids(b: Block.Album): List<Grid> {
        val ids = b.mediaIds
        if (ids.isEmpty()) return emptyList()
        layoutOf(b)?.let { l -> return listOf(Grid(l, ids + List(l.count - ids.size) { null }, 0)) }
        var first = 0
        return AlbumLayouts.split(ids.size, AlbumLayouts.of(b.layoutId)).map { l ->
            Grid(l, ids.subList(first, first + l.count), first).also { first += l.count }
        }
    }

    /** The arrangements the layout sheet offers for [b]: the group of the smallest size that holds its photos. */
    fun choices(b: Block.Album): List<AlbumLayout> =
        AlbumLayouts.counts.firstOrNull { it >= b.mediaIds.size }?.let(AlbumLayouts::forCount).orEmpty()

    /**
     * How [n] photos picked together go into the text: one stays a single photo; more fill photo layouts, each the
     * usual arrangement for exactly its photos, the biggest first ([AlbumLayouts.split]: 5 → 4 + 1, 12 → 9 + 3).
     */
    fun pickGroups(n: Int): List<Int> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(1)
        else -> AlbumLayouts.split(n).map { it.count }
    }

    /** The usual arrangement for a group of [n] photos from [pickGroups] (n ≥ 2). */
    fun usual(n: Int): AlbumLayout = AlbumLayouts.exact(n) ?: AlbumLayouts.fitting(n) ?: AlbumLayouts.all.last()

    /** [b] in arrangement [layoutId], when it has a spot for every photo; else unchanged. */
    fun withLayout(b: Block.Album, layoutId: String): Block.Album {
        val l = AlbumLayouts.of(layoutId) ?: return b
        return if (l.count >= b.mediaIds.size) b.copy(layoutId = l.id) else b
    }

    /** What [add] did: the layout with the photos it took, and the photos it had no room for. */
    data class Added(val album: Block.Album, val rest: List<String>)

    /** The frame most of [b]'s photos have (the earliest of them when it's a tie): what a photo added to it gets. */
    fun mainFrame(b: Block.Album): PhotoFrame {
        val frames = b.mediaIds.map(b::frameOf)
        return frames.distinct().maxByOrNull { f -> frames.count { it == f } } ?: PhotoFrame.None
    }

    /**
     * [ids] after [b]'s photos: they fill its empty spots, and when there are more the layout grows to the usual
     * arrangement of the next size that holds them all. Past [AlbumLayouts.max] photos the rest are left over. The new
     * ones are framed [frame] (as most of the layout is).
     */
    fun add(b: Block.Album, ids: List<String>, frame: PhotoFrame = mainFrame(b)): Added {
        val now = b.mediaIds.size
        val take = ids.take((AlbumLayouts.max - now).coerceAtLeast(0))
        val total = now + take.size
        val current = layoutOf(b)
        val layout = if (current != null && current.count >= total) current else AlbumLayouts.fitting(total) ?: current
        val grown = take.fold(b.copy(layoutId = layout?.id ?: b.layoutId, mediaIds = b.mediaIds + take)) { a, id -> a.withFrame(id, frame) }
        return Added(grown, ids.drop(take.size))
    }

    /**
     * [b] without photo [index]; the others keep their order. The arrangement follows the photos: the usual one for
     * exactly that many when there is one, else the current one with an empty spot more. One photo or none left:
     * the caller turns it into a single photo or removes it.
     */
    fun remove(b: Block.Album, index: Int): Block.Album {
        if (index !in b.mediaIds.indices) return b
        val left = b.mediaIds.filterIndexed { i, _ -> i != index }
        return reflow(b.copy(mediaIds = left, frames = b.frames.filterKeys { it in left }), keep = layoutOf(b))
    }

    /**
     * [b]'s arrangement fitted to its photos after some were left out: [keep] while it holds exactly as many, else
     * the usual one for exactly that many, else [keep] while it holds them all, else the smallest that does.
     */
    fun reflow(b: Block.Album, keep: AlbumLayout? = AlbumLayouts.of(b.layoutId)): Block.Album {
        val n = b.mediaIds.size
        if (n == 0) return b
        val l = keep?.takeIf { it.count == n } ?: AlbumLayouts.exact(n) ?: keep?.takeIf { it.count >= n } ?: AlbumLayouts.fitting(n)
        return if (l == null) b else b.copy(layoutId = l.id)
    }

    /** [b] with photos [i] and [j] changing places (their spots). */
    fun swap(b: Block.Album, i: Int, j: Int): Block.Album {
        val ids = b.mediaIds
        if (i == j || i !in ids.indices || j !in ids.indices) return b
        return b.copy(mediaIds = ids.toMutableList().also { it[i] = ids[j]; it[j] = ids[i] })
    }

    /** [b] with photo [from] moved to position [to], the others shifting along. */
    fun move(b: Block.Album, from: Int, to: Int): Block.Album {
        val ids = b.mediaIds
        if (from !in ids.indices || to !in ids.indices || from == to) return b
        return b.copy(mediaIds = ids.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /**
     * The frame of a single photo that was framed [frame] in a layout (the layout taken apart, or the last one left;
     * also what a version without layouts shows): its own, and rounded for a plain one (how a single photo is placed).
     */
    fun singleFrame(frame: PhotoFrame): PhotoFrame = if (frame == PhotoFrame.None) PhotoFrame.Rounded else frame

    /** The width / height of [layout] across the column, gutters a [GUTTER] of the width (as the app and print draw it). */
    fun aspect(layout: AlbumLayout): Double = 100.0 / layout.heightFor(100.0, 100.0 * GUTTER)
}

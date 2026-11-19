package com.justdataplease.boringtravel.tags

import android.content.Context

// The tag tree itself (TagTree) is the engine's; reading it from the app's assets, once, is Android's.

private object TagTreeCache {
    @Volatile var cached: TagTree? = null
}

/** The tree when it was read already; null before. */
fun TagTree.Companion.peek(): TagTree? = TagTreeCache.cached

/** The app's tree, read once. */
fun TagTree.Companion.load(context: Context): TagTree = TagTreeCache.cached ?: runCatching {
    context.applicationContext.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
}.getOrDefault(TagTree(emptyList())).also { TagTreeCache.cached = it }

package com.justdataplease.boringtravel.compat

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * java.util.Base64 and Kotlin/JVM's synchronized as the Android app's AI files call them (ai/PictureReading.kt,
 * ai/own/OwnKeyStore.kt, ai/own/OwnKeyApi.kt), in plain Kotlin under the same names (see JavaTime.kt).
 */

/** java.util.Base64: the basic alphabet, padded when written, padding optional when read (as Java's decoder). */
@OptIn(ExperimentalEncodingApi::class)
object Base64 {
    class Encoder internal constructor() {
        fun encodeToString(src: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(src)
    }

    /** Throws IllegalArgumentException for text that isn't Base64, as Java's does. */
    class Decoder internal constructor() {
        fun decode(src: String): ByteArray =
            kotlin.io.encoding.Base64.Default.withPadding(kotlin.io.encoding.Base64.PaddingOption.PRESENT_OPTIONAL).decode(src)
    }

    private val encoder = Encoder()
    private val decoder = Decoder()

    fun getEncoder(): Encoder = encoder

    fun getDecoder(): Decoder = decoder
}

@OptIn(ExperimentalAtomicApi::class)
private val held = AtomicInt(0)

/**
 * Kotlin/JVM's synchronized for the few short sections the AI files guard (a remembered model list, a schema made
 * once): one lock for all of them, never held across a suspension, not re-entrant (none of them nests).
 */
@OptIn(ExperimentalAtomicApi::class)
fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R {
    while (!held.compareAndSet(0, 1)) Unit
    try {
        return block()
    } finally {
        held.store(0)
    }
}

/**
 * java.util.Collections.synchronizedList, as Copy from a link gathers a post's pictures while several download at once:
 * each change under [synchronized]'s lock.
 */
object Collections {
    fun <T> synchronizedList(list: MutableList<T>): MutableList<T> = LockedList(list)

    private class LockedList<T>(private val list: MutableList<T>) : AbstractMutableList<T>() {
        override val size: Int get() = synchronized(this) { list.size }
        override fun get(index: Int): T = synchronized(this) { list[index] }
        override fun set(index: Int, element: T): T = synchronized(this) { list.set(index, element) }
        override fun add(index: Int, element: T) = synchronized(this) { list.add(index, element) }
        override fun removeAt(index: Int): T = synchronized(this) { list.removeAt(index) }
    }
}

/** java.util.concurrent.atomic.AtomicInteger: a counter several coroutines add to. */
@OptIn(ExperimentalAtomicApi::class)
class AtomicInteger(initial: Int = 0) {
    private val value = AtomicInt(initial)

    fun incrementAndGet(): Int = value.addAndFetch(1)

    fun get(): Int = value.load()
}

/** java.security.MessageDigest for SHA-256 ([com.justdataplease.boringtravel.ios.Sha256]). */
class MessageDigest private constructor() {
    private var sum = com.justdataplease.boringtravel.ios.Sha256()

    fun update(bytes: ByteArray) = sum.update(bytes)

    fun update(bytes: ByteArray, offset: Int, length: Int) = sum.update(bytes, offset, length)

    /** The digest's bytes; the digest starts again, as Java's does. */
    fun digest(): ByteArray {
        val hex = sum.hex()
        sum = com.justdataplease.boringtravel.ios.Sha256()
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        fun getInstance(algorithm: String): MessageDigest {
            require(algorithm == "SHA-256") { "Only SHA-256 here: $algorithm" }
            return MessageDigest()
        }
    }
}

package com.justdataplease.boringtravel.export

/** A Java stream as the stream TripDocx writes here (compat/JavaIo.kt), for the Android app's TripDocxTest. */
fun java.io.OutputStream.asCompat(): com.justdataplease.boringtravel.compat.OutputStream {
    val java = this
    return object : com.justdataplease.boringtravel.compat.OutputStream() {
        override fun write(b: Int) = java.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = java.write(b, off, len)

        override fun flush() = java.flush()
    }
}

package com.justdataplease.boringtravel.clip

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

/**
 * H.264 MP4 from bitmaps, with no audio: MediaCodec encodes from an input Surface that EGL draws into; each frame
 * bitmap is uploaded as a GL texture and drawn as a full-screen quad, and stamped with
 * `eglPresentationTimeANDROID`, so the timing is exact however long a frame took to draw.
 *
 * Blocking: call [encode] on one background thread (the EGL context lives on it) and nothing else.
 */
class Mp4Encoder(
    val width: Int,
    val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = defaultBitRate(width, height, fps),
) {
    /**
     * Draws [frames] frames with [draw] and writes them to [output]. [progress] gets 0..1; [cancelled] is checked
     * before each frame and ends the work with a [CancellationException] (the partial file is deleted).
     */
    fun encode(output: File, frames: Int, draw: (frame: Int, bitmap: Bitmap) -> Unit, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        fun format(high: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // A key frame every 2 s (was 1 s): more of the bits go to detail; seeking stays quick.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
            if (high) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
        val plain = format(high = false)
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(plain)
        val codec = if (codecName != null) MediaCodec.createByCodecName(codecName) else MediaCodec.createEncoderByType(MIME)
        // H.264 High profile (sharper map lines and text at the same bit rate) where the encoder offers it.
        val high = runCatching {
            codec.codecInfo.getCapabilitiesForType(MIME).profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh && it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel41
            }
        }.getOrDefault(false)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var track = -1
        var egl: Egl? = null
        var bitmap: Bitmap? = null
        var ok = false
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            var idleTries = 0
            while (true) {
                if (cancelled()) throw CancellationException("Clip cancelled")
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++idleTries > 500) error("The encoder stopped answering.")
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "The video format changed twice." }
                        val m = requireNotNull(muxer)
                        track = m.addTrack(codec.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        idleTries = 0
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted) { "Video data arrived before its format." }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            requireNotNull(muxer).writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            output.parentFile?.mkdirs()
            output.delete()
            // Some encoders list High but refuse it with these settings: then the baseline settings, as before.
            if (!high || runCatching { codec.configure(format(high = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }.isFailure) {
                if (high) codec.reset()
                codec.configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            egl = Egl(inputSurface)
            val quad = Quad()
            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = frameBitmap
            val texture = quad.texture(frameBitmap)
            GLES20.glViewport(0, 0, width, height)

            for (f in 0 until frames) {
                if (cancelled()) throw CancellationException("Clip cancelled")
                drain(false)
                draw(f, frameBitmap)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameBitmap)
                quad.draw(texture)
                egl.present(f * 1_000_000_000L / fps)
                progress((f + 1).toFloat() / frames)
            }
            drain(false)
            codec.signalEndOfInputStream()
            drain(true)
            ok = true
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { egl?.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }.onFailure { ok = false }
            runCatching { muxer?.release() }
            bitmap?.recycle()
            if (!ok) output.delete()
        }
    }

    /** EGL on the encoder's input surface (recordable, so the codec accepts it). */
    private class Egl(surface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var window: EGLSurface = EGL14.EGL_NO_SURFACE
        private var released = false

        init {
          try {
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display." }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL didn't start." }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL config." }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "No EGL context." }
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "No EGL surface." }
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "EGL couldn't use the surface." }
          } catch (failure: Throwable) {
            // The caller cannot own this instance until construction succeeds.
            // Release any context/window allocated before a driver initialization failure.
            release()
            throw failure
          }
        }

        fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, window, nanos)
            check(EGL14.eglSwapBuffers(display, window)) { "EGL couldn't hand the frame over." }
        }

        fun release() {
            if (released || display == EGL14.EGL_NO_DISPLAY) return
            released = true
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    /** One textured quad covering the frame; texture rows run top-down like the bitmap. */
    private class Quad {
        private val program: Int
        private val aPosition: Int
        private val aTex: Int
        private val uSampler: Int
        private val vertices: FloatBuffer = floatBuffer(
            // x, y, u, v (v flipped: bitmap row 0 is the top of the picture)
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        init {
            program = link(
                """
                attribute vec4 aPosition;
                attribute vec2 aTex;
                varying vec2 vTex;
                void main() { gl_Position = aPosition; vTex = aTex; }
                """.trimIndent(),
                """
                precision mediump float;
                varying vec2 vTex;
                uniform sampler2D uSampler;
                void main() { gl_FragColor = texture2D(uSampler, vTex); }
                """.trimIndent(),
            )
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        }

        fun texture(bitmap: Bitmap): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return ids[0]
        }

        fun draw(texture: Int) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(uSampler, 0)
            vertices.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aPosition)
            vertices.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTex)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Drawing the frame failed." }
        }

        private fun link(vertex: String, fragment: String): Int {
            val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "GL program: " + GLES20.glGetProgramInfoLog(p) }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }

        private fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "GL shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        const val KEY_FRAME_SECONDS = 2

        /**
         * About 0.26 bits per pixel per frame: 16 Mbps for 1080 × 1920 at 30 fps (was 10), 9 for the square (was 8),
         * within 6–20 Mbps. Maps are fine lines and small labels that move every frame; social apps re-encode, so a
         * clean source matters.
         */
        fun defaultBitRate(width: Int, height: Int, fps: Int = 30): Int =
            (width.toLong() * height * fps * 0.26).toLong().coerceIn(6_000_000L, 20_000_000L).toInt()

        /**
         * The largest size at or below [width] × [height] (same shape, even numbers) the phone's H.264 encoder
         * takes; most take 1080p as is, some want multiples of 16.
         */
        fun supportedSize(width: Int, height: Int): Pair<Int, Int> {
            val caps = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
                    .mapNotNull { it.getCapabilitiesForType(MIME).videoCapabilities }
            }.getOrDefault(emptyList())
            if (caps.isEmpty()) return width to height
            fun ok(w: Int, h: Int) = caps.any { it.isSizeSupported(w, h) }
            if (ok(width, height)) return width to height
            for (scale in listOf(1.0, 0.9, 0.75, 2.0 / 3, 0.5)) {
                val w = ((width * scale).toInt() / 16) * 16
                val h = ((height * scale).toInt() / 16) * 16
                if (w > 0 && h > 0 && ok(w, h)) return w to h
            }
            return width to height
        }
    }
}

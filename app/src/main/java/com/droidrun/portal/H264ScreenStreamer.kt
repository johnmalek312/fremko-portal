package com.droidrun.portal

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264ScreenStreamer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val targetFps: Int = 15,
    private val targetBitrate: Int = 2_000_000
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var nalPrefixSize: Int = 4 // 2 or 4 depending on device/codec config

    data class Size(val width: Int, val height: Int, val densityDpi: Int)

    private fun getScreenSize(): Size {
        val m: DisplayMetrics = context.resources.displayMetrics
        return Size(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    fun start(onFrame: (ByteArray) -> Unit) {
        if (running.get()) return
        running.set(true)

        val (width, height, densityDpi) = getScreenSize()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // seconds
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()

        codec = c
        inputSurface = surface

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "h264_stream",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        thread = HandlerThread("h264_encoder_loop").also { it.start() }
        handler = Handler(thread!!.looper)

        handler?.post {
            val bufferInfo = MediaCodec.BufferInfo()
            while (running.get()) {
                val outIndex = try { c.dequeueOutputBuffer(bufferInfo, 10_000) } catch (_: Throwable) { -1 }
                when {
                    outIndex >= 0 -> {
                        val buf: ByteBuffer? = try { c.getOutputBuffer(outIndex) } catch (_: Throwable) { null }
                        if (buf != null && bufferInfo.size > 0) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            val outBytes = ByteArray(bufferInfo.size)
                            buf.get(outBytes)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isConfig) {
                                // Detect prefix size and capture SPS/PPS for future keyframes
                                try { detectPrefixSize(ByteBuffer.wrap(outBytes)) } catch (_: Throwable) {}
                                parseAndStoreSpsPpsFromCsdbuffer(outBytes)
                            } else {
                                val annexB = convertToAnnexBRobust(outBytes)
                                val prefix = if (isKey) buildSpsPpsPrefix() else null
                                val toSend = if (prefix != null) {
                                    ByteArray(prefix.size + annexB.size).also {
                                        System.arraycopy(prefix, 0, it, 0, prefix.size)
                                        System.arraycopy(annexB, 0, it, prefix.size, annexB.size)
                                    }
                                } else annexB
                                onFrame(toSend)
                            }
                        }
                        try { c.releaseOutputBuffer(outIndex, false) } catch (_: Throwable) {}
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = try { c.outputFormat } catch (_: Throwable) { null }
                        val csd0 = newFormat?.getByteBuffer("csd-0")
                        val csd1 = newFormat?.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            try { detectPrefixSize(csd0.duplicate()) } catch (_: Throwable) {}
                        }
                        if (csd0 != null && csd1 != null) {
                            sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                            pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                        }
                    }
                    else -> {
                        // no output; continue
                    }
                }
            }
        }
    }

    fun updateBitrate(newBitrate: Int) {
        val c = codec ?: return
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                val b = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate) }
                c.setParameters(b)
            } catch (_: Throwable) {}
        }
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                val b = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
                c.setParameters(b)
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { handler?.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
        try { thread?.quitSafely() } catch (_: Throwable) {}
        handler = null
        thread = null

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    private fun parseAndStoreSpsPpsFromCsdbuffer(csd: ByteArray) {
        // csd from MediaCodec is typically AVC configuration: [length][SPS][length][PPS] or already Annex-B
        val annexB = convertToAnnexBRobust(csd)
        // annexB may contain multiple NALs; extract sps (type 7) and pps (type 8)
        var i = 0
        while (i + 4 <= annexB.size) {
            val idx = findStartCode(annexB, i)
            if (idx < 0) break
            val next = findStartCode(annexB, idx + 4).let { if (it < 0) annexB.size else it }
            val nalStart = idx + startCodeLength(annexB, idx)
            val nalType = annexB[nalStart].toInt() and 0x1F
            val nal = annexB.copyOfRange(nalStart, next)
            if (nalType == 7) sps = nal
            if (nalType == 8) pps = nal
            i = next
        }
    }

    private fun buildSpsPpsPrefix(): ByteArray? {
        val s = sps ?: return null
        val p = pps ?: return null
        val sc = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        return ByteArray(sc.size + s.size + sc.size + p.size).also { out ->
            var pos = 0
            System.arraycopy(sc, 0, out, pos, sc.size); pos += sc.size
            System.arraycopy(s, 0, out, pos, s.size); pos += s.size
            System.arraycopy(sc, 0, out, pos, sc.size); pos += sc.size
            System.arraycopy(p, 0, out, pos, p.size)
        }
    }

    private fun convertToAnnexBRobust(input: ByteArray): ByteArray {
        // Pass through if it already looks like Annex-B (00 00 00 01 or 00 00 01)
        if (looksLikeAnnexB(input)) return input

        var offset = 0
        val out = ArrayList<Byte>(input.size + 16)
        val prefixSize = if (nalPrefixSize == 2 || nalPrefixSize == 4) nalPrefixSize else 4
        while (offset + prefixSize <= input.size) {
            val nalLength = if (prefixSize == 4) {
                ((input[offset].toInt() and 0xFF) shl 24) or
                ((input[offset + 1].toInt() and 0xFF) shl 16) or
                ((input[offset + 2].toInt() and 0xFF) shl 8) or
                (input[offset + 3].toInt() and 0xFF)
            } else {
                ((input[offset].toInt() and 0xFF) shl 8) or
                (input[offset + 1].toInt() and 0xFF)
            }
            offset += prefixSize
            if (nalLength <= 0 || offset + nalLength > input.size) break
            // write start code (4-byte)
            out.add(0x00); out.add(0x00); out.add(0x00); out.add(0x01)
            for (k in 0 until nalLength) out.add(input[offset + k])
            offset += nalLength
        }
        return if (out.isNotEmpty()) out.toByteArray() else input
    }

    private fun looksLikeAnnexB(b: ByteArray): Boolean {
        return (b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte()) ||
               (b.size >= 3 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte())
    }

    private fun detectPrefixSize(csd0: ByteBuffer) {
        try {
            if (csd0.remaining() >= 5) {
                csd0.position(4)
                val lengthSizeMinusOne = csd0.get().toInt() and 0x03
                nalPrefixSize = (lengthSizeMinusOne + 1).coerceIn(1, 4) // usually 4 or 2
                if (nalPrefixSize != 2 && nalPrefixSize != 4) nalPrefixSize = 4
            }
        } catch (_: Throwable) {
            nalPrefixSize = 4
        }
    }

    private fun findStartCode(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < b.size) {
            if (i + 3 < b.size && b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) return i
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun startCodeLength(b: ByteArray, at: Int): Int {
        return if (at + 3 < b.size && b[at] == 0.toByte() && b[at + 1] == 0.toByte() && b[at + 2] == 0.toByte() && b[at + 3] == 1.toByte()) 4 else 3
    }
}



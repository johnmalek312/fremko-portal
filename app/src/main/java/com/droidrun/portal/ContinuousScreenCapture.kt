package com.droidrun.portal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics

/**
 * Continuously captures the device screen using MediaProjection and keeps only the latest
 * usable frame in memory. Frame rate is throttled to approximately [targetFps] to reduce
 * battery/CPU usage. Thread-safe and self-contained.
 */
class ContinuousScreenCapture(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val targetFps: Int = 7
) {
    private val frameIntervalMs: Long = (1000L / targetFps.coerceAtLeast(1)).toLong()

    private val frameLock = Any()
    @Volatile private var lastFrame: Bitmap? = null

    @Volatile private var isRunning: Boolean = false

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile private var lastProcessedMs: Long = 0L

    /** Returns true if continuous capture is currently running */
    fun isCurrentlyRunning(): Boolean = isRunning

    /** Starts continuous capture. No-op if already running. */
    fun start() {
        if (isRunning) return
        isRunning = true

        // Ensure the foreground service is running before creating the virtual display
        // This is required on Android 14+ to keep MediaProjection alive in background
        try {
            val appCtx = context.applicationContext
            appCtx.startForegroundService(Intent(appCtx, ScreenCaptureService::class.java))
        } catch (_: Throwable) {}

        val metrics: DisplayMetrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // Slightly larger maxImages gives us a small buffer while we throttle
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val thread = HandlerThread("continuous_screen_capture")
        thread.start()
        handlerThread = thread
        val looperHandler = Handler(thread.looper)
        handler = looperHandler

        // Register projection callback BEFORE creating the virtual display
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // MediaProjection revoked or stopped by the system/user
                stop()
            }
        }
        try {
            mediaProjection.registerCallback(callback, looperHandler)
            projectionCallback = callback
        } catch (_: Throwable) {
            // Best-effort; continue even if callback registration fails
        }

        // Create the virtual display that mirrors the screen into the ImageReader surface
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "continuous_screencap",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        reader.setOnImageAvailableListener({ r ->
            val now = SystemClock.elapsedRealtime()
            val image = try {
                r.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            image.use { img ->
                // Drop frames to throttle to target fps
                if (now - lastProcessedMs < frameIntervalMs) {
                    return@use
                }

                val imgWidth = img.width
                val imgHeight = img.height
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * imgWidth

                try {
                    try { buffer.rewind() } catch (_: Throwable) {}

                    val tempBitmap = Bitmap.createBitmap(
                        imgWidth + (rowPadding / pixelStride),
                        imgHeight,
                        Bitmap.Config.ARGB_8888
                    )

                    var copied = false
                    try {
                        tempBitmap.copyPixelsFromBuffer(buffer)
                        copied = true
                    } catch (_: Throwable) {
                        // ignore; will skip frame
                    }

                    if (copied) {
                        val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, imgWidth, imgHeight)
                        tempBitmap.recycle()

                        // Swap in the new frame atomically and recycle the previous one
                        var old: Bitmap? = null
                        synchronized(frameLock) {
                            old = lastFrame
                            lastFrame = cropped
                        }
                        try { old?.recycle() } catch (_: Throwable) {}
                        lastProcessedMs = now
                    } else {
                        try { tempBitmap.recycle() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    // Ignore individual frame failures
                }
            }
        }, looperHandler)

    }

    /** Returns a copy of the most recent frame instantly, or null if none yet available. */
    fun getLastFrame(): Bitmap? {
        synchronized(frameLock) {
            val src = lastFrame ?: return null
            // Return a non-mutable copy so callers can safely hold it while we update our internal one
            return try {
                src.copy(src.config, false)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 
     * Captures a fresh frame immediately, bypassing the FPS throttling.
     * Returns a copy of the newly captured frame, or null if capture fails.
     * This method blocks until a fresh frame is available or timeout occurs.
     */
    fun captureImmediateFrame(timeoutMs: Long = 2000): Bitmap? {
        if (!isRunning) return null
        
        val startTime = SystemClock.elapsedRealtime()
        val originalLastProcessed = lastProcessedMs
        
        // Reset throttling to force immediate capture of next frame
        lastProcessedMs = 0
        
        // Wait for a new frame to be captured
        var attempts = 0
        val maxAttempts = (timeoutMs / 50).toInt().coerceAtLeast(1)
        
        while (attempts < maxAttempts) {
            Thread.sleep(50) // Wait 50ms between checks
            
            // Check if a new frame was captured after we reset the timestamp
            if (lastProcessedMs > originalLastProcessed) {
                return getLastFrame()
            }
            
            attempts++
            
            // Check for timeout
            if (SystemClock.elapsedRealtime() - startTime >= timeoutMs) {
                break
            }
        }
        
        // Timeout occurred, return the last available frame if any
        return getLastFrame()
    }

    /** Stops capture and frees resources. Safe to call multiple times. */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        // Unregister projection callback if present
        try {
            projectionCallback?.let { mediaProjection.unregisterCallback(it) }
        } catch (_: Throwable) {}
        projectionCallback = null

        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null

        try { handlerThread?.quitSafely() } catch (_: Throwable) {}
        handler = null
        handlerThread = null

        var old: Bitmap? = null
        synchronized(frameLock) {
            old = lastFrame
            lastFrame = null
        }
        try { old?.recycle() } catch (_: Throwable) {}
    }
}


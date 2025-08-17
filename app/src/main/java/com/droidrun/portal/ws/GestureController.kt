package com.droidrun.portal.ws

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.droidrun.portal.DroidrunAccessibilityService

/**
 * Central place for high-level gesture utilities used by the WebSocket action layer.
 *
 * Keeps `WebSocketActivity` clean and isolates all direct AccessibilityService calls.
 */
object GestureController {
    private const val TAG = "GestureController"

    fun tap(x: Int, y: Int): Boolean {
        val service = DroidrunAccessibilityService.getInstance() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "tap error", e)
            false
        }
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 300): Boolean {
        val service = DroidrunAccessibilityService.getInstance() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val dur = durationMs.coerceIn(100, 2000)
            val stroke = GestureDescription.StrokeDescription(path, 0, dur.toLong())
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "swipe error", e)
            false
        }
    }

    data class PathPoint(val x: Float, val y: Float, val t: Long)

    fun gesturePath(points: List<PathPoint>, explicitDurationMs: Long? = null): Boolean {
        val service = DroidrunAccessibilityService.getInstance() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (points.size < 2) return false
        return try {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            val duration: Long = explicitDurationMs ?: run {
                val startT = points.first().t
                val endT = points.last().t
                val raw = (endT - startT).coerceAtLeast(1L)
                raw.coerceIn(50L, 10_000L)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "gesturePath error", e)
            false
        }
    }
}
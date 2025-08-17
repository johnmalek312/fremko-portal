package com.droidrun.portal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import android.os.StatFs

object DeviceInfoProvider {

    fun collect(context: Context): JSONObject {
        val result = JSONObject()
        try {
            // Static/build info
            result.put("serial", getSerial(context))
            result.put("device_name", getDeviceName(context))
            result.put("brand", Build.BRAND ?: JSONObject.NULL)
            result.put("model", Build.MODEL ?: JSONObject.NULL)
            result.put("sdk", Build.VERSION.SDK_INT)
            result.put("os_version", Build.VERSION.RELEASE ?: JSONObject.NULL)

            // Display
            val dm = getDisplayMetrics(context)
            result.put("width", dm?.widthPixels ?: JSONObject.NULL)
            result.put("height", dm?.heightPixels ?: JSONObject.NULL)
            result.put("dpi", dm?.densityDpi ?: JSONObject.NULL)
            result.put("orientation", getOrientation(context))

            // Battery and power
            val batteryInfo = getBatteryInfo(context)
            result.put("battery", batteryInfo.level)
            result.put("charging", batteryInfo.charging)
            result.put("power_source", batteryInfo.powerSource ?: JSONObject.NULL)

            // Brightness 0..100
            result.put("brightness", getBrightnessPercent(context))

            // Audio
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mediaVol = audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
            val mediaMax = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            result.put("ringer_mode", when (audio?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                else -> JSONObject.NULL
            })
            mediaVol?.let { result.put("volume_media", it) } ?: result.put("volume_media", JSONObject.NULL)
            mediaMax?.let { result.put("volume_max_media", it) }

            // Foreground app/activity (best-effort)
            val (fgPkg, fgAct) = getForegroundApp(context)
            result.put("foreground_app", fgPkg ?: JSONObject.NULL)
            result.put("foreground_activity", fgAct ?: JSONObject.NULL)

            // Network
            val net = getNetworkInfo(context)
            result.put("network_type", net.networkType ?: JSONObject.NULL)
            result.put("wifi_ssid", net.wifiSsid ?: JSONObject.NULL)
            result.put("is_metered", net.isMetered)
            result.put("operator", net.operator ?: JSONObject.NULL)

            // Storage (report in MB per spec)
            val storage = getStorageInfo()
            result.put("storage_total_mb", storage.totalMb)
            result.put("storage_free_mb", storage.freeMb)

            // Memory (MB)
            val mem = getMemoryInfo(context)
            result.put("mem_total_mb", mem.totalMb)
            result.put("mem_free_mb", mem.freeMb)

            // Locale/System
            val locale = Locale.getDefault()
            result.put("language", locale.language)
            result.put("country", locale.country)
            result.put("timezone", TimeZone.getDefault().id)

            // Build details
            result.put("build_id", Build.ID ?: JSONObject.NULL)
            result.put("build_fingerprint", Build.FINGERPRINT ?: JSONObject.NULL)
            result.put("security_patch", Build.VERSION.SECURITY_PATCH ?: JSONObject.NULL)
            result.put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: JSONObject.NULL)

            // Capabilities/state
            result.put("accessibility_enabled", isAccessibilityEnabled(context))
            result.put("notification_access", hasNotificationAccess(context))
            result.put("draw_over_apps", Settings.canDrawOverlays(context))
            result.put("nav_mode", getNavigationMode(context))
            result.put("has_hw_back_button", KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK))
        } catch (_: Throwable) {
            // Best-effort only; individual fields may be null
        }
        return result
    }

    private fun getDisplayMetrics(context: Context): DisplayMetrics? {
        return try {
            val dm = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            dm
        } catch (_: Throwable) { null }
    }

    private fun getOrientation(context: Context): String {
        return try {
            val o = context.resources.configuration.orientation
            if (o == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        } catch (_: Throwable) { "portrait" }
    }

    data class Battery(val level: Int, val charging: Boolean, val powerSource: String?)

    private fun getBatteryInfo(context: Context): Battery {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = try {
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct in 0..100) pct else -1
            } catch (_: Throwable) { -1 }

            val statusIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = statusIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = statusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val source = when {
                plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
                plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) && (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) -> "wireless"
                else -> if (charging) "other" else "none"
            }
            Battery(level = if (level >= 0) level else 0, charging = charging, powerSource = source)
        } catch (_: Throwable) {
            Battery(level = 0, charging = false, powerSource = null)
        }
    }

    private fun getBrightnessPercent(context: Context): Int {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
            // Convert 0..255 to 0..100
            (raw.coerceIn(0, 255) * 100f / 255f).toInt()
        } catch (_: Throwable) { 0 }
    }

    @SuppressLint("MissingPermission")
    private fun getForegroundApp(context: Context): Pair<String?, String?> {
        // Best-effort via UsageStats (requires user-granted access). Fallbacks return nulls.
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 60_000
            val events: UsageEvents = usm.queryEvents(start, end)
            var lastPackage: String? = null
            var lastActivity: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                    lastPackage = event.packageName
                    lastActivity = event.className
                }
            }
            Pair(lastPackage, lastActivity)
        } catch (_: Throwable) { Pair(null, null) }
    }

    data class Network(val networkType: String?, val wifiSsid: String?, val isMetered: Boolean, val operator: String?)

    @SuppressLint("MissingPermission")
    private fun getNetworkInfo(context: Context): Network {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var type: String? = null
        var ssid: String? = null
        var metered = false
        var operator: String? = null

        try {
            val nc = cm?.getNetworkCapabilities(cm.activeNetwork)
            type = when {
                nc == null -> "none"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            metered = cm?.isActiveNetworkMetered ?: false
        } catch (_: Throwable) {}

        if (type == "wifi") {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                val rawSsid = info?.ssid
                ssid = rawSsid?.takeIf { !it.equals("<unknown ssid>", ignoreCase = true) }?.trim('"')
            } catch (_: Throwable) { ssid = null }
        }

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            operator = tm?.networkOperatorName?.takeIf { !it.isNullOrBlank() }
        } catch (_: Throwable) { operator = null }

        return Network(type, ssid, metered, operator)
    }

    data class Storage(val totalMb: Long, val freeMb: Long)
    private fun getStorageInfo(): Storage {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalBytes = totalBlocks * blockSize
            val freeBytes = availableBlocks * blockSize
            Storage(totalBytes / (1024 * 1024), freeBytes / (1024 * 1024))
        } catch (_: Throwable) {
            Storage(0, 0)
        }
    }

    data class Memory(val totalMb: Long, val freeMb: Long)
    private fun getMemoryInfo(context: Context): Memory {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) mi.totalMem else 0L
            val avail = mi.availMem
            Memory(total / (1024 * 1024), avail / (1024 * 1024))
        } catch (_: Throwable) { Memory(0, 0) }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val service = context.packageName + "/" + DroidrunAccessibilityService::class.java.canonicalName
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            !enabled.isNullOrEmpty() && enabled.contains(service)
        } catch (_: Throwable) { false }
    }

    private fun hasNotificationAccess(context: Context): Boolean {
        return try {
            val enabledPkgs = NotificationManagerCompat.getEnabledListenerPackages(context)
            enabledPkgs.contains(context.packageName)
        } catch (_: Throwable) { false }
    }

    private fun getNavigationMode(context: Context): String {
        // Best-effort via Settings.Secure.NAVIGATION_MODE where available; fall back to buttons
        return try {
            val mode = Settings.Secure.getInt(context.contentResolver, "navigation_mode")
            when (mode) {
                2 -> "gestures" // fully gestural
                else -> "buttons" // 3-button or 2-button
            }
        } catch (_: Throwable) { "buttons" }
    }

    @SuppressLint("HardwareIds")
    private fun getSerial(context: Context): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) { null }
    }

    private fun getDeviceName(context: Context): String? {
        return try {
            // Settings Global device_name where available; fallback to Model
            val name = Settings.Global.getString(context.contentResolver, "device_name")
            if (!name.isNullOrBlank()) name else Build.MODEL
        } catch (_: Throwable) { Build.MODEL }
    }
}



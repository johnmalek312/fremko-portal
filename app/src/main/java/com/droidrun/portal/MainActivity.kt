package com.droidrun.portal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import com.google.android.material.slider.Slider
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context.MODE_PRIVATE
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var toggleOverlay: SwitchMaterial
    private lateinit var fetchButton: MaterialButton
    private lateinit var offsetSlider: Slider
    private lateinit var offsetInput: TextInputEditText
    private lateinit var offsetInputLayout: TextInputLayout
    private lateinit var accessibilityIndicator: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityStatusContainer: View
    private lateinit var accessibilityStatusCard: com.google.android.material.card.MaterialCardView
    private lateinit var reenableProjectionBtn: MaterialButton
    private lateinit var stopCaptureBtn: MaterialButton

    // WebSocket UI
    private lateinit var wsGoalInput: EditText
    private lateinit var wsSettingsBtn: MaterialButton
    private lateinit var wsSendGoalBtn: MaterialButton
    private lateinit var wsConnectBtn: MaterialButton
    private lateinit var wsClearLogsBtn: MaterialButton
    private lateinit var wsDisconnectBtn: MaterialButton
    private lateinit var wsLogText: TextView
    private lateinit var wsLogScroll: ScrollView

    // Shared log buffer (ported from WebSocketActivity)
    companion object {
        private const val DEFAULT_OFFSET = 0
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET
        private const val DEFAULT_JPEG_WIDTH = 1080

        private var globalLogBuffer: StringBuilder = StringBuilder()
        private const val MAX_LOG_CHARS = 20_000
        @Volatile var sharedMediaProjection: MediaProjection? = null
    }

    // WebSocket state
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val TAG = "DroidRun_WS"

    // Video streaming state (separate WS)
    private var videoSocket: WebSocket? = null
    private var isVideoConnected = false
    private var streamingJob: Job? = null
    private var h264Streamer: H264ScreenStreamer? = null
    private var activeStreamFps: Int = 7
    private var activeStreamQuality: Int = 70
    private var videoHandshakeOk: Boolean = false
    // Streaming mode/state
    private var isH264Stream: Boolean = true
    private var jpegFps: Int = 7
    private var jpegQuality: Int = 75

    // MediaProjection state
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var screenCaptureLauncher: ActivityResultLauncher<Intent>? = null
    @Volatile private var isRequestingScreenCapture: Boolean = false
    @Volatile private var userStoppedCapture: Boolean = false
    private var continuousCapture: ContinuousScreenCapture? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.Main)

    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Background connectivity + reconnect state
    private var wantControlConnection: Boolean = false
    private var desiredControlUrl: String? = null
    private var pendingFrameAfterConnect: String? = null
    private var controlReconnectAttempts: Int = 0
    private var controlReconnectRunnable: Runnable? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Video reconnect state
    private var wantVideoStream: Boolean = false
    private var desiredVideoUrl: String? = null
    private var videoReconnectAttempts: Int = 0
    private var videoReconnectRunnable: Runnable? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI elements
        statusText = findViewById(R.id.status_text)
        versionText = findViewById(R.id.version_text)
        fetchButton = findViewById(R.id.fetch_button)
        toggleOverlay = findViewById(R.id.toggle_overlay)
        offsetSlider = findViewById(R.id.offset_slider)
        offsetInput = findViewById(R.id.offset_input)
        offsetInputLayout = findViewById(R.id.offset_input_layout)
        accessibilityIndicator = findViewById(R.id.accessibility_indicator)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityStatusContainer = findViewById(R.id.accessibility_status_container)
        accessibilityStatusCard = findViewById(R.id.accessibility_status_card)
        reenableProjectionBtn = findViewById(R.id.reenable_projection_btn)
        stopCaptureBtn = findViewById(R.id.stop_capture_btn)

        // WebSocket UI elements
        wsGoalInput = findViewById(R.id.ws_goal)
        wsSettingsBtn = findViewById(R.id.ws_settings_btn)
        wsSendGoalBtn = findViewById(R.id.ws_send_goal_btn)
        wsConnectBtn = findViewById(R.id.ws_connect_btn)
        wsClearLogsBtn = findViewById(R.id.ws_clear_logs_btn)
        wsDisconnectBtn = findViewById(R.id.ws_disconnect_btn)
        wsLogText = findViewById(R.id.ws_log_text)
        wsLogScroll = findViewById(R.id.ws_log_scroll)
        
        // Set app version
        setAppVersion()
        
        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()
        
        fetchButton.setOnClickListener { fetchElementData() }

        toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }
        
        // Setup accessibility status container
        accessibilityStatusContainer.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // Check initial accessibility status and sync UI
        updateAccessibilityStatusIndicator()
        syncUIWithAccessibilityService()

        // Populate existing WebSocket logs if any
        if (globalLogBuffer.isNotEmpty()) {
            wsLogText.text = globalLogBuffer.toString()
            wsLogScroll.post { wsLogScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // Ensure logs are completely hidden from accessibility services
        try {
            wsLogText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            wsLogScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } catch (_: Exception) {}

        // WebSocket controls
        wsSettingsBtn.setOnClickListener { openWsSettings() }
        wsSendGoalBtn.setOnClickListener { sendGoal() }
        wsConnectBtn.setOnClickListener { connectOnly() }
        wsClearLogsBtn.setOnClickListener { clearWsLogs() }
        wsDisconnectBtn.setOnClickListener { closeConnection() }
        reenableProjectionBtn.setOnClickListener { reenableScreenCapture() }
        stopCaptureBtn.setOnClickListener { stopScreenCapture() }

        // Initialize MediaProjection permission launcher
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                try {
                    mediaProjection = mediaProjectionManager?.getMediaProjection(result.resultCode, result.data!!)
                    sharedMediaProjection = mediaProjection
                    appendLog("✔ Screen capture permission granted")

                    // Register MediaProjection callback BEFORE starting capture
                    mediaProjection?.let { mp ->
                        val cb = object : MediaProjection.Callback() {
                            override fun onStop() {
                                appendLog("⚠ MediaProjection stopped by system/user")
                                runOnUiThread {
                                    try { continuousCapture?.stop() } catch (_: Exception) {}
                                    continuousCapture = null
                                    mediaProjection = null
                                    sharedMediaProjection = null
                                    if (!userStoppedCapture) {
                                        // Attempt automatic re-enable to keep screen capture available
                                        try { reenableScreenCapture() } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                        try {
                            mp.registerCallback(cb, Handler(Looper.getMainLooper()))
                            mediaProjectionCallback = cb
                        } catch (e: Exception) {
                            appendLog("⚠ Failed to register MediaProjection callback: ${e.message}")
                        }
                    }

                    try { continuousCapture?.stop() } catch (_: Exception) {}
                    continuousCapture = mediaProjection?.let { mp ->
                        ContinuousScreenCapture(this, mp, 7).also { it.start() }
                    }
                    appendLog("▶ Continuous screen capture started (~7 fps)")
                } catch (e: Exception) {
                    appendLog("⚠ Failed to obtain MediaProjection: ${e.message}")
                }
            } else {
                appendLog("⚠ Screen capture permission denied")
            }
            isRequestingScreenCapture = false
        }

        // Auto-request screen capture on app startup if not already available
        if (sharedMediaProjection == null) {
            runOnUiThread {
                try {
                    try { startForegroundService(Intent(this, ScreenCaptureService::class.java)) } catch (_: Exception) {}
                    val intent = mediaProjectionManager?.createScreenCaptureIntent()
                    if (intent != null && !isRequestingScreenCapture) {
                        isRequestingScreenCapture = true
                        screenCaptureLauncher?.launch(intent)
                    }
                } catch (e: Exception) {
                    appendLog("⚠ Failed to request screen capture on startup: ${e.message}")
                    isRequestingScreenCapture = false
                }
            }
        } else {
            mediaProjection = sharedMediaProjection
            appendLog("✔ Screen capture ready")
            // Ensure callback is registered if we already have a projection
            if (mediaProjection != null && mediaProjectionCallback == null) {
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        appendLog("⚠ MediaProjection stopped by system/user")
                        runOnUiThread {
                            try { continuousCapture?.stop() } catch (_: Exception) {}
                            continuousCapture = null
                            mediaProjection = null
                            sharedMediaProjection = null
                            if (!userStoppedCapture) {
                                // Attempt automatic re-enable to keep screen capture available
                                try { reenableScreenCapture() } catch (_: Exception) {}
                            }
                        }
                    }
                }
                try {
                    mediaProjection?.registerCallback(cb, Handler(Looper.getMainLooper()))
                    mediaProjectionCallback = cb
                } catch (e: Exception) {
                    appendLog("⚠ Failed to register MediaProjection callback: ${e.message}")
                }
            }
            if (continuousCapture == null && mediaProjection != null) {
                continuousCapture = ContinuousScreenCapture(this, mediaProjection!!, 7).also { it.start() }
                appendLog("▶ Continuous screen capture started (~7 fps)")
            }
        }
    }

    private fun computeBackoffMs(attempts: Int, baseMs: Long = 1000L, maxMs: Long = 60_000L): Long {
        val pow = 1L shl attempts.coerceAtMost(10)
        return (baseMs * pow).coerceAtMost(maxMs)
    }

    private fun scheduleControlReconnect() {
        cancelControlReconnect()
        if (!wantControlConnection) return
        val delayMs = computeBackoffMs(controlReconnectAttempts)
        appendLog("reconnect(control) in ${delayMs} ms (attempt=${controlReconnectAttempts})")
        controlReconnectRunnable = Runnable {
            if (!wantControlConnection) return@Runnable
            val url = desiredControlUrl
            if (!isConnected && url != null) {
                connect(url, pendingFrameAfterConnect)
                controlReconnectAttempts++
            }
        }
        mainHandler.postDelayed(controlReconnectRunnable!!, delayMs)
    }

    private fun cancelControlReconnect() {
        controlReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        controlReconnectRunnable = null
    }

    private fun scheduleVideoReconnect() {
        cancelVideoReconnect()
        if (!wantVideoStream) return
        val delayMs = computeBackoffMs(videoReconnectAttempts)
        appendLog("reconnect(video) in ${delayMs} ms (attempt=${videoReconnectAttempts})")
        videoReconnectRunnable = Runnable {
            if (!wantVideoStream) return@Runnable
            val url = desiredVideoUrl
            if (!isVideoConnected && url != null) {
                connectVideo(url, null)
                videoReconnectAttempts++
            }
        }
        mainHandler.postDelayed(videoReconnectRunnable!!, delayMs)
    }

    private fun cancelVideoReconnect() {
        videoReconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        videoReconnectRunnable = null
    }

    private fun maintainForegroundService() {
        val shouldRun = wantControlConnection || wantVideoStream || (mediaProjection != null)
        try {
            if (shouldRun) {
                startForegroundService(Intent(this, ScreenCaptureService::class.java))
            } else {
                stopService(Intent(this, ScreenCaptureService::class.java))
            }
        } catch (_: Exception) {}
    }

    private fun promptIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }
    
    override fun onResume() {
        super.onResume()
        // Update the accessibility status indicator when app resumes
        updateAccessibilityStatusIndicator()
        syncUIWithAccessibilityService()

        // Register for network changes to trigger reconnection
        try {
            if (connectivityManager == null) connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (networkCallback == null) {
                val req = NetworkRequest.Builder().build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (wantControlConnection && !isConnected) scheduleControlReconnect()
                    }
                    override fun onLost(network: Network) {
                        // Will trigger onFailure/onClosed; backoff will schedule
                    }
                }
                connectivityManager?.registerNetworkCallback(req, cb)
                networkCallback = cb
            }
        } catch (_: Exception) {}
    }
    
    private fun syncUIWithAccessibilityService() {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // Sync overlay toggle
            toggleOverlay.isChecked = accessibilityService.isOverlayVisible()
            
            // Sync offset controls
            val currentOffset = accessibilityService.getOverlayOffset()
            updateOffsetSlider(currentOffset)
            updateOffsetInputField(currentOffset)
            
            statusText.text = "Connected to accessibility service"
        } else {
            statusText.text = "Accessibility service not available"
        }
    }
    
    private fun setupOffsetSlider() {
        // Initialize current value
        offsetSlider.value = DEFAULT_OFFSET.toFloat()

        // Listen for value changes
        offsetSlider.addOnChangeListener { _, value, fromUser ->
            val offsetValue = value.toInt()
            if (fromUser) {
                updateOffsetInputField(offsetValue)
                updateOverlayOffset(offsetValue)
            }
        }

        // Final update when user stops sliding
        offsetSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { }
            override fun onStopTrackingTouch(slider: Slider) {
                updateOverlayOffset(slider.value.toInt())
            }
        })
    }
    
    private fun setupOffsetInput() {
        // Set initial value
        isProgrammaticUpdate = true
        offsetInput.setText(DEFAULT_OFFSET.toString())
        isProgrammaticUpdate = false
        
        // Apply on enter key
        offsetInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }
        
        // Input validation and auto-apply
        offsetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return
                
                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value < MIN_OFFSET || value > MAX_OFFSET) {
                            offsetInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            offsetInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        offsetInputLayout.error = "Invalid number"
                    } else {
                        offsetInputLayout.error = null
                    }
                } catch (e: Exception) {
                    offsetInputLayout.error = "Invalid number"
                }
            }
        })
    }
    
    private fun applyInputOffset() {
        try {
            val inputText = offsetInput.text.toString()
            val offsetValue = inputText.toIntOrNull()
            
            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)
                
                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    offsetInput.setText(boundedValue.toString())
                    isProgrammaticUpdate = false
                    Toast.makeText(this, "Value adjusted to valid range", Toast.LENGTH_SHORT).show()
                }
                
                // Update slider to match and apply the offset
                offsetSlider.value = boundedValue.toFloat()
                updateOverlayOffset(boundedValue)
            } else {
                // Invalid input
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error applying input offset: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateOffsetSlider(currentOffset: Int) {
        // Ensure the offset is within our new bounds
        val boundedOffset = currentOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)
        
        // Update the slider to match the current offset from the service
        offsetSlider.value = boundedOffset.toFloat()
    }
    
    private fun updateOffsetInputField(currentOffset: Int) {
        // Set flag to prevent TextWatcher from triggering
        isProgrammaticUpdate = true
        
        // Update the text input to match the current offset
        offsetInput.setText(currentOffset.toString())
        
        // Reset flag
        isProgrammaticUpdate = false
    }
    
    private fun updateOverlayOffset(offsetValue: Int) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayOffset(offsetValue)
                if (success) {
                    statusText.text = "Element offset updated to: $offsetValue"
                    Log.d("DROIDRUN_MAIN", "Offset updated successfully: $offsetValue")
                } else {
                    statusText.text = "Failed to update offset"
                    Log.e("DROIDRUN_MAIN", "Failed to update offset: $offsetValue")
                }
            } else {
                statusText.text = "Accessibility service not available"
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for offset update")
            }
        } catch (e: Exception) {
            statusText.text = "Error updating offset: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error updating offset: ${e.message}")
        }
    }
    
    private fun fetchElementData() {
        try {
            statusText.text = "Fetching combined state data..."
            val data = fetchCombinedStateFromProvider()
            statusText.text = "Combined state data received: ${data.length} characters"
            appendLog("Combined state length: ${data.length}")
            Toast.makeText(this, "Combined state received successfully!", Toast.LENGTH_SHORT).show()
            Log.d("DROIDRUN_MAIN", "Combined state data received: ${data.substring(0, Math.min(100, data.length))}...")
            
        } catch (e: Exception) {
            statusText.text = "Error fetching data: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error fetching combined state data: ${e.message}")
        }
    }
    
    private fun toggleOverlayVisibility(visible: Boolean) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayVisible(visible)
                if (success) {
                    statusText.text = "Visualization overlays ${if (visible) "enabled" else "disabled"}"
                    Log.d("DROIDRUN_MAIN", "Overlay visibility toggled to: $visible")
                } else {
                    statusText.text = "Failed to toggle overlay"
                    Log.e("DROIDRUN_MAIN", "Failed to toggle overlay visibility")
                }
            } else {
                statusText.text = "Accessibility service not available"
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for overlay toggle")
            }
        } catch (e: Exception) {
            statusText.text = "Error changing visibility: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error toggling overlay: ${e.message}")
        }
    }
    

    private fun fetchPhoneStateData() {
        try {
            statusText.text = "Fetching phone state..."
            
            // Use ContentProvider to get phone state
            val uri = Uri.parse("content://com.droidrun.portal/")
            val command = JSONObject().apply {
                put("action", "phone_state")
            }
            
            val cursor = contentResolver.query(
                uri,
                null,
                command.toString(),
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)
                    
                    if (jsonResponse.getString("status") == "success") {
                        val data = jsonResponse.getString("data")
                        statusText.text = "Phone state received: ${data.length} characters"
                        appendLog("Phone state length: ${data.length}")
                        Toast.makeText(this, "Phone state received successfully!", Toast.LENGTH_SHORT).show()
                        
                        Log.d("DROIDRUN_MAIN", "Phone state received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        statusText.text = "Error: $error"
                    }
                }
            }
            
        } catch (e: Exception) {
            statusText.text = "Error fetching phone state: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error fetching phone state: ${e.message}")
        }
    }

    /* ----------------------------------------------------------------------------------------- */
    /*                                       WS helpers                                           */
    /* ----------------------------------------------------------------------------------------- */

    private fun appendLog(message: String) {
        runOnUiThread {
            globalLogBuffer.append(message).append('\n')
            if (globalLogBuffer.length > MAX_LOG_CHARS) {
                globalLogBuffer = StringBuilder(globalLogBuffer.takeLast(MAX_LOG_CHARS).toString())
            }
            if (::wsLogText.isInitialized) {
                wsLogText.text = globalLogBuffer.toString()
                wsLogScroll.post { wsLogScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun sanitizeForLog(json: String): String {
        return json.replace(Regex("\\\"base64\\\"\\s*:\\s*\\\"[^\\\"]+\\\""), "\"base64\":\"base64://image redacted\"")
    }

    private fun buildImageMetaJson(byteSize: Int, format: String = "JPEG"): JSONObject {
        return JSONObject().apply {
            put("format", format)
            put("byte_length", byteSize)
        }
    }

    private fun clearWsLogs() {
        runOnUiThread {
            globalLogBuffer.clear()
            if (::wsLogText.isInitialized) wsLogText.text = ""
        }
    }

    private fun openWsSettings() {
        startActivity(Intent(this, WsSettingsActivity::class.java))
    }

    private fun closeConnection() {
        var didSomething = false
        if (isConnected || wantControlConnection || webSocket != null) {
            disconnect()
            didSomething = true
        }
        if (isVideoConnected || wantVideoStream || videoSocket != null) {
            // Stop video streaming and cancel any pending reconnects
            try { stopVideoStreaming() } catch (_: Exception) {}
            didSomething = true
        }
        if (!didSomething) {
            appendLog("No active connection")
        }
    }

    private fun ensureConnected(url: String, frameAfterConnect: String?) {
        if (isConnected && webSocket != null) {
            frameAfterConnect?.let { webSocket?.send(it) }
            return
        }
        connect(url, frameAfterConnect)
    }

    private fun connect(url: String, frameAfterConnect: String?) {
        appendLog("Connecting to $url …")
        desiredControlUrl = url
        pendingFrameAfterConnect = frameAfterConnect
        wantControlConnection = true
        maintainForegroundService()

        val client = OkHttpClient.Builder()
            .pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                controlReconnectAttempts = 0
                appendLog("✔ Connected (${response.code})")
                frameAfterConnect?.let { wsFrame ->
                    appendLog("→ goal (post-connect): $wsFrame")
                    webSocket.send(wsFrame)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                appendLog("← text: $text")
                handleIncomingFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                appendLog("← binary: ${bytes.size} B")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("✖ Disconnected: $code $reason")
                isConnected = false
                if (wantControlConnection) scheduleControlReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                appendLog("⚠ Failure: ${t.message}")
                Log.e(TAG, "WebSocket error", t)
                isConnected = false
                if (wantControlConnection) scheduleControlReconnect()
            }
        })
    }

    private fun disconnect() {
        appendLog("Disconnecting …")
        webSocket?.close(1000, "User requested")
        webSocket = null
        isConnected = false
        wantControlConnection = false
        cancelControlReconnect()
        maintainForegroundService()
    }

    private fun connectOnly() {
        val prefs = getSharedPreferences(WsSettingsActivity.PREFS, MODE_PRIVATE)
        val url = prefs.getString(WsSettingsActivity.KEY_URL, "ws://10.0.2.2:10001") ?: "ws://10.0.2.2:10001"
        desiredControlUrl = url
        wantControlConnection = true
        maintainForegroundService()
        ensureConnected(url, null)
        promptIgnoreBatteryOptimizations()
    }

    private fun sendGoal() {
        val prefs = getSharedPreferences(WsSettingsActivity.PREFS, MODE_PRIVATE)
        val url = prefs.getString(WsSettingsActivity.KEY_URL, "ws://10.0.2.2:10001") ?: "ws://10.0.2.2:10001"

        val goalText = wsGoalInput.text.toString().ifBlank { "Open the Settings app" }
        val frameObj = JSONObject().apply {
            put("type", "goal")
            put("goal", goalText)
            prefs.getString(WsSettingsActivity.KEY_PROVIDER, "")?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
            prefs.getString(WsSettingsActivity.KEY_MODEL, "")?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            put("steps", prefs.getInt(WsSettingsActivity.KEY_STEPS, 150))
            put("timeout", prefs.getInt(WsSettingsActivity.KEY_TIMEOUT, 1000))
            put("reasoning", prefs.getBoolean(WsSettingsActivity.KEY_REASONING, false))
            put("reflection", prefs.getBoolean(WsSettingsActivity.KEY_REFLECTION, false))
            put("tracing", prefs.getBoolean(WsSettingsActivity.KEY_TRACING, false))
            put("debug", prefs.getBoolean(WsSettingsActivity.KEY_DEBUG, false))
            put("vision", prefs.getBoolean(WsSettingsActivity.KEY_VISION, false))
        }
        val json = frameObj.toString()
        appendLog("→ goal: $json")
        desiredControlUrl = url
        pendingFrameAfterConnect = json
        wantControlConnection = true
        maintainForegroundService()
        ensureConnected(url, json)
        promptIgnoreBatteryOptimizations()
    }

    /* ----------------------------------------------------------------------------------------- */
    /*                                Protocol implementation                                    */
    /* ----------------------------------------------------------------------------------------- */

    private fun handleIncomingFrame(raw: String) {
        try {
            appendLog("← raw: $raw")
            val obj = JSONObject(raw)
            val type = obj.optString("type")
            when (type) {
                "action" -> {
                    appendLog("← action: id=${obj.optInt("id", -1)} name=${obj.optString("name")} args=${obj.optJSONObject("args")}")
                    processAction(obj)
                }
                "goal" -> appendLog("← goal: ${obj.optString("goal")}")
                "pong" -> appendLog("← pong")
                else -> appendLog("← unhandled type: $type")
            }
        } catch (e: Exception) {
            appendLog("⚠ Invalid JSON: ${e.message}")
        }
    }

    private fun handleGetState(args: JSONObject): Triple<String, String, Any?> {
        val combinedState = fetchCombinedStateFromProvider()
        return Triple("ok", "State returned", JSONObject(combinedState))
    }

    private fun handleGetDeviceInfo(args: JSONObject): Triple<String, String, Any?> {
        try {
            val infoObj = DeviceInfoProvider.collect(this)
            return Triple("ok", "Device info returned", infoObj)
        } catch (e: Exception) {
            return Triple("error", e.message ?: "device_info failed", null)
        }
    }

    private fun handleTapByCoordinates(args: JSONObject): Triple<String, String, Any?> {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        if (x >= 0 && y >= 0) {
            val ok = com.droidrun.portal.ws.GestureController.tap(x, y)
            val status = if (ok) "ok" else "error"
            val info = if (ok) "tap delivered" else "tap failed"
            return Triple(status, info, null)
        } else {
            return Triple("error", "Invalid or missing x/y", null)
        }
    }

    private fun handleSwipe(args: JSONObject): Triple<String, String, Any?> {
        val sx = args.optInt("start_x", -1)
        val sy = args.optInt("start_y", -1)
        val ex = args.optInt("end_x", -1)
        val ey = args.optInt("end_y", -1)
        val duration = args.optInt("duration_ms", 300)
        if (sx >= 0 && sy >= 0 && ex >= 0 && ey >= 0) {
            val ok = com.droidrun.portal.ws.GestureController.swipe(sx, sy, ex, ey, duration)
            val status = if (ok) "ok" else "error"
            val info = if (ok) "swipe delivered" else "swipe failed"
            return Triple(status, info, null)
        } else {
            return Triple("error", "Invalid or missing coordinates", null)
        }
    }

    private fun handleGesturePath(args: JSONObject): Triple<String, String, Any?> {
        val ptsArray = args.optJSONArray("points")
        val durationMs = if (args.has("duration_ms")) args.optLong("duration_ms", -1L).takeIf { it > 0 } else null
        if (ptsArray != null && ptsArray.length() >= 2) {
            val points = mutableListOf<com.droidrun.portal.ws.GestureController.PathPoint>()
            for (i in 0 until ptsArray.length()) {
                val p = ptsArray.optJSONObject(i) ?: continue
                val x = p.optDouble("x", Double.NaN)
                val y = p.optDouble("y", Double.NaN)
                val t = p.optLong("t", if (i == 0) 0L else i * 16L)
                if (!x.isNaN() && !y.isNaN()) {
                    points.add(com.droidrun.portal.ws.GestureController.PathPoint(x.toFloat(), y.toFloat(), t))
                }
            }
            if (points.size >= 2) {
                val ok = com.droidrun.portal.ws.GestureController.gesturePath(points, durationMs)
                val status = if (ok) "ok" else "error"
                val info = if (ok) "gesture delivered" else "gesture failed"
                return Triple(status, info, null)
            } else {
                return Triple("error", "Insufficient valid points", null)
            }
        } else {
            return Triple("error", "Missing or invalid points[]", null)
        }
    }

    private fun handleBack(args: JSONObject): Triple<String, String, Any?> {
        val svc = DroidrunAccessibilityService.getInstance()
        val ok = svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true
        val status = if (ok) "ok" else "error"
        val info = if (ok) "back performed" else "back failed"
        return Triple(status, info, null)
    }

    private fun handleInputText(args: JSONObject): Triple<String, String, Any?> {
        val text = args.optString("text", "")
        val append = args.optBoolean("append", false)
        if (text.isNotBlank()) {
            val svc = DroidrunAccessibilityService.getInstance()
            val ok = if (svc != null) {
                val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: svc.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                if (focused != null) {
                    try {
                        val finalText = if (append) {
                            val existingText = focused.text?.toString() ?: ""
                            existingText + text
                        } else text
                        val bundle = android.os.Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                finalText
                            )
                        }
                        val res = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                        focused.recycle()
                        if (res && svc.isKeyboardVisible()) {
                            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        res
                    } catch (e: Exception) {
                        try { focused.recycle() } catch (_: Exception) {}
                        false
                    }
                } else false
            } else false
            val status = if (ok) "ok" else "error"
            val info = if (ok) "text input delivered" else "text input failed"
            return Triple(status, info, null)
        } else {
            return Triple("error", "Missing text", null)
        }
    }

    private fun handlePressKey(args: JSONObject): Triple<String, String, Any?> {
        val keyCode = args.optInt("keycode", -1)
        if (keyCode >= 0) {
            val svc = DroidrunAccessibilityService.getInstance()
            var ok = false
            if (svc != null) {
                ok = when (keyCode) {
                    3 -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    4 -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    187 -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    66 -> { // ENTER adds newline
                        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: svc.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        if (focused != null) {
                            try {
                                val existing = focused.text?.toString() ?: ""
                                val bundle = android.os.Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                        existing + "\n"
                                    )
                                }
                                val res = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                                focused.recycle()
                                res
                            } catch (e: Exception) {
                                try { focused.recycle() } catch (_: Exception) {}
                                false
                            }
                        } else false
                    }
                    67 -> { // DELETE last char
                        val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: svc.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                        if (focused != null) {
                            try {
                                val existing = focused.text?.toString() ?: ""
                                val newText = if (existing.isNotEmpty()) existing.dropLast(1) else existing
                                val bundle = android.os.Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                        newText
                                    )
                                }
                                val res = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                                focused.recycle()
                                res
                            } catch (e: Exception) {
                                try { focused.recycle() } catch (_: Exception) {}
                                false
                            }
                        } else false
                    }
                    else -> false
                }
            }
            val status = if (ok) "ok" else "error"
            val info = if (ok) "key delivered" else "unsupported keycode without IME"
            return Triple(status, info, null)
        } else {
            return Triple("error", "Invalid or missing keycode", null)
        }
    }

    private fun handleStartApp(args: JSONObject): Triple<String, String, Any?> {
        val pkg = args.optString("package", "")
        val actRaw = args.opt("activity")
        val act = if (actRaw == null || actRaw == JSONObject.NULL) null else actRaw.toString()
        if (pkg.isNotBlank()) {
            try {
                val intent: Intent? = if (act.isNullOrBlank()) {
                    packageManager.getLaunchIntentForPackage(pkg)
                } else {
                    val activityClass = when {
                        act.startsWith(".") -> pkg + act
                        act.contains("/") -> act.substringAfter("/")
                        else -> act
                    }
                    Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(pkg, activityClass)
                    }
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return Triple("ok", "app start requested", null)
                } else {
                    return Triple("error", "Unable to resolve activity", null)
                }
            } catch (e: Exception) {
                return Triple("error", e.message ?: "start_app failed", null)
            }
        } else {
            return Triple("error", "Missing package", null)
        }
    }

    private fun handleListPackages(args: JSONObject): Triple<String, String, Any?> {
        val includeSystem = args.optBoolean("include_system_apps", false)
        try {
            val apps = packageManager.getInstalledApplications(0)
            val result = JSONArray()
            for (app in apps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (includeSystem || !isSystem) {
                    result.put(app.packageName)
                }
            }
            return Triple("ok", "", result)
        } catch (e: Exception) {
            return Triple("error", e.message ?: "list_packages failed", null)
        }
    }

    private fun handleListApps(args: JSONObject): Triple<String, String, Any?> {
        try {
            val uri = Uri.parse("content://com.droidrun.portal/installed_apps")
            val cursor = contentResolver.query(uri, null, null, null, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)
                    
                    if (jsonResponse.getString("status") == "success") {
                        val appsData = JSONArray(jsonResponse.getString("data"))
                        
                        // Convert to base64 encoded PNG data for each app
                        val resultArray = JSONArray()
                        for (i in 0 until appsData.length()) {
                            val app = appsData.getJSONObject(i)
                            val iconPath = app.getString("iconPath")
                            
                            try {
                                val iconFile = File(iconPath)
                                if (iconFile.exists()) {
                                    val bytes = iconFile.readBytes()
                                    val base64Icon = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                                    
                                    val appResult = JSONObject().apply {
                                        put("appName", app.getString("appName"))
                                        put("packageName", app.getString("packageName"))
                                        put("icon", base64Icon)
                                    }
                                    resultArray.put(appResult)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to read icon for ${app.optString("packageName")}", e)
                                // Still add the app without icon
                                val appResult = JSONObject().apply {
                                    put("appName", app.getString("appName"))
                                    put("packageName", app.getString("packageName"))
                                    put("icon", "")
                                }
                                resultArray.put(appResult)
                            }
                        }
                        
                        return Triple("ok", "", resultArray)
                    } else {
                        val error = jsonResponse.optString("error", "Unknown error")
                        return Triple("error", error, null)
                    }
                } else {
                    return Triple("error", "No data returned from content provider", null)
                }
            } ?: return Triple("error", "Failed to query content provider", null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed apps", e)
            return Triple("error", e.message ?: "list_apps failed", null)
        }
    }

    private fun handleStopStream(args: JSONObject): Triple<String, String, Any?> {
        stopVideoStreaming()
        return Triple("ok", "stream_stop_requested", null)
    }

    private fun handleTakeScreenshot(args: JSONObject, id: Int): Triple<String, String, Any?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mediaProjection == null) {
                runOnUiThread {
                    try {
                        val intent = mediaProjectionManager?.createScreenCaptureIntent()
                        if (intent != null && !isRequestingScreenCapture) {
                            isRequestingScreenCapture = true
                            screenCaptureLauncher?.launch(intent)
                        } else {
                            appendLog("⚠ Unable to create screen capture intent")
                        }
                    } catch (e: Exception) {
                        appendLog("⚠ Screen capture permission error: ${e.message}")
                    }
                }
                // Defer sending result; user must grant permission and retry
                return Triple("deferred", "", null)
            } else {
                val tFetchStart = SystemClock.elapsedRealtime()
                val bmp = continuousCapture?.getLastFrame()
                val fetchElapsed = SystemClock.elapsedRealtime() - tFetchStart
                appendLog("frame fetch time: ${fetchElapsed} ms")

                if (bmp != null) {
                    val rawSize = try { bmp.byteCount } catch (_: Exception) { -1 }
                    val currentId = id
                    ioScope.launch {
                        val tCompStart = SystemClock.elapsedRealtime()
                        val bytes = encodeFastJpeg(bmp, maxWidth = 1080, quality = 75, convertToRGB565 = true)
                        val compElapsed = SystemClock.elapsedRealtime() - tCompStart
                        val rawSizeKb = if (rawSize >= 0) rawSize / 1024 else -1
                        val compressedSizeKb = bytes.size / 1024
                        appendLog("compression time: ${compElapsed} ms; size before=${rawSizeKb} KB, after=${compressedSizeKb} KB")
                        val meta = buildImageMetaJson(bytes.size, format = "JPEG")
                        val result = JSONObject().apply {
                            put("type", "action_result")
                            put("id", currentId)
                            put("status", "ok")
                            put("data", meta)
                        }
                        val json = result.toString()
                        appendLog("→ action_result: ${sanitizeForLog(json)}")
                        webSocket?.send(json)
                        try { webSocket?.send(ByteString.of(*bytes)) } catch (_: Exception) {}
                    }
                    return Triple("async", "", null)
                } else {
                    return Triple("error", "no frame yet", null)
                }
            }
        } else {
            return Triple("error", "Android API < 21 not supported", null)
        }
    }

    private fun handleStartJpegStream(args: JSONObject): Triple<String, String, Any?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mediaProjection == null) {
                return Triple("error", "screen_capture_not_ready", null)
            } else {
                val fps = args.optInt("fps", 7).coerceIn(1, 30)
                val quality = args.optInt("quality", 75).coerceIn(30, 95)
                val url = args.optString("url", "").ifBlank {
                    val prefs = getSharedPreferences(WsSettingsActivity.PREFS, MODE_PRIVATE)
                    val base = prefs.getString(WsSettingsActivity.KEY_URL, "ws://10.0.2.2:10001")
                        ?: "ws://10.0.2.2:10001"
                    if (base.endsWith("/stream")) base else base.trimEnd('/') + "/stream"
                }

                startJpegStreaming(url, fps, quality)
                val data = JSONObject().apply {
                    put("url", url)
                    put("fps", fps)
                    put("quality", quality)
                }
                return Triple("ok", "jpeg_stream_start_requested", data)
            }
        } else {
            return Triple("error", "Android API < 21 not supported", null)
        }
    }

    private fun handleUpdateJpegStream(args: JSONObject): Triple<String, String, Any?> {
        if (!wantVideoStream || isH264Stream) {
            return Triple("error", "no_active_jpeg_stream", null)
        } else {
            val newFps = if (args.has("fps")) args.optInt("fps", jpegFps).coerceIn(1, 30) else null
            val newQuality = if (args.has("quality")) args.optInt("quality", jpegQuality).coerceIn(30, 95) else null
            if (newFps != null) jpegFps = newFps
            if (newQuality != null) jpegQuality = newQuality
            try { continuousCapture?.stop() } catch (_: Exception) {}
            mediaProjection?.let { mp ->
                continuousCapture = ContinuousScreenCapture(this, mp, jpegFps).also { it.start() }
            }
            startOrRestartJpegStreamingJob()
            val data = JSONObject().apply { put("fps", jpegFps); put("quality", jpegQuality) }
            return Triple("ok", "jpeg_stream_updated", data)
        }
    }

    private fun handleStartStream(args: JSONObject): Triple<String, String, Any?> {
        // Args: url (optional), fps (default 7), max_width (default 1080), quality (default 70)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mediaProjection == null) {
                return Triple("error", "screen_capture_not_ready", null)
            } else {
                val fps = args.optInt("fps", 7).coerceIn(1, 30)
                val maxWidth = args.optInt("max_width", 1080).coerceAtLeast(320)
                val quality = args.optInt("quality", 70).coerceIn(30, 2000)
                val url = args.optString("url", "").ifBlank {
                    // Derive from control URL preference with "/stream"
                    val prefs = getSharedPreferences(WsSettingsActivity.PREFS, MODE_PRIVATE)
                    val base = prefs.getString(WsSettingsActivity.KEY_URL, "ws://10.0.2.2:10001")
                        ?: "ws://10.0.2.2:10001"
                    if (base.endsWith("/stream")) base else base.trimEnd('/') + "/stream"
                }

                // Stop ContinuousScreenCapture while H.264 streaming is active to avoid multiple virtual displays
                try { continuousCapture?.stop() } catch (_: Exception) {}
                continuousCapture = null
                appendLog("⏸ Continuous screen capture paused during H264 streaming")

                startVideoStreaming(url, fps, maxWidth, quality)
                val started = JSONObject().apply {
                    put("url", url)
                    put("fps", fps)
                    put("max_width", maxWidth)
                    put("quality", quality)
                }
                return Triple("ok", "stream_start_requested", started)
            }
        } else {
            return Triple("error", "Android API < 21 not supported", null)
        }
    }

    private fun handleUpdateStream(args: JSONObject): Triple<String, String, Any?> {
        // Args: fps (1..30, optional), quality (30..95, optional)
        val newFps = if (args.has("fps")) args.optInt("fps", activeStreamFps).coerceIn(1, 30) else null
        val newQuality = if (args.has("quality")) args.optInt("quality", activeStreamQuality).coerceIn(30, 2000) else null

        if (h264Streamer == null || !isVideoConnected) {
            return Triple("error", "no_active_stream", null)
        } else {
            var changed = false

            // Update quality -> bitrate on the fly
            if (newQuality != null && newQuality != activeStreamQuality) {
                val newBitrate = (newQuality * 25) * 1000
                try { h264Streamer?.updateBitrate(newBitrate) } catch (_: Exception) {}
                try { h264Streamer?.requestKeyFrame() } catch (_: Exception) {}
                activeStreamQuality = newQuality
                changed = true
            }

            // Update fps -> requires restarting the encoder
            if (newFps != null && newFps != activeStreamFps) {
                restartH264Streamer(newFps, activeStreamQuality)
                // Also align the continuous capture helper for screenshots
                try { continuousCapture?.stop() } catch (_: Exception) {}
                continuousCapture = mediaProjection?.let { mp ->
                    ContinuousScreenCapture(this, mp, newFps).also { it.start() }
                }
                activeStreamFps = newFps
                changed = true
            }

            val status = if (changed) "ok" else "ok"
            val data = JSONObject().apply {
                put("fps", activeStreamFps)
                put("quality", activeStreamQuality)
            }
            val info = if (changed) "stream_updated" else "no_change"
            return Triple(status, info, data)
        }
    }

    private fun processAction(action: JSONObject) {
        val id = action.optInt("id", -1)
        val name = action.optString("name")
        val args = action.optJSONObject("args") ?: JSONObject()

        var status = "error"
        var info = "Unknown action: $name"
        var data: Any? = null

        try {
            when (name) {
                "get_state" -> {
                    val (newStatus, newInfo, newData) = handleGetState(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "get_device_info" -> {
                    val (newStatus, newInfo, newData) = handleGetDeviceInfo(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "tap_by_coordinates" -> {
                    val (newStatus, newInfo, newData) = handleTapByCoordinates(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "swipe" -> {
                    val (newStatus, newInfo, newData) = handleSwipe(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "gesture_path" -> {
                    val (newStatus, newInfo, newData) = handleGesturePath(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "back" -> {
                    val (newStatus, newInfo, newData) = handleBack(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "input_text" -> {
                    val (newStatus, newInfo, newData) = handleInputText(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "press_key" -> {
                    val (newStatus, newInfo, newData) = handlePressKey(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "start_app" -> {
                    val (newStatus, newInfo, newData) = handleStartApp(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "list_packages" -> {
                    val (newStatus, newInfo, newData) = handleListPackages(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "list_apps" -> {
                    val (newStatus, newInfo, newData) = handleListApps(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "take_screenshot" -> {
                    val result = handleTakeScreenshot(args, id)
                    if (result.first == "deferred" || result.first == "async") {
                        return
                    } else {
                        status = result.first
                        info = result.second
                        data = result.third
                    }
                }
                "start_jpeg_stream" -> {
                    val (newStatus, newInfo, newData) = handleStartJpegStream(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "update_jpeg_stream" -> {
                    val (newStatus, newInfo, newData) = handleUpdateJpegStream(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                
                "start_stream" -> {
                    val (newStatus, newInfo, newData) = handleStartStream(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "update_stream" -> {
                    val (newStatus, newInfo, newData) = handleUpdateStream(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                "stop_stream" -> {
                    val (newStatus, newInfo, newData) = handleStopStream(args)
                    status = newStatus
                    info = newInfo
                    data = newData
                }
                else -> {
                    // leave status=error
                }
            }
        } catch (e: Exception) {
            status = "error"
            info = e.message ?: "exception"
            Log.e(TAG, "Action processing failed", e)
        }

        val result = JSONObject().apply {
            put("type", "action_result")
            put("id", id)
            put("status", status)
            if (data != null) put("data", data) else put("info", info)
        }
        val json = result.toString()
        appendLog("→ action_result: ${sanitizeForLog(json)}")
        webSocket?.send(json)
    }

    // ------------------------------ Video streaming (MJPEG over WS) ------------------------------
    private fun startVideoStreaming(url: String, fps: Int, maxWidth: Int, quality: Int) {
        // Avoid duplicate jobs
        streamingJob?.cancel()

        desiredVideoUrl = url
        wantVideoStream = true
        maintainForegroundService()

        // Open or reuse video WS
        if (!isVideoConnected || videoSocket == null) {
            connectVideo(url, null)
        }

        // Start H.264 encoder and pipe to WS
        isH264Stream = true
        val bitrate = (quality.coerceIn(30, 2000) * 25) * 1000 // crude mapping q->kbps
        val mp = mediaProjection
        if (mp == null) {
            appendLog("⚠ Cannot start H264: mediaProjection null")
            return
        }
        // Store desired params; actual start happens after handshake OK
        activeStreamFps = fps
        activeStreamQuality = quality.coerceIn(30, 2000)
        if (videoHandshakeOk) {
            sendVideoStreamStart()
            restartH264Streamer(activeStreamFps, activeStreamQuality)
        } else {
            appendLog("waiting for video handshake ack before starting stream…")
        }
    }

    private fun startJpegStreaming(url: String, fps: Int, quality: Int) {
        streamingJob?.cancel()
        desiredVideoUrl = url
        wantVideoStream = true
        isH264Stream = false
        jpegFps = fps
        jpegQuality = quality
        maintainForegroundService()

        // Ensure ContinuousScreenCapture at requested fps
        try { continuousCapture?.stop() } catch (_: Exception) {}
        mediaProjection?.let { mp ->
            continuousCapture = ContinuousScreenCapture(this, mp, jpegFps).also { it.start() }
        }

        if (!isVideoConnected || videoSocket == null) {
            connectVideo(url, null)
        }

        if (videoHandshakeOk) { sendJpegStreamStart(); startOrRestartJpegStreamingJob() } else appendLog("waiting for video handshake ack before starting JPEG stream…")
    }

    private fun restartH264Streamer(fps: Int, quality: Int) {
        val mp = mediaProjection ?: return
        val bitrate = (quality.coerceIn(30, 2000) * 25) * 1000
        try { h264Streamer?.stop() } catch (_: Exception) {}
        h264Streamer = H264ScreenStreamer(this, mp, targetFps = fps, targetBitrate = bitrate).also { streamer ->
            streamer.start { nalBytes ->
                val qSize = try { videoSocket?.queueSize() ?: 0L } catch (_: Exception) { 0L }
                if (qSize < 6_000_000L) {
                    // Wrap each Annex-B payload with 4-byte big-endian length header
                    val header = ByteBuffer.allocate(4).putInt(nalBytes.size).array()
                    val framed = ByteArray(header.size + nalBytes.size).also {
                        System.arraycopy(header, 0, it, 0, header.size)
                        System.arraycopy(nalBytes, 0, it, header.size, nalBytes.size)
                    }
                    try { videoSocket?.send(ByteString.of(*framed)) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopVideoStreaming() {
        try { streamingJob?.cancel() } catch (_: Exception) {}
        streamingJob = null
        try { h264Streamer?.stop() } catch (_: Exception) {}
        h264Streamer = null
        try { disconnectVideo() } catch (_: Exception) {}
        wantVideoStream = false
        cancelVideoReconnect()
        maintainForegroundService()
        // Resume ContinuousScreenCapture for snapshots/telemetry after streaming stops
        val mp = mediaProjection
        if (mp != null && !userStoppedCapture && continuousCapture == null) {
            try {
                continuousCapture = ContinuousScreenCapture(this, mp, 7).also { it.start() }
                appendLog("▶ Continuous screen capture resumed (~7 fps)")
            } catch (_: Exception) {}
        }
    }

    private fun connectVideo(url: String, frameAfterConnect: String?) {
        appendLog("Connecting video WS to $url …")

        val client = OkHttpClient.Builder()
            .pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        videoSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isVideoConnected = true
                videoHandshakeOk = false
                videoReconnectAttempts = 0
                appendLog("✔ Video WS connected (${response.code})")
                // Identification handshake: send serial first
                val serial = try { DeviceInfoProvider.collect(this@MainActivity).optString("serial", "") } catch (_: Exception) { "" }
                val identify = JSONObject().apply {
                    put("type", "identify")
                    put("serial", serial)
                }.toString()
                try { webSocket.send(identify) } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                appendLog("← video/text: $text")
                // Detect handshake ack; accept common forms
                var acked = false
                try {
                    val obj = JSONObject(text)
                    val status = obj.optString("status").lowercase()
                    val type = obj.optString("type").lowercase()
                    val okFlag = obj.optBoolean("ok", false)
                    acked = (status == "ok") || okFlag || type in setOf("ok", "identify_ok", "identify_ack", "ack")
                } catch (_: Exception) {
                    val t = text.trim().lowercase()
                    acked = (t == "ok" || t == "okay" || t == "ready")
                }
                if (acked && !videoHandshakeOk) {
                    videoHandshakeOk = true
                    appendLog("✔ Video handshake acknowledged by server")
                    if (isH264Stream) {
                        sendVideoStreamStart()
                        restartH264Streamer(activeStreamFps, activeStreamQuality)
                    } else {
                        sendJpegStreamStart()
                        startOrRestartJpegStreamingJob()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Typically unused for incoming on video WS
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("Video WS closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                appendLog("✖ Video WS disconnected: $code $reason")
                isVideoConnected = false
                videoHandshakeOk = false
                // Do not auto-reconnect video WS; fully stop streaming until explicitly requested again
                try { stopVideoStreaming() } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                appendLog("⚠ Video WS failure: ${t.message}")
                Log.e(TAG, "Video WebSocket error", t)
                isVideoConnected = false
                videoHandshakeOk = false
                // Do not auto-reconnect video WS; fully stop streaming until explicitly requested again
                try { stopVideoStreaming() } catch (_: Exception) {}
            }
        })
    }

    private fun disconnectVideo() {
        appendLog("Disconnecting video WS …")
        try { videoSocket?.close(1000, "stream end") } catch (_: Exception) {}
        videoSocket = null
        isVideoConnected = false
        videoHandshakeOk = false
    }

    private fun sendVideoStreamStart() {
        val vs = videoSocket ?: return
        val serial = try { DeviceInfoProvider.collect(this).optString("serial", "") } catch (_: Exception) { "" }
        val start = JSONObject().apply {
            put("type", "stream_start")
            put("format", "H264")
            put("fps", activeStreamFps)
            put("bitrate_kbps", (activeStreamQuality * 25))
            put("serial", serial)
        }.toString()
        try { vs.send(start) } catch (_: Exception) {}
    }

    private fun sendJpegStreamStart() {
        val vs = videoSocket ?: return
        val serial = try { DeviceInfoProvider.collect(this).optString("serial", "") } catch (_: Exception) { "" }
        val start = JSONObject().apply {
            put("type", "stream_start")
            put("format", "JPEG")
            put("fps", jpegFps)
            put("quality", jpegQuality)
            put("serial", serial)
        }.toString()
        try { vs.send(start) } catch (_: Exception) {}
    }

    private fun startOrRestartJpegStreamingJob() {
        try { streamingJob?.cancel() } catch (_: Exception) {}
        val vs = videoSocket ?: return
        val frameIntervalMs = (1000L / jpegFps.coerceIn(1, 30)).coerceAtLeast(10L)
        streamingJob = ioScope.launch {
            while (isActive && wantVideoStream && !isH264Stream) {
                val bmp = continuousCapture?.getLastFrame()
                if (bmp != null) {
                    val bytes = encodeFastJpeg(bmp, maxWidth = DEFAULT_JPEG_WIDTH, quality = jpegQuality, convertToRGB565 = true)
                    val header = ByteBuffer.allocate(4).putInt(bytes.size).array()
                    val framed = ByteArray(header.size + bytes.size).also {
                        System.arraycopy(header, 0, it, 0, header.size)
                        System.arraycopy(bytes, 0, it, header.size, bytes.size)
                    }
                    val qSize = try { vs.queueSize() } catch (_: Exception) { 0L }
                    if (qSize < 6_000_000L) {
                        try { vs.send(ByteString.of(*framed)) } catch (_: Exception) {}
                    }
                }
                delay(frameIntervalMs)
            }
        }
    }

    private fun sendActionResult(id: Int, status: String, info: String? = null, data: Any? = null) {
        val result = JSONObject().apply {
            put("type", "action_result")
            put("id", id)
            put("status", status)
            if (data != null) put("data", data) else put("info", info ?: "")
        }
        val json = result.toString()
        appendLog("→ action_result: ${sanitizeForLog(json)}")
        webSocket?.send(json)
    }

    private suspend fun encodeFastJpeg(
        src: Bitmap,
        maxWidth: Int = 1080,
        quality: Int = 75,
        convertToRGB565: Boolean = true
    ): ByteArray = withContext(Dispatchers.Default) {
        val scale = if (src.width > maxWidth) maxWidth.toFloat() / src.width else 1f
        val scaled: Bitmap = if (scale < 0.999f) {
            val newW = (src.width * scale).roundToInt()
            val newH = (src.height * scale).roundToInt()
            Bitmap.createScaledBitmap(src, newW, newH, true)
        } else {
            src
        }

        val toCompress: Bitmap = if (convertToRGB565 && scaled.config != Bitmap.Config.RGB_565) {
            try {
                scaled.copy(Bitmap.Config.RGB_565, false)
            } catch (_: Throwable) {
                scaled
            }
        } else scaled

        val estimated = (toCompress.byteCount / 6).coerceAtLeast(32 * 1024)
        val baos = java.io.ByteArrayOutputStream(estimated)
        toCompress.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val bytes = baos.toByteArray()

        if (toCompress !== src && toCompress !== scaled) toCompress.recycle()
        if (scaled !== src) scaled.recycle()

        bytes
    }

    private fun fetchCombinedStateFromProvider(): String {
        val uri = Uri.parse("content://com.droidrun.portal/state")
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val res = it.getString(0)
                val obj = JSONObject(res)
                if (obj.optString("status") == "success") {
                    return obj.getString("data")
                } else {
                    throw RuntimeException(obj.optString("error"))
                }
            }
        }
        throw RuntimeException("No data returned from ContentProvider")
    }
    
    // Check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = packageName + "/" + DroidrunAccessibilityService::class.java.canonicalName
        
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            return enabledServices?.contains(accessibilityServiceName) == true
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking accessibility status: ${e.message}")
            return false
        }
    }
    
    // Update the accessibility status indicator based on service status
    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        if (isEnabled) {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            accessibilityStatusText.text = "ENABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
        } else {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            accessibilityStatusText.text = "DISABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
        }
    }
    
    // Open accessibility settings to enable the service
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please enable Droidrun Portal in Accessibility Services",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening accessibility settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening accessibility settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val version = packageInfo.versionName
            versionText.text = "Version: $version"
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error getting app version: ${e.message}")
            versionText.text = "Version: N/A"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopVideoStreaming() } catch (_: Exception) {}
        try { continuousCapture?.stop() } catch (_: Exception) {}
        try { mediaProjectionCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) } } catch (_: Exception) {}
        mediaProjectionCallback = null
        try { networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        networkCallback = null
        cancelControlReconnect()
        cancelVideoReconnect()
    }

    private fun reenableScreenCapture() {
        appendLog("Re-enabling screen capture …")
        userStoppedCapture = false
        try { h264Streamer?.stop() } catch (_: Exception) {}
        h264Streamer = null
        try { continuousCapture?.stop() } catch (_: Exception) {}
        continuousCapture = null
        try { mediaProjectionCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) } } catch (_: Exception) {}
        mediaProjectionCallback = null
        mediaProjection = null
        sharedMediaProjection = null

        try { startForegroundService(Intent(this, ScreenCaptureService::class.java)) } catch (_: Exception) {}
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null && !isRequestingScreenCapture) {
            isRequestingScreenCapture = true
            try { screenCaptureLauncher?.launch(intent) } catch (e: Exception) {
                isRequestingScreenCapture = false
                appendLog("⚠ Failed to launch screen capture intent: ${e.message}")
            }
        } else {
            appendLog("⚠ Unable to create screen capture intent")
        }
    }

    private fun stopScreenCapture() {
        appendLog("Stopping screen capture to save CPU …")
        userStoppedCapture = true
        // Stop streaming/encoders
        try { stopVideoStreaming() } catch (_: Exception) {}
        try { h264Streamer?.stop() } catch (_: Exception) {}
        h264Streamer = null
        // Stop continuous capture
        try { continuousCapture?.stop() } catch (_: Exception) {}
        continuousCapture = null
        // Unregister callbacks and release projection
        try { mediaProjectionCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) } } catch (_: Exception) {}
        mediaProjectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        sharedMediaProjection = null
        // Optionally stop the foreground service notification
        try { stopService(Intent(this, ScreenCaptureService::class.java)) } catch (_: Exception) {}
        appendLog("✔ Screen capture stopped. Use 'Re-enable Screen Capture' to resume.")
    }
} 
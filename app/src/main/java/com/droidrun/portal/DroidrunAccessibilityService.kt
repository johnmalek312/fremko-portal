package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class DroidrunAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DroidrunA11yService"
        private var instance: DroidrunAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5

        // Periodic update constants
        private const val REFRESH_INTERVAL_MS = 250L // Update every 250ms
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)

        fun getInstance(): DroidrunAccessibilityService? = instance
    }

    private lateinit var overlayManager: OverlayManager
    private val screenBounds = Rect()
    private lateinit var configManager: ConfigManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Periodic update state
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""
    private val visibleElements = mutableListOf<ElementNode>()

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        screenBounds.set(0, 0, bounds.width(), bounds.height())
        
        // Initialize ConfigManager
        configManager = ConfigManager.getInstance(this)
        isInitialized = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager.showOverlay()
        instance = this

        // Configure accessibility service
        serviceInfo = AccessibilityServiceInfo().apply {
            // Listen to all events
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            // Monitor all packages
            packageNames = null

            // Set feedback type
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Set flags for better access
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            // Enable screenshot capability (API 34+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
        }

        // Apply loaded configuration
        applyConfiguration()

        // Start periodic updates
        startPeriodicUpdates()

        Log.d(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""
        
        // Detect package changes
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }
        
        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }
        
        // Trigger update on relevant events
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Let the periodic runnable handle updates
            }
        }
    }

    // Periodic update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized && configManager.overlayVisible) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime
                
                if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                    refreshVisibleElements()
                    lastUpdateTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "Started periodic updates")
    }
    
    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped periodic updates")
    }

    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }
        
        try {
            if (currentPackageName.isEmpty()) {
                overlayManager.clearElements()
                overlayManager.refreshOverlay()
                return
            }
            
            // Clear previous elements
            clearElementList()
            
            // Get fresh elements
            val elements = getVisibleElementsInternal()
            
            // Update overlay if visible
            if (configManager.overlayVisible && elements.isNotEmpty()) {
                overlayManager.clearElements()
                
                elements.forEach { rootElement ->
                    addElementAndChildrenToOverlay(rootElement, 0)
                }
                
                overlayManager.refreshOverlay()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun resetOverlayState() {
        try {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
            clearElementList()
            Log.d(TAG, "Reset overlay state for package change")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting overlay state: ${e.message}", e)
        }
    }

    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling node: ${e.message}")
            }
        }
        visibleElements.clear()
    }

    private fun applyConfiguration() {
        mainHandler.post {
            try {
                val config = configManager.getCurrentConfiguration()
                if (config.overlayVisible) {
                    overlayManager.showOverlay()
                } else {
                    overlayManager.hideOverlay()
                }
                overlayManager.setPositionOffsetY(config.overlayOffset)
                Log.d(TAG, "Applied configuration: overlayVisible=${config.overlayVisible}, overlayOffset=${config.overlayOffset}")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}", e)
            }
        }
    }

    // Public methods for MainActivity to call directly
    fun setOverlayVisible(visible: Boolean): Boolean {
        return try {
            configManager.overlayVisible = visible
            
            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    // Trigger immediate refresh when showing overlay
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }
            
            Log.d(TAG, "Overlay visibility set to: $visible")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay visibility: ${e.message}", e)
            false
        }
    }

    fun isOverlayVisible(): Boolean = configManager.overlayVisible

    fun setOverlayOffset(offset: Int): Boolean {
        return try {
            configManager.overlayOffset = offset
            
            mainHandler.post {
                overlayManager.setPositionOffsetY(offset)
            }
            
            Log.d(TAG, "Overlay offset set to: $offset")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay offset: ${e.message}", e)
            false
        }
    }

    fun getOverlayOffset(): Int = configManager.overlayOffset



    fun getVisibleElements(): MutableList<ElementNode> {
        return getVisibleElementsInternal()
    }

    private fun getVisibleElementsInternal(): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1) // Start indexing from 1

        val rootNode = rootInActiveWindow ?: return elements
        val rootElement = findAllVisibleElements(rootNode, 0, null, indexCounter)
        rootElement?.let {
            collectRootElements(it, elements)
        }

        // Store the elements for cleanup later
        synchronized(visibleElements) {
            clearElementList()
            visibleElements.addAll(elements)
        }

        return elements
    }

    private fun collectRootElements(element: ElementNode, rootElements: MutableList<ElementNode>) {
        rootElements.add(element)
    }

    private fun findAllVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        indexCounter: IndexCounter
    ): ElementNode? {
        try {

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isInScreen = Rect.intersects(rect, screenBounds)
            val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE

            var currentElement: ElementNode? = null

            if (isInScreen && hasSize) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                val displayText = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                    else -> className.substringAfterLast('.')
                }

                val elementType = if (node.isClickable) {
                    "Clickable"
                } else if (node.isCheckable) {
                    "Checkable"
                } else if (node.isEditable) {
                    "Input"
                } else if (text.isNotEmpty()) {
                    "Text"
                } else if (node.isScrollable) {
                    "Container"
                } else {
                    "View"
                }

                val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)

                currentElement = ElementNode(
                    AccessibilityNodeInfo(node),
                    Rect(rect),
                    displayText,
                    className.substringAfterLast('.'),
                    windowLayer,
                    System.currentTimeMillis(),
                    id
                )

                // Assign unique index
                currentElement.overlayIndex = indexCounter.getNext()

                // Set parent-child relationship
                parent?.addChild(currentElement)
            }

            // Recursively process children
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                val childElement = findAllVisibleElements(childNode, windowLayer, currentElement, indexCounter)
                // Children are already added to currentElement via parent?.addChild() call above
            }

            return currentElement

        } catch (e: Exception) {
            Log.e(TAG, "Error in findAllVisibleElements: ${e.message}", e)
            return null
        }
    }

     fun getPhoneState(): PhoneState {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val keyboardVisible = detectKeyboardVisibility()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val appName = getAppName(currentPackage)

        return PhoneState(focusedNode, keyboardVisible, currentPackage, appName)
    }

    private fun detectKeyboardVisibility(): Boolean {
        try {
            val windows = windows
            if (windows != null) {
                val hasInputMethodWindow = windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                windows.forEach { it.recycle() }
                return hasInputMethodWindow
            } else { return false }
        } catch (e: Exception) { return false}
    }

    fun isKeyboardVisible(): Boolean {
        return detectKeyboardVisibility()
    }

    private fun getAppName(packageName: String?): String? {
        return try {
            if (packageName == null) return null
            
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package $packageName: ${e.message}")
            null
        }
    }

    // Helper class to maintain global index counter
    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        stopPeriodicUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        clearElementList()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    private fun addElementAndChildrenToOverlay(element: ElementNode, depth: Int) {
        overlayManager.addElement(
            text = element.text,
            rect = element.rect,
            type = element.className,
            index = element.overlayIndex
        )

        for (child in element.children) {
            addElementAndChildrenToOverlay(child, depth + 1)
        }
    }
}
package com.droidrun.portal

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized configuration manager for Droidrun Portal
 * Handles SharedPreferences operations and provides a clean API for configuration management
 */
class ConfigManager private constructor(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "droidrun_config"
        private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        private const val KEY_OVERLAY_OFFSET = "overlay_offset"
        private const val DEFAULT_OFFSET = 0
        
        @Volatile
        private var INSTANCE: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Overlay visibility
    var overlayVisible: Boolean
        get() = sharedPrefs.getBoolean(KEY_OVERLAY_VISIBLE, true)
        set(value) {
            sharedPrefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, value).apply()
        }
    
    // Overlay offset
    var overlayOffset: Int
        get() = sharedPrefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        set(value) {
            sharedPrefs.edit().putInt(KEY_OVERLAY_OFFSET, value).apply()
        }
    
    // Listener interface for configuration changes
    interface ConfigChangeListener {
        fun onOverlayVisibilityChanged(visible: Boolean)
        fun onOverlayOffsetChanged(offset: Int)
    }
    
    private val listeners = mutableSetOf<ConfigChangeListener>()
    
    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }
    
    fun setOverlayVisibleWithNotification(visible: Boolean) {
        overlayVisible = visible
        listeners.forEach { it.onOverlayVisibilityChanged(visible) }
    }
    
    fun setOverlayOffsetWithNotification(offset: Int) {
        overlayOffset = offset
        listeners.forEach { it.onOverlayOffsetChanged(offset) }
    }
    
    // Bulk configuration update
    fun updateConfiguration(overlayVisible: Boolean? = null, overlayOffset: Int? = null) {
        val editor = sharedPrefs.edit()
        var hasChanges = false
        
        overlayVisible?.let {
            editor.putBoolean(KEY_OVERLAY_VISIBLE, it)
            hasChanges = true
        }
        
        overlayOffset?.let {
            editor.putInt(KEY_OVERLAY_OFFSET, it)
            hasChanges = true
        }
        
        if (hasChanges) {
            editor.apply()
            
            // Notify listeners
            overlayVisible?.let { listeners.forEach { listener -> listener.onOverlayVisibilityChanged(it) } }
            overlayOffset?.let { listeners.forEach { listener -> listener.onOverlayOffsetChanged(it) } }
        }
    }
    
    // Get all configuration as a data class
    data class Configuration(
        val overlayVisible: Boolean,
        val overlayOffset: Int
    )
    
    fun getCurrentConfiguration(): Configuration {
        return Configuration(
            overlayVisible = overlayVisible,
            overlayOffset = overlayOffset
        )
    }
} 
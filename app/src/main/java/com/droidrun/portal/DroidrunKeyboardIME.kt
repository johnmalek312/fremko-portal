package com.droidrun.portal

import android.inputmethodservice.InputMethodService
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest

class DroidrunKeyboardIME : InputMethodService() {
    private val TAG = "DroidrunKeyboardIME"

    companion object {
        private var instance: DroidrunKeyboardIME? = null
        
        fun getInstance(): DroidrunKeyboardIME? = instance
        
        /**
         * Check if the DroidrunKeyboardIME is currently active and available
         */
        fun isAvailable(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "DroidrunKeyboardIME: onCreate() called")
    }

    fun inputText(text: String): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                ic.commitText(text, 1)
                Log.d(TAG, "Direct text input successful: $text")
                true
            } else {
                Log.w(TAG, "No input connection available for direct input")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct text input", e)
            false
        }
    }

    /**
     * Direct method to input text from Base64 without using broadcasts
     */
    fun inputB64Text(base64Text: String): Boolean {
        return try {
            val decoded = Base64.decode(base64Text, Base64.DEFAULT)
            val text = String(decoded, Charsets.UTF_8)
            inputText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 for direct input", e)
            false
        }
    }

    fun inputHexText(hexText: String): Boolean {
        return try {
            // Remove any whitespace or separators
            val cleanHex = hexText.replace("\\s".toRegex(), "").replace(":", "").replace("-", "")
            
            // Check if hex string has even length
            if (cleanHex.length % 2 != 0) {
                Log.e(TAG, "Invalid hex string length: ${cleanHex.length}")
                return false
            }
            
            // Convert hex to byte array
            val bytes = ByteArray(cleanHex.length / 2)
            for (i in bytes.indices) {
                val index = i * 2
                bytes[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
            }
            
            val text = String(bytes, Charsets.UTF_8)
            inputText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding hex for direct input", e)
            false
        }
    }

    /**
     * Direct method to clear text without using broadcasts
     */
    fun clearText(): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                val extractedText = ic.getExtractedText(ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    val curPos = extractedText.text
                    val beforePos = ic.getTextBeforeCursor(curPos.length, 0)
                    val afterPos = ic.getTextAfterCursor(curPos.length, 0)
                    ic.deleteSurroundingText(beforePos?.length ?: 0, afterPos?.length ?: 0)
                    Log.d(TAG, "Direct text clear successful")
                    true
                } else {
                    Log.w(TAG, "No extracted text available for clearing")
                    false
                }
            } else {
                Log.w(TAG, "No input connection available for direct clear")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in direct text clear", e)
            false
        }
    }

    /**
     * Direct method to send key events without using broadcasts
     */
    fun sendKeyEventDirect(keyCode: Int): Boolean {
        return try {
            val ic = currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                Log.d(TAG, "Direct key event sent: $keyCode")
                true
            } else {
                Log.w(TAG, "No input connection available for direct key event")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending direct key event", e)
            false
        }
    }

    /**
     * Get current input connection status
     */
    fun hasInputConnection(): Boolean {
        return currentInputConnection != null
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView called")
        
        // Inflate the existing keyboard layout XML
        return layoutInflater.inflate(R.layout.keyboard_view, null)
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput called - restarting: $restarting")
    }

    override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        Log.d(TAG, "onStartInputView called - keyboard should be visible now")
    }

    override fun onDestroy() {
        Log.d(TAG, "DroidrunKeyboardIME: onDestroy() called")
        instance = null
        super.onDestroy()
    }
}
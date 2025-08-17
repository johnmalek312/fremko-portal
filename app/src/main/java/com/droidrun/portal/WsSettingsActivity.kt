package com.droidrun.portal

import android.os.Bundle
import android.widget.EditText
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class WsSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "ws_settings"
        const val KEY_URL = "url"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model"
        const val KEY_STEPS = "steps"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_REASONING = "reasoning"
        const val KEY_REFLECTION = "reflection"
        const val KEY_TRACING = "tracing"
        const val KEY_DEBUG = "debug"
        const val KEY_VISION = "vision"
    }

    private lateinit var urlEdit: EditText
    private lateinit var providerEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var stepsEdit: EditText
    private lateinit var timeoutEdit: EditText
    private lateinit var reasoningSw: SwitchMaterial
    private lateinit var reflectionSw: SwitchMaterial
    private lateinit var tracingSw: SwitchMaterial
    private lateinit var debugSw: SwitchMaterial
    private lateinit var visionSw: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ws_settings)

        urlEdit = findViewById(R.id.ws_url_edit)
        providerEdit = findViewById(R.id.ws_provider_edit)
        modelEdit = findViewById(R.id.ws_model_edit)
        stepsEdit = findViewById(R.id.ws_steps_edit)
        timeoutEdit = findViewById(R.id.ws_timeout_edit)
        reasoningSw = findViewById(R.id.ws_reasoning_switch)
        reflectionSw = findViewById(R.id.ws_reflection_switch)
        tracingSw = findViewById(R.id.ws_tracing_switch)
        debugSw = findViewById(R.id.ws_debug_switch)
        visionSw = findViewById(R.id.ws_vision_switch)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        urlEdit.setText(prefs.getString(KEY_URL, "ws://10.0.2.2:10001"))
        providerEdit.setText(prefs.getString(KEY_PROVIDER, ""))
        modelEdit.setText(prefs.getString(KEY_MODEL, ""))
        stepsEdit.setText(prefs.getInt(KEY_STEPS, 150).toString())
        timeoutEdit.setText(prefs.getInt(KEY_TIMEOUT, 1000).toString())
        reasoningSw.isChecked = prefs.getBoolean(KEY_REASONING, false)
        reflectionSw.isChecked = prefs.getBoolean(KEY_REFLECTION, false)
        tracingSw.isChecked = prefs.getBoolean(KEY_TRACING, false)
        debugSw.isChecked = prefs.getBoolean(KEY_DEBUG, false)
        visionSw.isChecked = prefs.getBoolean(KEY_VISION, false)

        findViewById<MaterialButton>(R.id.ws_settings_save_btn).setOnClickListener {
            saveAndFinish()
        }
    }

    private fun saveAndFinish() {
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            prefs.putString(KEY_URL, urlEdit.text.toString())
            prefs.putString(KEY_PROVIDER, providerEdit.text.toString())
            prefs.putString(KEY_MODEL, modelEdit.text.toString())
            prefs.putInt(KEY_STEPS, stepsEdit.text.toString().toIntOrNull() ?: 150)
            prefs.putInt(KEY_TIMEOUT, timeoutEdit.text.toString().toIntOrNull() ?: 1000)
            prefs.putBoolean(KEY_REASONING, reasoningSw.isChecked)
            prefs.putBoolean(KEY_REFLECTION, reflectionSw.isChecked)
            prefs.putBoolean(KEY_TRACING, tracingSw.isChecked)
            prefs.putBoolean(KEY_DEBUG, debugSw.isChecked)
            prefs.putBoolean(KEY_VISION, visionSw.isChecked)
            prefs.apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
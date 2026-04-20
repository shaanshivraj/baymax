package com.phoneai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.widget.SeekBar
import android.content.Context

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_AUDIO = 100
    }

    // Handles result from "Draw over other apps" settings screen
    private val overlayResultLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { checkAndLaunch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            requestPermissionsAndLaunch()
        }

        // Silent OTA update check on every launch
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            UpdateChecker.checkAndPrompt(this@MainActivity)
        }

        setupCustomization()
    }

    private fun setupCustomization() {
        val prefs = getSharedPreferences("phoneai_prefs", Context.MODE_PRIVATE)
        // Default size is 66dp. Let's map it: 40dp to 120dp. 
        // SeekBar 0-100 maps to 40-140dp. Default 66dp means progress 26.
        val currentSize = prefs.getInt("bubble_size", 66)
        
        val seekSize = findViewById<SeekBar>(R.id.seekSize)
        val tvLabel = findViewById<TextView>(R.id.tvSizeLabel)
        
        seekSize.progress = currentSize - 40
        tvLabel.text = "Bubble Size: ${currentSize}dp"

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = progress + 40
                tvLabel.text = "Bubble Size: ${newSize}dp"
                prefs.edit().putInt("bubble_size", newSize).apply()
                
                // Notify service to update instantly
                sendBroadcast(Intent("com.phoneai.app.UPDATE_SIZE"))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
        // Auto-launch if all permissions already granted
        if (hasAllPermissions()) startBubbleService()
    }

    private fun updateStatusText() {
        val tv = findViewById<TextView>(R.id.tvStatus)
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        tv.text = when {
            overlay && mic -> "✅ Ready! Launching floating bubble..."
            !overlay       -> "⚠️ Need 'Draw over other apps' permission"
            !mic           -> "⚠️ Need microphone permission"
            else           -> "Tap below to start"
        }
    }

    private fun requestPermissionsAndLaunch() {
        // Step 1: Microphone
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        // Step 2: Draw over other apps (system overlay)
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayResultLauncher.launch(intent)
            return
        }
        startBubbleService()
    }

    private fun checkAndLaunch() {
        if (hasAllPermissions()) startBubbleService()
        else updateStatusText()
    }

    private fun hasAllPermissions(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return overlay && mic
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Minimize so the floating bubble is visible
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) requestPermissionsAndLaunch()
    }
}

package com.example.appnewtry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.Toast
import android.util.Log

class MainActivity : ComponentActivity() {
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            checkAndRequestPermissions()
        } else {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d("MainActivity", "MediaProjection permission granted with resultCode: ${result.resultCode}")
            val serviceIntent = Intent(this, FloatingWindowService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("resultData", result.data)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("MainActivity", "Service started with projection data: resultCode=${result.resultCode}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error starting service: ${e.message}")
                Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("MainActivity", "MediaProjection permission denied: resultCode=${result.resultCode}")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Storage permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStartFloating).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                checkAndRequestPermissions()
            }
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        Log.d("MainActivity", "Requesting MediaProjection permission")
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting projection: ${e.message}")
            Toast.makeText(this, "Failed to request screen capture", Toast.LENGTH_SHORT).show()
        }
    }
}
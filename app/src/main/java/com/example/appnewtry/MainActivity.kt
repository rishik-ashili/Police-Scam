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
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.concurrent.fixedRateTimer

class MainActivity : ComponentActivity() {
    private lateinit var locationHelper: LocationHelper
    private lateinit var notificationAdapter: NotificationAdapter
    private var random = Random()

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
            Toast.makeText(this, "Required permissions were denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAPTURE_AUDIO_OUTPUT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize LocationHelper
        locationHelper = LocationHelper(this)

        // Initialize RecyclerView and adapter
        setupNotificationsRecyclerView()

        // Setup location updates
        setupLocationUpdates()

        // Setup scam statistics updates
        setupScamStatistics()

        // Setup awareness news
        setupAwarenessNews()

        findViewById<Button>(R.id.btnStartFloating).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                checkAndRequestPermissions()
            }
        }

        // Start periodic updates
        startPeriodicUpdates()
    }

    private fun setupNotificationsRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.notificationsRecyclerView)
        notificationAdapter = NotificationAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = notificationAdapter
        }

        // Add some initial notifications
        addSampleNotifications()
    }

    private fun setupLocationUpdates() {
        lifecycleScope.launch {
            locationHelper.locationFlow.collect { location ->
                location?.let {
                    val locationText = findViewById<TextView>(R.id.locationText)
                    locationText.text = String.format(
                        "%.6f, %.6f",
                        location.latitude,
                        location.longitude
                    )
                }
            }
        }
    }

    private fun setupScamStatistics() {
        val scamStatsText = findViewById<TextView>(R.id.scamStatsText)
        val randomScams = random.nextInt(5) + 2 // Random number between 2 and 6
        scamStatsText.text = "$randomScams scams detected in your area in the last 24 hours"
    }

    private fun setupAwarenessNews() {
        val awarenessNews = findViewById<TextView>(R.id.awarenessNews)
        awarenessNews.text = """
            • New OTP scam targeting bank customers
            • Police warning: Fake job offer scams on the rise
            • Be aware: Government impersonation calls reported
            • Tips: How to identify and avoid crypto scams
        """.trimIndent()
    }

    private fun addSampleNotifications() {
        val sampleNotifications = listOf(
            NotificationItem(
                "High Risk Call Detected",
                "A potentially fraudulent call was detected and recorded"
            ),
            NotificationItem(
                "Location Alert",
                "Multiple scams reported in your area"
            ),
            NotificationItem(
                "Security Update",
                "App has been updated with new scam detection patterns"
            )
        )
        notificationAdapter.updateNotifications(sampleNotifications)
    }

    private fun startPeriodicUpdates() {
        // Update scam statistics every hour
        fixedRateTimer("ScamStatsUpdate", period = 3600000) {
            runOnUiThread {
                setupScamStatistics()
            }
        }

        // Periodically add new notifications (for demo)
        fixedRateTimer("NotificationUpdate", period = 300000) {
            runOnUiThread {
                val randomNotification = NotificationItem(
                    "Security Alert",
                    "New suspicious activity pattern detected in your area"
                )
                notificationAdapter.addNotification(randomNotification)
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

        // Add all required permissions except CAPTURE_AUDIO_OUTPUT
        permissions.addAll(REQUIRED_PERMISSIONS.filter { it != Manifest.permission.CAPTURE_AUDIO_OUTPUT })

        // Check if we have CAPTURE_AUDIO_OUTPUT permission
        val hasAudioCapture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // On older versions, we don't need this permission
        }

        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        } else if (hasAudioCapture) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Audio capture permission is required", Toast.LENGTH_SHORT).show()
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

    override fun onDestroy() {
        super.onDestroy()
        locationHelper.stopLocationUpdates()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
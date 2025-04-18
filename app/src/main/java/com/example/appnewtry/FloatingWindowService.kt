package com.example.appnewtry

import android.app.Activity
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.data.LineData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var dropdownButtons: LinearLayout
    private var floatingWindows = mutableListOf<View>()
    private var isScreenshotting = false
    private var screenshotJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screenshotManager: ScreenshotManager? = null
    private var objectDetector: ObjectDetector? = null
    private lateinit var screenshotAdapter: ScreenshotAdapter
    private var screenshotCount = 0  // Add counter for screenshots
    private var voiceAnalysisManager: VoiceAnalysisManager? = null
    private var isRecording = false
    private var currentAlert: Dialog? = null
    private lateinit var locationHelper: LocationHelper
    private var currentLocation: android.location.Location? = null
    private lateinit var detectionHistoryAdapter: DetectionHistoryAdapter

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        screenshotManager = ScreenshotManager(this)
        objectDetector = ObjectDetector(this)
        screenshotAdapter = ScreenshotAdapter()
        locationHelper = LocationHelper(this)
        
        // Start collecting location updates
        scope.launch {
            locationHelper.locationFlow.collect { location ->
                currentLocation = location
            }
        }
        
        locationHelper.startLocationUpdates()
        setupFloatingIcon()
        startForeground()
    }

    private fun startForeground() {
        val channelId = "screenshot_service"
        val channelName = "Screenshot Service"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screenshot Service")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_menu)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e("FloatingService", "No intent received in onStartCommand")
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("resultData")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d("FloatingService", "Received valid projection data with resultCode: $resultCode")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection != null) {
                // Initialize screenshot manager with existing projection
                screenshotManager = ScreenshotManager(this)
                screenshotManager?.initializeWithProjection(mediaProjection)
                
                // Initialize voice analysis manager with same projection
                voiceAnalysisManager = VoiceAnalysisManager(this)
                voiceAnalysisManager?.initializeMediaProjection(mediaProjection)
            } else {
                Log.e("FloatingService", "Failed to create MediaProjection")
            }
        } else {
            Log.e("FloatingService", "Invalid projection data received: resultCode=$resultCode, data=$data")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupFloatingIcon() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_icon, null)
        dropdownButtons = floatingView.findViewById(R.id.dropdownButtons)

        // Set up close button
        floatingView.findViewById<View>(R.id.btnCloseFloating).setOnClickListener {
            stopScreenshots()
            stopSelf()
            windowManager.removeView(floatingView)
            floatingWindows.forEach { windowManager.removeView(it) }
        }

        // Set up drag functionality
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView.findViewById<View>(R.id.floatingIcon).setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(event.rawX - initialTouchX) < 10 && Math.abs(event.rawY - initialTouchY) < 10) {
                        toggleDropdown()
                    }
                    true
                }
                else -> false
            }
        }

        // Set up button click listeners
        for (i in 1..3) {
            floatingView.findViewById<Button>(
                resources.getIdentifier("button$i", "id", packageName)
            ).setOnClickListener {
                createFloatingWindow(i)
                dropdownButtons.visibility = View.GONE
            }
        }

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100
        windowManager.addView(floatingView, params)
    }

    private fun toggleDropdown() {
        dropdownButtons.visibility = if (dropdownButtons.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun createFloatingWindow(buttonNumber: Int) {
        val layoutRes = when (buttonNumber) {
            2 -> R.layout.layout_floating_window_screenshots
            3 -> R.layout.layout_detection_history
            else -> R.layout.layout_floating_window
        }

        val windowView = LayoutInflater.from(this).inflate(layoutRes, null)
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        setupWindowDrag(windowView, windowParams)

        when (buttonNumber) {
            2 -> setupScreenshotControls(windowView)
            1 -> setupVoiceAnalysisControls(windowView)
            3 -> setupDetectionHistoryControls(windowView)
        }

        windowView.findViewById<Button>(R.id.btnClose).setOnClickListener {
            when (buttonNumber) {
                2 -> stopScreenshots()
                1 -> stopVoiceRecording()
            }
            windowManager.removeView(windowView)
            floatingWindows.remove(windowView)
        }

        windowParams.gravity = Gravity.TOP or Gravity.START
        windowParams.x = 100
        windowParams.y = 200
        windowManager.addView(windowView, windowParams)
        floatingWindows.add(windowView)
    }

    private fun setupWindowDrag(windowView: View, windowParams: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        windowView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(windowView, windowParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupScreenshotControls(windowView: View) {
        val btnStartScreenshots = windowView.findViewById<Button>(R.id.btnStartScreenshots)
        val statusText = windowView.findViewById<TextView>(R.id.screenshotStatus)
        val recyclerView = windowView.findViewById<RecyclerView>(R.id.screenshotsRecyclerView)

        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = screenshotAdapter

        btnStartScreenshots.setOnClickListener {
            if (!isScreenshotting) {
                startScreenshots(statusText)
                btnStartScreenshots.text = "Stop Recording"
            } else {
                stopScreenshots()
                btnStartScreenshots.text = "Start Recording"
                statusText.text = "Status: Analyzing screenshots..."
                // Automatically process screenshots after stopping
                processLatestScreenshots()
            }
        }
    }

    private fun setupVoiceAnalysisControls(windowView: View) {
        val btnStartVoiceRecording = windowView.findViewById<Button>(R.id.btnStartVoiceRecording)
        val recordingStatus = windowView.findViewById<TextView>(R.id.recordingStatus)
        val transcriptionText = windowView.findViewById<TextView>(R.id.transcriptionText)
        val riskScoreText = windowView.findViewById<TextView>(R.id.riskScoreText)
        val riskGraph = windowView.findViewById<com.github.mikephil.charting.charts.LineChart>(R.id.riskGraph)

        // Initialize voice analysis manager
        voiceAnalysisManager = VoiceAnalysisManager(this)

        // Setup graph
        riskGraph.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 100f
                setDrawGridLines(true)
            }
        }

        // Collect flows
        scope.launch {
            voiceAnalysisManager?.transcriptionFlow?.collect { text ->
                transcriptionText.text = text
            }
        }

        scope.launch {
            voiceAnalysisManager?.riskScoreFlow?.collect { score ->
                riskScoreText.text = "${String.format("%.1f", score)}%"
                // Add to detection history for all scores
                addToDetectionHistory(DetectionType.AUDIO, score)
            }
        }

        scope.launch {
            voiceAnalysisManager?.graphDataFlow?.collect { entries ->
                if (entries.isNotEmpty()) {
                    val dataSet = voiceAnalysisManager?.createLineDataSet(entries)
                    riskGraph.data = LineData(dataSet)
                    riskGraph.invalidate()
                }
            }
        }

        btnStartVoiceRecording.setOnClickListener {
            if (!isRecording) {
                startVoiceRecording(btnStartVoiceRecording, recordingStatus)
            } else {
                stopVoiceRecording(btnStartVoiceRecording, recordingStatus)
            }
        }
    }

    private fun setupDetectionHistoryControls(windowView: View) {
        val recyclerView = windowView.findViewById<RecyclerView>(R.id.detectionHistoryRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        // Initialize adapter if not already initialized
        if (!::detectionHistoryAdapter.isInitialized) {
            detectionHistoryAdapter = DetectionHistoryAdapter()
        }
        
        // Set adapter to RecyclerView
        recyclerView.adapter = detectionHistoryAdapter
        
        // Add divider between items
        recyclerView.addItemDecoration(
            androidx.recyclerview.widget.DividerItemDecoration(
                this,
                androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
            )
        )
    }

    private fun startScreenshots(statusText: TextView) {
        isScreenshotting = true
        screenshotCount = 0  // Reset counter when starting new session
        screenshotJob = scope.launch {
            while (isScreenshotting) {
                try {
                    takeScreenshot(screenshotCount++)
                    statusText.text = "Status: Screenshots taken: $screenshotCount"
                    delay(1000) // Increased delay to 1 second for better reliability
                } catch (e: Exception) {
                    Log.e("FloatingService", "Error in screenshot loop: ${e.message}")
                }
            }
        }
    }

    private fun stopScreenshots() {
        isScreenshotting = false
        screenshotJob?.cancel()
        screenshotJob = null
        // Don't reset screenshotCount here to maintain the count for processing
    }

    private fun takeScreenshot(count: Int) {
        try {
            Log.d("FloatingService", "Taking screenshot #$count")
            screenshotManager?.captureScreenshot { bitmap ->
                if (bitmap != null) {
                    Log.d("FloatingService", "Screenshot captured successfully")
                    saveScreenshot(bitmap, count)
                } else {
                    Log.e("FloatingService", "Failed to capture screenshot: bitmap is null")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "Failed to capture screenshot", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error taking screenshot: ${e.message}")
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Failed to capture screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun View.getRootView(): View {
        var context = this@FloatingWindowService
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        
        return View(context).apply {
            layoutParams = ViewGroup.LayoutParams(size.x, size.y)
        }
    }

    private fun saveScreenshot(bitmap: Bitmap, count: Int) {
        try {
            Log.d("FloatingService", "Starting to save screenshot #$count")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "Screenshot_${timestamp}_$count.jpg"
            Log.d("FloatingService", "Generated filename: $filename")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 and above - Use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                        stream.flush()
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)

                    Log.d("FloatingService", "Screenshot saved successfully to MediaStore")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "Screenshot saved to gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Below Android 10 - Use direct file saving
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val image = File(imagesDir, filename)

                FileOutputStream(image).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                    stream.flush()
                }

                // Notify media scanner
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(image.absolutePath),
                    arrayOf("image/jpeg")
                ) { path, uri ->
                    Log.d("FloatingService", "Media scan completed for path: $path")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "Screenshot saved to gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error saving screenshot: ${e.message}")
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun showAlert(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                // Dismiss any existing alert
                currentAlert?.dismiss()
                
                // Create and show new alert
                val dialog = Dialog(this)
                dialog.setContentView(R.layout.alert_dialog)

                // Update alert message with location if available
                val alertMessage = dialog.findViewById<TextView>(R.id.alertMessage)
                val locationText = currentLocation?.let {
                    "\nLocation: ${String.format("%.6f", it.latitude)}, ${String.format("%.6f", it.longitude)}"
                } ?: "\nLocation: Unavailable"
                
                alertMessage.text = "$message$locationText"
                
                // Set dialog window attributes
                dialog.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setGravity(Gravity.CENTER)
                    
                    // Set layout parameters
                    val params = attributes
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    attributes = params
                }

                currentAlert = dialog
                dialog.show()

                // Automatically dismiss after 3.5 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    dialog.dismiss()
                    currentAlert = null
                }, 4000)
            } catch (e: Exception) {
                Log.e("FloatingService", "Error showing alert: ${e.message}")
            }
        }
    }

    private fun processLatestScreenshots() {
        Log.d("FloatingService", "Starting to process latest $screenshotCount screenshots")
        
        scope.launch(Dispatchers.IO) {
            try {
                val screenshots = loadLatestScreenshots(screenshotCount)
                Log.d("FloatingService", "Loaded ${screenshots.size} screenshots")

                var imagesWithDetections = 0
                val processedScreenshots = screenshots.mapIndexed { index, bitmap ->
                    Log.d("FloatingService", "Processing screenshot $index")
                    val (processedBitmap, detections) = objectDetector?.detect(bitmap) 
                        ?: Pair(bitmap, emptyList())
                    
                    if (detections.isNotEmpty()) {
                        imagesWithDetections++
                    }
                    
                    Log.d("FloatingService", "Screenshot $index: Found ${detections.size} detections")
                    ScreenshotAdapter.ScreenshotItem(processedBitmap, detections)
                }

                val totalImages = processedScreenshots.size
                val detectionPercentage = if (totalImages > 0) {
                    (imagesWithDetections.toFloat() / totalImages.toFloat()) * 100
                } else {
                    0f
                }

                withContext(Dispatchers.Main) {
                    screenshotAdapter.updateScreenshots(processedScreenshots)
                    
                    // Add to detection history regardless of risk level
                    addToDetectionHistory(DetectionType.IMAGE, detectionPercentage)
                    
                    // Show alert only for high risk
                    if (detectionPercentage >= 55.0f) {
                        showAlert("Suspicious Activity Level: ${String.format("%.1f", detectionPercentage)}%")
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingService", "Error processing screenshots: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun loadLatestScreenshots(count: Int): List<Bitmap> {
        Log.d("FloatingService", "Loading latest $count screenshots")
        val screenshots = mutableListOf<Bitmap>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Pictures%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    var loadedCount = 0
                    Log.d("FloatingService", "Found ${cursor.count} images in gallery")
                    
                    while (cursor.moveToNext() && loadedCount < count) {  // Only load the specified number of screenshots
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        try {
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
                            } else {
                                MediaStore.Images.Media.getBitmap(contentResolver, uri)
                            }
                            screenshots.add(bitmap)
                            loadedCount++
                            Log.d("FloatingService", "Loaded screenshot $loadedCount of $count: ${bitmap.width}x${bitmap.height}")
                        } catch (e: Exception) {
                            Log.e("FloatingService", "Error loading screenshot: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FloatingService", "Error querying MediaStore: ${e.message}")
            }
        }
        Log.d("FloatingService", "Loaded total of ${screenshots.size} screenshots")
        return screenshots
    }

    private fun startVoiceRecording(button: Button, status: TextView) {
        isRecording = true
        button.text = "Stop Recording"
        status.text = "Status: Recording..."
        voiceAnalysisManager?.startListening()
    }

    private fun stopVoiceRecording(button: Button? = null, status: TextView? = null) {
        isRecording = false
        button?.text = "Start Recording"
        status?.text = "Status: Ready"
        voiceAnalysisManager?.stopListening()
    }

    private fun addToDetectionHistory(type: DetectionType, riskScore: Float) {
        try {
            if (!::detectionHistoryAdapter.isInitialized) {
                detectionHistoryAdapter = DetectionHistoryAdapter()
            }
            
            val detection = DetectionItem(
                timestamp = System.currentTimeMillis(),
                type = type,
                riskScore = riskScore
            )
            
            Handler(Looper.getMainLooper()).post {
                detectionHistoryAdapter.addDetection(detection)
                Log.d("FloatingService", "Added detection to history: $detection")
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "Error adding to detection history: ${e.message}")
        }
    }

    fun handleHighVoiceRisk(riskScore: Float) {
        Log.d("FloatingService", "Handling high voice risk with score: $riskScore")
        // Only show alert for high risk, detection is already added in setupVoiceAnalysisControls
        showAlert("Scam Risk Level: ${String.format("%.1f", riskScore)}%")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenshots()
        stopVoiceRecording()
        screenshotManager?.tearDown()
        objectDetector?.close()
        voiceAnalysisManager?.destroy()
        locationHelper.stopLocationUpdates()
        currentAlert?.dismiss()
        windowManager.removeView(floatingView)
        floatingWindows.forEach { windowManager.removeView(it) }
    }
} 
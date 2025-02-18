package com.example.appnewtry

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat
import android.util.Log
import android.graphics.Point
import android.graphics.Rect
import android.view.PixelCopy
import android.app.Activity

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var dropdownButtons: LinearLayout
    private var floatingWindows = mutableListOf<View>()
    private var isScreenshotting = false
    private var screenshotJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screenshotManager: ScreenshotManager? = null

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
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
            screenshotManager = ScreenshotManager(this)
            screenshotManager?.initializeProjection(resultCode, data)
        } else {
            Log.e("FloatingService", "Invalid projection data received: resultCode=$resultCode, data=$data")
        }

        // Rest of your service initialization code...
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
        val layoutRes = if (buttonNumber == 2) {
            R.layout.layout_floating_window_screenshots
        } else {
            R.layout.layout_floating_window
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

        if (buttonNumber == 2) {
            setupScreenshotControls(windowView)
        }

        windowView.findViewById<Button>(R.id.btnClose).setOnClickListener {
            if (buttonNumber == 2) {
                stopScreenshots()
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

        btnStartScreenshots.setOnClickListener {
            if (!isScreenshotting) {
                startScreenshots(statusText)
                btnStartScreenshots.text = "Stop Screenshots"
            } else {
                stopScreenshots()
                btnStartScreenshots.text = "Start Screenshots"
                statusText.text = "Status: Ready"
            }
        }
    }

    private fun startScreenshots(statusText: TextView) {
        isScreenshotting = true
        screenshotJob = scope.launch {
            var screenshotCount = 0
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

    override fun onDestroy() {
        super.onDestroy()
        stopScreenshots()
        screenshotManager?.tearDown()
        windowManager.removeView(floatingView)
        floatingWindows.forEach { windowManager.removeView(it) }
    }
} 
package com.example.appnewtry

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.graphics.Point
import android.view.Surface

class ScreenshotManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    init {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
            screenDensity = context.resources.displayMetrics.densityDpi
            Log.d("ScreenshotManager", "Initialized with width: $screenWidth, height: $screenHeight, density: $screenDensity")
        } catch (e: Exception) {
            Log.e("ScreenshotManager", "Error in initialization: ${e.message}")
        }
    }

    fun initializeProjection(resultCode: Int, data: Intent) {
        try {
            Log.d("ScreenshotManager", "Starting projection initialization with resultCode: $resultCode")
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // Clean up existing resources
            tearDown()
            
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e("ScreenshotManager", "Failed to create MediaProjection")
                isInitialized = false
                return
            }

            // Register callback to handle projection state changes
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("ScreenshotManager", "MediaProjection stopped")
                    tearDown()
                }
            }, handler)

            // Create new ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    Log.d("ScreenshotManager", "New image is available from ImageReader")
                }, handler)
            }

            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            if (virtualDisplay == null) {
                Log.e("ScreenshotManager", "Failed to create Virtual Display")
                tearDown()
                return
            }

            isInitialized = true
            Log.d("ScreenshotManager", "MediaProjection initialized successfully")
        } catch (e: Exception) {
            Log.e("ScreenshotManager", "Error in initializeProjection: ${e.message}")
            e.printStackTrace()
            tearDown()
        }
    }

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        if (!isInitialized) {
            Log.e("ScreenshotManager", "Screenshot manager not initialized")
            callback(null)
            return
        }

        try {
            Log.d("ScreenshotManager", "Starting screenshot capture")
            
            if (imageReader == null) {
                Log.e("ScreenshotManager", "ImageReader is null")
                callback(null)
                return
            }

            // Add a delay to ensure screen content is ready
            handler.postDelayed({
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image == null) {
                        Log.e("ScreenshotManager", "Acquired image is null")
                        callback(null)
                        return@postDelayed
                    }

                    Log.d("ScreenshotManager", "Image acquired, processing...")

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    // Create bitmap
                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight,
                        Bitmap.Config.ARGB_8888
                    )

                    buffer.rewind() // Ensure buffer is ready to be read
                    bitmap.copyPixelsFromBuffer(buffer)

                    // Crop the bitmap to remove padding
                    val croppedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0,
                        screenWidth, screenHeight
                    )
                    bitmap.recycle()
                    
                    image.close()
                    Log.d("ScreenshotManager", "Screenshot captured successfully")
                    callback(croppedBitmap)
                } catch (e: Exception) {
                    Log.e("ScreenshotManager", "Error processing image: ${e.message}")
                    e.printStackTrace()
                    callback(null)
                }
            }, 150) // Increased delay for better reliability
        } catch (e: Exception) {
            Log.e("ScreenshotManager", "Error capturing screenshot: ${e.message}")
            e.printStackTrace()
            callback(null)
        }
    }

    fun tearDown() {
        try {
            isInitialized = false
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            handler.removeCallbacksAndMessages(null)
            Log.d("ScreenshotManager", "Resources released successfully")
        } catch (e: Exception) {
            Log.e("ScreenshotManager", "Error in tearDown: ${e.message}")
            e.printStackTrace()
        }
    }
} 
package com.example.appnewtry

import android.content.Context
import android.graphics.*
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

class ObjectDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = listOf("class_0") // Single class model
    private val imageSize = 640 // YOLOv5 default input size
    private val numClasses = 1  // Only one class
    private val objectThreshold = 0.25f // Lowered threshold for testing

    init {
        try {
            Log.d("ObjectDetector", "Initializing ObjectDetector")
            val model = FileUtil.loadMappedFile(context, "model.tflite")
            Log.d("ObjectDetector", "Model file loaded successfully, size: ${model.capacity()} bytes")
            
            // Configure interpreter for 32-bit model
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useNNAPI = false  // Disable NNAPI as we're using a 32-bit model
            }
            
            interpreter = Interpreter(model, options)
            Log.d("ObjectDetector", "Interpreter created successfully")
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error initializing ObjectDetector: ${e.message}")
            e.printStackTrace()
        }
    }

    fun detect(bitmap: Bitmap): Pair<Bitmap, List<Detection>> {
        try {
            Log.d("ObjectDetector", "Starting detection on bitmap: ${bitmap.width}x${bitmap.height}")
            
            // Convert hardware bitmap to software bitmap if necessary
            val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                Log.d("ObjectDetector", "Converting HARDWARE bitmap to SOFTWARE bitmap")
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bitmap
            }
            
            val inputBitmap = preprocessImage(softwareBitmap)
            Log.d("ObjectDetector", "Image preprocessed to ${imageSize}x${imageSize}")

            // Prepare input tensor
            val inputBuffer = ByteBuffer.allocateDirect(1 * imageSize * imageSize * 3 * 4) // 4 bytes per float
            inputBuffer.order(ByteOrder.nativeOrder())
            fillInputBuffer(inputBitmap, inputBuffer)
            
            // Prepare output tensor with correct shape [1, 5, 8400]
            val outputBuffer = Array(1) {
                Array(5) {
                    FloatArray(8400)
                }
            }

            Log.d("ObjectDetector", "Running model inference")
            interpreter?.run(inputBuffer, outputBuffer)
            Log.d("ObjectDetector", "Model inference completed")

            val detections = processDetections(outputBuffer[0], bitmap.width, bitmap.height)
            Log.d("ObjectDetector", "Found ${detections.size} detections")

            // Clean up if we created a new bitmap
            if (softwareBitmap != bitmap) {
                softwareBitmap.recycle()
            }

            return drawDetections(bitmap, detections)
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error in detect(): ${e.message}")
            e.printStackTrace()
            return Pair(bitmap, emptyList())
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
    }

    private fun fillInputBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        val pixels = IntArray(imageSize * imageSize)
        bitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)
        
        var pixel = 0
        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val value = pixels[pixel++]
                // Extract RGB values and normalize to [0, 1]
                buffer.putFloat(((value shr 16) and 0xFF) / 255.0f)
                buffer.putFloat(((value shr 8) and 0xFF) / 255.0f)
                buffer.putFloat((value and 0xFF) / 255.0f)
            }
        }
    }

    private fun processDetections(
        outputArray: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        var detectionCount = 0
        
        // YOLOv5 output format: [x, y, w, h, confidence]
        val xScale = originalWidth.toFloat()
        val yScale = originalHeight.toFloat()
        
        // Process each grid cell
        for (i in 0 until 8400) {
            val confidence = outputArray[4][i]  // Confidence score is in the 5th channel
            
            if (confidence > objectThreshold) {
                try {
                    // Get normalized coordinates from first 4 channels
                    val x = outputArray[0][i] * xScale  // Already normalized by model
                    val y = outputArray[1][i] * yScale  // Already normalized by model
                    val w = outputArray[2][i] * xScale  // Already normalized by model
                    val h = outputArray[3][i] * yScale  // Already normalized by model

                    // Convert to corner coordinates for RectF
                    val left = x - (w / 2)
                    val top = y - (h / 2)
                    val right = x + (w / 2)
                    val bottom = y + (h / 2)

                    Log.d("ObjectDetector", "Detection $detectionCount: left=$left, top=$top, right=$right, bottom=$bottom, conf=$confidence")

                    detections.add(
                        Detection(
                            RectF(left, top, right, bottom),
                            labels[0],
                            confidence
                        )
                    )
                    detectionCount++
                } catch (e: Exception) {
                    Log.e("ObjectDetector", "Error processing detection $i: ${e.message}")
                }
            }
        }

        Log.d("ObjectDetector", "Processed $detectionCount detections")
        return detections
    }

    private fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Pair<Bitmap, List<Detection>> {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        
        // Create paint for bounding box
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f  // Increased stroke width
            color = Color.RED
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true  // Added anti-aliasing
        }

        // Create paint for background of text
        val textBackgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(180, 255, 0, 0)  // Semi-transparent red
        }

        // Create paint for text
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = bitmap.width / 25f  // Scaled text size
            style = Paint.Style.FILL
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true  // Added anti-aliasing
        }

        detections.forEach { detection ->
            try {
                // Draw bounding box
                canvas.drawRect(detection.boundingBox, boxPaint)
                
                // Prepare text
                val text = "${detection.label} ${String.format("%.2f", detection.confidence)}"
                val textBounds = Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                
                // Draw text background
                val padding = 8f
                val textBackground = RectF(
                    detection.boundingBox.left,
                    detection.boundingBox.top - textBounds.height() - padding * 2,
                    detection.boundingBox.left + textBounds.width() + padding * 2,
                    detection.boundingBox.top
                )
                canvas.drawRect(textBackground, textBackgroundPaint)
                
                // Draw text
                canvas.drawText(
                    text,
                    detection.boundingBox.left + padding,
                    detection.boundingBox.top - padding,
                    textPaint
                )
                
                Log.d("ObjectDetector", "Drew detection: ${detection.label} at ${detection.boundingBox}")
            } catch (e: Exception) {
                Log.e("ObjectDetector", "Error drawing detection: ${e.message}")
            }
        }

        return Pair(outputBitmap, detections)
    }

    data class Detection(
        val boundingBox: RectF,
        val label: String,
        val confidence: Float
    )

    fun close() {
        try {
            interpreter?.close()
            Log.d("ObjectDetector", "Interpreter closed successfully")
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error closing interpreter: ${e.message}")
        }
    }
} 
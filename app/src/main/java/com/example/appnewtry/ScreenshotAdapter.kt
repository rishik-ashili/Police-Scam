package com.example.appnewtry

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ScreenshotAdapter : RecyclerView.Adapter<ScreenshotAdapter.ScreenshotViewHolder>() {
    private val screenshots = mutableListOf<ScreenshotItem>()

    data class ScreenshotItem(
        val bitmap: Bitmap,
        val detections: List<ObjectDetector.Detection>
    )

    class ScreenshotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.screenshotImage)
        val detectionInfo: TextView = view.findViewById(R.id.detectionInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScreenshotViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return ScreenshotViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScreenshotViewHolder, position: Int) {
        val item = screenshots[position]
        holder.imageView.setImageBitmap(item.bitmap)
        
        // Create detection summary
        val detectionSummary = item.detections
            .groupBy { it.label }
            .map { (label, detections) -> "$label: ${detections.size}" }
            .joinToString(", ")
        
        holder.detectionInfo.text = if (detectionSummary.isNotEmpty()) {
            "Detected: $detectionSummary"
        } else {
            "No objects detected"
        }
    }

    override fun getItemCount() = screenshots.size

    fun updateScreenshots(newScreenshots: List<ScreenshotItem>) {
        screenshots.clear()
        screenshots.addAll(newScreenshots)
        notifyDataSetChanged()
    }

    fun addScreenshot(screenshot: ScreenshotItem) {
        screenshots.add(screenshot)
        notifyItemInserted(screenshots.size - 1)
    }
} 
package com.example.appnewtry

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetectionItem(
    val timestamp: Long,
    val type: DetectionType,
    val riskScore: Float,
    var isReported: Boolean = false
)

enum class DetectionType {
    AUDIO,
    IMAGE
}

class DetectionHistoryAdapter : RecyclerView.Adapter<DetectionHistoryAdapter.ViewHolder>() {
    private val detections = mutableListOf<DetectionItem>()
    private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTimeText: TextView = view.findViewById(R.id.dateTimeText)
        val detectionTypeText: TextView = view.findViewById(R.id.detectionTypeText)
        val riskScoreText: TextView = view.findViewById(R.id.riskScoreText)
        val reportButton: Button = view.findViewById(R.id.reportButton)
        val reportedText: TextView = view.findViewById(R.id.reportedText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_detection_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val detection = detections[position]
            Log.d("DetectionAdapter", "Binding detection at position $position: $detection")
            
            // Set date and time
            holder.dateTimeText.text = dateFormat.format(Date(detection.timestamp))
            
            // Set detection type
            holder.detectionTypeText.text = detection.type.name
            
            // Set risk score
            holder.riskScoreText.text = String.format("%.1f%%", detection.riskScore)

            // Handle report status
            if (detection.isReported) {
                holder.reportButton.visibility = View.GONE
                holder.reportedText.visibility = View.VISIBLE
            } else {
                holder.reportButton.visibility = View.VISIBLE
                holder.reportedText.visibility = View.GONE
                
                holder.reportButton.setOnClickListener {
                    detection.isReported = true
                    notifyItemChanged(position)
                }
            }
        } catch (e: Exception) {
            Log.e("DetectionAdapter", "Error binding detection at position $position: ${e.message}")
        }
    }

    override fun getItemCount(): Int = detections.size

    fun addDetection(detection: DetectionItem) {
        try {
            Log.d("DetectionAdapter", "Adding new detection: $detection")
            detections.add(0, detection) // Add to the beginning of the list
            notifyItemInserted(0)
            Log.d("DetectionAdapter", "Current detection count: ${detections.size}")
        } catch (e: Exception) {
            Log.e("DetectionAdapter", "Error adding detection: ${e.message}")
        }
    }

    fun getDetections(): List<DetectionItem> = detections.toList()
} 
package com.example.appnewtry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotificationItem(
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {
    private val notifications = mutableListOf<NotificationItem>()
    private val dateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.notificationTitle)
        val message: TextView = view.findViewById(R.id.notificationMessage)
        val time: TextView = view.findViewById(R.id.notificationTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notification = notifications[position]
        holder.title.text = notification.title
        holder.message.text = notification.message
        holder.time.text = dateFormat.format(Date(notification.timestamp))
    }

    override fun getItemCount() = notifications.size

    fun addNotification(notification: NotificationItem) {
        notifications.add(0, notification) // Add to the beginning of the list
        notifyItemInserted(0)
    }

    fun updateNotifications(newNotifications: List<NotificationItem>) {
        notifications.clear()
        notifications.addAll(newNotifications)
        notifyDataSetChanged()
    }
} 
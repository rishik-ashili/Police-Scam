package com.example.appnewtry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationHelper(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _locationFlow = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationFlow

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _locationFlow.value = location
            Log.d("LocationHelper", "Location updated: ${location.latitude}, ${location.longitude}")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // Update every 5 seconds
                    10f,   // Or when moved 10 meters
                    locationListener
                )
                
                // Get last known location immediately
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    _locationFlow.value = it
                }
                
                Log.d("LocationHelper", "Started location updates")
            } catch (e: Exception) {
                Log.e("LocationHelper", "Error starting location updates: ${e.message}")
            }
        }
    }

    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
            Log.d("LocationHelper", "Stopped location updates")
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error stopping location updates: ${e.message}")
        }
    }
} 
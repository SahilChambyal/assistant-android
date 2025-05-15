package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log

/**
 * AccessibilityMonitorService is a foreground service that periodically saves
 * accessibility events to files. It runs in the background and maintains a
 * persistent notification to indicate its active status.
 */
class AccessibilityMonitorService : Service() {
    // Notification channel ID for Android O and above
    private val CHANNEL_ID = "assistant_monitor_channel"
    // Handler for scheduling periodic tasks
    private val handler = Handler(Looper.getMainLooper())
    // Interval for saving events (1 second)
    private val saveInterval: Long = 1000
    // Flag to control the service's running state
    private var running = false
    private var lastCaptureTime = 0L
    private var lastContentHash = 0
    private val MIN_CAPTURE_INTERVAL = 1000L // Minimum 1 second between captures
    private val MAX_CAPTURE_INTERVAL = 5000L // Maximum 5 seconds without capture

    /**
     * Runnable that periodically saves accessibility events to files.
     * Only saves events when the screen is on to conserve resources.
     */
    private val saveRunnable = object : Runnable {
        override fun run() {
            try {
                if (isScreenOn()) {
                    MyAccessibilityService.saveLatestEventToFile(this@AccessibilityMonitorService)
                }
            } catch (e: Exception) {
                Log.e("AccessibilityMonitorService", "Error saving event", e)
            }
            if (running) {
                handler.postDelayed(this, saveInterval)
            }
        }
    }

    private val captureNowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.assistant.CAPTURE_NOW") {
                MyAccessibilityService.saveLatestEventToFile(this@AccessibilityMonitorService)
                lastCaptureTime = System.currentTimeMillis()
            }
        }
    }

    private val smartCaptureRunnable = object : Runnable {
        override fun run() {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastCapture = currentTime - lastCaptureTime

            // Check if we should capture
            if (shouldCapture(timeSinceLastCapture)) {
                MyAccessibilityService.saveLatestEventToFile(this@AccessibilityMonitorService)
                lastCaptureTime = currentTime
            }

            // Schedule next check
            val nextInterval = calculateNextInterval(timeSinceLastCapture)
            handler.postDelayed(this, nextInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Create and start foreground notification
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Assistant Service Running")
            .setContentText("Saving accessibility events every second...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        registerReceiver(
            captureNowReceiver,
            IntentFilter("com.example.assistant.CAPTURE_NOW"),
            Context.RECEIVER_NOT_EXPORTED
        )
        handler.post(smartCaptureRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        handler.post(saveRunnable)
        return START_STICKY // Service will be restarted if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        handler.removeCallbacks(saveRunnable)
        unregisterReceiver(captureNowReceiver)
        handler.removeCallbacks(smartCaptureRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Creates a notification channel for Android O and above.
     * Required for showing foreground service notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Assistant Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Checks if the device's screen is currently on.
     * Uses appropriate API based on Android version.
     * @return true if the screen is on, false otherwise
     */
    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
    }

    private fun shouldCapture(timeSinceLastCapture: Long): Boolean {
        // Always capture if max interval exceeded
        if (timeSinceLastCapture >= MAX_CAPTURE_INTERVAL) {
            return true
        }

        // Check if content has changed
        val currentHash = MyAccessibilityService.getContentHash()
        if (currentHash != lastContentHash) {
            lastContentHash = currentHash
            // Only capture if minimum interval has passed
            return timeSinceLastCapture >= MIN_CAPTURE_INTERVAL
        }

        return false
    }

    private fun calculateNextInterval(timeSinceLastCapture: Long): Long {
        // If content is static, check less frequently
        return if (timeSinceLastCapture > 3000L) {
            2000L // Check every 2 seconds for static content
        } else {
            500L // Check every 0.5 seconds for dynamic content
        }
    }
}

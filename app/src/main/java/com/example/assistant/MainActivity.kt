package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * MainActivity serves as the primary interface for the application.
 * It manages the accessibility service lifecycle and provides user controls
 * for starting/stopping the service and viewing collected data.
 */
class MainActivity : AppCompatActivity() {
    // UI components for displaying status and controlling the service
    private lateinit var statusText: TextView
    private lateinit var apiStatusText: TextView
    private lateinit var apiStatusIndicator: View
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // API status check handler
    private val apiCheckHandler = Handler(Looper.getMainLooper())
    private val apiCheckRunnable = object : Runnable {
        override fun run() {
            checkApiStatus()
            apiCheckHandler.postDelayed(this, 30000) // Check every 30 seconds
        }
    }

    private val apiStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.assistant.API_STATUS_UPDATE") {
                updateApiStatus(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        statusText = findViewById(R.id.statusText)
        apiStatusText = findViewById(R.id.apiStatusText)
        apiStatusIndicator = findViewById(R.id.apiStatusIndicator)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        
        // Set up click listeners for the control buttons
        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopServiceAndUpdateStatus() }        // Start API status checking
        startApiStatusCheck()
          // Register broadcast receiver for API status updates
        registerReceiver(
            apiStatusReceiver,
            IntentFilter("com.example.assistant.API_STATUS_UPDATE"),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Update initial status based on accessibility service state
        if (isAccessibilityEnabled(this)) {
            updateStatus("Service is running")
        } else {
            updateStatus("Waiting for permissions")
        }
    }

    private fun startApiStatusCheck() {
        apiCheckHandler.post(apiCheckRunnable)
    }

    private fun stopApiStatusCheck() {
        apiCheckHandler.removeCallbacks(apiCheckRunnable)
    }    private fun checkApiStatus() {
        // Use the actual upload API endpoint
        val url = "https://assistant-cloud.vercel.app/api/upload"
          Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                connection.disconnect()
                
                runOnUiThread {
                    val isOnline = responseCode in 200..599 // Accept any response from server as "online"
                    updateApiStatus(isOnline)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateApiStatus(false)
                }
            }
        }.start()
    }

    private fun updateApiStatus(isOnline: Boolean) {
        apiStatusIndicator.isActivated = isOnline
        apiStatusText.text = if (isOnline) "API Online" else "API Offline"
        apiStatusText.setTextColor(ContextCompat.getColor(this,
            if (isOnline) R.color.text_primary else R.color.text_error))
    }

    override fun onDestroy() {
        unregisterReceiver(apiStatusReceiver)
        stopApiStatusCheck()
        super.onDestroy()
    }

    /**
     * Checks if accessibility service is enabled and starts the service if permissions are granted.
     * If accessibility is not enabled, it prompts the user to enable it in system settings.
     */
    private fun checkPermissionsAndStart() {
        if (!isAccessibilityEnabled(this)) {
            updateStatus("Waiting for permissions: Enable Accessibility")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }
        updateStatus("All permissions granted, starting data collection")
        window.decorView.postDelayed({
            val serviceIntent = Intent(this, AccessibilityMonitorService::class.java)
            startForegroundService(serviceIntent)
        }, 300)
    }

    /**
     * Stops the accessibility monitoring service and updates the UI status.
     */
    private fun stopServiceAndUpdateStatus() {
        val serviceIntent = Intent(this, AccessibilityMonitorService::class.java)
        stopService(serviceIntent)
        updateStatus("Service stopped")
    }

    /**
     * Updates the status text displayed to the user.
     * @param message The status message to display
     */
    private fun updateStatus(message: String) {
        statusText.text = message
    }

    companion object {
        /**
         * Checks if the accessibility service is enabled for this application.
         * @param context The application context
         * @return true if accessibility service is enabled, false otherwise
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(context.packageName)
        }
    }
}

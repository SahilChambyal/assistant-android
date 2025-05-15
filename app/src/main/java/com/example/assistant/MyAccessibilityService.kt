package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * MyAccessibilityService is an accessibility service that captures and processes
 * accessibility events from the system. It collects raw accessibility data and
 * delegates text extraction to specialized extractors.
 */
class MyAccessibilityService : AccessibilityService() {
    companion object {
        // Volatile to ensure thread safety when accessing from different threads
        @Volatile
        private var latestData: EventData? = null

        @Volatile
        private var contentHash = 0

        fun getContentHash(): Int = contentHash

        /**
         * Saves the latest captured view hierarchy to a file.
         * Called periodically by the AccessibilityMonitorService.
         *
         * @param context The application context
         */
        fun saveLatestEventToFile(context: Context) {
            latestData?.let { data ->
                try {
                    FileUtils.saveEvent(context, data)
                    // Start the upload service
                    val intent = Intent(context, FileUploadService::class.java)
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.e("MyAccessibilityService", "Error writing event to file", e)
                }
                latestData = null
            }
        }
    }

    /**
     * Called when an accessibility event occurs. Captures the current view hierarchy
     * and stores it for later saving to a file.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow
        if (root != null) {
            // Calculate hash of current content
            contentHash = calculateContentHash(root)

            // Extract data directly to protobuf
            latestData = DirectProtobufExtractor().extractToProtobuf(root)

            // Trigger immediate capture for important events
            if (isImportantEvent(event)) {
                sendBroadcast(Intent("com.example.assistant.CAPTURE_NOW"))
            }
        }
    }

    override fun onInterrupt() {
        // Required by AccessibilityService, but we don't need to do anything here
    }

    private fun calculateContentHash(node: AccessibilityNodeInfo): Int {
        var hash = 31
        hash = hash * 17 + (node.text?.toString()?.hashCode() ?: 0)
        hash = hash * 17 + (node.contentDescription?.toString()?.hashCode() ?: 0)
        hash = hash * 17 + node.childCount

        // Include important children in hash
        for (i in 0 until minOf(node.childCount, 5)) {
            node.getChild(i)?.let { child ->
                hash = hash * 17 + calculateContentHash(child)
                child.recycle()
            }
        }
        return hash
    }

    private fun isImportantEvent(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
            else -> false
        }
    }

    // Add method to save and trigger upload
    private fun saveAndTriggerUpload() {
        latestData?.let { data ->
            try {
                FileUtils.saveEvent(applicationContext, data)
                // Start the upload service if it's not already running
                val intent = Intent(this, FileUploadService::class.java)
                startService(intent)
            } catch (e: Exception) {
                Log.e("MyAccessibilityService", "Error writing event to file", e)
            }
            latestData = null
        }
    }
}

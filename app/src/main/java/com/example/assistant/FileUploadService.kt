package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

class FileUploadService : Service() {
    companion object {
        private const val TAG = "FileUploadService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FileUploadChannel"
        private val deletedFilesLock = Object()
        private val deletedFiles = HashSet<String>()
        private val processingMutex = Mutex()
        
        fun setDeviceName(context: Context, name: String) {
            context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                .edit()
                .putString("device_name", name)
                .apply()
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val maxRetries = 3
    private val retryDelayMillis = 30000L // 30 seconds

    private fun deleteFiles(files: List<File>): Boolean {
        var allSuccessful = true
        synchronized(deletedFilesLock) {
            files.forEach { file ->
                val fileName = file.name
                if (!deletedFiles.contains(fileName)) {
                    try {
                        // Get canonical file to resolve any symbolic links
                        val canonicalFile = file.canonicalFile
                        if (canonicalFile.exists()) {
                            // Get canonical paths for both files to ensure accurate comparison
                            val appDirPath = applicationContext.filesDir.canonicalPath
                            val filePath = canonicalFile.canonicalPath
                            
                            if (filePath.startsWith(appDirPath)) {
                                if (canonicalFile.delete()) {
                                    Log.d(TAG, "File deleted successfully: $fileName")
                                    deletedFiles.add(fileName)
                                } else {
                                    Log.w(TAG, "Could not delete file: $fileName")
                                    allSuccessful = false
                                }
                            } else {
                                Log.e(TAG, "File is outside app directory. File: $filePath, App dir: $appDirPath")
                                allSuccessful = false
                            }
                        } else {
                            // File doesn't exist, mark it as deleted
                            deletedFiles.add(fileName)
                            Log.d(TAG, "File already deleted: $fileName")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting file $fileName", e)
                        allSuccessful = false
                    }
                }
            }
        }
        return allSuccessful
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFileUploadWorker()
        return START_STICKY
    }

    private fun startFileUploadWorker() {
        coroutineScope.launch {
            while (isActive) {
                // Wait until the start of the next minute
                val currentTimeMillis = System.currentTimeMillis()
                val nextMinuteMillis = (currentTimeMillis / 60000 + 1) * 60000
                val delayMillis = nextMinuteMillis - currentTimeMillis
                delay(delayMillis)

                // Process files with mutex to prevent concurrent processing
                processingMutex.withLock {
                    processFiles()
                }
            }
        }
    }

    private suspend fun processFiles() {
        try {
            // Get files and filter out any that might be in the process of being written
            val currentTimeMillis = System.currentTimeMillis()
            val files = FileUtils.getEventFiles(applicationContext).filter { file ->
                // Only process files that are at least 5 seconds old to avoid race conditions
                currentTimeMillis - file.lastModified() > 5000
            }

            if (files.isEmpty()) {
                updateNotification("No files to upload")
                return
            }

            // Clear deleted files set at start of new batch
            synchronized(deletedFilesLock) {
                deletedFiles.clear()
            }

            updateNotification("Found ${files.size} files to upload")
            var retryCount = 0
            var shouldContinueRetrying = true
            
            withContext(Dispatchers.IO) {
                while (retryCount < maxRetries && shouldContinueRetrying) {
                    try {
                        // Filter out any files that no longer exist
                        val existingFiles = files.filter { it.exists() }
                        if (existingFiles.isEmpty()) {
                            Log.d(TAG, "All files have been processed")
                            return@withContext
                        }

                        // Create multipart request with existing files
                        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                        
                        existingFiles.forEach { file ->
                            // Get canonical path to ensure we're using the actual file
                            val canonicalFile = file.canonicalFile
                            if (canonicalFile.exists() && 
                                Path(canonicalFile.absolutePath).isRegularFile() &&
                                canonicalFile.canonicalPath.startsWith(applicationContext.filesDir.canonicalPath)) {
                                builder.addFormDataPart(
                                    "files",
                                    file.name,
                                    canonicalFile.asRequestBody("application/octet-stream".toMediaType())
                                )
                            } else {
                                Log.w(TAG, "Skipping invalid file: ${file.name}")
                            }
                        }
                        builder.addFormDataPart("deviceName", getDeviceName())
                        
                        val requestBody = builder.build()
                        val request = Request.Builder()
                            .url("https://assistant-cloud.vercel.app/api/upload")
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            when {
                                response.isSuccessful -> {
                                    val successfullyDeleted = deleteFiles(existingFiles)
                                    withContext(Dispatchers.Main) {
                                        updateNotification(
                                            if (successfullyDeleted) "Upload complete" 
                                            else "Upload complete but some files couldn't be deleted",
                                            isError = !successfullyDeleted
                                        )
                                        // Broadcast API is online
                                        sendBroadcast(Intent("com.example.assistant.API_STATUS_UPDATE"))
                                    }
                                    shouldContinueRetrying = false
                                }
                                response.code in 500..599 -> {
                                    val errorMessage = "Server error (${response.code}), attempt ${retryCount + 1}/$maxRetries"
                                    withContext(Dispatchers.Main) {
                                        updateNotification(errorMessage, isError = true)
                                    }
                                    Log.w(TAG, errorMessage)
                                    if (retryCount + 1 >= maxRetries) {
                                        shouldContinueRetrying = false
                                    } else {
                                        delay(retryDelayMillis)
                                        retryCount++
                                    }
                                }
                                else -> {
                                    val errorBody = response.body?.string() ?: "No error body"
                                    val errorMessage = "Upload failed (${response.code}): $errorBody"
                                    withContext(Dispatchers.Main) {
                                        updateNotification(errorMessage, isError = true)
                                    }
                                    Log.e(TAG, errorMessage)
                                    shouldContinueRetrying = false
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error uploading batch", e)
                        delay(retryDelayMillis)
                        retryCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing files", e)
            updateNotification("Error processing files", isError = true)
        }
    }

    private fun updateNotification(message: String, isError: Boolean = false) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Assistant")
            .setContentText(message)
            .setSmallIcon(if (isError) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setSilent(!isError) // Make sound for errors
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        if (isError) {
            Log.e(TAG, "Upload error: $message")
        } else {
            Log.d(TAG, "Upload status: $message")
        }
    }

    private fun getDeviceName(): String {
        val sharedPrefs = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        var deviceName = sharedPrefs.getString("device_name", null)
        
        if (deviceName == null) {
            val manufacturer = android.os.Build.MANUFACTURER
            val model = android.os.Build.MODEL
            deviceName = if (model.lowercase().startsWith(manufacturer.lowercase())) {
                model.replaceFirstChar { it.uppercaseChar() }
            } else {
                "${manufacturer.replaceFirstChar { it.uppercaseChar() }} $model"
            }
            sharedPrefs.edit().putString("device_name", deviceName).apply()
        }
        
        return deviceName
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "File Upload Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for the file upload service notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Assistant")
            .setContentText("Monitoring files for upload")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service if it's killed
        val restartServiceIntent = Intent(applicationContext, FileUploadService::class.java)
        startService(restartServiceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        // Attempt to restart service
        val restartServiceIntent = Intent(applicationContext, FileUploadService::class.java)
        startService(restartServiceIntent)
    }
}

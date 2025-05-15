package com.example.assistant

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import net.jpountz.lz4.LZ4FrameOutputStream

object FileUtils {
    private const val TAG = "FileUtils"
    private const val EVENT_FILE_PREFIX = "event_"
    private const val FILE_DATE_FORMAT = "HH:mm:ss_dd-MM-yyyy"
      fun saveEvent(context: Context, eventData: EventData) {
        try {
            val packageName = eventData.packageName
            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat(FILE_DATE_FORMAT, Locale.US)
            val dateStr = dateFormat.format(Date(timestamp))
            
            // Save as Protocol Buffer file with LZ4 compression
            val fileName = "${EVENT_FILE_PREFIX}${packageName.replace('.', '_')}_$dateStr.pb"
            val file = File(context.filesDir, fileName)
            
            // Write directly to file with LZ4 compression
            val byteStream = ByteArrayOutputStream()
            LZ4FrameOutputStream(byteStream).use { lz4Out ->
                eventData.writeTo(lz4Out)
            }
            FileOutputStream(file).use { fileOut ->
                fileOut.write(byteStream.toByteArray())
            }
            Log.d(TAG, "Saved event data to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving event data", e)
        }
    }
      @Synchronized
    fun getEventFiles(context: Context): List<File> {
        return try {
            context.filesDir.listFiles { file ->
                file.name.startsWith(EVENT_FILE_PREFIX) && 
                file.name.endsWith(".pb") &&
                file.isFile &&  // Ensure it's a regular file
                file.canRead() // Ensure we can read it
            }?.filter { it.length() > 0 }  // Only include non-empty files
            ?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing event files", e)
            emptyList()
        }
    }
    
    fun deleteFile(context: Context, fileName: String): Boolean {
        return try {
            val file = File(context.filesDir, fileName)
            if (file.exists() && file.delete()) {
                Log.d(TAG, "Successfully deleted file: $fileName")
                true
            } else {
                Log.e(TAG, "Failed to delete file: $fileName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: $fileName", e)
            false
        }
    }
}

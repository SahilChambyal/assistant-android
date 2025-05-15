package com.example.assistant

import net.jpountz.lz4.LZ4FrameOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import android.util.Log

class DataManager {
    companion object {
        private const val TAG = "DataManager"
        
        fun saveEventData(jsonData: JSONObject, outputFile: File) {
            try {
                // Convert JSON to protobuf
                val builder = EventData.newBuilder()
                
                with(jsonData) {
                    builder.timestamp = getLong("timestamp")
                    builder.packageName = getString("packageName")
                    builder.windowId = getInt("windowId")
                    builder.windowTitle = getString("windowTitle")
                    builder.windowType = getString("windowType")
                    
                    // Parse textFocusedData
                    val textFocusedJson = getJSONObject("textFocusedData")
                    val textFocusedBuilder = TextFocusedData.newBuilder()
                    textFocusedBuilder.timestamp = textFocusedJson.getLong("timestamp")
                    textFocusedBuilder.packageName = textFocusedJson.getString("packageName")
                    
                    // Parse textData array
                    val textDataArray = textFocusedJson.getJSONArray("textData")
                    for (i in 0 until textDataArray.length()) {
                        val textJson = textDataArray.getJSONObject(i)
                        val textDataBuilder = TextData.newBuilder()
                        textDataBuilder.text = textJson.getString("text")
                        textDataBuilder.type = textJson.getString("type")
                        textDataBuilder.isClickable = textJson.getBoolean("isClickable")
                        textDataBuilder.isEditable = textJson.getBoolean("isEditable")
                        textDataBuilder.className = textJson.getString("className")
                        textDataBuilder.depth = textJson.getInt("depth")
                        textDataBuilder.x = textJson.getInt("x")
                        textDataBuilder.y = textJson.getInt("y")
                        textDataBuilder.width = textJson.getInt("width")
                        textDataBuilder.height = textJson.getInt("height")
                        textFocusedBuilder.addTextData(textDataBuilder)
                    }
                    
                    // Parse screenSummary
                    val screenSummaryJson = textFocusedJson.getJSONObject("screenSummary")
                    val screenSummaryBuilder = ScreenSummary.newBuilder()
                    screenSummaryBuilder.combinedText = screenSummaryJson.getString("combinedText")
                    
                    // Parse elementCounts
                    val elementCountsJson = screenSummaryJson.getJSONObject("elementCounts")
                    val elementCountsBuilder = ElementCounts.newBuilder()
                    elementCountsJson.keys().forEach { key ->
                        elementCountsBuilder.putCounts(key, elementCountsJson.getInt(key))
                    }
                    
                    screenSummaryBuilder.elementCounts = elementCountsBuilder.build()
                    screenSummaryBuilder.hasEmailField = screenSummaryJson.getBoolean("hasEmailField")
                    screenSummaryBuilder.hasPasswordField = screenSummaryJson.getBoolean("hasPasswordField")
                    screenSummaryBuilder.hasSearchField = screenSummaryJson.getBoolean("hasSearchField")
                    screenSummaryBuilder.clickableCount = screenSummaryJson.getInt("clickableCount")
                    screenSummaryBuilder.editableCount = screenSummaryJson.getInt("editableCount")
                    
                    textFocusedBuilder.screenSummary = screenSummaryBuilder.build()
                    builder.textFocusedData = textFocusedBuilder.build()
                }
                
                // Serialize and compress
                val eventData = builder.build()
                val byteStream = ByteArrayOutputStream()
                LZ4FrameOutputStream(byteStream).use { lz4Out ->
                    eventData.writeTo(lz4Out)
                }
                
                // Write to file
                FileOutputStream(outputFile).use { fileOut ->
                    fileOut.write(byteStream.toByteArray())
                }
                
                Log.d(TAG, "Successfully saved compressed protobuf data to ${outputFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving event data", e)
                throw e
            }
        }
    }
}

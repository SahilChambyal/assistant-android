package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

class DirectProtobufExtractor {
    fun extractToProtobuf(root: AccessibilityNodeInfo): EventData {
        return EventData.newBuilder().apply {
            timestamp = System.currentTimeMillis()
            packageName = root.packageName?.toString() ?: ""
            windowId = root.windowId
            windowTitle = root.className?.toString() ?: ""
            windowType = inferWindowType(root)
            
            textFocusedData = extractTextFocusedData(root)
        }.build()
    }

    private fun extractTextFocusedData(root: AccessibilityNodeInfo): TextFocusedData {
        val allTextData = mutableListOf<TextData>()
        extractAllText(root, allTextData, 0)

        return TextFocusedData.newBuilder().apply {
            timestamp = System.currentTimeMillis()
            packageName = root.packageName?.toString() ?: ""
            addAllTextData(allTextData)
            screenSummary = createScreenSummary(allTextData)
        }.build()
    }

    private fun extractAllText(
        node: AccessibilityNodeInfo,
        collector: MutableList<TextData>,
        depth: Int
    ) {
        val texts = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        }

        if (texts.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            collector.add(TextData.newBuilder().apply {
                text = texts.joinToString(" | ")
                type = inferNodeType(node)
                isClickable = node.isClickable
                isEditable = node.isEditable
                className = node.className?.toString() ?: ""
                this.depth = depth
                x = rect.centerX()
                y = rect.centerY()
                width = rect.width()
                height = rect.height()
            }.build())
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractAllText(child, collector, depth + 1)
                child.recycle()
            }
        }
    }

    private fun createScreenSummary(textData: List<TextData>): ScreenSummary {
        val elementCounts = mutableMapOf<String, Int>()
        var clickableCount = 0
        var editableCount = 0
        var hasEmailField = false
        var hasPasswordField = false
        var hasSearchField = false
        val allText = StringBuilder()

        textData.forEach { data ->
            // Update element counts
            elementCounts[data.type] = (elementCounts[data.type] ?: 0) + 1
            
            // Update counters
            if (data.isClickable) clickableCount++
            if (data.isEditable) editableCount++
            
            // Check for special fields
            val lowerText = data.text.lowercase()
            if (data.isEditable) {
                when {
                    lowerText.contains("email") || lowerText.contains("e-mail") -> hasEmailField = true
                    lowerText.contains("password") || data.className.contains("password", ignoreCase = true) -> hasPasswordField = true
                    lowerText.contains("search") || data.className.contains("search", ignoreCase = true) -> hasSearchField = true
                }
            }
            
            // Append to combined text
            allText.append(data.text).append(" ")
        }

        return ScreenSummary.newBuilder().apply {
            combinedText = allText.toString().trim()
            this.elementCounts = ElementCounts.newBuilder().apply {
                putAllCounts(elementCounts)
            }.build()
            this.hasEmailField = hasEmailField
            this.hasPasswordField = hasPasswordField
            this.hasSearchField = hasSearchField
            this.clickableCount = clickableCount
            this.editableCount = editableCount
        }.build()
    }

    private fun inferNodeType(node: AccessibilityNodeInfo): String {
        return when {
            node.isEditable -> "edittext"
            node.isClickable && node.className?.contains("Button") == true -> "button"
            node.isClickable -> "clickable"
            node.className?.contains("TextView") == true -> "text"
            node.className?.contains("ImageView") == true -> "image"
            else -> "other"
        }
    }

    private fun inferWindowType(node: AccessibilityNodeInfo): String {
        return when {
            node.className?.contains("Activity") == true -> "activity"
            node.className?.contains("Dialog") == true -> "dialog"
            node.className?.contains("Menu") == true -> "menu"
            else -> "other"
        }
    }
}

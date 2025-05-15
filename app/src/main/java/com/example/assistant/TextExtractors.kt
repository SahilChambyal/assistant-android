package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

// Text-focused ML extraction
class TextFocusedMLExtractor {

    // Single extraction method focused on text for ML
    fun extractTextFocusedData(root: AccessibilityNodeInfo): JSONObject {
        val result = JSONObject()
        result.put("timestamp", System.currentTimeMillis())
        result.put("packageName", root.packageName?.toString() ?: "")

        // Extract all text with context
        val allTextData = mutableListOf<JSONObject>()
        extractAllText(root, allTextData, 0)
        result.put("textData", JSONArray(allTextData))

        // Get screen summary
        result.put("screenSummary", createScreenSummary(allTextData))

        // Keep the original hierarchy
        result.put("fullHierarchy", serializeNodeToJson(root))

        return result
    }

    private fun extractAllText(
        node: AccessibilityNodeInfo,
        collector: MutableList<JSONObject>,
        depth: Int
    ) {
        val texts = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        }
        if (texts.isNotEmpty()) {
            collector.add(JSONObject().apply {
                put("text", texts.joinToString(" | "))
                put("type", inferNodeType(node))
                put("isClickable", node.isClickable)
                put("isEditable", node.isEditable)
                put("className", node.className?.toString() ?: "")
                put("depth", depth)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                put("x", rect.centerX())
                put("y", rect.centerY())
                put("width", rect.width())
                put("height", rect.height())
            })
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractAllText(child, collector, depth + 1)
                child.recycle()
            }
        }
    }

    private fun createScreenSummary(textData: List<JSONObject>): JSONObject {
        return JSONObject().apply {
            val allText = textData.map { it.getString("text") }.joinToString(" ")
            put("combinedText", allText)
            val typeCounts = textData.groupBy { it.getString("type") }
                .mapValues { it.value.size }
            put("elementCounts", JSONObject(typeCounts))
            put("hasEmailField", allText.contains("email", ignoreCase = true))
            put("hasPasswordField", textData.any { it.getString("type") == "password" })
            put("hasSearchField", allText.contains("search", ignoreCase = true))
            put("clickableCount", textData.count { it.getBoolean("isClickable") })
            put("editableCount", textData.count { it.getBoolean("isEditable") })
        }
    }

    private fun inferNodeType(node: AccessibilityNodeInfo): String {
        return when {
            node.isPassword -> "password"
            node.isEditable -> "input"
            node.className?.toString()?.contains("Button") == true -> "button"
            node.isClickable && node.text != null -> "clickable_text"
            node.className?.toString()?.contains("TextView") == true -> "text"
            node.className?.toString()?.contains("ImageView") == true -> "image"
            else -> "other"
        }
    }

    // Local implementation for full hierarchy serialization
    private fun serializeNodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val obj = JSONObject()
        obj.put("className", node.className?.toString() ?: "")
        obj.put("text", node.text?.toString() ?: "")
        obj.put("contentDescription", node.contentDescription?.toString() ?: "")
        obj.put("packageName", node.packageName?.toString() ?: "")
        obj.put("viewIdResourceName", node.viewIdResourceName ?: "")
        obj.put("isClickable", node.isClickable)
        obj.put("isEditable", node.isEditable)
        obj.put("isPassword", node.isPassword)
        obj.put("childCount", node.childCount)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        obj.put("bounds", "[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.put(serializeNodeToJson(child))
                child.recycle()
            }
        }
        obj.put("children", children)
        return obj
    }
}

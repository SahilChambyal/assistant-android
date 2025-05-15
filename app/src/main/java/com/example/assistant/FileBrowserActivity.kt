package com.example.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * FileBrowserActivity provides a user interface for browsing and viewing
 * the collected accessibility event files. It displays a list of JSON files
 * and allows users to view their contents or open them in external apps.
 */
class FileBrowserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        // Initialize UI components
        val fileListView = findViewById<ListView>(R.id.fileListView)
        val fileContentText = findViewById<TextView>(R.id.fileContentText)

        // Get list of event files and create adapter for the ListView
        val files = FileUtils.getEventFiles(this)
        val fileNames = files.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fileNames)
        fileListView.adapter = adapter

        // Handle file selection - display content in the TextView
        fileListView.setOnItemClickListener { _, _, position, _ ->
            val file = files[position]
            val content = file.readText()
            fileContentText.text = content
        }

        // Handle long press - open file in external app
        fileListView.setOnItemLongClickListener { _, _, position, _ ->
            val file = files[position]
            // Create a content URI for the file using FileProvider
            val uri: Uri = FileProvider.getUriForFile(
                this,
                applicationContext.packageName + ".fileprovider",
                file
            )
            // Create intent to view the file
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "text/plain")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open JSON file with"))
            true
        }
    }
}

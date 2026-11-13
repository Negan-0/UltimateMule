package com.nostroteam.ultimatemule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }
        statusText = TextView(this).apply { textSize = 16f }
        val btnStorage = Button(this).apply {
            text = "1. SBLOCCA MEMORIA TOTALE"
            setOnClickListener { requestAllFilesAccess() }
        }
        val btnBattery = Button(this).apply {
            text = "2. DISATTIVA BLOCCO BATTERIA"
            setOnClickListener { disableBatteryOptimization() }
        }
        layout.addView(statusText)
        layout.addView(btnStorage)
        layout.addView(btnBattery)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        checkAndCreateFolders()
    }

    private fun checkAndCreateFolders() {
        if (Environment.isExternalStorageManager()) {
            val root = Environment.getExternalStorageDirectory()
            val muleDir = File(root, "UltimateMule")
            File(muleDir, "Incoming").mkdirs()
            File(muleDir, "Temp").mkdirs()
            File(muleDir, "Config").mkdirs()
            statusText.text = "✅ MURO ABBATTUTO!\n\nCartella creata in:\n${muleDir.absolutePath}"
        } else {
            statusText.text = "⚠️ PROTEZIONE ATTIVA.\nPremi i pulsanti qui sotto per sbloccare Android."
        }
    }

    private fun requestAllFilesAccess() {
        if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}

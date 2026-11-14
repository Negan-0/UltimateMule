package com.ultimatemule.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.irc.IrcXdccEngine
import com.ultimatemule.app.service.P2PEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var folderInput: EditText
    private lateinit var ircLogText: TextView

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ircEngine: IrcXdccEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Richiede il permesso per mostrare la notifica su Android 13/14/15/16
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        val title = TextView(this).apply {
            text = "🐴 UltimateMule (Android ${Build.VERSION.RELEASE})"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 24)
        }

        val btnUnlockStorage = Button(this).apply {
            text = "1. ABBATTI PROTEZIONE MEMORIA (ANDROID 16)"
            setOnClickListener { requestAllFilesAccess() }
        }

        val btnUnlockBattery = Button(this).apply {
            text = "2. SBLOCCA BATTERIA (CODA SEMPRE ATTIVA)"
            setOnClickListener { disableBatteryOptimization() }
        }

        val folderLabel = TextView(this).apply {
            text = "\nCartella di salvataggio assegnata:"
            textSize = 14f
        }

        folderInput = EditText(this).apply {
            setText(savedPath)
        }

        val btnSaveFolder = Button(this).apply {
            text = "SALVA E CREA CARTELLE (Incoming / Temp / Config)"
            setOnClickListener {
                val chosenPath = folderInput.text.toString().trim()
                prefs.edit().putString("custom_download_path", chosenPath).apply()
                verifyAndCreateFolders(chosenPath)
            }
        }

        val btnStartEngine = Button(this).apply {
            text = "3. AVVIA SERVIZIO BACKGROUND P2P"
            setOnClickListener {
                try {
                    val serviceIntent = Intent(this@MainActivity, P2PEngineService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Toast.makeText(this@MainActivity, "Motore P2P in Background Avviato!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    statusText.text = "Errore avvio servizio: ${e.message}"
                }
            }
        }

        // --- SEZIONE TEST MOTORE IRC / XDCC ---
        val ircTitle = TextView(this).apply {
            text = "\n💬 Rete IRC / XDCC (Test Motore Kotlin Puro)"
            textSize = 18f
            setPadding(0, 30, 0, 10)
        }

        val ircServerInput = EditText(this).apply {
            hint = "Server IRC (es. irc.libera.chat)"
            setText("irc.libera.chat")
        }

        val ircChannelInput = EditText(this).apply {
            hint = "Canale (es. #ultimatemule-test)"
            setText("#ultimatemule-test")
        }

        val btnConnectIrc = Button(this).apply {
            text = "CONNETTI A SERVER IRC"
            setOnClickListener {
                val srv = ircServerInput.text.toString().trim()
                val chan = ircChannelInput.text.toString().trim()
                val incomingDir = File(folderInput.text.toString().trim(), "Incoming")
                incomingDir.mkdirs()

                ircEngine?.disconnect()
                val engine = IrcXdccEngine(
                    server = srv,
                    port = 6667,
                    nickname = "MuleDroid" + (100..999).random(),
                    downloadDir = incomingDir,
                    scope = mainScope,
                    useSsl = false,
                    autoJoinChannel = chan
                )
                ircEngine = engine

                mainScope.launch {
                    engine.events.collect { event ->
                        when (event) {
                            is IrcXdccEngine.Event.Log -> appendIrcLog("ℹ️ ${event.text}")
                            is IrcXdccEngine.Event.Connected -> appendIrcLog("✅ Connesso a ${event.server}!")
                            is IrcXdccEngine.Event.ChannelMessage -> appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                            is IrcXdccEngine.Event.DccOfferReceived -> appendIrcLog("📥 Offerta DCC: ${event.offer.filename}")
                            is IrcXdccEngine.Event.DownloadProgress -> appendIrcLog("⬇️ ${event.filename}: ${event.received / 1024} KB")
                            is IrcXdccEngine.Event.DownloadComplete -> appendIrcLog("🎉 Completato: ${event.file.absolutePath}")
                            is IrcXdccEngine.Event.DownloadFailed -> appendIrcLog("❌ Fallito: ${event.reason}")
                            is IrcXdccEngine.Event.Disconnected -> appendIrcLog("🔌 Disconnesso: ${event.reason}")
                        }
                    }
                }
                engine.connect()
            }
        }

        ircLogText = TextView(this).apply {
            text = "Log IRC pronto..."
            textSize = 12f
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.parseColor("#00FF00"))
            setPadding(20, 20, 20, 20)
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(btnUnlockStorage)
        layout.addView(btnUnlockBattery)
        layout.addView(folderLabel)
        layout.addView(folderInput)
        layout.addView(btnSaveFolder)
        layout.addView(btnStartEngine)
        layout.addView(ircTitle)
        layout.addView(ircServerInput)
        layout.addView(ircChannelInput)
        layout.addView(btnConnectIrc)
        layout.addView(ircLogText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun appendIrcLog(msg: String) {
        val current = ircLogText.text.toString()
        val lines = (current.split("\n") + msg).takeLast(15)
        ircLogText.text = lines.joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        verifyAndCreateFolders(folderInput.text.toString().trim())
    }

    private fun verifyAndCreateFolders(basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            val incomingDir = File(baseDir, "Incoming")
            val tempDir = File(baseDir, "Temp")
            val configDir = File(baseDir, "Config")

            val created = incomingDir.mkdirs() || incomingDir.exists()
            tempDir.mkdirs()
            configDir.mkdirs()

            if (created && incomingDir.canWrite()) {
                statusText.text = "✅ MURO ANDROID 16 ABBATTUTO!\n\n" +
                        "Cartelle attive e scrivibili:\n" +
                        "📂 ${incomingDir.absolutePath}\n" +
                        "📂 ${tempDir.absolutePath}\n" +
                        "📂 ${configDir.absolutePath}"
            } else {
                statusText.text = "❌ Permesso attivo, ma impossibile creare la cartella in: $basePath"
            }
        } else {
            statusText.text = "🔒 PROTEZIONE MEMORIA ATTIVA\n" +
                    "Premi il pulsante 1 qui sotto per autorizzare UltimateMule su Android 16."
        }
    }

    private fun requestAllFilesAccess() {
        if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "Permesso memoria già sbloccato!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Batteria già sbloccata!", Toast.LENGTH_SHORT).show()
        }
    }
}

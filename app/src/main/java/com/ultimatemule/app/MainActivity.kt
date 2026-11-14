package com.ultimatemule.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.irc.IrcXdccEngine
import com.ultimatemule.app.player.VlcPreviewPlayer
import com.ultimatemule.app.service.P2PEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Viste delle 4 Schede (stile eMule PC)
    private lateinit var tabKadServers: LinearLayout
    private lateinit var tabIrcXdcc: LinearLayout
    private lateinit var tabPreviewVlc: LinearLayout
    private lateinit var tabSettings: LinearLayout

    // Riferimenti UI
    private lateinit var topStatusBanner: TextView
    private lateinit var folderInput: EditText
    private lateinit var serverListLog: TextView
    private lateinit var ircLogText: TextView
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private lateinit var vlcFilesContainer: LinearLayout

    // Motori
    private var ircEngine: IrcXdccEngine? = null
    private var vlcPlayer: VlcPreviewPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Richiesta automatica Notifiche all'avvio per Android 13/14/15/16
        requestNotificationPermissionIfNeeded()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // --- INTESTAZIONE STILE eMule ---
        topStatusBanner = TextView(this).apply {
            text = "🐴 UltimateMule | Disconnesso | Android ${Build.VERSION.RELEASE}"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(30, 25, 30, 25)
            setTypeface(null, Typeface.BOLD)
        }
        rootLayout.addView(topStatusBanner)

        // --- BARRA DI NAVIGAZIONE A SCHEDE (TABS) ---
        val tabBarScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
        }

        val btnTab1 = createTabButton("🌐 Server & KAD") { showTab(1) }
        val btnTab2 = createTabButton("💬 IRC / XDCC") { showTab(2) }
        val btnTab3 = createTabButton("🎬 Anteprima VLC") { showTab(3) }
        val btnTab4 = createTabButton("⚙️ Opzioni & Permessi") { showTab(4) }

        tabBar.addView(btnTab1)
        tabBar.addView(btnTab2)
        tabBar.addView(btnTab3)
        tabBar.addView(btnTab4)
        tabBarScroll.addView(tabBar)
        rootLayout.addView(tabBarScroll)

        // --- CONTENITORE SCORREVOLE DELLE SCHEDE ---
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tabKadServers = buildKadServersTab()
        tabIrcXdcc = buildIrcXdccTab()
        tabPreviewVlc = buildPreviewVlcTab()
        tabSettings = buildSettingsTab()

        contentHolder.addView(tabKadServers)
        contentHolder.addView(tabIrcXdcc)
        contentHolder.addView(tabPreviewVlc)
        contentHolder.addView(tabSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        setContentView(rootLayout)

        // Avvia subito il servizio con la notifica
        P2PEngineService.updateNotificationStatus(this, "Motore pronto. Connetti KAD, Server o IRC.")
        showTab(1)
    }

    private fun createTabButton(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 13f
            setOnClickListener { onClick() }
        }
    }

    private fun showTab(index: Int) {
        tabKadServers.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabIrcXdcc.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabPreviewVlc.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 4) View.VISIBLE else View.GONE

        if (index == 3) {
            refreshIncompleteAndIncomingFiles()
        }
    }

    // =========================================================================
    // SCHEDA 1: RETE SERVER eD2K (Multi server.met) & RETE KAD (nodes.dat)
    // =========================================================================
    private fun buildKadServersTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🌐 Importazione Multipla server.met (OkHttp + GZIP)"))
            addView(infoLabel("Inserisci uno o più link server.met (uno per riga) per unire tutti i server e raccogliere il massimo degli utenti:"))

            val urlsInput = EditText(this@MainActivity).apply {
                setText(
                    "http://upd.emule-security.org/server.met\n" +
                    "http://www.gruk.org/server.met.gz"
                )
                minLines = 3
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#262626"))
                setPadding(20, 20, 20, 20)
            }
            addView(urlsInput)

            val btnImportServers = Button(this@MainActivity).apply {
                text = "📥 IMPORTA E UNISCI TUTTI I SERVER (GZIP)"
                setOnClickListener {
                    val urls = urlsInput.text.toString().split("\n")
                    val configDir = File(getBaseMulePath(), "Config")
                    val manager = Ed2kKadManager(configDir)

                    mainScope.launch {
                        appendServerLog("Avvio download multiplo con OkHttp + GZIP...")
                        val servers = manager.importMultipleServerMet(urls) { log ->
                            runOnUiThread { appendServerLog(log) }
                        }
                        val totalUsers = servers.sumOf { it.users }
                        topStatusBanner.text = "🐴 eD2K: ${servers.size} Server ($totalUsers Utenti) | KAD Attivo"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "eD2K: ${servers.size} Server caricati ($totalUsers utenti) | In ascolto"
                        )
                        if (servers.isNotEmpty()) {
                            appendServerLog("\n--- TOP SERVER CARICATI ---")
                            servers.take(12).forEach { s ->
                                appendServerLog("• ${s.name} (${s.ip}:${s.port}) - Utenti: ${s.users}")
                            }
                        }
                    }
                }
            }
            addView(btnImportServers)

            addView(sectionTitle("🕸️ Rete Serverless KAD (nodes.dat)"))
            val kadUrlInput = EditText(this@MainActivity).apply {
                setText("http://upd.emule-security.org/nodes.dat")
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#262626"))
                setPadding(20, 20, 20, 20)
            }
            addView(kadUrlInput)

            val btnBootstrapKad = Button(this@MainActivity).apply {
                text = "⚡ BOOTSTRAP RETE KAD (SCARICA NODES.DAT)"
                setOnClickListener {
                    val configDir = File(getBaseMulePath(), "Config")
                    val manager = Ed2kKadManager(configDir)
                    mainScope.launch {
                        val count = manager.importKadNodesDat(kadUrlInput.text.toString()) { log ->
                            runOnUiThread { appendServerLog(log) }
                        }
                        if (count > 0) {
                            P2PEngineService.updateNotificationStatus(
                                this@MainActivity,
                                "KAD Connesso ($count nodi attivi) | Coda attiva"
                            )
                        }
                    }
                }
            }
            addView(btnBootstrapKad)

            serverListLog = TextView(this@MainActivity).apply {
                text = "Registro Rete eD2K / KAD pronto..."
                textSize = 12f
                setBackgroundColor(Color.parseColor("#1B1B1B"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(24, 24, 24, 24)
            }
            addView(serverListLog)
        }
    }

    private fun appendServerLog(msg: String) {
        val lines = (serverListLog.text.toString().split("\n") + msg).takeLast(22)
        serverListLog.text = lines.joinToString("\n")
    }

    // =========================================================================
    // SCHEDA 2: RETE IRC / XDCC (Come mIRC per scaricare dai Bot)
    // =========================================================================
    private fun buildIrcXdccTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("💬 Client IRC & Downloader XDCC (mIRC)"))

            val srvInput = EditText(this@MainActivity).apply {
                hint = "Server IRC (es. irc.libera.chat)"
                setText("irc.libera.chat")
                setTextColor(Color.WHITE)
            }
            val chanInput = EditText(this@MainActivity).apply {
                hint = "Canale (es. #ultimatemule-test)"
                setText("#ultimatemule-test")
                setTextColor(Color.WHITE)
            }
            addView(srvInput)
            addView(chanInput)

            val btnConnect = Button(this@MainActivity).apply {
                text = "🔌 CONNETTI A SERVER IRC"
                setOnClickListener {
                    val incomingDir = File(getBaseMulePath(), "Incoming").apply { mkdirs() }
                    ircEngine?.disconnect()
                    val engine = IrcXdccEngine(
                        server = srvInput.text.toString().trim(),
                        port = 6667,
                        nickname = "MuleDroid" + (100..999).random(),
                        downloadDir = incomingDir,
                        scope = mainScope,
                        useSsl = false,
                        autoJoinChannel = chanInput.text.toString().trim()
                    )
                    ircEngine = engine

                    mainScope.launch {
                        engine.events.collect { event ->
                            when (event) {
                                is IrcXdccEngine.Event.Log -> appendIrcLog("ℹ️ ${event.text}")
                                is IrcXdccEngine.Event.Connected -> {
                                    appendIrcLog("✅ Connesso a ${event.server}")
                                    P2PEngineService.updateNotificationStatus(
                                        this@MainActivity,
                                        "IRC Connesso a ${event.server} | Pronto per XDCC"
                                    )
                                }
                                is IrcXdccEngine.Event.ChannelMessage ->
                                    appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                                is IrcXdccEngine.Event.DccOfferReceived ->
                                    appendIrcLog("📦 Avvio DCC: ${event.offer.filename} (${event.offer.size / 1024} KB)")
                                is IrcXdccEngine.Event.DownloadProgress -> {
                                    val pct = if (event.total > 0) ((event.received * 100) / event.total).toInt() else 0
                                    ircProgressBar.progress = pct
                                    val speedKb = event.speedBps / 1024
                                    ircProgressLabel.text = "⬇️ ${event.filename}: $pct% ($speedKb KB/s)"
                                    P2PEngineService.updateNotificationStatus(
                                        this@MainActivity,
                                        "Download XDCC: ${event.filename} ($pct% - $speedKb KB/s)"
                                    )
                                }
                                is IrcXdccEngine.Event.DownloadComplete -> {
                                    ircProgressBar.progress = 100
                                    ircProgressLabel.text = "✅ Completato: ${event.file.name}"
                                    appendIrcLog("🎉 Salvato in: ${event.file.absolutePath}")
                                }
                                is IrcXdccEngine.Event.DownloadFailed ->
                                    appendIrcLog("❌ Errore download: ${event.reason}")
                                is IrcXdccEngine.Event.Disconnected ->
                                    appendIrcLog("🔌 Disconnesso")
                            }
                        }
                    }
                    engine.connect()
                }
            }
            addView(btnConnect)

            addView(sectionTitle("📦 Scarica Pacchetto XDCC da Bot"))
            val botNameInput = EditText(this@MainActivity).apply {
                hint = "Nome del Bot (es. MovieBot-01)"
                setTextColor(Color.WHITE)
            }
            val packNumInput = EditText(this@MainActivity).apply {
                hint = "Numero Pacchetto (es. 12)"
                setTextColor(Color.WHITE)
            }
            addView(botNameInput)
            addView(packNumInput)

            val btnRequestPack = Button(this@MainActivity).apply {
                text = "⬇️ RICHIEDI PACCHETTO (XDCC SEND)"
                setOnClickListener {
                    val bot = botNameInput.text.toString().trim()
                    val pack = packNumInput.text.toString().trim().toIntOrNull()
                    if (bot.isNotEmpty() && pack != null) {
                        ircEngine?.requestPack(bot, pack)
                    } else {
                        Toast.makeText(this@MainActivity, "Inserisci Nome Bot e Numero Pack!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(btnRequestPack)

            ircProgressLabel = infoLabel("Nessun download XDCC attivo")
            ircProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            ircLogText = TextView(this@MainActivity).apply {
                text = "Terminale IRC pronto..."
                textSize = 12f
                setBackgroundColor(Color.parseColor("#111827"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(20, 20, 20, 20)
            }
            addView(ircLogText)
        }
    }

    private fun appendIrcLog(msg: String) {
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(18)
        ircLogText.text = lines.joinToString("\n")
    }

    // =========================================================================
    // SCHEDA 3: ANTEPRIMA VIDEO LIBVLC (File .part in Temp e File in Incoming)
    // =========================================================================
    private fun buildPreviewVlcTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🎬 Lettore Anteprime Video LibVLC"))
            addView(infoLabel("Riproduce direttamente i file incompleti (.part) nella cartella Temp e i file scaricati in Incoming:"))

            val videoSurface = SurfaceView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    620
                )
            }
            addView(videoSurface)
            vlcPlayer = VlcPreviewPlayer(this@MainActivity, videoSurface)

            val btnStopVlc = Button(this@MainActivity).apply {
                text = "⏹️ FERMA RIPRODUZIONE ANTEPRIMA"
                setOnClickListener { vlcPlayer?.stop() }
            }
            addView(btnStopVlc)

            val btnRefreshFiles = Button(this@MainActivity).apply {
                text = "🔄 AGGIORNA LISTA FILE IN TEMP & INCOMING"
                setOnClickListener { refreshIncompleteAndIncomingFiles() }
            }
            addView(btnRefreshFiles)

            vlcFilesContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(vlcFilesContainer)
        }
    }

    private fun refreshIncompleteAndIncomingFiles() {
        vlcFilesContainer.removeAllViews()
        val base = File(getBaseMulePath())
        val tempFiles = File(base, "Temp").listFiles()?.toList().orEmpty()
        val incomingFiles = File(base, "Incoming").listFiles()?.toList().orEmpty()
        val allFiles = tempFiles + incomingFiles

        if (allFiles.isEmpty()) {
            vlcFilesContainer.addView(infoLabel("Nessun file trovato in Temp o Incoming. Copia un file video o avvia un download per testare l'anteprima VLC!"))
            return
        }

        allFiles.forEach { file ->
            val btn = Button(this).apply {
                text = "▶️ ANTEPRIMA: ${file.name} (${file.length() / 1024} KB)"
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "Avvio LibVLC: ${file.name}", Toast.LENGTH_SHORT).show()
                    vlcPlayer?.playFileOrPart(file)
                }
            }
            vlcFilesContainer.addView(btn)
        }
    }

    // =========================================================================
    // SCHEDA 4: IMPOSTAZIONI eMule + SBLOCCO PERMESSI E NOTIFICHE ANDROID 16
    // =========================================================================
    private fun buildSettingsTab(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🔔 Permessi & Sblocco Totale Android ${Build.VERSION.RELEASE}"))

            val btnFixNotifications = Button(this@MainActivity).apply {
                text = "🔔 ATTIVA NOTIFICHE IN TEMPO REALE (ANDROID 16)"
                setOnClickListener { openOrRequestNotifications() }
            }
            addView(btnFixNotifications)

            val btnUnlockStorage = Button(this@MainActivity).apply {
                text = "📂 1. ABBATTI PROTEZIONE MEMORIA (ACCESSO TOTALE)"
                setOnClickListener { requestAllFilesAccess() }
            }
            addView(btnUnlockStorage)

            val btnUnlockBattery = Button(this@MainActivity).apply {
                text = "🔋 2. DISATTIVA BLOCCO BATTERIA (NON PERDERE LA CODA)"
                setOnClickListener { disableBatteryOptimization() }
            }
            addView(btnUnlockBattery)

            addView(sectionTitle("📁 Cartella di Salvataggio Specificata"))
            folderInput = EditText(this@MainActivity).apply {
                setText(savedPath)
                setTextColor(Color.WHITE)
            }
            addView(folderInput)

            val btnSaveFolder = Button(this@MainActivity).apply {
                text = "💾 SALVA E CREA CARTELLE (Incoming / Temp / Config)"
                setOnClickListener {
                    val chosen = folderInput.text.toString().trim()
                    prefs.edit().putString("custom_download_path", chosen).apply()
                    verifyAndCreateFolders(chosen)
                }
            }
            addView(btnSaveFolder)

            addView(sectionTitle("⚙️ Configurazione Parametri eMule (Come su PC)"))
            val tcpPortInput = EditText(this@MainActivity).apply {
                hint = "Porta TCP eMule (Default: 4662)"
                setText(prefs.getString("emule_tcp", "4662"))
                setTextColor(Color.WHITE)
            }
            val udpPortInput = EditText(this@MainActivity).apply {
                hint = "Porta UDP KAD (Default: 4672)"
                setText(prefs.getString("emule_udp", "4672"))
                setTextColor(Color.WHITE)
            }
            val chkObfuscation = CheckBox(this@MainActivity).apply {
                text = "Attiva Offuscamento Protocollo (Consigliato contro blocchi operatori)"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
            }
            val chkGzip = CheckBox(this@MainActivity).apply {
                text = "Attiva Compressione Pacchetti GZIP / Zlib"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
            }

            val btnSaveEmuleConfig = Button(this@MainActivity).apply {
                text = "✅ SALVA IMPOSTAZIONI EMULE"
                setOnClickListener {
                    prefs.edit()
                        .putString("emule_tcp", tcpPortInput.text.toString())
                        .putString("emule_udp", udpPortInput.text.toString())
                        .putBoolean("emule_obfuscation", chkObfuscation.isChecked)
                        .putBoolean("emule_gzip", chkGzip.isChecked)
                        .apply()
                    Toast.makeText(this@MainActivity, "Impostazioni eMule salvate!", Toast.LENGTH_SHORT).show()
                }
            }

            addView(infoLabel("Porta TCP Client (ID Alto):"))
            addView(tcpPortInput)
            addView(infoLabel("Porta UDP Rete KAD:"))
            addView(udpPortInput)
            addView(chkObfuscation)
            addView(chkGzip)
            addView(btnSaveEmuleConfig)
        }
    }

    // =========================================================================
    // UTILITÀ PERMESSI E NOTIFICHE ANDROID 16
    // =========================================================================
    private fun getBaseMulePath(): String {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val def = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        return prefs.getString("custom_download_path", def) ?: def
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun openOrRequestNotifications() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
            // Apre direttamente il pannello Notifiche dell'app su Android 16 per sbloccarle manualmente
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        } else {
            P2PEngineService.updateNotificationStatus(this, "Notifiche Attive e Funzionanti su Android 16!")
            Toast.makeText(this, "✅ Notifiche già attive! Controlla la tendina.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyAndCreateFolders(basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            val incoming = File(baseDir, "Incoming")
            val temp = File(baseDir, "Temp")
            val config = File(baseDir, "Config")
            incoming.mkdirs()
            temp.mkdirs()
            config.mkdirs()
            Toast.makeText(this, "✅ Cartelle pronte in: ${baseDir.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Sblocca prima la protezione memoria col Pulsante 1!", Toast.LENGTH_LONG).show()
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
        } else {
            Toast.makeText(this, "✅ Memoria totale già sbloccata!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "✅ Batteria già sbloccata!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 28, 0, 12)
        }
    }

    private fun infoLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#D1D5DB"))
            setPadding(0, 6, 0, 10)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vlcPlayer?.release()
    }
}
         

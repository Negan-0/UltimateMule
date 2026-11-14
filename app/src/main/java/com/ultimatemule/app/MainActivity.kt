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
import android.view.LayoutInflater
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
import com.ultimatemule.app.player.VlcPlayerManager
import com.ultimatemule.app.service.P2PEngineService
import com.ultimatemule.app.streaming.TorrentStreamEngine
import com.ultimatemule.app.streaming.TorrentStreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class MainActivity : Activity() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Le 5 Schede complete di UltimateMule
    private lateinit var tabKadServers: LinearLayout
    private lateinit var tabTorrent: LinearLayout
    private lateinit var tabIrcXdcc: LinearLayout
    private lateinit var tabPreviewVlc: LinearLayout
    private lateinit var tabSettings: LinearLayout

    // Riferimenti UI
    private lateinit var topStatusBanner: TextView
    private lateinit var folderInput: EditText
    private lateinit var serverListLog: TextView
    private lateinit var torrentStatusText: TextView
    private lateinit var torrentProgressBar: ProgressBar
    private lateinit var ircLogText: TextView
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private lateinit var vlcAudioInfoText: TextView
    private lateinit var vlcFilesContainer: LinearLayout

    // Motori
    private var ircEngine: IrcXdccEngine? = null
    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var torrentEngine: TorrentStreamEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        vlcManager = VlcPlayerManager(this)
        torrentEngine = TorrentStreamEngine(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // --- BANNER DI STATO IN ALTO ---
        topStatusBanner = TextView(this).apply {
            text = "🐴 UltimateMule | Reti P2P Pronte | Android ${Build.VERSION.RELEASE}"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2937"))
            setPadding(30, 25, 30, 25)
            setTypeface(null, Typeface.BOLD)
        }
        rootLayout.addView(topStatusBanner)

        // --- BARRA A 5 SCHEDE SCORREVOLE ---
        val tabBarScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
        }

        tabBar.addView(createTabButton("🌐 Server & KAD") { showTab(1) })
        tabBar.addView(createTabButton("🧲 Torrent/Magnet") { showTab(2) })
        tabBar.addView(createTabButton("💬 IRC / XDCC") { showTab(3) })
        tabBar.addView(createTabButton("🎬 Player VLC") { showTab(4) })
        tabBar.addView(createTabButton("⚙️ Opzioni") { showTab(5) })

        tabBarScroll.addView(tabBar)
        rootLayout.addView(tabBarScroll)

        // --- CONTENITORE PRINCIPALE ---
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        tabKadServers = buildKadServersTab()
        tabTorrent = buildTorrentTab()
        tabIrcXdcc = buildIrcXdccTab()
        tabPreviewVlc = buildPreviewVlcTab()
        tabSettings = buildSettingsTab()

        contentHolder.addView(tabKadServers)
        contentHolder.addView(tabTorrent)
        contentHolder.addView(tabIrcXdcc)
        contentHolder.addView(tabPreviewVlc)
        contentHolder.addView(tabSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        setContentView(rootLayout)

        observeTorrentEngineState()
        P2PEngineService.updateNotificationStatus(this, "Motore attivo: KAD | Server | Torrent | IRC")
        showTab(1)
    }

    private fun createTabButton(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 12f
            setOnClickListener { onClick() }
        }
    }

    private fun showTab(index: Int) {
        tabKadServers.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabTorrent.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabIrcXdcc.visibility = if (index == 3) View.VISIBLE else View.GONE
        tabPreviewVlc.visibility = if (index == 4) View.VISIBLE else View.GONE
        tabSettings.visibility = if (index == 5) View.VISIBLE else View.GONE

        if (index == 4) {
            refreshIncompleteAndIncomingFiles()
        }
    }

    // =========================================================================
    // SCHEDA 1: SERVER eD2K (Multi server.met) & RETE KAD (nodes.dat)
    // =========================================================================
    private fun buildKadServersTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🌐 Importazione Multipla server.met (OkHttp + GZIP)"))
            addView(infoLabel("Incolla più URL server.met (uno per riga) per raccogliere tutti i server e sommare gli utenti:"))

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
                text = "📥 IMPORTA E UNISCI SERVER.MET (GZIP)"
                setOnClickListener {
                    val urls = urlsInput.text.toString().split("\n")
                    val configDir = File(getBaseMulePath(), "Config")
                    val manager = Ed2kKadManager(configDir)

                    mainScope.launch {
                        appendServerLog("Download multiplo OkHttp + GZIP avviato...")
                        val servers = manager.importMultipleServerMet(urls) { log ->
                            runOnUiThread { appendServerLog(log) }
                        }
                        val totalUsers = servers.sumOf { it.users }
                        topStatusBanner.text = "🐴 eD2K: ${servers.size} Server ($totalUsers Utenti) | KAD Pronto"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "eD2K: ${servers.size} Server ($totalUsers utenti) | In ascolto"
                        )
                        if (servers.isNotEmpty()) {
                            appendServerLog("\n--- TOP SERVER CARICATI ---")
                            servers.take(10).forEach { s ->
                                appendServerLog("• ${s.name} (${s.ip}:${s.port}) - Utenti: ${s.users}")
                            }
                        }
                    }
                }
            }
            addView(btnImportServers)

            addView(sectionTitle("🕸️ Rete KAD Serverless (nodes.dat)"))
            val kadUrlInput = EditText(this@MainActivity).apply {
                setText("http://upd.emule-security.org/nodes.dat")
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#262626"))
                setPadding(20, 20, 20, 20)
            }
            addView(kadUrlInput)

            val btnBootstrapKad = Button(this@MainActivity).apply {
                text = "⚡ SCARICA NODES.DAT E CONNETTI KAD"
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
                                "Rete KAD Attiva ($count nodi) | Coda protetta"
                            )
                        }
                    }
                }
            }
            addView(btnBootstrapKad)

            serverListLog = TextView(this@MainActivity).apply {
                text = "Log Server eD2K / KAD pronto..."
                textSize = 12f
                setBackgroundColor(Color.parseColor("#1B1B1B"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(24, 24, 24, 24)
            }
            addView(serverListLog)
        }
    }

    private fun appendServerLog(msg: String) {
        val lines = (serverListLog.text.toString().split("\n") + msg).takeLast(20)
        serverListLog.text = lines.joinToString("\n")
    }

    // =========================================================================
    // SCHEDA 2: MOTORE TORRENT / MAGNET CON ANTEPRIMA MOOV HEAD/TAIL
    // =========================================================================
    private fun buildTorrentTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🧲 Motore Torrent & Magnet (Priorità Inizio/Fine per VLC)"))
            addView(infoLabel("Incolla un link Magnet, InfoHash, URL .torrent (supporta GZIP) o percorso file:"))

            val magnetInput = EditText(this@MainActivity).apply {
                hint = "magnet:?xt=urn:btih:... oppure https://.../file.torrent"
                minLines = 3
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#262626"))
                setPadding(20, 20, 20, 20)
            }
            addView(magnetInput)

            val btnStartTorrent = Button(this@MainActivity).apply {
                text = "🚀 AVVIA DOWNLOAD & ANTEPRIMA AUTOMATICA VLC"
                setOnClickListener {
                    val input = magnetInput.text.toString().trim()
                    if (input.isNotEmpty()) {
                        torrentEngine.startStream(input)
                    } else {
                        Toast.makeText(this@MainActivity, "Inserisci un Magnet o URL Torrent!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(btnStartTorrent)

            torrentStatusText = infoLabel("Stato Torrent: In attesa...")
            torrentProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(torrentStatusText)
            addView(torrentProgressBar)

            val btnStopKeep = Button(this@MainActivity).apply {
                text = "⏹️ FERMA TORRENT (MANTIENI FILE IN INCOMING)"
                setOnClickListener { torrentEngine.stopKeepFiles() }
            }
            val btnStopClear = Button(this@MainActivity).apply {
                text = "🗑️ FERMA E CANCELLA FILE TEMPORANEI"
                setOnClickListener { torrentEngine.stopAndClearCache() }
            }
            addView(btnStopKeep)
            addView(btnStopClear)
        }
    }

    private fun observeTorrentEngineState() {
        mainScope.launch {
            torrentEngine.state.collect { state ->
                when (state) {
                    is TorrentStreamState.Idle -> {
                        torrentStatusText.text = "Stato Torrent: Inattivo"
                        torrentProgressBar.progress = 0
                    }
                    is TorrentStreamState.Buffering -> {
                        torrentProgressBar.progress = state.progressPercent
                        val statusLine = "⬇️ ${state.torrentTitle}\n${state.statusText} | ${state.downloadSpeedKbps} KB/s | Peer: ${state.peers}"
                        torrentStatusText.text = statusLine
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Torrent: ${state.progressPercent}% (${state.downloadSpeedKbps} KB/s - ${state.peers} peer)"
                        )
                    }
                    is TorrentStreamState.Ready -> {
                        torrentProgressBar.progress = 100
                        torrentStatusText.text = "✅ Pronto per l'anteprima: ${state.videoFile.name}\nPercorso: ${state.videoFile.absolutePath}"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Anteprima pronta: ${state.torrentTitle}"
                        )
                        // Passa automaticamente alla scheda Player VLC e avvia l'anteprima!
                        showTab(4)
                        playWithVlcManager(state.videoFile)
                    }
                    is TorrentStreamState.Error -> {
                        torrentStatusText.text = "❌ ${state.message}"
                    }
                }
            }
        }
    }

    // =========================================================================
    // SCHEDA 3: RETE IRC / XDCC (Come mIRC)
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
                                        "IRC Connesso a ${event.server}"
                                    )
                                }
                                is IrcXdccEngine.Event.ChannelMessage ->
                                    appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                                is IrcXdccEngine.Event.DccOfferReceived ->
                                    appendIrcLog("📦 DCC SEND: ${event.offer.filename} (${event.offer.size / 1024} KB)")
                                is IrcXdccEngine.Event.DownloadProgress -> {
                                    val pct = if (event.total > 0) ((event.received * 100) / event.total).toInt() else 0
                                    ircProgressBar.progress = pct
                                    val speedKb = event.speedBps / 1024
                                    ircProgressLabel.text = "⬇️ ${event.filename}: $pct% ($speedKb KB/s)"
                                    P2PEngineService.updateNotificationStatus(
                                        this@MainActivity,
                                        "XDCC: ${event.filename} ($pct% - $speedKb KB/s)"
                                    )
                                }
                                is IrcXdccEngine.Event.DownloadComplete -> {
                                    ircProgressBar.progress = 100
                                    ircProgressLabel.text = "✅ Completato: ${event.file.name}"
                                    appendIrcLog("🎉 File salvato: ${event.file.absolutePath}")
                                }
                                is IrcXdccEngine.Event.DownloadFailed ->
                                    appendIrcLog("❌ Errore: ${event.reason}")
                                is IrcXdccEngine.Event.Disconnected ->
                                    appendIrcLog("🔌 Disconnesso")
                            }
                        }
                    }
                    engine.connect()
                }
            }
            addView(btnConnect)

            addView(sectionTitle("📦 Scarica Pacchetto da Bot XDCC"))
            val botNameInput = EditText(this@MainActivity).apply {
                hint = "Nome Bot (es. MovieBot-01)"
                setTextColor(Color.WHITE)
            }
            val packNumInput = EditText(this@MainActivity).apply {
                hint = "Numero Pacchetto (es. 12)"
                setTextColor(Color.WHITE)
            }
            addView(botNameInput)
            addView(packNumInput)

            val btnRequestPack = Button(this@MainActivity).apply {
                text = "⬇️ INVIA COMANDO XDCC SEND #PACK"
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
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(16)
        ircLogText.text = lines.joinToString("\n")
    }

    // =========================================================================
    // SCHEDA 4: ANTEPRIMA VIDEO CON IL TUO VLCPLAYERMANAGER
    // =========================================================================
    private fun buildPreviewVlcTab(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🎬 Anteprima Video LibVLC (Supporto .part, Torrent & Audio ITA)"))

            // Infla il VLCVideoLayout reale da XML per il tuo VlcPlayerManager
            vlcVideoLayout = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.vlc_player_container, this, false) as VLCVideoLayout
            addView(vlcVideoLayout)

            vlcAudioInfoText = infoLabel("Traccia Audio: Nessun video in riproduzione")
            addView(vlcAudioInfoText)

            // Barra controlli rapidi VLC (Play/Pausa, +10s, Aspect Ratio 16:9 / 21:9 / FILL)
            val controlsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            controlsRow.addView(Button(this@MainActivity).apply {
                text = "⏯️ Play/Pausa"
                setOnClickListener { vlcManager.togglePlayPause() }
            })
            controlsRow.addView(Button(this@MainActivity).apply {
                text = "⏩ +15s"
                setOnClickListener { vlcManager.seekBy(15_000L) }
            })
            controlsRow.addView(Button(this@MainActivity).apply {
                text = "📐 16:9"
                setOnClickListener { vlcManager.applyAspectRatioAndScale(vlcVideoLayout, "16:9") }
            })
            controlsRow.addView(Button(this@MainActivity).apply {
                text = "🔍 FILL"
                setOnClickListener { vlcManager.applyAspectRatioAndScale(vlcVideoLayout, "FILL") }
            })
            controlsRow.addView(Button(this@MainActivity).apply {
                text = "⏹️ Stop"
                setOnClickListener { vlcManager.stopAndDetach() }
            })
            addView(controlsRow)

            val btnRefreshFiles = Button(this@MainActivity).apply {
                text = "🔄 SCANSIONA FILE IN TEMP (.PART) E INCOMING"
                setOnClickListener { refreshIncompleteAndIncomingFiles() }
            }
            addView(btnRefreshFiles)

            vlcFilesContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(vlcFilesContainer)
        }
    }

    private fun playWithVlcManager(file: File) {
        vlcManager.attachAndPrepare(
            layout = vlcVideoLayout,
            streamUrl = file.absolutePath,
            onAudioTracksUpdated = { _, _, formattedInfo ->
                runOnUiThread {
                    vlcAudioInfoText.text = "🔊 Audio selezionato: $formattedInfo"
                }
            },
            onError = {
                runOnUiThread {
                    Toast.makeText(this, "Errore apertura anteprima VLC", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun refreshIncompleteAndIncomingFiles() {
        vlcFilesContainer.removeAllViews()
        val base = File(getBaseMulePath())
        val tempFiles = File(base, "Temp").walkTopDown().filter { it.isFile }.toList()
        val incomingFiles = File(base, "Incoming").walkTopDown().filter { it.isFile }.toList()
        val allFiles = tempFiles + incomingFiles

        if (allFiles.isEmpty()) {
            vlcFilesContainer.addView(infoLabel("Nessun file presente in Temp o Incoming."))
            return
        }

        allFiles.forEach { file ->
            val btn = Button(this).apply {
                text = "▶️ ANTEPRIMA: ${file.name} (${file.length() / 1024} KB)"
                setOnClickListener { playWithVlcManager(file) }
            }
            vlcFilesContainer.addView(btn)
        }
    }

    // =========================================================================
    // SCHEDA 5: IMPOSTAZIONI EMULE & SBLOCCO NOTIFICHE / MEMORIA ANDROID 16
    // =========================================================================
    private fun buildSettingsTab(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("🔔 Permessi & Sblocco Android ${Build.VERSION.RELEASE}"))

            addView(Button(this@MainActivity).apply {
                text = "🔔 SBLOCCA E TESTA NOTIFICHE ANDROID 16"
                setOnClickListener { openOrRequestNotifications() }
            })

            addView(Button(this@MainActivity).apply {
                text = "📂 1. ABBATTI PROTEZIONE MEMORIA (ACCESSO TOTALE)"
                setOnClickListener { requestAllFilesAccess() }
            })

            addView(Button(this@MainActivity).apply {
                text = "🔋 2. DISATTIVA BLOCCO BATTERIA"
                setOnClickListener { disableBatteryOptimization() }
            })

            addView(sectionTitle("📁 Cartella di Salvataggio Specificata"))
            folderInput = EditText(this@MainActivity).apply {
                setText(savedPath)
                setTextColor(Color.WHITE)
            }
            addView(folderInput)

            addView(Button(this@MainActivity).apply {
                text = "💾 SALVA E CREA CARTELLE (Incoming / Temp / Config)"
                setOnClickListener {
                    val chosen = folderInput.text.toString().trim()
                    prefs.edit().putString("custom_download_path", chosen).apply()
                    verifyAndCreateFolders(chosen)
                }
            })

            addView(sectionTitle("⚙️ Parametri Rete eMule (TCP / UDP / Offuscamento)"))
            val tcpPortInput = EditText(this@MainActivity).apply {
                setText(prefs.getString("emule_tcp", "4662"))
                setTextColor(Color.WHITE)
            }
            val udpPortInput = EditText(this@MainActivity).apply {
                setText(prefs.getString("emule_udp", "4672"))
                setTextColor(Color.WHITE)
            }
            val chkObfuscation = CheckBox(this@MainActivity).apply {
                text = "Attiva Offuscamento Protocollo"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
            }
            val chkGzip = CheckBox(this@MainActivity).apply {
                text = "Attiva Compressione GZIP / Zlib"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
            }

            addView(infoLabel("Porta TCP:"))
            addView(tcpPortInput)
            addView(infoLabel("Porta UDP KAD:"))
            addView(udpPortInput)
            addView(chkObfuscation)
            addView(chkGzip)

            addView(Button(this@MainActivity).apply {
                text = "✅ SALVA CONFIGURAZIONE EMULE"
                setOnClickListener {
                    prefs.edit()
                        .putString("emule_tcp", tcpPortInput.text.toString())
                        .putString("emule_udp", udpPortInput.text.toString())
                        .putBoolean("emule_obfuscation", chkObfuscation.isChecked)
                        .putBoolean("emule_gzip", chkGzip.isChecked)
                        .apply()
                    Toast.makeText(this@MainActivity, "Impostazioni salvate!", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

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
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        } else {
            P2PEngineService.updateNotificationStatus(this, "✅ Notifiche Attive su Android 16!")
            Toast.makeText(this, "Notifica aggiornata nella tendina!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyAndCreateFolders(basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            File(baseDir, "Incoming").mkdirs()
            File(baseDir, "Temp").mkdirs()
            File(baseDir, "Config").mkdirs()
            Toast.makeText(this, "✅ Cartelle pronte in: ${baseDir.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Premi prima il pulsante 1 per sbloccare la memoria!", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "✅ Memoria già sbloccata!", Toast.LENGTH_SHORT).show()
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
            textSize = 16f
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
        vlcManager.release()
    }
}

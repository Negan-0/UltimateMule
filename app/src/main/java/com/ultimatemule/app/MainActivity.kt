package com.ultimatemule.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.os.StrictMode
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.irc.IrcXdccEngine
import com.ultimatemule.app.player.VlcPlayerManager
import com.ultimatemule.app.search.GlobalSearchEngine
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

    // Le 6 Sezioni nell'ordine richiesto:
    // 0: Reti | 1: Cerca | 2: Torrent | 3: mIRC | 4: File & VLC (accanto a Opzioni!) | 5: Opzioni
    private lateinit var screenNetworks: LinearLayout
    private lateinit var screenSearch: LinearLayout
    private lateinit var screenTorrent: LinearLayout
    private lateinit var screenIrc: LinearLayout
    private lateinit var screenFilesVlc: LinearLayout
    private lateinit var screenSettings: LinearLayout

    private val navItems = mutableListOf<LinearLayout>()

    // Riferimenti UI Reti
    private lateinit var topStatusBanner: TextView
    private lateinit var btnGlobalConnectToggle: Button
    private lateinit var serverListLog: TextView
    private var selectedEd2kConnectionMode = 0 // 0 = Server+KAD, 1 = Solo Server, 2 = Solo KAD

    // Riferimenti UI Cerca
    private lateinit var btnDynamicSearch: Button
    private lateinit var searchResultsContainer: LinearLayout

    // Riferimenti UI File & VLC e Torrent
    private lateinit var activeDownloadBoxInFiles: LinearLayout
    private lateinit var activeDownloadTitleInFiles: TextView
    private lateinit var activeDownloadProgressInFiles: ProgressBar
    private lateinit var activeDownloadPreviewHolderInFiles: LinearLayout
    private lateinit var filesListContainer: LinearLayout

    private lateinit var torrentStatusText: TextView
    private lateinit var torrentProgressBar: ProgressBar

    // Riferimenti UI mIRC
    private lateinit var ircServerInput: EditText
    private lateinit var btnIrcConnectToggle: Button
    private var isIrcConnectedState = false
    private lateinit var ircActiveChannelsBanner: TextView
    private lateinit var ircLogText: TextView
    private lateinit var ircCommandInput: EditText
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private lateinit var ircChannelFilesContainer: LinearLayout
    private val scannedChannels = mutableListOf<IrcXdccEngine.IrcChannelInfo>()
    private val discoveredChannelPacks = linkedMapOf<String, IrcXdccEngine.ChannelPackItem>()

    // Motori P2P & Media
    private var ircEngine: IrcXdccEngine? = null
    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var torrentEngine: TorrentStreamEngine
    private lateinit var searchEngine: GlobalSearchEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        vlcManager = VlcPlayerManager(this)
        torrentEngine = TorrentStreamEngine(this)
        searchEngine = GlobalSearchEngine(File(getBaseMulePath(), "Config"))

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
        }

        // --- BARRA SUPERIORE COMPATTA ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(20, 14, 20, 14)
        }

        topStatusBanner = TextView(this).apply {
            text = "UltimateMule  |  Pronto"
            textSize = 11f
            setTextColor(Color.parseColor("#E5E7EB"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnQuickDisconnect = compactRowButton("SCOLLEGA RETI", "#991B1B", 0f) {
            disconnectAllNetworks()
        }

        topBar.addView(topStatusBanner)
        topBar.addView(btnQuickDisconnect)
        rootLayout.addView(topBar)

        // --- AREA CENTRALE SCORREVOLE ---
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 28)
        }

        screenNetworks = buildNetworksScreen()
        screenSearch = buildSearchScreen()
        screenTorrent = buildTorrentScreen()
        screenIrc = buildIrcScreen()
        screenFilesVlc = buildFilesVlcScreen()
        screenSettings = buildSettingsScreen()

        contentHolder.addView(screenNetworks)
        contentHolder.addView(screenSearch)
        contentHolder.addView(screenTorrent)
        contentHolder.addView(screenIrc)
        contentHolder.addView(screenFilesVlc)
        contentHolder.addView(screenSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        // --- BARRA INFERIORE CON "FILE & VLC" VICINO A "OPZIONI" ---
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        bottomNav.addView(createBottomNavItem(R.drawable.ic_networks, "Reti", 0))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_search, "Cerca", 1))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_torrent, "Torrent", 2))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_irc, "mIRC", 3))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_transfers, "File & VLC", 4))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_settings, "Opzioni", 5))

        rootLayout.addView(bottomNav)
        setContentView(rootLayout)

        observeTorrentEngineState()
        P2PEngineService.updateNotificationStatus(this, "Servizio P2P pronto")
        selectScreen(0)

        // WIZARD AUTOMATICO DI PRIMO AVVIO (1: Memoria -> 2: Cartella -> 3: Batteria -> 4: Notifiche)
        runSequentialOnboardingWizard()
    }

    override fun onResume() {
        super.onResume()
        runSequentialOnboardingWizard()
    }

    /**
     * All'installazione chiede in ordine automatico:
     * 1) Sblocco Memoria Totale
     * 2) Dove creare le cartelle Incoming / Temp / Config
     * 3) Disattivazione blocco Batteria
     * 4) Permesso Notifiche
     */
    private fun runSequentialOnboardingWizard() {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)

        // Passo 1: Accesso a tutti i file
        if (!Environment.isExternalStorageManager()) {
            if (!prefs.getBoolean("wizard_asking_storage", false)) {
                prefs.edit().putBoolean("wizard_asking_storage", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("Passo 1 di 3: Sblocco Memoria")
                    .setMessage("Benvenuto in UltimateMule.\nConcedi l'accesso ai file per salvare liberamente nella memoria del telefono.")
                    .setCancelable(false)
                    .setPositiveButton("SBLOCCA ORA") { _, _ ->
                        prefs.edit().putBoolean("wizard_asking_storage", false).apply()
                        requestAllFilesAccess()
                    }
                    .show()
            }
            return
        }

        // Passo 2: Scelta cartella dove creare i file
        if (!prefs.getBoolean("wizard_folder_configured", false)) {
            val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
            val input = styledEditText(prefs.getString("custom_download_path", defaultPath) ?: defaultPath)
            AlertDialog.Builder(this)
                .setTitle("Passo 2 di 3: Cartella di Salvataggio")
                .setMessage("Scegli o conferma la cartella dove verranno creati Incoming, Temp e Config:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("CONFERMA E CREA") { _, _ ->
                    val chosen = input.text.toString().trim().ifEmpty { defaultPath }
                    prefs.edit()
                        .putString("custom_download_path", chosen)
                        .putBoolean("wizard_folder_configured", true)
                        .apply()
                    verifyAndCreateFolders(chosen)
                    runSequentialOnboardingWizard()
                }
                .show()
            return
        }

        // Passo 3: Sblocco Batteria
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("wizard_battery_done", false)) {
            prefs.edit().putBoolean("wizard_battery_done", true).apply()
            disableBatteryOptimization()
            return
        }

        // Passo 4: Notifiche (Richiesta nativa Android)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun createBottomNavItem(iconRes: Int, label: String, index: Int): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 14, 4, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectScreen(index) }
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(44, 44)
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        item.addView(icon)
        item.addView(text)
        navItems.add(item)
        return item
    }

    private fun selectScreen(index: Int) {
        screenNetworks.visibility = if (index == 0) View.VISIBLE else View.GONE
        screenSearch.visibility = if (index == 1) View.VISIBLE else View.GONE
        screenTorrent.visibility = if (index == 2) View.VISIBLE else View.GONE
        screenIrc.visibility = if (index == 3) View.VISIBLE else View.GONE
        screenFilesVlc.visibility = if (index == 4) View.VISIBLE else View.GONE
        screenSettings.visibility = if (index == 5) View.VISIBLE else View.GONE

        navItems.forEachIndexed { idx, layout ->
            val icon = layout.getChildAt(0) as ImageView
            val label = layout.getChildAt(1) as TextView
            if (idx == index) {
                layout.setBackgroundColor(Color.parseColor("#252B38"))
                icon.setColorFilter(Color.parseColor("#38BDF8"))
                label.setTextColor(Color.parseColor("#38BDF8"))
                label.setTypeface(null, Typeface.BOLD)
            } else {
                layout.setBackgroundColor(Color.TRANSPARENT)
                icon.setColorFilter(Color.parseColor("#9CA3AF"))
                label.setTextColor(Color.parseColor("#9CA3AF"))
                label.setTypeface(null, Typeface.NORMAL)
            }
        }

        if (index == 4) {
            refreshDownloadedAndIncompleteFiles()
        }
    }

    private fun disconnectAllNetworks() {
        Ed2kKadManager.disconnectAll()
        disconnectIrcCleanly()
        torrentEngine.stopKeepFiles()
        vlcManager.stopAndDetach()
        stopService(Intent(this, P2PEngineService::class.java))

        updateConnectButtonLabel()
        btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))

        topStatusBanner.text = "UltimateMule  |  DISCONNESSO"
        serverListLog.text = "Tutte le reti sono state scollegate."
        Toast.makeText(this, "Scollegato da tutte le reti P2P.", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 1. SCHERMATA RETI: SCELTA SERVER+KAD / SOLO SERVER / SOLO KAD + AGGIUNTA SERVER/NODES
    // =========================================================================
    private fun buildNetworksScreen(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val savedKadUrls = prefs.getString(
            "custom_kad_urls",
            "http://upd.emule-security.org/nodes.dat\nhttp://www.nodes-dat.com/dl.php?load=nodes&trace=1"
        ) ?: ""
        val savedServerUrls = prefs.getString(
            "custom_server_urls",
            "http://upd.emule-security.org/server.met\nhttp://www.server-met.de/dl.php?load=gz&trace=1\nhttp://peerates.net/servers.php"
        ) ?: ""

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Modalità di Connessione eD2K / KAD"))
            addView(infoLabel("Scegli se collegarti e scaricare usando Entrambi, Solo Server eD2K o Solo Rete KAD:"))

            val modeSpinner = Spinner(this@MainActivity).apply {
                val modes = listOf(
                    "Modalità: SERVER eD2K + RETE KAD (Consigliato)",
                    "Modalità: SOLO SERVER eD2K",
                    "Modalità: SOLO RETE KAD (Serverless)"
                )
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        selectedEd2kConnectionMode = position
                        if (!Ed2kKadManager.isConnected) updateConnectButtonLabel()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(modeSpinner)

            val kadUrlsInput = styledEditText(savedKadUrls).apply { minLines = 2 }
            val serverUrlsInput = styledEditText(savedServerUrls).apply { minLines = 3 }

            btnGlobalConnectToggle = compactButton("CONNETTI (SERVER + KAD)", "#16A34A") {
                if (Ed2kKadManager.isConnected) {
                    Ed2kKadManager.disconnectAll()
                    updateConnectButtonLabel()
                    btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))
                    topStatusBanner.text = "UltimateMule  |  Disconnesso"
                    serverListLog.text = "Disconnesso."
                } else {
                    prefs.edit()
                        .putString("custom_kad_urls", kadUrlsInput.text.toString())
                        .putString("custom_server_urls", serverUrlsInput.text.toString())
                        .apply()

                    serverListLog.text = "Connessione in corso..."
                    btnGlobalConnectToggle.text = "CONNESSIONE IN CORSO..."
                    btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#D97706"))

                    val configDir = File(getBaseMulePath(), "Config")
                    val manager = Ed2kKadManager(configDir)
                    mainScope.launch {
                        var serversCount = 0
                        var totalUsers = 0
                        var nodesCount = 0

                        if (selectedEd2kConnectionMode == 0 || selectedEd2kConnectionMode == 1) {
                            val servers = manager.importMultipleServerMet(serverUrlsInput.text.toString().split("\n")) { log ->
                                runOnUiThread { appendServerLog(log) }
                            }
                            serversCount = servers.size
                            totalUsers = servers.sumOf { it.users }
                            servers.take(12).forEach { s ->
                                appendServerLog("• ${s.name} [${s.ip}:${s.port}] - Utenti: ${s.users}")
                            }
                        }

                        if (selectedEd2kConnectionMode == 0 || selectedEd2kConnectionMode == 2) {
                            nodesCount = manager.importMultipleKadNodesDat(kadUrlsInput.text.toString().split("\n")) { log ->
                                runOnUiThread { appendServerLog(log) }
                            }
                        }

                        btnGlobalConnectToggle.text = "DISCONNETTI RETE ATTIVA"
                        btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#DC2626"))

                        val summary = when (selectedEd2kConnectionMode) {
                            1 -> "Solo Server eD2K: $serversCount Server ($totalUsers ut.)"
                            2 -> "Solo Rete KAD: $nodesCount Nodi attivi"
                            else -> "eD2K: $serversCount Server ($totalUsers ut.) | KAD: $nodesCount nodi"
                        }
                        topStatusBanner.text = summary
                        P2PEngineService.updateNotificationStatus(this@MainActivity, summary)
                        appendServerLog("=== CONNESSO: $summary ===")
                    }
                }
            }
            addView(btnGlobalConnectToggle)

            serverListLog = TextView(this@MainActivity).apply {
                text = "Seleziona la modalità qui sopra e premi il tasto verde per connetterti."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(18, 16, 18, 16)
            }
            addView(serverListLog)

            // AGGIUNTA MANUALE SINGOLO SERVER (IP:PORTA)
            addView(sectionTitle("Aggiungi Nuovo Server eD2K Manuale (IP:Porta)"))
            val addSrvRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val customSrvIpPort = styledEditText("").apply {
                hint = "IP:Porta (es. 176.125.242.230:5555)"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val customSrvName = styledEditText("").apply {
                hint = "Nome Server"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val btnAddSrv = compactRowButton("+ SERVER", "#0284C7", 0f) {
                val raw = customSrvIpPort.text.toString().trim()
                val name = customSrvName.text.toString().trim().ifEmpty { "Server Personalizzato" }
                val parts = raw.split(":")
                val ip = parts.getOrNull(0).orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 4661
                if (ip.count { it == '.' } == 3) {
                    Ed2kKadManager.activeServers["$ip:$port"] = Ed2kKadManager.Ed2kServer(ip, port, name, "Aggiunto dall'utente", 50000, 1000000)
                    appendServerLog("Aggiunto server manuale: $name [$ip:$port]")
                    customSrvIpPort.setText("")
                    customSrvName.setText("")
                    Toast.makeText(this@MainActivity, "Server $name aggiunto!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Formato richiesto: IP:Porta", Toast.LENGTH_SHORT).show()
                }
            }
            addSrvRow.addView(customSrvIpPort)
            addSrvRow.addView(customSrvName)
            addSrvRow.addView(btnAddSrv)
            addView(addSrvRow)

            addView(sectionTitle("Liste URL server.met & nodes.dat (Puoi aggiungerne altri, uno per riga)"))
            addView(infoLabel("URL nodes.dat (Nodi KAD):"))
            addView(kadUrlsInput)
            addView(infoLabel("URL server.met (Liste Server eD2K):"))
            addView(serverUrlsInput)
        }
    }

    private fun updateConnectButtonLabel() {
        btnGlobalConnectToggle.text = when (selectedEd2kConnectionMode) {
            1 -> "CONNETTI SOLO SERVER eD2K"
            2 -> "CONNETTI SOLO RETE KAD"
            else -> "CONNETTI (SERVER + KAD)"
        }
    }

    private fun appendServerLog(msg: String) {
        val current = serverListLog.text.toString()
        val baseLines = if (current.startsWith("Seleziona la modalità")) emptyList() else current.split("\n")
        val lines = (baseLines + msg).takeLast(20)
        serverListLog.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 2. SCHERMATA CERCA: COLLEGATA A ED2K/KAD, TORRENT (CORSARO NERO/1337X) E CANALI IRC!
    //    NON CAMBIA PIÙ SCHERMATA DA SOLA QUANDO PREMI SCARICA!
    // =========================================================================
    private fun buildSearchScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Ricerca Multi-Rete (eD2K/KAD, IlCorsaroNero, 1337x, XDCC.eu & Canali IRC)"))
            addView(infoLabel("Cerca file su tutte le reti o solo su una specifica. Quando avvii un download resti in questa schermata:"))

            val searchInput = styledEditText("").apply {
                hint = "Es. titolo film ita, serie tv, mp3, archivio..."
            }
            addView(searchInput)

            val spinnersRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val netSpinner = Spinner(this@MainActivity).apply {
                val nets = listOf("Tutte le Reti (Globale)", "Solo eD2K / KAD", "Solo BitTorrent (Corsaro/1337x)", "Solo IRC XDCC (Canali + Bot)")
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, nets)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 10)
                }
            }

            val catSpinner = Spinner(this@MainActivity).apply {
                val cats = listOf("Tutti i Formati", "Video (MKV/MP4/AVI)", "Audio (MP3/FLAC)", "Archivi (RAR/ZIP/ISO)", "Programmi/Doc (APK/PDF)")
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, cats)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 0, 10)
                }
            }

            spinnersRow.addView(netSpinner)
            spinnersRow.addView(catSpinner)
            addView(spinnersRow)

            btnDynamicSearch = compactButton("RICERCA GLOBALE", "#0284C7") {
                val query = searchInput.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Scrivi cosa vuoi cercare", Toast.LENGTH_SHORT).show()
                    return@compactButton
                }
                val netFilter = when (netSpinner.selectedItemPosition) {
                    1 -> GlobalSearchEngine.NetworkType.ED2K_KAD
                    2 -> GlobalSearchEngine.NetworkType.TORRENT
                    3 -> GlobalSearchEngine.NetworkType.IRC_XDCC
                    else -> GlobalSearchEngine.NetworkType.ALL
                }
                val catFilter = when (catSpinner.selectedItemPosition) {
                    1 -> GlobalSearchEngine.FileCategory.VIDEO
                    2 -> GlobalSearchEngine.FileCategory.AUDIO
                    3 -> GlobalSearchEngine.FileCategory.ARCHIVE
                    4 -> GlobalSearchEngine.FileCategory.SOFTWARE_DOC
                    else -> GlobalSearchEngine.FileCategory.ALL
                }
                performGlobalSearch(query, netFilter, catFilter)
            }
            addView(btnDynamicSearch)

            netSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    btnDynamicSearch.text = when (position) {
                        1 -> "CERCA SU ED2K / KAD"
                        2 -> "CERCA SU BITTORRENT"
                        3 -> "CERCA SU IRC XDCC"
                        else -> "RICERCA GLOBALE"
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            searchResultsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 0)
            }
            addView(searchResultsContainer)
        }
    }

    private fun performGlobalSearch(
        query: String,
        netFilter: GlobalSearchEngine.NetworkType,
        catFilter: GlobalSearchEngine.FileCategory
    ) {
        searchResultsContainer.removeAllViews()
        searchResultsContainer.addView(infoLabel("Ricerca in corso per \"$query\"..."))

        // Invia anche @find ai canali IRC attivi
        if (netFilter == GlobalSearchEngine.NetworkType.ALL || netFilter == GlobalSearchEngine.NetworkType.IRC_XDCC) {
            ircEngine?.sendSearchCommandToActiveChannels(query)
        }

        mainScope.launch {
            val results = searchEngine.search(query, netFilter, catFilter)
            searchResultsContainer.removeAllViews()

            if (results.isEmpty()) {
                searchResultsContainer.addView(infoLabel("Nessun file trovato per \"$query\"."))
                return@launch
            }

            searchResultsContainer.addView(infoLabel("Trovati ${results.size} file disponibili:"))

            results.forEach { res ->
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1C212B"))
                    setPadding(20, 16, 20, 16)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, 14)
                    layoutParams = lp
                }

                val titleView = TextView(this@MainActivity).apply {
                    text = "[${res.fileExtension}]  ${res.title}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }

                val detailsView = TextView(this@MainActivity).apply {
                    text = "Dim: ${res.sizeText}  |  Fonti: ${res.sources}  |  Origine: ${res.providerName}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, 6, 0, 10)
                }

                val btnRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                btnRow.addView(compactRowButton("ISPEZIONA", "#374151", 1f) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ispezione [${res.fileExtension}]")
                        .setMessage(res.inspectionSummary)
                        .setPositiveButton("SCARICA") { _, _ -> startDownloadStayOnScreen(res) }
                        .setNegativeButton("Chiudi", null)
                        .show()
                })

                btnRow.addView(compactRowButton("SCARICA", "#16A34A", 1f) {
                    startDownloadStayOnScreen(res)
                })

                card.addView(titleView)
                card.addView(detailsView)
                card.addView(btnRow)
                searchResultsContainer.addView(card)
            }
        }
    }

    /**
     * Avvia il download SENZA cambiare schermata! L'utente resta su Cerca e decide lui quando andare su File & VLC.
     */
    private fun startDownloadStayOnScreen(res: GlobalSearchEngine.SearchResult) {
        when (res.network) {
            GlobalSearchEngine.NetworkType.IRC_XDCC -> {
                startIrcAutomatedDownloadInBackground(res.ircServer, res.ircChannel, res.ircBot, res.ircPack)
                Toast.makeText(
                    this,
                    "Richiesto pacchetto #${res.ircPack} a ${res.ircBot}. Vai su mIRC o File & VLC per seguirlo.",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                activeDownloadBoxInFiles.visibility = View.VISIBLE
                torrentEngine.startStream(res.actionPayload)
                Toast.makeText(
                    this,
                    "Download avviato: ${res.title}. Apri 'File & VLC' quando vuoi vedere l'avanzamento.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =========================================================================
    // 3. SCHERMATA TORRENT
    // =========================================================================
    private fun buildTorrentScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Aggiungi Link Magnet / URL .torrent / Hash"))
            val directInput = styledEditText("").apply {
                hint = "magnet:?xt=urn:btih:... oppure URL .torrent"
                minLines = 2
            }
            addView(directInput)

            addView(compactButton("AVVIA SCARICAMENTO TORRENT", "#0284C7") {
                val input = directInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    activeDownloadBoxInFiles.visibility = View.VISIBLE
                    torrentEngine.startStream(input)
                    Toast.makeText(this@MainActivity, "Scaricamento avviato!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Inserisci un link Magnet o Torrent", Toast.LENGTH_SHORT).show()
                }
            })

            addView(sectionTitle("Stato Motore BitTorrent"))
            torrentStatusText = infoLabel("Stato: Inattivo")
            torrentProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(torrentStatusText)
            addView(torrentProgressBar)

            val ctrlRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ctrlRow.addView(compactRowButton("FERMA (MANTIENI)", "#374151", 1f) {
                torrentEngine.stopKeepFiles()
            })
            ctrlRow.addView(compactRowButton("ELIMINA CACHE", "#991B1B", 1f) {
                torrentEngine.stopAndClearCache()
            })
            addView(ctrlRow)
        }
    }

    // =========================================================================
    // 4. SCHERMATA mIRC (SENZA BARRA DI RICERCA, CON SCUDO IP +ix E SERVER FUNZIONANTI)
    // =========================================================================
    private fun buildIrcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Client mIRC Protetto (IP Cloaking +ix & Anti-Leak CTCP Attivi)"))

            // Server IRC verificati e funzionanti per XDCC
            val verifiedServers = listOf(
                "Scegli Server IRC verificato...",
                "irc.rizon.net",
                "irc.abjects.net",
                "irc.libera.chat",
                "irc.criten.net",
                "irc.scenep2p.net",
                "irc.dal.net",
                "irc.efnet.org",
                "irc.quakenet.org"
            )

            val serverSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, verifiedServers)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position > 0) ircServerInput.setText(verifiedServers[position])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(serverSpinner)

            ircServerInput = styledEditText("irc.rizon.net").apply {
                hint = "Indirizzo Server IRC"
            }
            addView(ircServerInput)

            val connRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnIrcConnectToggle = compactRowButton("CONNETTI", "#16A34A", 1f) {
                if (isIrcConnectedState || ircEngine != null) {
                    disconnectIrcCleanly()
                } else {
                    connectToIrcServer(ircServerInput.text.toString().trim(), "")
                }
            }

            connRow.addView(btnIrcConnectToggle)
            connRow.addView(compactRowButton("SCANSIONA (/LIST)", "#2563EB", 1.1f) {
                if (ircEngine == null) {
                    Toast.makeText(this@MainActivity, "Premi prima CONNETTI", Toast.LENGTH_SHORT).show()
                } else {
                    scannedChannels.clear()
                    ircEngine?.requestChannelList()
                }
            })
            connRow.addView(compactRowButton("CANALI", "#0284C7", 0.9f) {
                showScannedChannelsDialog()
            })
            addView(connRow)

            ircActiveChannelsBanner = TextView(this@MainActivity).apply {
                text = "Stato IRC: Offline | Protezione IP +ix Pronta"
                textSize = 11f
                setTextColor(Color.parseColor("#FBBF24"))
                setBackgroundColor(Color.parseColor("#181C24"))
                setPadding(16, 12, 16, 12)
                setTypeface(null, Typeface.BOLD)
            }
            addView(ircActiveChannelsBanner)

            ircLogText = TextView(this@MainActivity).apply {
                text = "Console mIRC pronta. Lo scudo IP (+ix) viene attivato automaticamente alla connessione."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(16, 14, 16, 14)
                minLines = 7
            }
            addView(ircLogText)

            addView(sectionTitle("Barra Comandi mIRC"))
            val cmdRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            ircCommandInput = EditText(this@MainActivity).apply {
                hint = "/list, /join #canale, /msg Bot xdcc send #1"
                textSize = 12f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#6B7280"))
                setBackgroundColor(Color.parseColor("#1E2430"))
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }

            val btnSendCmd = compactRowButton("INVIA", "#16A34A", 0f) {
                val cmd = ircCommandInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    if (cmd.equals("/list", ignoreCase = true)) scannedChannels.clear()
                    ircEngine?.executeMircCommand(cmd)
                    ircCommandInput.setText("")
                }
            }

            cmdRow.addView(ircCommandInput)
            cmdRow.addView(btnSendCmd)
            addView(cmdRow)

            val quickCmdsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            quickCmdsRow.addView(compactRowButton("/list", "#374151", 1f) {
                scannedChannels.clear()
                ircEngine?.executeMircCommand("/list")
            })
            quickCmdsRow.addView(compactRowButton("!list", "#374151", 1f) {
                ircEngine?.executeMircCommand("!list")
            })
            quickCmdsRow.addView(compactRowButton("/names", "#374151", 1f) {
                ircEngine?.executeMircCommand("/names")
            })
            quickCmdsRow.addView(compactRowButton("/part", "#374151", 1f) {
                ircEngine?.executeMircCommand("/part")
            })
            addView(quickCmdsRow)

            ircProgressLabel = infoLabel("Nessun download XDCC in corso")
            ircProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            addView(sectionTitle("File Presenti nel Canale (Cercabili anche dalla scheda 'Cerca')"))
            ircChannelFilesContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 0)
            }
            addView(ircChannelFilesContainer)
        }
    }

    private fun setIrcButtonState(connected: Boolean) {
        isIrcConnectedState = connected
        if (connected) {
            btnIrcConnectToggle.text = "DISCONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            btnIrcConnectToggle.text = "CONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))
        }
    }

    private fun disconnectIrcCleanly() {
        ircEngine?.disconnect()
        ircEngine = null
        scannedChannels.clear()
        setIrcButtonState(false)
        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso"
        appendIrcLog("Disconnesso dal server IRC.")
    }

    private fun connectToIrcServer(server: String, channel: String, onConnectedAction: (() -> Unit)? = null) {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val base = File(getBaseMulePath())
        val incomingDir = File(base, "Incoming").apply { mkdirs() }
        val tempDir = File(base, "Temp").apply { mkdirs() }

        val customNick = prefs.getString("irc_nick", "")?.takeIf { it.isNotBlank() }
            ?: ("Mule" + (1000..9999).random())
        val useSsl = prefs.getBoolean("irc_ssl", false)
        val port = if (useSsl) 6697 else (prefs.getString("irc_port", "6667")?.toIntOrNull() ?: 6667)
        val ipCloak = prefs.getBoolean("irc_ip_cloak", true)
        val blockCtcp = prefs.getBoolean("irc_block_ctcp", true)
        val proxyHost = prefs.getString("irc_proxy_host", "") ?: ""
        val proxyPort = prefs.getString("irc_proxy_port", "0")?.toIntOrNull() ?: 0

        ircEngine?.disconnect()
        setIrcButtonState(true)

        val engine = IrcXdccEngine(
            server = server,
            port = port,
            nickname = customNick,
            incomingDir = incomingDir,
            tempDir = tempDir,
            scope = mainScope,
            useSsl = useSsl,
            ipCloakMode = ipCloak,
            blockCtcpInfo = blockCtcp,
            socksProxyHost = proxyHost,
            socksProxyPort = proxyPort,
            autoJoinChannel = channel.takeIf { it.isNotBlank() }
        )
        ircEngine = engine

        mainScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is IrcXdccEngine.Event.Log -> appendIrcLog(event.text)
                    is IrcXdccEngine.Event.Connected -> {
                        setIrcButtonState(true)
                        val shield = if (event.cloaked) "IP Protetto (+ix)" else "Connesso"
                        topStatusBanner.text = "UltimateMule  |  IRC: ${event.server} ($shield)"
                        ircActiveChannelsBanner.text = "Connesso a ${event.server} [$shield] | Premi SCANSIONA (/LIST)"
                        onConnectedAction?.invoke()
                    }
                    is IrcXdccEngine.Event.ChannelJoined -> {
                        ircActiveChannelsBanner.text = "Canale: ${event.channel} (IP Protetto +ix)"
                        appendIrcLog("Entrato in ${event.channel}. Ricezione elenco pacchetti del canale...")
                    }
                    is IrcXdccEngine.Event.ChannelMessage ->
                        appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                    is IrcXdccEngine.Event.ChannelListStart -> {
                        scannedChannels.clear()
                        appendIrcLog("Scansione /list avviata...")
                    }
                    is IrcXdccEngine.Event.ChannelListItem -> {
                        if (scannedChannels.size < 600) scannedChannels.add(event.info)
                    }
                    is IrcXdccEngine.Event.ChannelListEnd -> {
                        scannedChannels.sortByDescending { it.usersCount }
                        showScannedChannelsDialog()
                    }
                    is IrcXdccEngine.Event.ChannelPacksDiscovered -> {
                        event.packs.forEach { pack ->
                            discoveredChannelPacks["${pack.botNick}#${pack.packNumber}"] = pack
                        }
                        refreshIrcChannelFilesUI()
                    }
                    is IrcXdccEngine.Event.DccOfferReceived ->
                        appendIrcLog("DCC SEND: ${event.offer.filename} (${event.offer.size / 1024} KB)")
                    is IrcXdccEngine.Event.DownloadProgress -> {
                        val pct = if (event.total > 0) ((event.received * 100) / event.total).toInt() else 0
                        ircProgressBar.progress = pct
                        val speedKb = event.speedBps / 1024
                        ircProgressLabel.text = "XDCC: ${event.filename} - $pct% ($speedKb KB/s)"
                    }
                    is IrcXdccEngine.Event.DownloadComplete -> {
                        ircProgressBar.progress = 100
                        ircProgressLabel.text = "Completato: ${event.file.name}"
                    }
                    is IrcXdccEngine.Event.DownloadFailed ->
                        appendIrcLog("Errore DCC: ${event.reason}")
                    is IrcXdccEngine.Event.Disconnected -> {
                        setIrcButtonState(false)
                        ircEngine = null
                        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso"
                    }
                }
            }
        }
        engine.connect()
    }

    private fun showScannedChannelsDialog() {
        if (scannedChannels.isEmpty()) {
            Toast.makeText(this, "Nessun canale in lista. Premi SCANSIONA (/LIST).", Toast.LENGTH_SHORT).show()
            return
        }

        val displayList = scannedChannels.take(250)
        val labels = displayList.map { "${it.channel} (${it.usersCount} ut.) - ${it.topic.take(40)}" }.toTypedArray()
        val checkedItems = BooleanArray(labels.size) { false }

        AlertDialog.Builder(this)
            .setTitle("Seleziona Canali (${displayList.size})")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("CONFERMA ED ENTRA") { _, _ ->
                val selected = mutableListOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) selected.add(displayList[i].channel)
                }
                if (selected.isNotEmpty()) {
                    discoveredChannelPacks.clear()
                    ircEngine?.joinMultipleChannels(selected)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun refreshIrcChannelFilesUI() {
        ircChannelFilesContainer.removeAllViews()
        val packs = discoveredChannelPacks.values.toList()

        if (packs.isEmpty()) {
            ircChannelFilesContainer.addView(infoLabel("In attesa di annunci dai Bot del canale... (Puoi cercare qualsiasi file nella scheda 'Cerca'!)"))
            return
        }

        packs.take(35).forEach { pack ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1C212B"))
                setPadding(18, 14, 18, 14)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 10)
                layoutParams = lp
            }

            card.addView(TextView(this).apply {
                text = "[${pack.fileExtension}]  ${pack.filename}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })

            card.addView(TextView(this).apply {
                text = "Bot: ${pack.botNick}  |  Pack: #${pack.packNumber}  |  Dim: ${pack.sizeText}"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 4, 0, 8)
            })

            card.addView(compactButton("SCARICA (${pack.botNick} #${pack.packNumber})", "#16A34A") {
                ircEngine?.requestPack(pack.botNick, pack.packNumber)
                Toast.makeText(this@MainActivity, "Richiesto #${pack.packNumber} a ${pack.botNick}", Toast.LENGTH_SHORT).show()
            })

            ircChannelFilesContainer.addView(card)
        }
    }

    private fun startIrcAutomatedDownloadInBackground(server: String, channel: String, bot: String, pack: Int) {
        val activeSrv = ircEngine?.server.orEmpty()
        if (ircEngine != null && activeSrv.equals(server, ignoreCase = true)) {
            if (ircEngine?.joinedChannels?.contains(channel) != true) {
                ircEngine?.joinChannel(channel)
            }
            ircEngine?.requestPack(bot, pack)
        } else {
            ircServerInput.setText(server)
            connectToIrcServer(server, channel) {
                mainScope.launch {
                    kotlinx.coroutines.delay(2500L)
                    ircEngine?.requestPack(bot, pack)
                }
            }
        }
    }

    private fun appendIrcLog(msg: String) {
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(14)
        ircLogText.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 5. SCHERMATA FILE & VLC (ACCANTO A OPZIONI!)
    // =========================================================================
    private fun buildFilesVlcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            activeDownloadBoxInFiles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#182232"))
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 16)
                layoutParams = lp
            }

            activeDownloadBoxInFiles.addView(TextView(this@MainActivity).apply {
                text = "TRASFERIMENTO IN CORSO"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            })

            activeDownloadTitleInFiles = infoLabel("Nessun download attivo.")
            activeDownloadProgressInFiles = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }

            activeDownloadPreviewHolderInFiles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val dlCtrlRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            dlCtrlRow.addView(compactRowButton("PAUSA / MANTIENI", "#374151", 1f) {
                torrentEngine.stopKeepFiles()
                refreshDownloadedAndIncompleteFiles()
            })
            dlCtrlRow.addView(compactRowButton("ANNULLA ED ELIMINA", "#991B1B", 1f) {
                torrentEngine.stopAndClearCache()
                activeDownloadPreviewHolderInFiles.removeAllViews()
                refreshDownloadedAndIncompleteFiles()
            })

            activeDownloadBoxInFiles.addView(activeDownloadTitleInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadProgressInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadPreviewHolderInFiles)
            activeDownloadBoxInFiles.addView(dlCtrlRow)
            addView(activeDownloadBoxInFiles)

            addView(sectionTitle("Archivio File Scaricati (Incoming) e Incompleti (Temp)"))
            addView(compactButton("AGGIORNA ELENCO FILE", "#2563EB") {
                refreshDownloadedAndIncompleteFiles()
            })

            filesListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 0)
            }
            addView(filesListContainer)
        }
    }

    private fun observeTorrentEngineState() {
        mainScope.launch {
            torrentEngine.state.collect { state ->
                when (state) {
                    is TorrentStreamState.Idle -> {
                        activeDownloadTitleInFiles.text = "Nessun trasferimento attivo."
                        activeDownloadProgressInFiles.progress = 0
                        torrentStatusText.text = "Stato: Inattivo"
                        torrentProgressBar.progress = 0
                    }
                    is TorrentStreamState.Buffering -> {
                        val info = "${state.torrentTitle}\n${state.statusText} | ${state.downloadSpeedKbps} KB/s | Fonti: ${state.peers}"
                        activeDownloadProgressInFiles.progress = state.progressPercent
                        activeDownloadTitleInFiles.text = info
                        torrentProgressBar.progress = state.progressPercent
                        torrentStatusText.text = info
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Download: ${state.progressPercent}% (${state.downloadSpeedKbps} KB/s)"
                        )
                    }
                    is TorrentStreamState.Ready -> {
                        val readyMsg = "Pronto per l'anteprima: ${state.videoFile.name}"
                        activeDownloadProgressInFiles.progress = 100
                        activeDownloadTitleInFiles.text = readyMsg
                        torrentProgressBar.progress = 100
                        torrentStatusText.text = readyMsg

                        activeDownloadPreviewHolderInFiles.removeAllViews()
                        activeDownloadPreviewHolderInFiles.addView(
                            compactButton("APRI ANTEPRIMA LIBVLC", "#16A34A") {
                                openFullscreenVlcDialog(state.videoFile)
                            }
                        )
                        refreshDownloadedAndIncompleteFiles()
                    }
                    is TorrentStreamState.Error -> {
                        activeDownloadTitleInFiles.text = state.message
                        torrentStatusText.text = state.message
                    }
                }
            }
        }
    }

    private fun refreshDownloadedAndIncompleteFiles() {
        filesListContainer.removeAllViews()
        val base = File(getBaseMulePath())
        val tempFiles = File(base, "Temp").walkTopDown().filter { it.isFile }.toList()
        val incomingFiles = File(base, "Incoming").walkTopDown().filter { it.isFile }.toList()
        val allFiles = (tempFiles + incomingFiles).sortedByDescending { it.lastModified() }

        if (allFiles.isEmpty()) {
            filesListContainer.addView(infoLabel("Nessun file presente in Temp o Incoming."))
            return
        }

        allFiles.forEach { file ->
            val isPart = file.parentFile?.name == "Temp" || file.name.endsWith(".part")
            val statusBadge = if (isPart) "TEMP (.PART)" else "INCOMING"
            val ext = file.extension.uppercase().ifBlank { "FILE" }

            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1C212B"))
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 12)
                layoutParams = lp
            }

            rowCard.addView(TextView(this).apply {
                text = "[$ext]  ${file.name}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })

            rowCard.addView(TextView(this).apply {
                text = "$statusBadge  |  ${file.length() / 1024} KB"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 4, 0, 8)
            })

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(compactRowButton("ANTEPRIMA VLC", "#0284C7", 1f) { openFullscreenVlcDialog(file) })
            btnRow.addView(compactRowButton("APRI", "#16A34A", 1f) { openFileDirectly(file) })
            btnRow.addView(compactRowButton("ELIMINA", "#991B1B", 1f) { confirmAndDeleteFile(file) })

            rowCard.addView(btnRow)
            filesListContainer.addView(rowCard)
        }
    }

    private fun confirmAndDeleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Elimina File")
            .setMessage("Eliminare definitivamente:\n${file.name}?")
            .setPositiveButton("ELIMINA") { _, _ ->
                vlcManager.stopAndDetach()
                if (file.delete()) Toast.makeText(this, "Eliminato", Toast.LENGTH_SHORT).show()
                refreshDownloadedAndIncompleteFiles()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openFullscreenVlcDialog(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "File ancora a 0 byte, attendi qualche secondo.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181C24"))
            setPadding(20, 12, 20, 12)
        }

        topBar.addView(TextView(this).apply {
            text = file.name
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        topBar.addView(compactRowButton("CHIUDI", "#991B1B", 0f) {
            vlcManager.stopAndDetach()
            dialog.dismiss()
        })
        root.addView(topBar)

        val videoContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val vlcLayout = LayoutInflater.from(this)
            .inflate(R.layout.vlc_player_container, videoContainer, false) as VLCVideoLayout
        videoContainer.addView(vlcLayout)
        root.addView(videoContainer)

        val audioStatus = TextView(this).apply {
            text = "Riproduzione LibVLC: ${file.name}"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(20, 10, 20, 10)
        }
        root.addView(audioStatus)

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
            setPadding(10, 8, 10, 8)
        }

        bottomControls.addView(compactRowButton("PLAY/PAUSA", "#2563EB", 1f) { vlcManager.togglePlayPause() })
        bottomControls.addView(compactRowButton("-15s", "#374151", 1f) { vlcManager.seekBy(-15_000L) })
        bottomControls.addView(compactRowButton("+15s", "#374151", 1f) { vlcManager.seekBy(15_000L) })
        bottomControls.addView(compactRowButton("16:9", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "16:9") })
        bottomControls.addView(compactRowButton("FILL", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "FILL") })

        root.addView(bottomControls)

        dialog.setContentView(root)
        dialog.setOnDismissListener { vlcManager.stopAndDetach() }

        dialog.setOnShowListener {
            vlcLayout.postDelayed({
                vlcManager.attachAndPrepare(
                    layout = vlcLayout,
                    streamUrl = file.absolutePath,
                    onAudioTracksUpdated = { _, _, info ->
                        runOnUiThread { audioStatus.text = "Traccia Audio: $info" }
                    },
                    onError = {
                        runOnUiThread { audioStatus.text = "Formato non video o header ancora incompleto." }
                    }
                )
            }, 200L)
        }

        dialog.show()
    }

    private fun openFileDirectly(file: File) {
        val ext = file.extension.lowercase()
        val videoExts = setOf("mp4", "mkv", "avi", "ts", "webm", "mov", "m4v", "flv", "mpg", "mpeg", "part")
        if (ext in videoExts) {
            openFullscreenVlcDialog(file)
            return
        }

        try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Apri ${file.name}"))
        } catch (_: Exception) {
            Toast.makeText(this, "Nessuna app trovata per aprire .$ext", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 6. SCHERMATA OPZIONI COMPLETA: EMULE + UTORRENT + mIRC (CON OFFUSCAMENTO IP)
    // =========================================================================
    private fun buildSettingsScreen(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // --- CARTELLA DI SALVATAGGIO ---
            addView(sectionTitle("Cartella Principale di Salvataggio (Incoming / Temp / Config)"))
            val pathRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val folderInput = styledEditText(savedPath).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            pathRow.addView(folderInput)
            pathRow.addView(compactRowButton("APPLICA", "#16A34A", 0f) {
                val chosen = folderInput.text.toString().trim()
                prefs.edit().putString("custom_download_path", chosen).apply()
                verifyAndCreateFolders(chosen)
            })
            addView(pathRow)

            // =================================================================
            // SEZIONE 1: CONFIGURAZIONE COMPLETA eMule (eD2K / KAD)
            // =================================================================
            addView(sectionTitle("1. Impostazioni Complete eMule (eD2K & KAD)"))
            val emuleTcp = styledEditText(prefs.getString("emule_tcp", "4662") ?: "4662")
            val emuleUdp = styledEditText(prefs.getString("emule_udp", "4672") ?: "4672")
            val emuleMaxSources = styledEditText(prefs.getString("emule_max_sources", "1500") ?: "1500")
            val emuleMaxConn = styledEditText(prefs.getString("emule_max_conn", "500") ?: "500")
            val emuleDlLimit = styledEditText(prefs.getString("emule_dl_limit", "0") ?: "0")
            val emuleUlLimit = styledEditText(prefs.getString("emule_ul_limit", "0") ?: "0")

            addView(twoColumnFields("Porta TCP Client:", emuleTcp, "Porta UDP KAD:", emuleUdp))
            addView(twoColumnFields("Fonti Max per File:", emuleMaxSources, "Connessioni Max:", emuleMaxConn))
            addView(twoColumnFields("Limite Download (KB/s, 0=Inf):", emuleDlLimit, "Limite Upload (KB/s, 0=Inf):", emuleUlLimit))

            val chkEmuleObf = CheckBox(this@MainActivity).apply {
                text = "Offuscamento Protocollo Criptato (Anti-blocco ISP)"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmuleGzip = CheckBox(this@MainActivity).apply {
                text = "Compressione Pacchetti GZIP / Zlib Nativa"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmulePreview = CheckBox(this@MainActivity).apply {
                text = "Scarica prima il primo e l'ultimo chunk per anteprima LibVLC"
                isChecked = prefs.getBoolean("emule_preview_chunks", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmuleDeadSrv = CheckBox(this@MainActivity).apply {
                text = "Rimuovi automaticamente i Server inattivi (0 utenti)"
                isChecked = prefs.getBoolean("emule_remove_dead", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(chkEmuleObf)
            addView(chkEmuleGzip)
            addView(chkEmulePreview)
            addView(chkEmuleDeadSrv)

            // =================================================================
            // SEZIONE 2: CONFIGURAZIONE COMPLETA uTorrent (BitTorrent / DHT)
            // =================================================================
            addView(sectionTitle("2. Impostazioni Complete uTorrent (BitTorrent / DHT)"))
            val utMaxConn = styledEditText(prefs.getString("ut_max_conn", "300") ?: "300")
            val utMaxActive = styledEditText(prefs.getString("ut_max_active", "6") ?: "6")
            val utDlLimit = styledEditText(prefs.getString("ut_dl_limit", "0") ?: "0")
            val utUlLimit = styledEditText(prefs.getString("ut_ul_limit", "0") ?: "0")

            addView(twoColumnFields("Peer Max Globali:", utMaxConn, "Download Attivi Simultanei:", utMaxActive))
            addView(twoColumnFields("Limite Download (KB/s):", utDlLimit, "Limite Upload (KB/s):", utUlLimit))

            val chkUtDht = CheckBox(this@MainActivity).apply {
                text = "Abilita Rete DHT Decentralizzata & Peer Exchange (PEX)"
                isChecked = prefs.getBoolean("ut_dht", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkUtSequential = CheckBox(this@MainActivity).apply {
                text = "Download Sequenziale + Boost Atomi MP4/MKV per VLC"
                isChecked = prefs.getBoolean("ut_seq", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(chkUtDht)
            addView(chkUtSequential)

            // =================================================================
            // SEZIONE 3: CONFIGURAZIONE COMPLETA mIRC & PROTEZIONE OFFUSCAMENTO IP
            // =================================================================
            addView(sectionTitle("3. Impostazioni Complete mIRC & Scudo Offuscamento IP"))
            val ircNick = styledEditText(prefs.getString("irc_nick", "MuleAnon") ?: "MuleAnon")
            val ircPort = styledEditText(prefs.getString("irc_port", "6667") ?: "6667")
            val ircProxyHost = styledEditText(prefs.getString("irc_proxy_host", "") ?: "").apply {
                hint = "Opzionale: IP Proxy SOCKS5 / Tor (es. 127.0.0.1)"
            }
            val ircProxyPort = styledEditText(prefs.getString("irc_proxy_port", "") ?: "").apply {
                hint = "Porta Proxy (es. 9050)"
            }

            addView(twoColumnFields("Nickname IRC:", ircNick, "Porta IRC (6667 / 6697):", ircPort))
            addView(twoColumnFields("Proxy SOCKS5/Tor IP:", ircProxyHost, "Porta SOCKS5/Tor:", ircProxyPort))

            val chkIrcCloak = CheckBox(this@MainActivity).apply {
                text = "Offuscamento IP Automatico nelle Chat (MODE +ix Host Cloaking)"
                isChecked = prefs.getBoolean("irc_ip_cloak", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkIrcBlockCtcp = CheckBox(this@MainActivity).apply {
                text = "Blocca Tracciamento Posizione/Sistema (Anti-CTCP Leak)"
                isChecked = prefs.getBoolean("irc_block_ctcp", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkIrcSsl = CheckBox(this@MainActivity).apply {
                text = "Usa Connessione Criptata SSL/TLS (Porta 6697)"
                isChecked = prefs.getBoolean("irc_ssl", false)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(chkIrcCloak)
            addView(chkIrcBlockCtcp)
            addView(chkIrcSsl)

            addView(compactButton("SALVA TUTTE LE CONFIGURAZIONI (eMule / uTorrent / mIRC)", "#0284C7") {
                prefs.edit()
                    .putString("emule_tcp", emuleTcp.text.toString().trim())
                    .putString("emule_udp", emuleUdp.text.toString().trim())
                    .putString("emule_max_sources", emuleMaxSources.text.toString().trim())
                    .putString("emule_max_conn", emuleMaxConn.text.toString().trim())
                    .putString("emule_dl_limit", emuleDlLimit.text.toString().trim())
                    .putString("emule_ul_limit", emuleUlLimit.text.toString().trim())
                    .putBoolean("emule_obfuscation", chkEmuleObf.isChecked)
                    .putBoolean("emule_gzip", chkEmuleGzip.isChecked)
                    .putBoolean("emule_preview_chunks", chkEmulePreview.isChecked)
                    .putBoolean("emule_remove_dead", chkEmuleDeadSrv.isChecked)
                    .putString("ut_max_conn", utMaxConn.text.toString().trim())
                    .putString("ut_max_active", utMaxActive.text.toString().trim())
                    .putString("ut_dl_limit", utDlLimit.text.toString().trim())
                    .putString("ut_ul_limit", utUlLimit.text.toString().trim())
                    .putBoolean("ut_dht", chkUtDht.isChecked)
                    .putBoolean("ut_seq", chkUtSequential.isChecked)
                    .putString("irc_nick", ircNick.text.toString().trim())
                    .putString("irc_port", ircPort.text.toString().trim())
                    .putString("irc_proxy_host", ircProxyHost.text.toString().trim())
                    .putString("irc_proxy_port", ircProxyPort.text.toString().trim())
                    .putBoolean("irc_ip_cloak", chkIrcCloak.isChecked)
                    .putBoolean("irc_block_ctcp", chkIrcBlockCtcp.isChecked)
                    .putBoolean("irc_ssl", chkIrcSsl.isChecked)
                    .apply()
                Toast.makeText(this@MainActivity, "Tutte le impostazioni sono state salvate!", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun twoColumnFields(label1: String, input1: EditText, label2: String, input2: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val col1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 0)
                }
                addView(infoLabel(label1))
                addView(input1)
            }
            val col2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 0, 0)
                }
                addView(infoLabel(label2))
                addView(input2)
            }
            addView(col1)
            addView(col2)
        }
    }

    // =========================================================================
    // COMPONENTI UI COMPATTI E PROFESSIONALI
    // =========================================================================
    private fun styledEditText(initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(18, 12, 18, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 4, 0, 8)
            layoutParams = lp
        }
    }

    private fun compactButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(16, 12, 16, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 6, 0, 6)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun compactRowButton(label: String, hexColor: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(14, 12, 14, 12)
            val lp = if (weight > 0f) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lp.setMargins(4, 4, 4, 4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        }
    }

    private fun infoLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 2, 0, 4)
        }
    }

    private fun getBaseMulePath(): String {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val def = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        return prefs.getString("custom_download_path", def) ?: def
    }

    private fun verifyAndCreateFolders(basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            File(baseDir, "Incoming").mkdirs()
            File(baseDir, "Temp").mkdirs()
            File(baseDir, "Config").mkdirs()
            Toast.makeText(this, "Cartelle create in: ${baseDir.absolutePath}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllFilesAccess() {
        if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (_: Exception) {
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

    override fun onDestroy() {
        super.onDestroy()
        vlcManager.release()
    }
}

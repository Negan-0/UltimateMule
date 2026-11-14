package com.ultimatemule.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.ed2k.Ed2kTransferState
import com.ultimatemule.app.irc.IrcXdccEngine
import com.ultimatemule.app.player.VlcPlayerManager
import com.ultimatemule.app.search.GlobalSearchEngine
import com.ultimatemule.app.service.P2PEngineService
import com.ultimatemule.app.streaming.TorrentStreamEngine
import com.ultimatemule.app.streaming.TorrentStreamState
import com.ultimatemule.app.ui.OnboardingWizard
import com.ultimatemule.app.ui.SettingsScreenBuilder
import com.ultimatemule.app.ui.UiComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Le 6 Sezioni: 0: Reti | 1: Cerca | 2: Torrent | 3: mIRC | 4: File & VLC | 5: Opzioni
    private lateinit var screenNetworks: LinearLayout
    private lateinit var screenSearch: LinearLayout
    private lateinit var screenTorrent: LinearLayout
    private lateinit var screenIrc: LinearLayout
    private lateinit var screenFilesVlc: LinearLayout
    private lateinit var screenSettings: LinearLayout

    private val navItems = mutableListOf<LinearLayout>()

    // Riferimenti UI Reti (eD2K / KAD)
    private lateinit var topStatusBanner: TextView
    private lateinit var btnGlobalConnectToggle: Button
    private lateinit var serverListLog: TextView
    private var selectedEd2kConnectionMode = 0

    // Riferimenti UI Cerca
    private lateinit var btnDynamicSearch: Button
    private lateinit var searchResultsContainer: LinearLayout

    // Riferimenti UI File & VLC (Mostra separatamente eD2K e Torrent)
    private lateinit var activeEd2kBoxInFiles: LinearLayout
    private lateinit var activeEd2kTitleInFiles: TextView
    private lateinit var activeEd2kProgressInFiles: ProgressBar
    private lateinit var activeEd2kPreviewHolder: LinearLayout

    private lateinit var activeDownloadBoxInFiles: LinearLayout
    private lateinit var activeDownloadTitleInFiles: TextView
    private lateinit var activeDownloadProgressInFiles: ProgressBar
    private lateinit var activeDownloadPreviewHolderInFiles: LinearLayout
    private lateinit var filesListContainer: LinearLayout

    // Riferimenti UI Torrent
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

    // I 3 Motori P2P Separati + Media Player + Wizard
    private lateinit var ed2kManager: Ed2kKadManager
    private lateinit var torrentEngine: TorrentStreamEngine
    private var ircEngine: IrcXdccEngine? = null
    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var searchEngine: GlobalSearchEngine
    private lateinit var onboardingWizard: OnboardingWizard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        val configDir = File(UiComponents.getBaseMulePath(this), "Config")
        ed2kManager = Ed2kKadManager(configDir)
        torrentEngine = TorrentStreamEngine(this)
        vlcManager = VlcPlayerManager(this)
        searchEngine = GlobalSearchEngine(configDir)
        onboardingWizard = OnboardingWizard(this)

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

        val btnQuickDisconnect = UiComponents.compactRowButton(this, "SCOLLEGA RETI", "#991B1B", 0f) {
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
        screenSettings = SettingsScreenBuilder.build(this)

        contentHolder.addView(screenNetworks)
        contentHolder.addView(screenSearch)
        contentHolder.addView(screenTorrent)
        contentHolder.addView(screenIrc)
        contentHolder.addView(screenFilesVlc)
        contentHolder.addView(screenSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        // --- BARRA DI NAVIGAZIONE INFERIORE ---
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tabs = listOf(
            R.drawable.ic_networks to "Reti",
            R.drawable.ic_search to "Cerca",
            R.drawable.ic_torrent to "Torrent",
            R.drawable.ic_irc to "mIRC",
            R.drawable.ic_transfers to "File & VLC",
            R.drawable.ic_settings to "Opzioni"
        )
        tabs.forEachIndexed { idx, pair ->
            val navItem = UiComponents.createBottomNavItem(this, pair.first, pair.second, idx) {
                selectScreen(it)
            }
            navItems.add(navItem)
            bottomNav.addView(navItem)
        }

        rootLayout.addView(bottomNav)
        setContentView(rootLayout)

        observeEd2kEngineState()
        observeTorrentEngineState()
        P2PEngineService.updateNotificationStatus(this, "Servizio P2P pronto")
        selectScreen(0)
    }

    override fun onResume() {
        super.onResume()
        onboardingWizard.checkAndRunNextStep()
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
    // 1. SCHERMATA RETI (MOTORE EMULE: ED2K + KAD)
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

            addView(UiComponents.sectionTitle(this@MainActivity, "Modalità di Connessione eMule (eD2K / KAD)"))
            addView(UiComponents.infoLabel(this@MainActivity, "Scegli se collegarti e scaricare usando Entrambi, Solo Server eD2K o Solo Rete KAD:"))

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

            val kadUrlsInput = UiComponents.styledEditText(this@MainActivity, savedKadUrls).apply { minLines = 2 }
            val serverUrlsInput = UiComponents.styledEditText(this@MainActivity, savedServerUrls).apply { minLines = 3 }

            btnGlobalConnectToggle = UiComponents.compactButton(this@MainActivity, "CONNETTI (SERVER + KAD)", "#16A34A") {
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

                    val configDir = File(UiComponents.getBaseMulePath(this@MainActivity), "Config")
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
                                appendServerLog("- ${s.name} [${s.ip}:${s.port}] - Utenti: ${s.users}")
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

            addView(UiComponents.sectionTitle(this@MainActivity, "Aggiungi Nuovo Server eD2K Manuale (IP:Porta)"))
            val addSrvRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val customSrvIpPort = UiComponents.styledEditText(this@MainActivity, "").apply {
                hint = "IP:Porta (es. 176.125.242.230:5555)"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val customSrvName = UiComponents.styledEditText(this@MainActivity, "").apply {
                hint = "Nome Server"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val btnAddSrv = UiComponents.compactRowButton(this@MainActivity, "+ SERVER", "#0284C7", 0f) {
                val raw = customSrvIpPort.text.toString().trim()
                val name = customSrvName.text.toString().trim().ifEmpty { "Server Personalizzato" }
                val parts = raw.split(":")
                val ip = parts.getOrNull(0).orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 4661
                if (ip.count { it == '.' } == 3) {
                    Ed2kKadManager.activeServers["$ip:$port"] =
                        Ed2kKadManager.Ed2kServer(ip, port, name, "Aggiunto dall'utente", 50000, 1000000)
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

            addView(UiComponents.sectionTitle(this@MainActivity, "Liste URL server.met & nodes.dat (Uno per riga)"))
            addView(UiComponents.infoLabel(this@MainActivity, "URL nodes.dat (Nodi KAD):"))
            addView(kadUrlsInput)
            addView(UiComponents.infoLabel(this@MainActivity, "URL server.met (Liste Server eD2K):"))
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
    // 2. SCHERMATA CERCA GLOBALE (SMISTA I RISULTATI AI 3 MOTORI SEPARATI)
    // =========================================================================
    private fun buildSearchScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(this@MainActivity, "Ricerca Globale (eMule eD2K/KAD, Siti Torrent & mIRC XDCC)"))
            addView(UiComponents.infoLabel(this@MainActivity, "I 3 motori lavorano separatamente. Cliccando SCARICA si avvia il motore specifico della rete:"))

            val searchInput = UiComponents.styledEditText(this@MainActivity, "").apply {
                hint = "Es. the Boys s01 e 3 ita, film 1080p ita, mp3..."
            }
            addView(searchInput)

            val spinnersRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val netSpinner = Spinner(this@MainActivity).apply {
                val nets = listOf(
                    "Tutte le Reti (Globale)",
                    "Solo eMule (eD2K / KAD)",
                    "Solo BitTorrent (Siti + DHT)",
                    "Solo mIRC XDCC (Canali + Bot)"
                )
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, nets)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 10)
                }
            }

            val catSpinner = Spinner(this@MainActivity).apply {
                val cats = listOf(
                    "Tutti i Formati",
                    "Video (MKV/MP4/AVI)",
                    "Audio (MP3/FLAC)",
                    "Archivi (RAR/ZIP/ISO)",
                    "Programmi/Doc (APK/PDF)"
                )
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, cats)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 0, 10)
                }
            }

            spinnersRow.addView(netSpinner)
            spinnersRow.addView(catSpinner)
            addView(spinnersRow)

            btnDynamicSearch = UiComponents.compactButton(this@MainActivity, "RICERCA GLOBALE", "#0284C7") {
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
                        1 -> "CERCA SU EMULE (ED2K / KAD)"
                        2 -> "CERCA SU BITTORRENT"
                        3 -> "CERCA SU MIRC XDCC"
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
        searchResultsContainer.addView(UiComponents.infoLabel(this, "Ricerca in corso per \"$query\"..."))

        if (netFilter == GlobalSearchEngine.NetworkType.ALL || netFilter == GlobalSearchEngine.NetworkType.IRC_XDCC) {
            ircEngine?.sendSearchCommandToActiveChannels(query)
        }

        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val langPref = when (prefs.getInt("search_lang_pref", 0)) {
            1 -> GlobalSearchEngine.LanguagePreference.ITALIAN_PRIORITY
            2 -> GlobalSearchEngine.LanguagePreference.ITALIAN_STRICT
            3 -> GlobalSearchEngine.LanguagePreference.ENGLISH
            else -> GlobalSearchEngine.LanguagePreference.ANY
        }
        val customSitesRaw = prefs.getString(
            "custom_torrent_sites",
            GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES
        ) ?: GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES
        val customSiteTemplates = customSitesRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        mainScope.launch {
            val results = searchEngine.search(
                query = query,
                netFilter = netFilter,
                catFilter = catFilter,
                langPref = langPref,
                customTorrentSiteTemplates = customSiteTemplates
            )
            searchResultsContainer.removeAllViews()

            if (results.isEmpty()) {
                searchResultsContainer.addView(UiComponents.infoLabel(this@MainActivity, "Nessun file trovato per \"$query\"."))
                return@launch
            }

            searchResultsContainer.addView(UiComponents.infoLabel(this@MainActivity, "Trovati ${results.size} file disponibili:"))

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

                val netTag = when (res.network) {
                    GlobalSearchEngine.NetworkType.ED2K_KAD -> "eMule"
                    GlobalSearchEngine.NetworkType.TORRENT -> "Torrent"
                    GlobalSearchEngine.NetworkType.IRC_XDCC -> "mIRC"
                    else -> "P2P"
                }

                val titleView = TextView(this@MainActivity).apply {
                    text = "[$netTag | ${res.fileExtension}]  ${res.title}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }

                val detailsView = TextView(this@MainActivity).apply {
                    text = "Dim: ${res.sizeText}  |  Fonti: ${res.sources}  |  Rete: ${res.providerName}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, 6, 0, 10)
                }

                val btnRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                btnRow.addView(UiComponents.compactRowButton(this@MainActivity, "ISPEZIONA", "#374151", 1f) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ispezione [${res.fileExtension}]")
                        .setMessage(res.inspectionSummary)
                        .setPositiveButton("SCARICA CON ${netTag.uppercase()}") { _, _ ->
                            dispatchDownloadToDedicatedEngine(res)
                        }
                        .setNegativeButton("Chiudi", null)
                        .show()
                })

                btnRow.addView(UiComponents.compactRowButton(this@MainActivity, "SCARICA ($netTag)", "#16A34A", 1f) {
                    dispatchDownloadToDedicatedEngine(res)
                })

                card.addView(titleView)
                card.addView(detailsView)
                card.addView(btnRow)
                searchResultsContainer.addView(card)
            }
        }
    }

    /**
     * Smista rigorosamente il download al motore dedicato corrispondente:
     * - ED2K_KAD -> Motore nativo Ed2kKadManager
     * - TORRENT  -> Motore nativo TorrentStreamEngine
     * - IRC_XDCC -> Motore nativo IrcXdccEngine
     */
    private fun dispatchDownloadToDedicatedEngine(res: GlobalSearchEngine.SearchResult) {
        val baseDir = File(UiComponents.getBaseMulePath(this))
        val tempDir = File(baseDir, "Temp").apply { mkdirs() }
        val incomingDir = File(baseDir, "Incoming").apply { mkdirs() }

        when (res.network) {
            GlobalSearchEngine.NetworkType.ED2K_KAD -> {
                activeEd2kBoxInFiles.visibility = View.VISIBLE
                ed2kManager.startEd2kDownload(
                    ed2kLink = res.actionPayload,
                    tempDir = tempDir,
                    incomingDir = incomingDir
                )
                Toast.makeText(
                    this,
                    "Avviato nel motore eMule (eD2K/KAD): ${res.title}",
                    Toast.LENGTH_LONG
                ).show()
            }
            GlobalSearchEngine.NetworkType.TORRENT -> {
                activeDownloadBoxInFiles.visibility = View.VISIBLE
                torrentEngine.startStream(res.actionPayload)
                Toast.makeText(
                    this,
                    "Avviato nel motore BitTorrent: ${res.title}",
                    Toast.LENGTH_LONG
                ).show()
            }
            GlobalSearchEngine.NetworkType.IRC_XDCC -> {
                startIrcAutomatedDownloadInBackground(res.ircServer, res.ircChannel, res.ircBot, res.ircPack)
                Toast.makeText(
                    this,
                    "Avviato nel motore mIRC XDCC: Pacchetto #${res.ircPack} da ${res.ircBot}",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {}
        }
    }

    // =========================================================================
    // 3. SCHERMATA TORRENT
    // =========================================================================
    private fun buildTorrentScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(this@MainActivity, "Aggiungi Link Magnet / URL .torrent / InfoHash"))
            val directInput = UiComponents.styledEditText(this@MainActivity, "").apply {
                hint = "magnet:?xt=urn:btih:... oppure URL .torrent"
                minLines = 2
            }
            addView(directInput)

            addView(UiComponents.compactButton(this@MainActivity, "AVVIA SCARICAMENTO TORRENT", "#0284C7") {
                val input = directInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    activeDownloadBoxInFiles.visibility = View.VISIBLE
                    torrentEngine.startStream(input)
                    Toast.makeText(this@MainActivity, "Scaricamento BitTorrent avviato!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Inserisci un link Magnet o Torrent", Toast.LENGTH_SHORT).show()
                }
            })

            addView(UiComponents.sectionTitle(this@MainActivity, "Stato Motore BitTorrent"))
            torrentStatusText = UiComponents.infoLabel(this@MainActivity, "Stato: Inattivo")
            torrentProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(torrentStatusText)
            addView(torrentProgressBar)

            val ctrlRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ctrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "FERMA (MANTIENI)", "#374151", 1f) {
                torrentEngine.stopKeepFiles()
            })
            ctrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "ELIMINA CACHE", "#991B1B", 1f) {
                torrentEngine.stopAndClearCache()
            })
            addView(ctrlRow)
        }
    }

    // =========================================================================
    // 4. SCHERMATA mIRC
    // =========================================================================
    private fun buildIrcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(this@MainActivity, "Client mIRC Protetto (IP Cloaking +ix & Anti-Leak CTCP)"))

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

            ircServerInput = UiComponents.styledEditText(this@MainActivity, "irc.rizon.net").apply {
                hint = "Indirizzo Server IRC"
            }
            addView(ircServerInput)

            val connRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnIrcConnectToggle = UiComponents.compactRowButton(this@MainActivity, "CONNETTI", "#16A34A", 1f) {
                if (isIrcConnectedState || ircEngine != null) {
                    disconnectIrcCleanly()
                } else {
                    connectToIrcServer(ircServerInput.text.toString().trim(), "")
                }
            }

            connRow.addView(btnIrcConnectToggle)
            connRow.addView(UiComponents.compactRowButton(this@MainActivity, "SCANSIONA (/LIST)", "#2563EB", 1.1f) {
                if (ircEngine == null) {
                    Toast.makeText(this@MainActivity, "Premi prima CONNETTI", Toast.LENGTH_SHORT).show()
                } else {
                    scannedChannels.clear()
                    ircEngine?.requestChannelList()
                }
            })
            connRow.addView(UiComponents.compactRowButton(this@MainActivity, "CANALI", "#0284C7", 0.9f) {
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
                text = "Console mIRC pronta. Lo scudo IP (+ix) viene attivato dopo la registrazione sul server."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(16, 14, 16, 14)
                minLines = 7
            }
            addView(ircLogText)

            addView(UiComponents.sectionTitle(this@MainActivity, "Barra Comandi mIRC"))
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

            val btnSendCmd = UiComponents.compactRowButton(this@MainActivity, "INVIA", "#16A34A", 0f) {
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
            quickCmdsRow.addView(UiComponents.compactRowButton(this@MainActivity, "/list", "#374151", 1f) {
                scannedChannels.clear()
                ircEngine?.executeMircCommand("/list")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(this@MainActivity, "!list", "#374151", 1f) {
                ircEngine?.executeMircCommand("!list")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(this@MainActivity, "/names", "#374151", 1f) {
                ircEngine?.executeMircCommand("/names")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(this@MainActivity, "/part", "#374151", 1f) {
                ircEngine?.executeMircCommand("/part")
            })
            addView(quickCmdsRow)

            ircProgressLabel = UiComponents.infoLabel(this@MainActivity, "Nessun download XDCC in corso")
            ircProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            addView(UiComponents.sectionTitle(this@MainActivity, "File Presenti nel Canale"))
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
        val base = File(UiComponents.getBaseMulePath(this))
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
                        appendIrcLog("Entrato in ${event.channel}. In ascolto annunci pacchetti...")
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
            ircChannelFilesContainer.addView(
                UiComponents.infoLabel(this, "In attesa di annunci dai Bot del canale...")
            )
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

            card.addView(UiComponents.compactButton(this, "SCARICA (${pack.botNick} #${pack.packNumber})", "#16A34A") {
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
    // 5. SCHERMATA FILE & VLC (MOSTRA SEPARATAMENTE I TRASFERIMENTI EMULE E TORRENT)
    // =========================================================================
    private fun buildFilesVlcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // --- BOX 1: MOTORE EMULE (ED2K / KAD) ---
            activeEd2kBoxInFiles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1D283A"))
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 14)
                layoutParams = lp
            }

            activeEd2kBoxInFiles.addView(TextView(this@MainActivity).apply {
                text = "MOTORE EMULE (eD2K / KAD)"
                textSize = 12f
                setTextColor(Color.parseColor("#4ADE80"))
                setTypeface(null, Typeface.BOLD)
            })

            activeEd2kTitleInFiles = UiComponents.infoLabel(this@MainActivity, "Nessun trasferimento eMule attivo.")
            activeEd2kProgressInFiles = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            activeEd2kPreviewHolder = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val ed2kCtrlRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ed2kCtrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "PAUSA / MANTIENI .PART", "#374151", 1f) {
                Ed2kKadManager.stopCurrentDownload(deletePart = false)
                refreshDownloadedAndIncompleteFiles()
            })
            ed2kCtrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "ANNULLA ED ELIMINA", "#991B1B", 1f) {
                Ed2kKadManager.stopCurrentDownload(deletePart = true)
                activeEd2kPreviewHolder.removeAllViews()
                refreshDownloadedAndIncompleteFiles()
            })

            activeEd2kBoxInFiles.addView(activeEd2kTitleInFiles)
            activeEd2kBoxInFiles.addView(activeEd2kProgressInFiles)
            activeEd2kBoxInFiles.addView(activeEd2kPreviewHolder)
            activeEd2kBoxInFiles.addView(ed2kCtrlRow)
            addView(activeEd2kBoxInFiles)

            // --- BOX 2: MOTORE BITTORRENT ---
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
                text = "MOTORE BITTORRENT (DHT / MAGNET)"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            })

            activeDownloadTitleInFiles = UiComponents.infoLabel(this@MainActivity, "Nessun download BitTorrent attivo.")
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
            dlCtrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "PAUSA / MANTIENI", "#374151", 1f) {
                torrentEngine.stopKeepFiles()
                refreshDownloadedAndIncompleteFiles()
            })
            dlCtrlRow.addView(UiComponents.compactRowButton(this@MainActivity, "ANNULLA ED ELIMINA", "#991B1B", 1f) {
                torrentEngine.stopAndClearCache()
                activeDownloadPreviewHolderInFiles.removeAllViews()
                refreshDownloadedAndIncompleteFiles()
            })

            activeDownloadBoxInFiles.addView(activeDownloadTitleInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadProgressInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadPreviewHolderInFiles)
            activeDownloadBoxInFiles.addView(dlCtrlRow)
            addView(activeDownloadBoxInFiles)

            addView(UiComponents.sectionTitle(this@MainActivity, "Archivio File Scaricati (Incoming) e Incompleti (Temp)"))
            addView(UiComponents.compactButton(this@MainActivity, "AGGIORNA ELENCO FILE", "#2563EB") {
                refreshDownloadedAndIncompleteFiles()
            })

            filesListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 0)
            }
            addView(filesListContainer)
        }
    }

    private fun observeEd2kEngineState() {
        mainScope.launch {
            Ed2kKadManager.transferState.collect { state ->
                when (state) {
                    is Ed2kTransferState.Idle -> {
                        activeEd2kTitleInFiles.text = "Nessun trasferimento eMule attivo."
                        activeEd2kProgressInFiles.progress = 0
                    }
                    is Ed2kTransferState.Downloading -> {
                        val info = "${state.fileName}\n${state.statusText} | ${state.speedKbps} KB/s | Fonti: ${state.activeSources}"
                        activeEd2kTitleInFiles.text = info
                        activeEd2kProgressInFiles.progress = state.progressPercent
                        activeEd2kPreviewHolder.removeAllViews()
                        if (state.partFile.exists() && state.partFile.length() > 0L) {
                            activeEd2kPreviewHolder.addView(
                                UiComponents.compactButton(this@MainActivity, "ANTEPRIMA LIBVLC (.PART)", "#0284C7") {
                                    UiComponents.openFullscreenVlcDialog(this@MainActivity, vlcManager, state.partFile)
                                }
                            )
                        }
                    }
                    is Ed2kTransferState.Completed -> {
                        activeEd2kProgressInFiles.progress = 100
                        activeEd2kTitleInFiles.text = "Completato in Incoming: ${state.fileName}"
                        activeEd2kPreviewHolder.removeAllViews()
                        activeEd2kPreviewHolder.addView(
                            UiComponents.compactButton(this@MainActivity, "APRI CON LIBVLC", "#16A34A") {
                                UiComponents.openFullscreenVlcDialog(this@MainActivity, vlcManager, state.finalFile)
                            }
                        )
                        refreshDownloadedAndIncompleteFiles()
                    }
                    is Ed2kTransferState.Error -> {
                        activeEd2kTitleInFiles.text = state.message
                    }
                }
            }
        }
    }

    private fun observeTorrentEngineState() {
        mainScope.launch {
            torrentEngine.state.collect { state ->
                when (state) {
                    is TorrentStreamState.Idle -> {
                        activeDownloadTitleInFiles.text = "Nessun download BitTorrent attivo."
                        activeDownloadProgressInFiles.progress = 0
                        torrentStatusText.text = "Stato: Inattivo"
                        torrentProgressBar.progress = 0
                    }
                    is TorrentStreamState.Buffering -> {
                        val info = "${state.torrentTitle}\n${state.statusText} | ${state.downloadSpeedKbps} KB/s | Peer: ${state.peers}"
                        activeDownloadProgressInFiles.progress = state.progressPercent
                        activeDownloadTitleInFiles.text = info
                        torrentProgressBar.progress = state.progressPercent
                        torrentStatusText.text = info
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Torrent: ${state.progressPercent}% (${state.downloadSpeedKbps} KB/s)"
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
                            UiComponents.compactButton(this@MainActivity, "APRI ANTEPRIMA LIBVLC", "#16A34A") {
                                UiComponents.openFullscreenVlcDialog(this@MainActivity, vlcManager, state.videoFile)
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
        val base = File(UiComponents.getBaseMulePath(this))
        val tempFiles = File(base, "Temp").walkTopDown().filter { it.isFile }.toList()
        val incomingFiles = File(base, "Incoming").walkTopDown().filter { it.isFile }.toList()
        val allFiles = (tempFiles + incomingFiles).sortedByDescending { it.lastModified() }

        if (allFiles.isEmpty()) {
            filesListContainer.addView(UiComponents.infoLabel(this, "Nessun file presente in Temp o Incoming."))
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
            btnRow.addView(UiComponents.compactRowButton(this, "ANTEPRIMA VLC", "#0284C7", 1f) {
                UiComponents.openFullscreenVlcDialog(this, vlcManager, file)
            })
            btnRow.addView(UiComponents.compactRowButton(this, "APRI", "#16A34A", 1f) {
                UiComponents.openFileDirectly(this, vlcManager, file)
            })
            btnRow.addView(UiComponents.compactRowButton(this, "ELIMINA", "#991B1B", 1f) {
                confirmAndDeleteFile(file)
            })

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

    override fun onDestroy() {
        super.onDestroy()
        vlcManager.release()
    }
}

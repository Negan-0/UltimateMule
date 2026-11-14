package com.ultimatemule.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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

    // Le 6 Sezioni
    private lateinit var screenNetworks: LinearLayout
    private lateinit var screenSearch: LinearLayout
    private lateinit var screenTorrent: LinearLayout
    private lateinit var screenFilesVlc: LinearLayout
    private lateinit var screenIrc: LinearLayout
    private lateinit var screenSettings: LinearLayout

    private val navItems = mutableListOf<LinearLayout>()

    // Riferimenti UI Reti
    private lateinit var topStatusBanner: TextView
    private lateinit var btnGlobalConnectToggle: Button
    private lateinit var serverListLog: TextView

    // Riferimenti UI Cerca
    private lateinit var btnDynamicSearch: Button
    private lateinit var searchResultsContainer: LinearLayout

    // Riferimenti UI Avanzamento in File & VLC e in Torrent
    private lateinit var activeDownloadBoxInFiles: LinearLayout
    private lateinit var activeDownloadTitleInFiles: TextView
    private lateinit var activeDownloadProgressInFiles: ProgressBar
    private lateinit var activeDownloadPreviewHolderInFiles: LinearLayout
    private lateinit var filesListContainer: LinearLayout

    private lateinit var torrentStatusText: TextView
    private lateinit var torrentProgressBar: ProgressBar

    // Riferimenti UI mIRC (con Bottone Toggle Connetti/Disconnetti Verde/Rosso)
    private lateinit var ircServerInput: EditText
    private lateinit var btnIrcConnectToggle: Button
    private var isIrcConnectedState = false
    private lateinit var ircActiveChannelsBanner: TextView
    private lateinit var ircLogText: TextView
    private lateinit var ircCommandInput: EditText
    private lateinit var ircSearchInput: EditText
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private lateinit var ircChannelFilesContainer: LinearLayout
    private val scannedChannels = mutableListOf<IrcXdccEngine.IrcChannelInfo>()
    private val discoveredChannelPacks = linkedMapOf<String, IrcXdccEngine.ChannelPackItem>()

    private lateinit var folderInput: EditText

    // Motori P2P & Media
    private var ircEngine: IrcXdccEngine? = null
    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var torrentEngine: TorrentStreamEngine
    private lateinit var searchEngine: GlobalSearchEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        requestNotificationPermissionIfNeeded()

        vlcManager = VlcPlayerManager(this)
        torrentEngine = TorrentStreamEngine(this)
        searchEngine = GlobalSearchEngine(File(getBaseMulePath(), "Config"))

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
        }

        // --- BARRA SUPERIORE ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(24, 18, 24, 18)
        }

        topStatusBanner = TextView(this).apply {
            text = "UltimateMule  |  Pronto per la connessione"
            textSize = 12f
            setTextColor(Color.parseColor("#E5E7EB"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnQuickDisconnect = Button(this).apply {
            text = "SCOLLEGA TUTTO"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#991B1B"))
            setPadding(18, 10, 18, 10)
            setOnClickListener { disconnectAllNetworks() }
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
            setPadding(28, 20, 28, 36)
        }

        screenNetworks = buildNetworksScreen()
        screenSearch = buildSearchScreen()
        screenTorrent = buildTorrentScreen()
        screenFilesVlc = buildFilesVlcScreen()
        screenIrc = buildIrcScreen()
        screenSettings = buildSettingsScreen()

        contentHolder.addView(screenNetworks)
        contentHolder.addView(screenSearch)
        contentHolder.addView(screenTorrent)
        contentHolder.addView(screenFilesVlc)
        contentHolder.addView(screenIrc)
        contentHolder.addView(screenSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        // --- BARRA DI NAVIGAZIONE INFERIORE A 6 ICONE VETTORIALI ---
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
        bottomNav.addView(createBottomNavItem(R.drawable.ic_transfers, "File & VLC", 3))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_irc, "mIRC", 4))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_settings, "Opzioni", 5))

        rootLayout.addView(bottomNav)
        setContentView(rootLayout)

        observeTorrentEngineState()
        P2PEngineService.updateNotificationStatus(this, "Servizio P2P pronto")
        selectScreen(0)
    }

    private fun createBottomNavItem(iconRes: Int, label: String, index: Int): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 18, 4, 18)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectScreen(index) }
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(48, 48)
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
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
        screenFilesVlc.visibility = if (index == 3) View.VISIBLE else View.GONE
        screenIrc.visibility = if (index == 4) View.VISIBLE else View.GONE
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

        if (index == 3) {
            refreshDownloadedAndIncompleteFiles()
        }
    }

    private fun disconnectAllNetworks() {
        Ed2kKadManager.disconnectAll()
        disconnectIrcCleanly()
        torrentEngine.stopKeepFiles()
        vlcManager.stopAndDetach()
        stopService(Intent(this, P2PEngineService::class.java))

        btnGlobalConnectToggle.text = "CONNETTI TUTTO (SERVER + KAD)"
        btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))

        topStatusBanner.text = "UltimateMule  |  DISCONNESSO DA TUTTE LE RETI"
        serverListLog.text = "Reti scollegate. Premi il pulsante verde in alto per riconnettere Server eD2K e Nodi KAD."
        Toast.makeText(this, "Scollegato da tutte le reti P2P.", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 1. SCHERMATA RETI: UN SOLO PULSANTE PRINCIPALE CHE FA TUTTO SUBITO E PULISCE IL LOG
    // =========================================================================
    private fun buildNetworksScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Connessione Automatica Completa (Server eD2K + Rete KAD)"))
            addView(infoLabel("Ti basta premere il pulsante verde qui sotto: unisce da solo tutti i Server attivi (escludendo quelli morti con 0 utenti) e connette tutti i nodi KAD in un unico passaggio."))

            val kadUrlsInput = styledEditText(
                "http://upd.emule-security.org/nodes.dat\n" +
                "http://www.nodes-dat.com/dl.php?load=nodes&trace=1"
            ).apply { minLines = 2 }

            val serverUrlsInput = styledEditText(
                "http://upd.emule-security.org/server.met\n" +
                "http://www.server-met.de/dl.php?load=gz&trace=1\n" +
                "http://peerates.net/servers.php"
            ).apply { minLines = 3 }

            btnGlobalConnectToggle = styledButton("CONNETTI TUTTO (SERVER + KAD)", "#16A34A") {
                if (Ed2kKadManager.isConnected) {
                    // Se è già connesso, premendolo disconnette tutto!
                    Ed2kKadManager.disconnectAll()
                    btnGlobalConnectToggle.text = "CONNETTI TUTTO (SERVER + KAD)"
                    btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))
                    topStatusBanner.text = "UltimateMule  |  eD2K & KAD Disconnessi"
                    serverListLog.text = "Disconnesso da Server eD2K e Rete KAD."
                } else {
                    // PULISCE SUBITO LA SCRITTA INIZIALE ED ESEGUE TUTTO IN AUTOMATICO!
                    serverListLog.text = "Avvio connessione globale e pulizia server offline (0 utenti) in corso..."
                    btnGlobalConnectToggle.text = "CONNESSIONE IN CORSO..."
                    btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#D97706"))

                    val configDir = File(getBaseMulePath(), "Config")
                    val manager = Ed2kKadManager(configDir)
                    mainScope.launch {
                        val servers = manager.importMultipleServerMet(serverUrlsInput.text.toString().split("\n")) { log ->
                            runOnUiThread { appendServerLog(log) }
                        }
                        val nodes = manager.importMultipleKadNodesDat(kadUrlsInput.text.toString().split("\n")) { log ->
                            runOnUiThread { appendServerLog(log) }
                        }
                        val totalUsers = servers.sumOf { it.users }
                        val totalFiles = servers.sumOf { it.files }

                        btnGlobalConnectToggle.text = "DISCONNETTI TUTTO (SERVER + KAD ATTIVI)"
                        btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#DC2626"))

                        topStatusBanner.text = "eD2K: ${servers.size} Server Attivi ($totalUsers ut.) | KAD: $nodes nodi"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Connesso: ${servers.size} Server eD2K | $nodes Nodi KAD"
                        )

                        appendServerLog("\n=== CONNESSIONE COMPLETATA (PRONTO PER CERCARE) ===")
                        appendServerLog("Server Attivi: ${servers.size} | Utenti Totali: $totalUsers | File: $totalFiles | Nodi KAD: $nodes")
                        servers.take(15).forEach { s ->
                            appendServerLog("• ${s.name} [${s.ip}:${s.port}] -> Utenti: ${s.users}")
                        }
                    }
                }
            }
            addView(btnGlobalConnectToggle)

            // Il riquadro di stato è subito sotto il pulsante principale, così vedi tutto immediatamente
            serverListLog = TextView(this@MainActivity).apply {
                text = "Stato: Non connesso. Premi il pulsante verde qui sopra per connettere contemporaneamente Server eD2K e Rete KAD."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(20, 20, 20, 20)
            }
            addView(serverListLog)

            addView(sectionTitle("Configurazione Sorgenti URL (Usate automaticamente dal pulsante verde)"))
            addView(infoLabel("Lista URL nodes.dat (Rete KAD):"))
            addView(kadUrlsInput)
            addView(infoLabel("Lista URL server.met (Server eD2K):"))
            addView(serverUrlsInput)
        }
    }

    private fun appendServerLog(msg: String) {
        val current = serverListLog.text.toString()
        val baseLines = if (current.startsWith("Stato: Non connesso")) emptyList() else current.split("\n")
        val lines = (baseLines + msg).takeLast(24)
        serverListLog.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 2. SCHERMATA CERCA: BOTTONE CON TESTO DINAMICO E RICERCA MULTI-VARIANTE
    // =========================================================================
    private fun buildSearchScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Ricerca File P2P"))
            addView(infoLabel("Scrivi il nome del file (es. Superman ita). Il motore cerca automaticamente anche le varianti (.ITA, .ITALIAN, Multi) e ti porta nella scheda 'File & VLC' appena avvii lo scaricamento:"))

            val searchInput = styledEditText("").apply {
                hint = "Es. Superman ita, nome film, album mp3, archivio..."
            }
            addView(searchInput)

            val spinnersRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val netSpinner = Spinner(this@MainActivity).apply {
                val nets = listOf("Tutte le Reti (Globale)", "Solo Rete eD2K / KAD", "Solo BitTorrent", "Solo Bot IRC XDCC")
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, nets)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 8, 12)
                }
            }

            val catSpinner = Spinner(this@MainActivity).apply {
                val cats = listOf("Tutti i Formati", "Video (MKV/MP4/AVI)", "Audio (MP3/FLAC)", "Archivi (RAR/ZIP/ISO)", "Programmi/Doc (APK/PDF)")
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, cats)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(8, 0, 0, 12)
                }
            }

            spinnersRow.addView(netSpinner)
            spinnersRow.addView(catSpinner)
            addView(spinnersRow)

            btnDynamicSearch = styledButton("RICERCA GLOBALE", "#0284C7") {
                val query = searchInput.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Inserisci il nome del file da cercare", Toast.LENGTH_SHORT).show()
                    return@styledButton
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

            // Cambia dinamicamente il testo del bottone di ricerca quando l'utente seleziona una rete specifica!
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
                setPadding(0, 16, 0, 0)
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
        searchResultsContainer.addView(infoLabel("Ricerca in corso per \"$query\" (incluse varianti titolo/lingua)..."))

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
                    setPadding(24, 20, 24, 20)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, 18)
                    layoutParams = lp
                }

                val netName = when (res.network) {
                    GlobalSearchEngine.NetworkType.ED2K_KAD -> "eD2K / KAD"
                    GlobalSearchEngine.NetworkType.TORRENT -> "BitTorrent"
                    GlobalSearchEngine.NetworkType.IRC_XDCC -> "IRC XDCC (${res.ircBot})"
                    else -> "P2P"
                }

                val titleView = TextView(this@MainActivity).apply {
                    text = "[${res.fileExtension}]  ${res.title}"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }

                val detailsView = TextView(this@MainActivity).apply {
                    text = "Formato: .${res.fileExtension}  |  Dim: ${res.sizeText}  |  Fonti: ${res.sources} (${res.completeSourcesPercent}% compl.)  |  Rete: $netName"
                    textSize = 12f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, 8, 0, 12)
                }

                val btnRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                btnRow.addView(rowButton("ISPEZIONA CONTENUTO", "#374151", 1f) {
                    showPreDownloadInspectionDialog(res)
                })

                btnRow.addView(rowButton("SCARICA FILE", "#16A34A", 1f) {
                    startDownloadFromSearchResult(res)
                })

                card.addView(titleView)
                card.addView(detailsView)
                card.addView(btnRow)
                searchResultsContainer.addView(card)
            }
        }
    }

    private fun showPreDownloadInspectionDialog(res: GlobalSearchEngine.SearchResult) {
        AlertDialog.Builder(this)
            .setTitle("Ispezione File [${res.fileExtension}]")
            .setMessage(res.inspectionSummary)
            .setPositiveButton("AVVIA DOWNLOAD") { _, _ ->
                startDownloadFromSearchResult(res)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    /**
     * Quando l'utente avvia un download dalla Ricerca, va direttamente nella sezione "File & VLC" (indice 3)
     * dove mostra lo stato di avanzamento del download!
     */
    private fun startDownloadFromSearchResult(res: GlobalSearchEngine.SearchResult) {
        when (res.network) {
            GlobalSearchEngine.NetworkType.IRC_XDCC -> {
                startIrcAutomatedDownload(res.ircServer, res.ircChannel, res.ircBot, res.ircPack)
            }
            else -> {
                selectScreen(3) // Apre direttamente la scheda "File & VLC"!
                activeDownloadBoxInFiles.visibility = View.VISIBLE
                torrentEngine.startStream(res.actionPayload)
            }
        }
    }

    // =========================================================================
    // 3. SCHERMATA TORRENT (Per inserimento manuale link Magnet / .torrent)
    // =========================================================================
    private fun buildTorrentScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Inserimento Diretto Link Magnet / File .torrent"))
            addView(infoLabel("Incolla qui un link Magnet, InfoHash o URL .torrent:"))

            val directInput = styledEditText("").apply {
                hint = "magnet:?xt=urn:btih:... oppure https://.../file.torrent"
                minLines = 2
            }
            addView(directInput)

            addView(styledButton("AVVIA DOWNLOAD E VAI A FILE & VLC", "#0284C7") {
                val input = directInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    selectScreen(3)
                    activeDownloadBoxInFiles.visibility = View.VISIBLE
                    torrentEngine.startStream(input)
                } else {
                    Toast.makeText(this@MainActivity, "Inserisci un link Magnet o Torrent", Toast.LENGTH_SHORT).show()
                }
            })

            addView(sectionTitle("Stato Motore Torrent"))
            torrentStatusText = infoLabel("Nessun download attivo.")
            torrentProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(torrentStatusText)
            addView(torrentProgressBar)
        }
    }

    // =========================================================================
    // 4. SCHERMATA FILE & VLC (Con Stato Avanzamento Download Live + Archivio File)
    // =========================================================================
    private fun buildFilesVlcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // PANNELLO STATO AVANZAMENTO DOWNLOAD ATTIVO (Visibile direttamente in File & VLC!)
            activeDownloadBoxInFiles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#182232"))
                setPadding(24, 20, 24, 20)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 20)
                layoutParams = lp
            }

            activeDownloadBoxInFiles.addView(TextView(this@MainActivity).apply {
                text = "STATO AVANZAMENTO DOWNLOAD IN CORSO"
                textSize = 13f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            })

            activeDownloadTitleInFiles = infoLabel("Nessun download in corso al momento.")
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
            dlCtrlRow.addView(rowButton("INTERROMPI (MANTIENI FILE)", "#374151", 1f) {
                torrentEngine.stopKeepFiles()
                refreshDownloadedAndIncompleteFiles()
            })
            dlCtrlRow.addView(rowButton("INTERROMPI ED ELIMINA", "#991B1B", 1f) {
                torrentEngine.stopAndClearCache()
                activeDownloadPreviewHolderInFiles.removeAllViews()
                refreshDownloadedAndIncompleteFiles()
            })

            activeDownloadBoxInFiles.addView(activeDownloadTitleInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadProgressInFiles)
            activeDownloadBoxInFiles.addView(activeDownloadPreviewHolderInFiles)
            activeDownloadBoxInFiles.addView(dlCtrlRow)
            addView(activeDownloadBoxInFiles)

            // ARCHIVIO FILE SCARICATI E INCOMPLETI
            addView(sectionTitle("Archivio File in Temp (.part) e Incoming"))
            addView(infoLabel("Guarda l'anteprima con LibVLC mentre il file scarica, apri il file completato o eliminalo:"))

            addView(styledButton("AGGIORNA ELENCO FILE", "#2563EB") {
                refreshDownloadedAndIncompleteFiles()
            })

            filesListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 0)
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
                        val info = "File: ${state.torrentTitle}\n${state.statusText}  |  Velocità: ${state.downloadSpeedKbps} KB/s  |  Fonti: ${state.peers}"
                        activeDownloadProgressInFiles.progress = state.progressPercent
                        activeDownloadTitleInFiles.text = info

                        torrentProgressBar.progress = state.progressPercent
                        torrentStatusText.text = info

                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Download: ${state.progressPercent}% (${state.downloadSpeedKbps} KB/s - ${state.peers} fonti)"
                        )
                    }
                    is TorrentStreamState.Ready -> {
                        val readyMsg = "File pronto per l'anteprima: ${state.videoFile.name}\nPercorso: ${state.videoFile.absolutePath}"
                        activeDownloadProgressInFiles.progress = 100
                        activeDownloadTitleInFiles.text = readyMsg
                        torrentProgressBar.progress = 100
                        torrentStatusText.text = readyMsg

                        activeDownloadPreviewHolderInFiles.removeAllViews()
                        activeDownloadPreviewHolderInFiles.addView(
                            styledButton("APRI ANTEPRIMA LIBVLC ADESSO", "#16A34A") {
                                openFullscreenVlcDialog(state.videoFile)
                            }
                        )
                        refreshDownloadedAndIncompleteFiles()
                    }
                    is TorrentStreamState.Error -> {
                        activeDownloadTitleInFiles.text = "Errore: ${state.message}"
                        torrentStatusText.text = "Errore: ${state.message}"
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
            filesListContainer.addView(infoLabel("Nessun file presente nelle cartelle Temp o Incoming."))
            return
        }

        allFiles.forEach { file ->
            val isPart = file.parentFile?.name == "Temp" || file.name.endsWith(".part")
            val statusBadge = if (isPart) "INCOMPLETO (TEMP)" else "COMPLETO (INCOMING)"
            val ext = file.extension.uppercase().ifBlank { "FILE" }

            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1C212B"))
                setPadding(24, 20, 24, 20)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 16)
                layoutParams = lp
            }

            val nameView = TextView(this).apply {
                text = "[$ext]  ${file.name}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val infoView = TextView(this).apply {
                text = "Stato: $statusBadge  |  Dimensione: ${file.length() / 1024} KB\nPercorso: ${file.absolutePath}"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 6, 0, 12)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnRow.addView(rowButton("ANTEPRIMA VLC", "#0284C7", 1f) {
                openFullscreenVlcDialog(file)
            })
            btnRow.addView(rowButton("APRI FILE", "#16A34A", 1f) {
                openFileDirectly(file)
            })
            btnRow.addView(rowButton("ELIMINA", "#991B1B", 1f) {
                confirmAndDeleteFile(file)
            })

            rowCard.addView(nameView)
            rowCard.addView(infoView)
            rowCard.addView(btnRow)
            filesListContainer.addView(rowCard)
        }
    }

    private fun confirmAndDeleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Elimina File")
            .setMessage("Vuoi cancellare definitivamente dal dispositivo il file:\n\n${file.name}?")
            .setPositiveButton("ELIMINA") { _, _ ->
                try {
                    vlcManager.stopAndDetach()
                    if (file.delete()) {
                        Toast.makeText(this, "File eliminato: ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                    refreshDownloadedAndIncompleteFiles()
                } catch (e: Exception) {
                    Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openFullscreenVlcDialog(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Il file è ancora a 0 byte, attendi qualche secondo.", Toast.LENGTH_SHORT).show()
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
            setPadding(24, 16, 24, 16)
        }

        val titleText = TextView(this).apply {
            text = file.name
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = rowButton("CHIUDI PLAYER", "#991B1B", 0f) {
            vlcManager.stopAndDetach()
            dialog.dismiss()
        }

        topBar.addView(titleText)
        topBar.addView(btnClose)
        root.addView(topBar)

        val videoContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val vlcLayout = LayoutInflater.from(this)
            .inflate(R.layout.vlc_player_container, videoContainer, false) as VLCVideoLayout
        videoContainer.addView(vlcLayout)
        root.addView(videoContainer)

        val audioStatus = TextView(this).apply {
            text = "Apertura LibVLC: ${file.name} ..."
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(24, 12, 24, 12)
        }
        root.addView(audioStatus)

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
            setPadding(12, 12, 12, 12)
        }

        bottomControls.addView(rowButton("PLAY/PAUSA", "#2563EB", 1f) { vlcManager.togglePlayPause() })
        bottomControls.addView(rowButton("-15s", "#374151", 1f) { vlcManager.seekBy(-15_000L) })
        bottomControls.addView(rowButton("+15s", "#374151", 1f) { vlcManager.seekBy(15_000L) })
        bottomControls.addView(rowButton("16:9", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "16:9") })
        bottomControls.addView(rowButton("FILL", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "FILL") })

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
                        runOnUiThread {
                            audioStatus.text = "Il file non è un flusso audio/video o l'header è ancora incompleto."
                        }
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
        } catch (e: Exception) {
            Toast.makeText(this, "Nessuna app esterna trovata per aprire .$ext", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 5. SCHERMATA mIRC CON PULSANTE TOGGLE CONNETTI (VERDE) <-> DISCONNETTI (ROSSO)
    // =========================================================================
    private fun buildIrcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("1. Connessione Server IRC & Selezione Canali (/list)"))

            val presetServers = listOf(
                "Scegli Server IRC predefinito...",
                "irc.rizon.net",
                "irc.ircgate.it",
                "irc.quartznet.org",
                "irc.chlame.net",
                "irc.abjects.net",
                "irc.libera.chat"
            )

            val serverSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetServers)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position > 0) {
                            ircServerInput.setText(presetServers[position])
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(serverSpinner)

            ircServerInput = styledEditText("irc.rizon.net").apply {
                hint = "Indirizzo Server IRC (es. irc.rizon.net)"
            }
            addView(ircServerInput)

            // RIGA PULSANTI CON TOGGLE VERDE/ROSSO CONNETTI <-> DISCONNETTI
            val connRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnIrcConnectToggle = rowButton("CONNETTI", "#16A34A", 1f) {
                if (isIrcConnectedState || ircEngine != null) {
                    // Se siamo già connessi, premendo il bottone ROSSO ci disconnettiamo e possiamo cambiare server!
                    disconnectIrcCleanly()
                    Toast.makeText(this@MainActivity, "Disconnesso dal server IRC. Ora puoi scegliere un altro server.", Toast.LENGTH_SHORT).show()
                } else {
                    connectToIrcServer(ircServerInput.text.toString().trim(), "")
                }
            }

            connRow.addView(btnIrcConnectToggle)

            connRow.addView(rowButton("SCANSIONA (/LIST)", "#2563EB", 1.1f) {
                if (ircEngine == null) {
                    Toast.makeText(this@MainActivity, "Premi prima CONNETTI!", Toast.LENGTH_SHORT).show()
                } else {
                    scannedChannels.clear()
                    ircEngine?.requestChannelList()
                }
            })

            connRow.addView(rowButton("SCEGLI CANALE", "#0284C7", 1.1f) {
                showScannedChannelsDialog()
            })
            addView(connRow)

            ircActiveChannelsBanner = TextView(this@MainActivity).apply {
                text = "Stato IRC: Disconnesso (Scegli un server e premi il tasto verde CONNETTI)"
                textSize = 12f
                setTextColor(Color.parseColor("#FBBF24"))
                setBackgroundColor(Color.parseColor("#181C24"))
                setPadding(20, 14, 20, 14)
                setTypeface(null, Typeface.BOLD)
            }
            addView(ircActiveChannelsBanner)

            ircLogText = TextView(this@MainActivity).apply {
                text = "Console mIRC pronta."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(20, 18, 20, 18)
                minLines = 7
            }
            addView(ircLogText)

            addView(sectionTitle("2. Barra Comandi Diretti mIRC"))
            val cmdRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            ircCommandInput = EditText(this@MainActivity).apply {
                hint = "Scrivi comando: /list, /join #canale, /msg Bot xdcc send #1"
                textSize = 13f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#6B7280"))
                setBackgroundColor(Color.parseColor("#1E2430"))
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 8, 4)
                }
            }

            val btnSendCmd = rowButton("INVIA", "#16A34A", 0f) {
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
            quickCmdsRow.addView(rowButton("/list", "#374151", 1f) {
                scannedChannels.clear()
                ircEngine?.executeMircCommand("/list")
            })
            quickCmdsRow.addView(rowButton("/names", "#374151", 1f) {
                ircEngine?.executeMircCommand("/names")
            })
            quickCmdsRow.addView(rowButton("!list", "#374151", 1f) {
                ircEngine?.executeMircCommand("!list")
            })
            quickCmdsRow.addView(rowButton("@find ", "#374151", 1f) {
                ircCommandInput.setText("@find ")
                ircCommandInput.setSelection(ircCommandInput.text.length)
            })
            addView(quickCmdsRow)

            ircProgressLabel = infoLabel("Nessun download XDCC in corso")
            ircProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            addView(sectionTitle("3. Cerca File su IRC & Elenco File del Canale"))
            ircSearchInput = styledEditText("").apply {
                hint = "Nome film o file da cercare nei Bot..."
            }
            addView(ircSearchInput)

            val ircSearchBtnsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ircSearchBtnsRow.addView(rowButton("CERCA NEL CANALE ATTIVO", "#0284C7", 1f) {
                val q = ircSearchInput.text.toString().trim()
                if (q.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Scrivi cosa vuoi cercare nel canale", Toast.LENGTH_SHORT).show()
                } else {
                    discoveredChannelPacks.clear()
                    refreshIrcChannelFilesUI()
                    ircEngine?.searchFilesInIrc(q, globalAllChannels = false)
                }
            })
            ircSearchBtnsRow.addView(rowButton("CERCA IN TUTTI I CANALI", "#2563EB", 1f) {
                val q = ircSearchInput.text.toString().trim()
                if (q.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Scrivi cosa vuoi cercare su IRC", Toast.LENGTH_SHORT).show()
                } else {
                    discoveredChannelPacks.clear()
                    refreshIrcChannelFilesUI()
                    if (ircEngine != null) {
                        ircEngine?.searchFilesInIrc(q, globalAllChannels = true)
                    } else {
                        connectToIrcServer(ircServerInput.text.toString().trim(), "") {
                            ircEngine?.searchFilesInIrc(q, globalAllChannels = true)
                        }
                    }
                }
            })
            addView(ircSearchBtnsRow)

            ircChannelFilesContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 0)
            }
            addView(ircChannelFilesContainer)
        }
    }

    private fun setIrcButtonState(connected: Boolean) {
        isIrcConnectedState = connected
        if (connected) {
            btnIrcConnectToggle.text = "DISCONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#DC2626")) // Rosso!
        } else {
            btnIrcConnectToggle.text = "CONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#16A34A")) // Verde!
        }
    }

    private fun disconnectIrcCleanly() {
        ircEngine?.disconnect()
        ircEngine = null
        scannedChannels.clear()
        discoveredChannelPacks.clear()
        refreshIrcChannelFilesUI()
        setIrcButtonState(false)
        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso (Puoi selezionare un altro server e premere CONNETTI)"
        appendIrcLog("Disconnesso dal server IRC.")
    }

    private fun connectToIrcServer(server: String, channel: String, onConnectedAction: (() -> Unit)? = null) {
        val base = File(getBaseMulePath())
        val incomingDir = File(base, "Incoming").apply { mkdirs() }
        val tempDir = File(base, "Temp").apply { mkdirs() }

        ircEngine?.disconnect()
        setIrcButtonState(true) // Diventa subito ROSSO ("DISCONNETTI")

        val engine = IrcXdccEngine(
            server = server,
            port = 6667,
            nickname = "MuleUser" + (100..999).random(),
            incomingDir = incomingDir,
            tempDir = tempDir,
            scope = mainScope,
            useSsl = false,
            autoJoinChannel = channel.takeIf { it.isNotBlank() }
        )
        ircEngine = engine

        mainScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is IrcXdccEngine.Event.Log -> appendIrcLog(event.text)
                    is IrcXdccEngine.Event.Connected -> {
                        setIrcButtonState(true)
                        appendIrcLog("Connesso al server ${event.server}")
                        topStatusBanner.text = "UltimateMule  |  IRC Online: ${event.server}"
                        ircActiveChannelsBanner.text = "Connesso a ${event.server} | Premi SCANSIONA (/LIST) per scegliere un canale"
                        onConnectedAction?.invoke()
                    }
                    is IrcXdccEngine.Event.ChannelJoined -> {
                        val joinedList = event.allJoined.joinToString(", ")
                        ircActiveChannelsBanner.text = "Canale Attivo: ${event.channel}  |  Canali: $joinedList"
                        appendIrcLog("Entrato in ${event.channel}. Caricamento file presenti nel canale...")
                    }
                    is IrcXdccEngine.Event.ChannelMessage ->
                        appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                    is IrcXdccEngine.Event.ChannelListStart -> {
                        scannedChannels.clear()
                        appendIrcLog("Scansione /list in corso su ${event.server}...")
                    }
                    is IrcXdccEngine.Event.ChannelListItem -> {
                        if (scannedChannels.size < 600) scannedChannels.add(event.info)
                    }
                    is IrcXdccEngine.Event.ChannelListEnd -> {
                        scannedChannels.sortByDescending { it.usersCount }
                        appendIrcLog("Fine /list: trovati ${scannedChannels.size} canali.")
                        showScannedChannelsDialog()
                    }
                    is IrcXdccEngine.Event.ChannelPacksDiscovered -> {
                        event.packs.forEach { pack ->
                            discoveredChannelPacks["${pack.botNick}#${pack.packNumber}"] = pack
                        }
                        refreshIrcChannelFilesUI()
                    }
                    is IrcXdccEngine.Event.DccOfferReceived ->
                        appendIrcLog("Avvio DCC SEND: ${event.offer.filename} (${event.offer.size / 1024} KB)")
                    is IrcXdccEngine.Event.DownloadProgress -> {
                        val pct = if (event.total > 0) ((event.received * 100) / event.total).toInt() else 0
                        ircProgressBar.progress = pct
                        val speedKb = event.speedBps / 1024
                        ircProgressLabel.text = "Download XDCC: ${event.filename} - $pct% ($speedKb KB/s)"
                    }
                    is IrcXdccEngine.Event.DownloadComplete -> {
                        ircProgressBar.progress = 100
                        ircProgressLabel.text = "Completato in Incoming: ${event.file.name}"
                        appendIrcLog("File completato: ${event.file.name}")
                    }
                    is IrcXdccEngine.Event.DownloadFailed ->
                        appendIrcLog("Errore DCC: ${event.reason}")
                    is IrcXdccEngine.Event.Disconnected -> {
                        setIrcButtonState(false)
                        ircEngine = null
                        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso"
                        appendIrcLog("Connessione IRC chiusa.")
                    }
                }
            }
        }
        engine.connect()
    }

    private fun showScannedChannelsDialog() {
        if (scannedChannels.isEmpty()) {
            Toast.makeText(this, "Nessun canale in lista. Premi 'SCANSIONA (/LIST)'.", Toast.LENGTH_LONG).show()
            return
        }

        val displayList = scannedChannels.take(250)
        val labels = displayList.map { "${it.channel} (${it.usersCount} utenti) - ${it.topic.take(45)}" }.toTypedArray()
        val checkedItems = BooleanArray(labels.size) { false }

        AlertDialog.Builder(this)
            .setTitle("Seleziona Canali (${displayList.size} disponibili)")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("CONFERMA ED ENTRA") { _, _ ->
                val selected = mutableListOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) selected.add(displayList[i].channel)
                }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Nessun canale selezionato!", Toast.LENGTH_SHORT).show()
                } else {
                    discoveredChannelPacks.clear()
                    ircEngine?.joinMultipleChannels(selected)
                }
            }
            .setNeutralButton("ENTRA NEL 1° CANALE PIÙ POPOLATO") { _, _ ->
                val top1 = displayList.firstOrNull()?.channel
                if (top1 != null) {
                    discoveredChannelPacks.clear()
                    ircEngine?.joinChannel(top1)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun refreshIrcChannelFilesUI() {
        ircChannelFilesContainer.removeAllViews()
        val packs = discoveredChannelPacks.values.toList()

        if (packs.isEmpty()) {
            ircChannelFilesContainer.addView(infoLabel("Nessun file elencato al momento. Entra in un canale o usa la barra di ricerca IRC qui sopra."))
            return
        }

        ircChannelFilesContainer.addView(infoLabel("File trovati nel canale / rete IRC (${packs.size} pacchetti):"))

        packs.take(40).forEach { pack ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1C212B"))
                setPadding(22, 18, 22, 18)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 14)
                layoutParams = lp
            }

            val title = TextView(this).apply {
                text = "[${pack.fileExtension}]  ${pack.filename}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val sub = TextView(this).apply {
                text = "Canale: ${pack.channel}  |  Bot: ${pack.botNick}  |  Pack: #${pack.packNumber}  |  Dim: ${pack.sizeText}"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 6, 0, 10)
            }

            val btnDownload = styledButton("SCARICA DAL BOT (${pack.botNick} #${pack.packNumber})", "#16A34A") {
                val activeSrv = ircEngine?.server.orEmpty()
                if (ircEngine != null && activeSrv.equals(pack.server, ignoreCase = true)) {
                    if (ircEngine?.joinedChannels?.contains(pack.channel) != true) {
                        ircEngine?.joinChannel(pack.channel)
                    }
                    ircEngine?.requestPack(pack.botNick, pack.packNumber)
                } else {
                    startIrcAutomatedDownload(pack.server, pack.channel, pack.botNick, pack.packNumber)
                }
            }

            card.addView(title)
            card.addView(sub)
            card.addView(btnDownload)
            ircChannelFilesContainer.addView(card)
        }
    }

    private fun startIrcAutomatedDownload(server: String, channel: String, bot: String, pack: Int) {
        selectScreen(4)
        ircServerInput.setText(server)
        connectToIrcServer(server, channel) {
            mainScope.launch {
                kotlinx.coroutines.delay(2500L)
                ircEngine?.requestPack(bot, pack)
            }
        }
    }

    private fun appendIrcLog(msg: String) {
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(14)
        ircLogText.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 6. SCHERMATA OPZIONI
    // =========================================================================
    private fun buildSettingsScreen(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Permessi Sistema Android ${Build.VERSION.RELEASE}"))
            addView(styledButton("ATTIVA NOTIFICHE IN BACKGROUND", "#0284C7") { openOrRequestNotifications() })
            addView(styledButton("1. SBLOCCA ACCESSO TOTALE MEMORIA", "#2563EB") { requestAllFilesAccess() })
            addView(styledButton("2. DISATTIVA OTTIMIZZAZIONE BATTERIA", "#2563EB") { disableBatteryOptimization() })

            addView(sectionTitle("Cartella di Salvataggio Assegnata"))
            folderInput = styledEditText(savedPath)
            addView(folderInput)

            addView(styledButton("SALVA E CREA CARTELLE (Incoming / Temp / Config)", "#16A34A") {
                val chosen = folderInput.text.toString().trim()
                prefs.edit().putString("custom_download_path", chosen).apply()
                verifyAndCreateFolders(chosen)
            })

            addView(sectionTitle("Configurazione Avanzata eMule (Porte, Fonti Massime, Connessioni)"))
            val tcpPortInput = styledEditText(prefs.getString("emule_tcp", "4662") ?: "4662")
            val udpPortInput = styledEditText(prefs.getString("emule_udp", "4672") ?: "4672")
            val maxSourcesInput = styledEditText(prefs.getString("emule_max_sources", "1500") ?: "1500")
            val maxConnInput = styledEditText(prefs.getString("emule_max_conn", "500") ?: "500")

            val chkObfuscation = CheckBox(this@MainActivity).apply {
                text = "Abilita Offuscamento Protocollo (Anti-Filtro Operatori)"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
            }
            val chkGzip = CheckBox(this@MainActivity).apply {
                text = "Abilita Compressione Pacchetti GZIP / Zlib"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
            }
            val chkFirstLastChunk = CheckBox(this@MainActivity).apply {
                text = "Scarica prima il primo e l'ultimo pezzo per l'anteprima VLC"
                isChecked = prefs.getBoolean("emule_preview_chunks", true)
                setTextColor(Color.WHITE)
            }

            addView(infoLabel("Porta TCP Client (ID Alto):"))
            addView(tcpPortInput)
            addView(infoLabel("Porta UDP KAD:"))
            addView(udpPortInput)
            addView(infoLabel("Fonti Massime per File:"))
            addView(maxSourcesInput)
            addView(infoLabel("Connessioni Massime simultanee:"))
            addView(maxConnInput)
            addView(chkObfuscation)
            addView(chkGzip)
            addView(chkFirstLastChunk)

            addView(styledButton("SALVA TUTTE LE IMPOSTAZIONI EMULE", "#16A34A") {
                prefs.edit()
                    .putString("emule_tcp", tcpPortInput.text.toString())
                    .putString("emule_udp", udpPortInput.text.toString())
                    .putString("emule_max_sources", maxSourcesInput.text.toString())
                    .putString("emule_max_conn", maxConnInput.text.toString())
                    .putBoolean("emule_obfuscation", chkObfuscation.isChecked)
                    .putBoolean("emule_gzip", chkGzip.isChecked)
                    .putBoolean("emule_preview_chunks", chkFirstLastChunk.isChecked)
                    .apply()
                Toast.makeText(this@MainActivity, "Impostazioni salvate.", Toast.LENGTH_SHORT).show()
            })
        }
    }

    // =========================================================================
    // COMPONENTI UI
    // =========================================================================
    private fun styledEditText(initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(24, 18, 24, 18)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 6, 0, 12)
            layoutParams = lp
        }
    }

    private fun styledButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(18, 16, 18, 16)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun rowButton(label: String, hexColor: String, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(20, 16, 20, 16)
            val lp = if (weight > 0f) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lp.setMargins(4, 6, 4, 6)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 6)
        }
    }

    private fun infoLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 4, 0, 8)
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
            P2PEngineService.updateNotificationStatus(this, "Notifiche attive su Android 16")
            Toast.makeText(this, "Notifiche attive.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyAndCreateFolders(basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            File(baseDir, "Incoming").mkdirs()
            File(baseDir, "Temp").mkdirs()
            File(baseDir, "Config").mkdirs()
            Toast.makeText(this, "Cartelle configurate in: ${baseDir.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Sblocca prima l'accesso alla memoria col pulsante 1.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Accesso memoria già attivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            Toast.makeText(this, "Batteria già sbloccata.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vlcManager.release()
    }
}

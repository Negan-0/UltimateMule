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

    // Contenitori delle 5 schermate principali
    private lateinit var screenNetworks: LinearLayout
    private lateinit var screenSearch: LinearLayout
    private lateinit var screenTransfers: LinearLayout
    private lateinit var screenIrc: LinearLayout
    private lateinit var screenSettings: LinearLayout

    private val navItems = mutableListOf<LinearLayout>()

    // Riferimenti UI
    private lateinit var topStatusBanner: TextView
    private lateinit var folderInput: EditText
    private lateinit var serverListLog: TextView
    private lateinit var searchResultsContainer: LinearLayout
    private lateinit var transfersStatusText: TextView
    private lateinit var transfersProgressBar: ProgressBar
    private lateinit var filesListContainer: LinearLayout

    // Riferimenti mIRC
    private lateinit var ircServerInput: EditText
    private lateinit var ircChannelInput: EditText
    private lateinit var ircCommandInput: EditText
    private lateinit var ircLogText: TextView
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private val scannedChannels = mutableListOf<IrcXdccEngine.IrcChannelInfo>()

    // Motori P2P & Media
    private var ircEngine: IrcXdccEngine? = null
    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var torrentEngine: TorrentStreamEngine
    private val searchEngine = GlobalSearchEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permette l'apertura diretta dei file nella cartella Incoming con app esterne
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        requestNotificationPermissionIfNeeded()

        vlcManager = VlcPlayerManager(this)
        torrentEngine = TorrentStreamEngine(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
        }

        // --- BARRA DI STATO SUPERIORE PULITA (SENZA EMOJI) ---
        topStatusBanner = TextView(this).apply {
            text = "UltimateMule  |  eD2K: In attesa  |  KAD: Pronto  |  IRC: Offline"
            textSize = 13f
            setTextColor(Color.parseColor("#E5E7EB"))
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(32, 26, 32, 26)
            setTypeface(null, Typeface.BOLD)
        }
        rootLayout.addView(topStatusBanner)

        // --- AREA CENTRALE SCORREVOLE ---
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 40)
        }

        screenNetworks = buildNetworksScreen()
        screenSearch = buildSearchScreen()
        screenTransfers = buildTransfersScreen()
        screenIrc = buildIrcScreen()
        screenSettings = buildSettingsScreen()

        contentHolder.addView(screenNetworks)
        contentHolder.addView(screenSearch)
        contentHolder.addView(screenTransfers)
        contentHolder.addView(screenIrc)
        contentHolder.addView(screenSettings)

        contentScroll.addView(contentHolder)
        rootLayout.addView(contentScroll)

        // --- BARRA DI NAVIGAZIONE INFERIORE CON ICONE VETTORIALI ---
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
        bottomNav.addView(createBottomNavItem(R.drawable.ic_transfers, "File & VLC", 2))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_irc, "mIRC", 3))
        bottomNav.addView(createBottomNavItem(R.drawable.ic_settings, "Opzioni", 4))

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
            setPadding(8, 20, 8, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { selectScreen(index) }
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(54, 54)
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 11f
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
        screenTransfers.visibility = if (index == 2) View.VISIBLE else View.GONE
        screenIrc.visibility = if (index == 3) View.VISIBLE else View.GONE
        screenSettings.visibility = if (index == 4) View.VISIBLE else View.GONE

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

        if (index == 2) {
            refreshDownloadedAndIncompleteFiles()
        }
    }

    // =========================================================================
    // 1. SCHERMATA RETI: SERVER eD2K (server.met) + KAD (nodes.dat)
    // =========================================================================
    private fun buildNetworksScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Rete KAD Serverless (nodes.dat)"))
            addView(infoLabel("KAD funziona anche senza server centrali. Connettendola insieme ai Server eD2K ottieni il massimo delle fonti disponibili."))

            val kadUrlInput = styledEditText("http://upd.emule-security.org/nodes.dat")
            addView(kadUrlInput)

            addView(styledButton("CONNETTI RETE KAD (SCARICA NODES.DAT)", "#0284C7") {
                val configDir = File(getBaseMulePath(), "Config")
                val manager = Ed2kKadManager(configDir)
                mainScope.launch {
                    val count = manager.importKadNodesDat(kadUrlInput.text.toString()) { log ->
                        runOnUiThread { appendServerLog(log) }
                    }
                    if (count > 0) {
                        topStatusBanner.text = "UltimateMule  |  KAD: Attiva ($count nodi)  |  eD2K: Pronto"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Rete KAD Attiva ($count nodi)"
                        )
                    }
                }
            })

            addView(sectionTitle("Lista Multipla Server eD2K (OkHttp + GZIP)"))
            addView(infoLabel("Opzionale: importa e unisci più liste server.met contemporaneamente per velocizzare la ricerca iniziale delle fonti."))

            val urlsInput = styledEditText(
                "http://upd.emule-security.org/server.met\n" +
                "http://www.gruk.org/server.met.gz"
            ).apply { minLines = 3 }
            addView(urlsInput)

            addView(styledButton("IMPORTA E UNISCI SERVER.MET (GZIP)", "#2563EB") {
                val urls = urlsInput.text.toString().split("\n")
                val configDir = File(getBaseMulePath(), "Config")
                val manager = Ed2kKadManager(configDir)

                mainScope.launch {
                    appendServerLog("Scaricamento liste server.met con GZIP...")
                    val servers = manager.importMultipleServerMet(urls) { log ->
                        runOnUiThread { appendServerLog(log) }
                    }
                    val totalUsers = servers.sumOf { it.users }
                    topStatusBanner.text = "UltimateMule  |  eD2K: ${servers.size} Server ($totalUsers utenti)  |  KAD: Attiva"
                    P2PEngineService.updateNotificationStatus(
                        this@MainActivity,
                        "eD2K: ${servers.size} Server ($totalUsers utenti) | KAD Attiva"
                    )
                    if (servers.isNotEmpty()) {
                        appendServerLog("\n--- SERVER ED2K ATTIVI ---")
                        servers.take(12).forEach { s ->
                            appendServerLog("${s.name} [${s.ip}:${s.port}] - Utenti: ${s.users}")
                        }
                    }
                }
            })

            serverListLog = TextView(this@MainActivity).apply {
                text = "Console Reti eD2K / KAD pronta."
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(24, 24, 24, 24)
            }
            addView(serverListLog)
        }
    }

    private fun appendServerLog(msg: String) {
        val clean = msg.replace(Regex("[^\\x20-\\x7E\\xA0-\\xFF\\n]"), "")
        val lines = (serverListLog.text.toString().split("\n") + clean).takeLast(20)
        serverListLog.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 2. SCHERMATA CERCA: RICERCA GLOBALE (TORRENT / IRC XDCC / ED2K-KAD)
    // =========================================================================
    private fun buildSearchScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Ricerca Globale File (KAD / Server / IRC XDCC / Torrent)"))
            addView(infoLabel("Cerca per nome file oppure incolla un link diretto (ed2k:// o magnet:?):"))

            val searchInput = styledEditText("").apply {
                hint = "Inserisci nome film, audio, software o link..."
            }
            addView(searchInput)

            val filterSpinner = Spinner(this@MainActivity).apply {
                val options = listOf(
                    "Tutte le Reti (Globale)",
                    "Solo BitTorrent / DHT",
                    "Solo Bot IRC XDCC",
                    "Solo eD2K / KAD"
                )
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setBackgroundColor(Color.parseColor("#262B36"))
            }
            addView(filterSpinner)

            addView(styledButton("AVVIA RICERCA", "#0284C7") {
                val query = searchInput.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Inserisci un termine di ricerca", Toast.LENGTH_SHORT).show()
                    return@styledButton
                }
                val filter = when (filterSpinner.selectedItemPosition) {
                    1 -> GlobalSearchEngine.NetworkType.TORRENT
                    2 -> GlobalSearchEngine.NetworkType.IRC_XDCC
                    3 -> GlobalSearchEngine.NetworkType.ED2K_KAD
                    else -> GlobalSearchEngine.NetworkType.ALL
                }
                performGlobalSearch(query, filter)
            })

            searchResultsContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
            }
            addView(searchResultsContainer)
        }
    }

    private fun performGlobalSearch(query: String, filter: GlobalSearchEngine.NetworkType) {
        searchResultsContainer.removeAllViews()
        searchResultsContainer.addView(infoLabel("Ricerca in corso per \"$query\"..."))

        mainScope.launch {
            val results = searchEngine.search(query, filter)
            searchResultsContainer.removeAllViews()

            if (results.isEmpty()) {
                searchResultsContainer.addView(infoLabel("Nessun risultato trovato per \"$query\"."))
                return@launch
            }

            searchResultsContainer.addView(infoLabel("Trovati ${results.size} risultati (ordinati per fonti disponibili):"))

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

                val netLabel = when (res.network) {
                    GlobalSearchEngine.NetworkType.TORRENT -> "BITTORRENT"
                    GlobalSearchEngine.NetworkType.IRC_XDCC -> "IRC XDCC (${res.ircServer} ${res.ircChannel})"
                    else -> "ED2K / KAD"
                }

                val titleView = TextView(this@MainActivity).apply {
                    text = res.title
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }

                val metaView = TextView(this@MainActivity).apply {
                    text = "Rete: $netLabel  |  Dimensione: ${res.sizeText}  |  Fonti: ${res.sources}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, 6, 0, 12)
                }

                val btnDownload = styledButton("SCARICA E PREPARA ANTEPRIMA", "#16A34A") {
                    when (res.network) {
                        GlobalSearchEngine.NetworkType.IRC_XDCC -> {
                            startIrcAutomatedDownload(res.ircServer, res.ircChannel, res.ircBot, res.ircPack)
                        }
                        else -> {
                            selectScreen(2)
                            torrentEngine.startStream(res.actionPayload)
                        }
                    }
                }

                card.addView(titleView)
                card.addView(metaView)
                card.addView(btnDownload)
                searchResultsContainer.addView(card)
            }
        }
    }

    // =========================================================================
    // 3. SCHERMATA FILE & VLC: TRASFERIMENTI, ANTEPRIMA LIBVLC E APERTURA FILE
    // =========================================================================
    private fun buildTransfersScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Aggiungi Download Diretto (Magnet / URL .torrent)"))
            val directInput = styledEditText("").apply {
                hint = "magnet:?xt=urn:btih:... oppure URL .torrent"
            }
            addView(directInput)

            addView(styledButton("AVVIA SCARICAMENTO", "#0284C7") {
                val input = directInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    torrentEngine.startStream(input)
                } else {
                    Toast.makeText(this@MainActivity, "Inserisci un link valido", Toast.LENGTH_SHORT).show()
                }
            })

            addView(sectionTitle("Stato Trasferimento Attivo"))
            transfersStatusText = infoLabel("Nessun trasferimento torrent attivo.")
            transfersProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(transfersStatusText)
            addView(transfersProgressBar)

            val actionsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            actionsRow.addView(styledButton("FERMA (MANTIENI FILE)", "#374151") {
                torrentEngine.stopKeepFiles()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

            actionsRow.addView(styledButton("ELIMINA CACHE", "#991B1B") {
                torrentEngine.stopAndClearCache()
                refreshDownloadedAndIncompleteFiles()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(actionsRow)

            addView(sectionTitle("Archivio File Scaricati (Incoming) e Incompleti (Temp / .part)"))
            addView(infoLabel("Da qui puoi avviare l'anteprima interna con LibVLC (anche per i file incompleti) oppure aprire direttamente il file:"))

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
            val tag = if (isPart) "[INCOMPLETO / TEMP]" else "[COMPLETO / INCOMING]"

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
                text = file.name
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val infoView = TextView(this).apply {
                text = "$tag  |  ${file.length() / 1024} KB\n${file.absolutePath}"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 6, 0, 12)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnPreviewVlc = styledButton("ANTEPRIMA LIBVLC", "#0284C7") {
                openFullscreenVlcDialog(file)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnOpenDirect = styledButton("APRI FILE", "#16A34A") {
                openFileDirectly(file)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            btnRow.addView(btnPreviewVlc)
            btnRow.addView(btnOpenDirect)

            rowCard.addView(nameView)
            rowCard.addView(infoView)
            rowCard.addView(btnRow)
            filesListContainer.addView(rowCard)
        }
    }

    /**
     * Apre una finestra Player dedicata a tutto schermo che garantisce l'inizializzazione
     * della superficie hardware prima di agganciare LibVLC.
     */
    private fun openFullscreenVlcDialog(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Il file è ancora vuoto (0 byte), attendi qualche secondo.", Toast.LENGTH_SHORT).show()
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

        val btnClose = styledButton("CHIUDI PLAYER", "#991B1B") {
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
            text = "Inizializzazione LibVLC per: ${file.name} ..."
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

        bottomControls.addView(styledButton("PLAY / PAUSA", "#2563EB") {
            vlcManager.togglePlayPause()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        bottomControls.addView(styledButton("-15s", "#374151") {
            vlcManager.seekBy(-15_000L)
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        bottomControls.addView(styledButton("+15s", "#374151") {
            vlcManager.seekBy(15_000L)
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        bottomControls.addView(styledButton("16:9", "#374151") {
            vlcManager.applyAspectRatioAndScale(vlcLayout, "16:9")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        bottomControls.addView(styledButton("FILL", "#374151") {
            vlcManager.applyAspectRatioAndScale(vlcLayout, "FILL")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

        root.addView(bottomControls)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            vlcManager.stopAndDetach()
        }

        dialog.setOnShowListener {
            // Attende che la finestra sia disegnata (dimensioni reali > 0) prima di avviare LibVLC
            vlcLayout.postDelayed({
                vlcManager.attachAndPrepare(
                    layout = vlcLayout,
                    streamUrl = file.absolutePath,
                    onAudioTracksUpdated = { _, _, info ->
                        runOnUiThread { audioStatus.text = "Traccia Audio: $info" }
                    },
                    onError = {
                        runOnUiThread {
                            audioStatus.text = "Formato non video o header incompleto. Riprova tra qualche secondo."
                        }
                    }
                )
            }, 200L)
        }

        dialog.show()
    }

    /**
     * Apre direttamente qualsiasi file scaricato (video, audio, archivio, documento)
     */
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
            Toast.makeText(this, "Impossibile aprire il file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeTorrentEngineState() {
        mainScope.launch {
            torrentEngine.state.collect { state ->
                when (state) {
                    is TorrentStreamState.Idle -> {
                        transfersStatusText.text = "Stato Torrent: Inattivo"
                        transfersProgressBar.progress = 0
                    }
                    is TorrentStreamState.Buffering -> {
                        transfersProgressBar.progress = state.progressPercent
                        transfersStatusText.text = "${state.torrentTitle}\n${state.statusText}  |  ${state.downloadSpeedKbps} KB/s  |  Peer: ${state.peers}"
                        P2PEngineService.updateNotificationStatus(
                            this@MainActivity,
                            "Download: ${state.progressPercent}% (${state.downloadSpeedKbps} KB/s)"
                        )
                    }
                    is TorrentStreamState.Ready -> {
                        transfersProgressBar.progress = 100
                        transfersStatusText.text = "Pronto per la riproduzione: ${state.videoFile.name}"
                        refreshDownloadedAndIncompleteFiles()
                        openFullscreenVlcDialog(state.videoFile)
                    }
                    is TorrentStreamState.Error -> {
                        transfersStatusText.text = "Errore: ${state.message}"
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. SCHERMATA mIRC: COMANDI /LIST, SCANSIONE CANALI E XDCC
    // =========================================================================
    private fun buildIrcScreen(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Client mIRC & Scanner Canali (/list)"))
            addView(infoLabel("Scegli un server IRC predefinito oppure scrivi qualsiasi indirizzo server:"))

            val presetServers = listOf(
                "Seleziona Server IRC rapido...",
                "irc.rizon.net",
                "irc.ircgate.it",
                "irc.quartznet.org",
                "irc.chlame.net",
                "irc.freenode.net",
                "irc.libera.chat",
                "irc.abjects.net"
            )

            val serverSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetServers)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position > 0) {
                            ircServerInput.setText(presetServers[position])
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
            addView(serverSpinner)

            ircServerInput = styledEditText("irc.rizon.net").apply {
                hint = "Indirizzo Server IRC"
            }
            ircChannelInput = styledEditText("").apply {
                hint = "Canale automatico opzionale (es. #canale)"
            }
            addView(ircServerInput)
            addView(ircChannelInput)

            val connRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            connRow.addView(styledButton("CONNETTI SERVER", "#0284C7") {
                connectToIrcServer(
                    ircServerInput.text.toString().trim(),
                    ircChannelInput.text.toString().trim()
                )
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })

            connRow.addView(styledButton("SCANSIONA CANALI (/LIST)", "#2563EB") {
                if (ircEngine == null) {
                    Toast.makeText(this@MainActivity, "Connettiti prima a un server IRC!", Toast.LENGTH_SHORT).show()
                } else {
                    scannedChannels.clear()
                    ircEngine?.requestChannelList()
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(connRow)

            addView(styledButton("APRI LISTA CANALI TROVATI CON /LIST", "#374151") {
                showScannedChannelsDialog()
            })

            // --- BARRA COMANDI mIRC (/list, /join, /msg, /nick, /whois) ---
            addView(sectionTitle("Barra Comandi mIRC"))
            addView(infoLabel("Comandi supportati: /list, /join #canale, /part, /msg NomeBot xdcc send #1, /nick NuovoNick, /whois"))

            val cmdRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ircCommandInput = styledEditText("/list").apply {
                hint = "Scrivi comando (es. /list o /join #canale)"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSendCmd = styledButton("INVIA", "#16A34A") {
                val cmd = ircCommandInput.text.toString().trim()
                if (cmd.equals("/list", ignoreCase = true)) {
                    scannedChannels.clear()
                }
                ircEngine?.executeMircCommand(cmd)
                ircCommandInput.setText("")
            }
            cmdRow.addView(ircCommandInput)
            cmdRow.addView(btnSendCmd)
            addView(cmdRow)

            // --- STATO SCARICAMENTO XDCC + PULSANTE ANTEPRIMA LIVE ---
            ircProgressLabel = infoLabel("Nessun download XDCC attivo")
            ircProgressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            ircLogText = TextView(this@MainActivity).apply {
                text = "Console mIRC pronta. Connettiti a un server e usa /list per esplorare tutti i canali."
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(20, 20, 20, 20)
            }
            addView(ircLogText)
        }
    }

    private fun connectToIrcServer(server: String, channel: String, onConnectedAction: (() -> Unit)? = null) {
        val base = File(getBaseMulePath())
        val incomingDir = File(base, "Incoming").apply { mkdirs() }
        val tempDir = File(base, "Temp").apply { mkdirs() }

        ircEngine?.disconnect()
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
                        appendIrcLog("Connesso a ${event.server}")
                        topStatusBanner.text = "UltimateMule  |  IRC: Online (${event.server})"
                        P2PEngineService.updateNotificationStatus(this@MainActivity, "IRC Connesso: ${event.server}")
                        onConnectedAction?.invoke()
                    }
                    is IrcXdccEngine.Event.ChannelMessage ->
                        appendIrcLog("[${event.target}] <${event.from}> ${event.text}")
                    is IrcXdccEngine.Event.ChannelListStart -> {
                        scannedChannels.clear()
                        appendIrcLog("Ricezione lista canali da ${event.server}...")
                    }
                    is IrcXdccEngine.Event.ChannelListItem -> {
                        if (scannedChannels.size < 500) {
                            scannedChannels.add(event.info)
                        }
                    }
                    is IrcXdccEngine.Event.ChannelListEnd -> {
                        scannedChannels.sortByDescending { it.usersCount }
                        appendIrcLog("Scansione /list completata (${scannedChannels.size} canali). Premi 'APRI LISTA CANALI'.")
                        showScannedChannelsDialog()
                    }
                    is IrcXdccEngine.Event.DccOfferReceived ->
                        appendIrcLog("Ricevuto DCC SEND: ${event.offer.filename} (${event.offer.size / 1024} KB)")
                    is IrcXdccEngine.Event.DownloadProgress -> {
                        val pct = if (event.total > 0) ((event.received * 100) / event.total).toInt() else 0
                        ircProgressBar.progress = pct
                        val speedKb = event.speedBps / 1024
                        ircProgressLabel.text = "Scaricamento: ${event.filename} - $pct% ($speedKb KB/s)"
                    }
                    is IrcXdccEngine.Event.DownloadComplete -> {
                        ircProgressBar.progress = 100
                        ircProgressLabel.text = "Completato: ${event.file.name}"
                        appendIrcLog("File completato in Incoming: ${event.file.name}")
                    }
                    is IrcXdccEngine.Event.DownloadFailed ->
                        appendIrcLog("Errore DCC: ${event.reason}")
                    is IrcXdccEngine.Event.Disconnected ->
                        appendIrcLog("Disconnesso dal server IRC")
                }
            }
        }
        engine.connect()
    }

    private fun showScannedChannelsDialog() {
        if (scannedChannels.isEmpty()) {
            Toast.makeText(this, "Nessun canale in lista. Connettiti e premi 'SCANSIONA CANALI (/LIST)'.", Toast.LENGTH_LONG).show()
            return
        }

        val items = scannedChannels.map { "${it.channel} (${it.usersCount} utenti) - ${it.topic.take(60)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Canali trovati sul Server (${scannedChannels.size}) - Tocca per entrare")
            .setItems(items) { _, which ->
                val chosen = scannedChannels[which].channel
                ircChannelInput.setText(chosen)
                ircEngine?.joinChannel(chosen)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun startIrcAutomatedDownload(server: String, channel: String, bot: String, pack: Int) {
        selectScreen(3)
        ircServerInput.setText(server)
        ircChannelInput.setText(channel)
        connectToIrcServer(server, channel) {
            mainScope.launch {
                kotlinx.coroutines.delay(2500L)
                ircEngine?.requestPack(bot, pack)
            }
        }
    }

    private fun appendIrcLog(msg: String) {
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(18)
        ircLogText.text = lines.joinToString("\n")
    }

    // =========================================================================
    // 5. SCHERMATA OPZIONI: PERMESSI ANDROID 16, CARTELLE E PORTE EMULE
    // =========================================================================
    private fun buildSettingsScreen(): LinearLayout {
        val prefs = getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionTitle("Permessi di Sistema (Android ${Build.VERSION.RELEASE})"))

            addView(styledButton("ATTIVA NOTIFICHE IN BACKGROUND", "#0284C7") {
                openOrRequestNotifications()
            })

            addView(styledButton("1. SBLOCCA ACCESSO TOTALE MEMORIA", "#2563EB") {
                requestAllFilesAccess()
            })

            addView(styledButton("2. DISATTIVA OTTIMIZZAZIONE BATTERIA", "#2563EB") {
                disableBatteryOptimization()
            })

            addView(sectionTitle("Cartella Principale di Salvataggio"))
            folderInput = styledEditText(savedPath)
            addView(folderInput)

            addView(styledButton("SALVA E CREA CARTELLE (Incoming / Temp / Config)", "#16A34A") {
                val chosen = folderInput.text.toString().trim()
                prefs.edit().putString("custom_download_path", chosen).apply()
                verifyAndCreateFolders(chosen)
            })

            addView(sectionTitle("Parametri Rete eMule (Porte TCP / UDP / Compressione)"))
            val tcpPortInput = styledEditText(prefs.getString("emule_tcp", "4662") ?: "4662")
            val udpPortInput = styledEditText(prefs.getString("emule_udp", "4672") ?: "4672")

            val chkObfuscation = CheckBox(this@MainActivity).apply {
                text = "Abilita Offuscamento Protocollo"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
            }
            val chkGzip = CheckBox(this@MainActivity).apply {
                text = "Abilita Compressione Pacchetti GZIP / Zlib"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
            }

            addView(infoLabel("Porta TCP Client:"))
            addView(tcpPortInput)
            addView(infoLabel("Porta UDP KAD:"))
            addView(udpPortInput)
            addView(chkObfuscation)
            addView(chkGzip)

            addView(styledButton("SALVA IMPOSTAZIONI EMULE", "#16A34A") {
                prefs.edit()
                    .putString("emule_tcp", tcpPortInput.text.toString())
                    .putString("emule_udp", udpPortInput.text.toString())
                    .putBoolean("emule_obfuscation", chkObfuscation.isChecked)
                    .putBoolean("emule_gzip", chkGzip.isChecked)
                    .apply()
                Toast.makeText(this@MainActivity, "Configurazione salvata.", Toast.LENGTH_SHORT).show()
            })
        }
    }

    // =========================================================================
    // COMPONENTI UI & UTILITÀ
    // =========================================================================
    private fun styledEditText(initial: String): EditText {
        return EditText(this).apply {
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(24, 20, 24, 20)
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
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(20, 18, 20, 18)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(4, 8, 4, 8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
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
       

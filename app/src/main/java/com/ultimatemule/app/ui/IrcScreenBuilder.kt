package com.ultimatemule.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.MainActivity
import com.ultimatemule.app.irc.IrcXdccEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object IrcScreenBuilder {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var ircServerInput: EditText
    private lateinit var btnIrcConnectToggle: Button
    private lateinit var ircActiveChannelsBanner: TextView
    private lateinit var ircLogText: TextView
    private lateinit var ircCommandInput: EditText
    private lateinit var ircProgressBar: ProgressBar
    private lateinit var ircProgressLabel: TextView
    private lateinit var ircChannelFilesContainer: LinearLayout

    private var isIrcConnectedState = false
    private val scannedChannels = mutableListOf<IrcXdccEngine.IrcChannelInfo>()
    private val discoveredChannelPacks = linkedMapOf<String, IrcXdccEngine.ChannelPackItem>()

    fun build(activity: MainActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(activity, "Client mIRC Protetto (IP Cloaking +ix & Anti-Leak CTCP)"))

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

            val serverSpinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, verifiedServers)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        if (position > 0) ircServerInput.setText(verifiedServers[position])
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(serverSpinner)

            ircServerInput = UiComponents.styledEditText(activity, "irc.rizon.net").apply {
                hint = "Indirizzo Server IRC"
            }
            addView(ircServerInput)

            val connRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnIrcConnectToggle = UiComponents.compactRowButton(activity, "CONNETTI", "#16A34A", 1f) {
                if (isIrcConnectedState || activity.ircEngine != null) {
                    disconnectIrc(activity)
                } else {
                    connectToIrcServer(activity, ircServerInput.text.toString().trim(), "")
                }
            }

            connRow.addView(btnIrcConnectToggle)
            connRow.addView(UiComponents.compactRowButton(activity, "SCANSIONA (/LIST)", "#2563EB", 1.1f) {
                if (activity.ircEngine == null) {
                    Toast.makeText(activity, "Premi prima CONNETTI", Toast.LENGTH_SHORT).show()
                } else {
                    scannedChannels.clear()
                    activity.ircEngine?.requestChannelList()
                }
            })
            connRow.addView(UiComponents.compactRowButton(activity, "CANALI", "#0284C7", 0.9f) {
                showScannedChannelsDialog(activity)
            })
            addView(connRow)

            ircActiveChannelsBanner = TextView(activity).apply {
                text = "Stato IRC: Offline | Protezione IP +ix Pronta"
                textSize = 11f
                setTextColor(Color.parseColor("#FBBF24"))
                setBackgroundColor(Color.parseColor("#181C24"))
                setPadding(16, 12, 16, 12)
                setTypeface(null, Typeface.BOLD)
            }
            addView(ircActiveChannelsBanner)

            ircLogText = TextView(activity).apply {
                text = "Console mIRC pronta. Lo scudo IP (+ix) viene attivato dopo la registrazione sul server."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(16, 14, 16, 14)
                minLines = 7
            }
            addView(ircLogText)

            addView(UiComponents.sectionTitle(activity, "Barra Comandi mIRC"))
            val cmdRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            ircCommandInput = EditText(activity).apply {
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

            val btnSendCmd = UiComponents.compactRowButton(activity, "INVIA", "#16A34A", 0f) {
                val cmd = ircCommandInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    if (cmd.equals("/list", ignoreCase = true)) scannedChannels.clear()
                    activity.ircEngine?.executeMircCommand(cmd)
                    ircCommandInput.setText("")
                }
            }

            cmdRow.addView(ircCommandInput)
            cmdRow.addView(btnSendCmd)
            addView(cmdRow)

            val quickCmdsRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            quickCmdsRow.addView(UiComponents.compactRowButton(activity, "/list", "#374151", 1f) {
                scannedChannels.clear()
                activity.ircEngine?.executeMircCommand("/list")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(activity, "!list", "#374151", 1f) {
                activity.ircEngine?.executeMircCommand("!list")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(activity, "/names", "#374151", 1f) {
                activity.ircEngine?.executeMircCommand("/names")
            })
            quickCmdsRow.addView(UiComponents.compactRowButton(activity, "/part", "#374151", 1f) {
                activity.ircEngine?.executeMircCommand("/part")
            })
            addView(quickCmdsRow)

            ircProgressLabel = UiComponents.infoLabel(activity, "Nessun download XDCC in corso")
            ircProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(ircProgressLabel)
            addView(ircProgressBar)

            addView(UiComponents.sectionTitle(activity, "File Presenti nel Canale"))
            ircChannelFilesContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 0)
            }
            addView(ircChannelFilesContainer)
        }
    }

    fun setIrcButtonState(connected: Boolean) {
        isIrcConnectedState = connected
        if (connected) {
            btnIrcConnectToggle.text = "DISCONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            btnIrcConnectToggle.text = "CONNETTI"
            btnIrcConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))
        }
    }

    fun disconnectIrc(activity: MainActivity) {
        activity.ircEngine?.disconnect()
        activity.ircEngine = null
        scannedChannels.clear()
        setIrcButtonState(false)
        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso"
        appendLog("Disconnesso dal server IRC.")
    }

    fun connectToIrcServer(activity: MainActivity, server: String, channel: String, onConnectedAction: (() -> Unit)? = null) {
        val prefs = activity.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val base = File(UiComponents.getBaseMulePath(activity))
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

        activity.ircEngine?.disconnect()
        setIrcButtonState(true)

        val engine = IrcXdccEngine(
            server = server,
            port = port,
            nickname = customNick,
            incomingDir = incomingDir,
            tempDir = tempDir,
            scope = scope,
            useSsl = useSsl,
            ipCloakMode = ipCloak,
            blockCtcpInfo = blockCtcp,
            socksProxyHost = proxyHost,
            socksProxyPort = proxyPort,
            autoJoinChannel = channel.takeIf { it.isNotBlank() }
        )
        activity.ircEngine = engine

        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is IrcXdccEngine.Event.Log -> appendLog(event.text)
                    is IrcXdccEngine.Event.Connected -> {
                        setIrcButtonState(true)
                        val shield = if (event.cloaked) "IP Protetto (+ix)" else "Connesso"
                        activity.topStatusBanner.text = "UltimateMule  |  IRC: ${event.server} ($shield)"
                        ircActiveChannelsBanner.text = "Connesso a ${event.server} [$shield] | Premi SCANSIONA (/LIST)"
                        onConnectedAction?.invoke()
                    }
                    is IrcXdccEngine.Event.ChannelJoined -> {
                        ircActiveChannelsBanner.text = "Canale: ${event.channel} (IP Protetto +ix)"
                        appendLog("Entrato in ${event.channel}. In ascolto annunci pacchetti...")
                    }
                    is IrcXdccEngine.Event.ChannelMessage ->
                        appendLog("[${event.target}] <${event.from}> ${event.text}")
                    is IrcXdccEngine.Event.ChannelListStart -> {
                        scannedChannels.clear()
                        appendLog("Scansione /list avviata...")
                    }
                    is IrcXdccEngine.Event.ChannelListItem -> {
                        if (scannedChannels.size < 600) scannedChannels.add(event.info)
                    }
                    is IrcXdccEngine.Event.ChannelListEnd -> {
                        scannedChannels.sortByDescending { it.usersCount }
                        showScannedChannelsDialog(activity)
                    }
                    is IrcXdccEngine.Event.ChannelPacksDiscovered -> {
                        event.packs.forEach { pack ->
                            discoveredChannelPacks["${pack.botNick}#${pack.packNumber}"] = pack
                        }
                        refreshIrcChannelFilesUI(activity)
                    }
                    is IrcXdccEngine.Event.DccOfferReceived ->
                        appendLog("DCC SEND: ${event.offer.filename} (${event.offer.size / 1024} KB)")
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
                        appendLog("Errore DCC: ${event.reason}")
                    is IrcXdccEngine.Event.Disconnected -> {
                        setIrcButtonState(false)
                        activity.ircEngine = null
                        ircActiveChannelsBanner.text = "Stato IRC: Disconnesso"
                    }
                }
            }
        }
        engine.connect()
    }

    private fun showScannedChannelsDialog(activity: MainActivity) {
        if (scannedChannels.isEmpty()) {
            Toast.makeText(activity, "Nessun canale in lista. Premi SCANSIONA (/LIST).", Toast.LENGTH_SHORT).show()
            return
        }

        val displayList = scannedChannels.take(250)
        val labels = displayList.map { "${it.channel} (${it.usersCount} ut.) - ${it.topic.take(40)}" }.toTypedArray()
        val checkedItems = BooleanArray(labels.size) { false }

        AlertDialog.Builder(activity)
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
                    activity.ircEngine?.joinMultipleChannels(selected)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun refreshIrcChannelFilesUI(activity: MainActivity) {
        ircChannelFilesContainer.removeAllViews()
        val packs = discoveredChannelPacks.values.toList()

        if (packs.isEmpty()) {
            ircChannelFilesContainer.addView(
                UiComponents.infoLabel(activity, "In attesa di annunci dai Bot del canale...")
            )
            return
        }

        packs.take(35).forEach { pack ->
            val card = LinearLayout(activity).apply {
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

            card.addView(TextView(activity).apply {
                text = "[${pack.fileExtension}]  ${pack.filename}"
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })

            card.addView(TextView(activity).apply {
                text = "Bot: ${pack.botNick}  |  Pack: #${pack.packNumber}  |  Dim: ${pack.sizeText}"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 4, 0, 8)
            })

            card.addView(UiComponents.compactButton(activity, "SCARICA (${pack.botNick} #${pack.packNumber})", "#16A34A") {
                activity.ircEngine?.requestPack(pack.botNick, pack.packNumber)
                Toast.makeText(activity, "Richiesto #${pack.packNumber} a ${pack.botNick}", Toast.LENGTH_SHORT).show()
            })

            ircChannelFilesContainer.addView(card)
        }
    }

    private fun appendLog(msg: String) {
        val lines = (ircLogText.text.toString().split("\n") + msg).takeLast(14)
        ircLogText.text = lines.joinToString("\n")
    }

    fun startIrcAutomatedDownloadInBackground(activity: MainActivity, server: String, channel: String, bot: String, pack: Int) {
        val activeSrv = activity.ircEngine?.server.orEmpty()
        if (activity.ircEngine != null && activeSrv.equals(server, ignoreCase = true)) {
            if (activity.ircEngine?.joinedChannels?.contains(channel) != true) {
                activity.ircEngine?.joinChannel(channel)
            }
            activity.ircEngine?.requestPack(bot, pack)
        } else {
            ircServerInput.setText(server)
            connectToIrcServer(activity, server, channel) {
                scope.launch {
                    kotlinx.coroutines.delay(2500L)
                    activity.ircEngine?.requestPack(bot, pack)
                }
            }
        }
    }
}

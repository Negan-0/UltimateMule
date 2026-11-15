package com.ultimatemule.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.MainActivity
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.service.P2PEngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object NetworksScreenBuilder {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun build(activity: MainActivity): LinearLayout {
        val prefs = activity.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val savedKadUrls = prefs.getString(
            "custom_kad_urls",
            "http://upd.emule-security.org/nodes.dat\nhttp://www.nodes-dat.com/dl.php?load=nodes&trace=1"
        ) ?: ""
        val savedServerUrls = prefs.getString(
            "custom_server_urls",
            "http://upd.emule-security.org/server.met\nhttp://www.server-met.de/dl.php?load=gz&trace=1\nhttp://peerates.net/servers.php"
        ) ?: ""

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(activity, "Modalità di Connessione eMule (eD2K / KAD)"))
            addView(UiComponents.infoLabel(activity, "Scegli se collegarti e scaricare usando Entrambi, Solo Server eD2K o Solo Rete KAD:"))

            val modeSpinner = Spinner(activity).apply {
                val modes = listOf(
                    "Modalità: SERVER eD2K + RETE KAD (Consigliato)",
                    "Modalità: SOLO SERVER eD2K",
                    "Modalità: SOLO RETE KAD (Serverless)"
                )
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, modes)
                setBackgroundColor(Color.parseColor("#262B36"))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        activity.selectedEd2kConnectionMode = position
                        if (!Ed2kKadManager.isConnected) updateConnectButtonLabel(activity)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(modeSpinner)

            val kadUrlsInput = UiComponents.styledEditText(activity, savedKadUrls).apply { minLines = 2 }
            val serverUrlsInput = UiComponents.styledEditText(activity, savedServerUrls).apply { minLines = 3 }

            // Riferimento al pulsante di connessione globale memorizzato nella Activity
            activity.btnGlobalConnectToggle = UiComponents.compactButton(activity, "CONNETTI (SERVER + KAD)", "#16A34A") {
                if (Ed2kKadManager.isConnected) {
                    Ed2kKadManager.disconnectAll()
                    updateConnectButtonLabel(activity)
                    activity.btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#16A34A"))
                    activity.topStatusBanner.text = "UltimateMule  |  Disconnesso"
                    activity.serverListLog.text = "Disconnesso."
                } else {
                    prefs.edit()
                        .putString("custom_kad_urls", kadUrlsInput.text.toString())
                        .putString("custom_server_urls", serverUrlsInput.text.toString())
                        .apply()

                    activity.serverListLog.text = "Connessione in corso..."
                    activity.btnGlobalConnectToggle.text = "CONNESSIONE IN CORSO..."
                    activity.btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#D97706"))

                    val configDir = File(UiComponents.getBaseMulePath(activity), "Config")
                    val manager = Ed2kKadManager(configDir)
                    scope.launch {
                        var serversCount = 0
                        var totalUsers = 0
                        var nodesCount = 0

                        if (activity.selectedEd2kConnectionMode == 0 || activity.selectedEd2kConnectionMode == 1) {
                            val servers = manager.importMultipleServerMet(serverUrlsInput.text.toString().split("\n")) { log ->
                                activity.runOnUiThread { appendLog(activity, log) }
                            }
                            serversCount = servers.size
                            totalUsers = servers.sumOf { it.users }
                            servers.take(12).forEach { s ->
                                appendLog(activity, "- ${s.name} [${s.ip}:${s.port}] - Utenti: ${s.users}")
                            }
                        }

                        if (activity.selectedEd2kConnectionMode == 0 || activity.selectedEd2kConnectionMode == 2) {
                            nodesCount = manager.importMultipleKadNodesDat(kadUrlsInput.text.toString().split("\n")) { log ->
                                activity.runOnUiThread { appendLog(activity, log) }
                            }
                        }

                        activity.btnGlobalConnectToggle.text = "DISCONNETTI RETE ATTIVA"
                        activity.btnGlobalConnectToggle.setBackgroundColor(Color.parseColor("#DC2626"))

                        val summary = when (activity.selectedEd2kConnectionMode) {
                            1 -> "Solo Server eD2K: $serversCount Server ($totalUsers ut.)"
                            2 -> "Solo Rete KAD: $nodesCount Nodi attivi"
                            else -> "eD2K: $serversCount Server ($totalUsers ut.) | KAD: $nodesCount nodi"
                        }
                        activity.topStatusBanner.text = summary
                        P2PEngineService.updateNotificationStatus(activity, summary)
                        appendLog(activity, "=== CONNESSO: $summary ===")
                    }
                }
            }
            addView(activity.btnGlobalConnectToggle)

            activity.serverListLog = TextView(activity).apply {
                text = "Seleziona la modalità qui sopra e premi il tasto verde per connetterti."
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Color.parseColor("#0B0E14"))
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(18, 16, 18, 16)
            }
            addView(activity.serverListLog)

            addView(UiComponents.sectionTitle(activity, "Aggiungi Nuovo Server eD2K Manuale (IP:Porta)"))
            val addSrvRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val customSrvIpPort = UiComponents.styledEditText(activity, "").apply {
                hint = "IP:Porta (es. 176.125.242.230:5555)"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val customSrvName = UiComponents.styledEditText(activity, "").apply {
                hint = "Nome Server"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            val btnAddSrv = UiComponents.compactRowButton(activity, "+ SERVER", "#0284C7", 0f) {
                val raw = customSrvIpPort.text.toString().trim()
                val name = customSrvName.text.toString().trim().ifEmpty { "Server Personalizzato" }
                val parts = raw.split(":")
                val ip = parts.getOrNull(0).orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 4661
                if (ip.count { it == '.' } == 3) {
                    Ed2kKadManager.activeServers["$ip:$port"] =
                        Ed2kKadManager.Ed2kServer(ip, port, name, "Aggiunto dall'utente", 50000, 1000000)
                    appendLog(activity, "Aggiunto server manuale: $name [$ip:$port]")
                    customSrvIpPort.setText("")
                    customSrvName.setText("")
                    Toast.makeText(activity, "Server $name aggiunto!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Formato richiesto: IP:Porta", Toast.LENGTH_SHORT).show()
                }
            }
            addSrvRow.addView(customSrvIpPort)
            addSrvRow.addView(customSrvName)
            addSrvRow.addView(btnAddSrv)
            addView(addSrvRow)

            addView(UiComponents.sectionTitle(activity, "Liste URL server.met & nodes.dat (Uno per riga)"))
            addView(UiComponents.infoLabel(activity, "URL nodes.dat (Nodi KAD):"))
            addView(kadUrlsInput)
            addView(UiComponents.infoLabel(activity, "URL server.met (Liste Server eD2K):"))
            addView(serverUrlsInput)
        }
    }

    private fun updateConnectButtonLabel(activity: MainActivity) {
        activity.btnGlobalConnectToggle.text = when (activity.selectedEd2kConnectionMode) {
            1 -> "CONNETTI SOLO SERVER eD2K"
            2 -> "CONNETTI SOLO RETE KAD"
            else -> "CONNETTI (SERVER + KAD)"
        }
    }

    private fun appendLog(activity: MainActivity, msg: String) {
        val current = activity.serverListLog.text.toString()
        val baseLines = if (current.startsWith("Seleziona la modalità")) emptyList() else current.split("\n")
        val lines = (baseLines + msg).takeLast(20)
        activity.serverListLog.text = lines.joinToString("\n")
    }
}

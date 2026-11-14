package com.ultimatemule.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Environment
import android.os.PowerManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.ultimatemule.app.search.GlobalSearchEngine
import java.io.File

object SettingsScreenBuilder {

    fun build(activity: Activity): LinearLayout {
        val prefs = activity.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        val savedPath = prefs.getString("custom_download_path", defaultPath) ?: defaultPath

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // --- STATO PERMESSI MEMORIA & BATTERIA ---
            addView(UiComponents.sectionTitle(activity, "Permessi Sistema Android (Memoria & Batteria)"))
            val permRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            permRow.addView(UiComponents.compactRowButton(activity, "VERIFICA MEMORIA", "#374151", 1f) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(activity, "Permesso Memoria Totale già attivo!", Toast.LENGTH_SHORT).show()
                } else {
                    OnboardingWizard.requestAllFilesAccess(activity)
                }
            })
            permRow.addView(UiComponents.compactRowButton(activity, "SBLOCCA BATTERIA", "#0284C7", 1f) {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    Toast.makeText(activity, "Batteria già sbloccata (Nessuna restrizione)!", Toast.LENGTH_SHORT).show()
                } else {
                    OnboardingWizard.requestDisableBatteryOptimization(activity)
                }
            })
            addView(permRow)

            // --- CARTELLA DI SALVATAGGIO ---
            addView(UiComponents.sectionTitle(activity, "Cartella Principale di Salvataggio (Incoming / Temp / Config)"))
            val pathRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val folderInput = UiComponents.styledEditText(activity, savedPath).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 4, 6, 4)
                }
            }
            pathRow.addView(folderInput)
            pathRow.addView(UiComponents.compactRowButton(activity, "APPLICA", "#16A34A", 0f) {
                val chosen = folderInput.text.toString().trim()
                prefs.edit().putString("custom_download_path", chosen).apply()
                UiComponents.verifyAndCreateFolders(activity, chosen)
            })
            addView(pathRow)

            // =================================================================
            // SEZIONE RICERCA & SITI TORRENT PERSONALIZZATI
            // =================================================================
            addView(UiComponents.sectionTitle(activity, "Lingua Ricerca & Siti Torrent Manuali (Anti-Cambio Dominio)"))
            addView(UiComponents.infoLabel(activity, "Lingua predefinita per i risultati di ricerca:"))

            val langOptions = listOf(
                "Qualsiasi Lingua (Risultati Globali + ITA se scritto nella ricerca)",
                "Italiano Prioritario (Aggiunge e mette in cima i file ITA)",
                "Solo Italiano (Scarta automaticamente i risultati non ITA)",
                "Inglese Prioritario (ENG)"
            )
            val langSpinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, langOptions)
                setBackgroundColor(Color.parseColor("#262B36"))
                val savedLangIdx = prefs.getInt("search_lang_pref", 0).coerceIn(0, langOptions.size - 1)
                setSelection(savedLangIdx)
            }
            addView(langSpinner)

            addView(UiComponents.infoLabel(activity, "Siti Torrent (uno per riga, usa {query} dove va la parola cercata):"))
            val savedTorrentSites = prefs.getString(
                "custom_torrent_sites",
                GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES
            ) ?: GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES

            val torrentSitesInput = UiComponents.styledEditText(activity, savedTorrentSites).apply {
                minLines = 4
            }
            addView(torrentSitesInput)

            // =================================================================
            // SEZIONE 1: CONFIGURAZIONE COMPLETA eMule (eD2K / KAD)
            // =================================================================
            addView(UiComponents.sectionTitle(activity, "1. Impostazioni Complete eMule (eD2K & KAD)"))
            val emuleTcp = UiComponents.styledEditText(activity, prefs.getString("emule_tcp", "4662") ?: "4662")
            val emuleUdp = UiComponents.styledEditText(activity, prefs.getString("emule_udp", "4672") ?: "4672")
            val emuleMaxSources = UiComponents.styledEditText(activity, prefs.getString("emule_max_sources", "1500") ?: "1500")
            val emuleMaxConn = UiComponents.styledEditText(activity, prefs.getString("emule_max_conn", "500") ?: "500")
            val emuleDlLimit = UiComponents.styledEditText(activity, prefs.getString("emule_dl_limit", "0") ?: "0")
            val emuleUlLimit = UiComponents.styledEditText(activity, prefs.getString("emule_ul_limit", "0") ?: "0")

            addView(UiComponents.twoColumnFields(activity, "Porta TCP Client:", emuleTcp, "Porta UDP KAD:", emuleUdp))
            addView(UiComponents.twoColumnFields(activity, "Fonti Max per File:", emuleMaxSources, "Connessioni Max:", emuleMaxConn))
            addView(UiComponents.twoColumnFields(activity, "Limite Download (KB/s, 0=Inf):", emuleDlLimit, "Limite Upload (KB/s, 0=Inf):", emuleUlLimit))

            val chkEmuleObf = CheckBox(activity).apply {
                text = "Offuscamento Protocollo Criptato (Anti-blocco ISP)"
                isChecked = prefs.getBoolean("emule_obfuscation", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmuleGzip = CheckBox(activity).apply {
                text = "Compressione Pacchetti GZIP / Zlib Nativa"
                isChecked = prefs.getBoolean("emule_gzip", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmulePreview = CheckBox(activity).apply {
                text = "Scarica prima il primo e l'ultimo chunk per anteprima LibVLC"
                isChecked = prefs.getBoolean("emule_preview_chunks", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkEmuleDeadSrv = CheckBox(activity).apply {
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
            addView(UiComponents.sectionTitle(activity, "2. Impostazioni Complete uTorrent (BitTorrent / DHT)"))
            val utMaxConn = UiComponents.styledEditText(activity, prefs.getString("ut_max_conn", "300") ?: "300")
            val utMaxActive = UiComponents.styledEditText(activity, prefs.getString("ut_max_active", "6") ?: "6")
            val utDlLimit = UiComponents.styledEditText(activity, prefs.getString("ut_dl_limit", "0") ?: "0")
            val utUlLimit = UiComponents.styledEditText(activity, prefs.getString("ut_ul_limit", "0") ?: "0")

            addView(UiComponents.twoColumnFields(activity, "Peer Max Globali:", utMaxConn, "Download Attivi Simultanei:", utMaxActive))
            addView(UiComponents.twoColumnFields(activity, "Limite Download (KB/s):", utDlLimit, "Limite Upload (KB/s):", utUlLimit))

            val chkUtDht = CheckBox(activity).apply {
                text = "Abilita Rete DHT Decentralizzata & Peer Exchange (PEX)"
                isChecked = prefs.getBoolean("ut_dht", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkUtSequential = CheckBox(activity).apply {
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
            addView(UiComponents.sectionTitle(activity, "3. Impostazioni Complete mIRC & Scudo Offuscamento IP"))
            val ircNick = UiComponents.styledEditText(activity, prefs.getString("irc_nick", "MuleAnon") ?: "MuleAnon")
            val ircPort = UiComponents.styledEditText(activity, prefs.getString("irc_port", "6667") ?: "6667")
            val ircProxyHost = UiComponents.styledEditText(activity, prefs.getString("irc_proxy_host", "") ?: "").apply {
                hint = "Opzionale: IP Proxy SOCKS5 / Tor (es. 127.0.0.1)"
            }
            val ircProxyPort = UiComponents.styledEditText(activity, prefs.getString("irc_proxy_port", "") ?: "").apply {
                hint = "Porta Proxy (es. 9050)"
            }

            addView(UiComponents.twoColumnFields(activity, "Nickname IRC:", ircNick, "Porta IRC (6667 / 6697):", ircPort))
            addView(UiComponents.twoColumnFields(activity, "Proxy SOCKS5/Tor IP:", ircProxyHost, "Porta SOCKS5/Tor:", ircProxyPort))

            val chkIrcCloak = CheckBox(activity).apply {
                text = "Offuscamento IP Automatico nelle Chat (MODE +ix Host Cloaking)"
                isChecked = prefs.getBoolean("irc_ip_cloak", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkIrcBlockCtcp = CheckBox(activity).apply {
                text = "Blocca Tracciamento Posizione/Sistema (Anti-CTCP Leak)"
                isChecked = prefs.getBoolean("irc_block_ctcp", true)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            val chkIrcSsl = CheckBox(activity).apply {
                text = "Usa Connessione Criptata SSL/TLS (Porta 6697)"
                isChecked = prefs.getBoolean("irc_ssl", false)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(chkIrcCloak)
            addView(chkIrcBlockCtcp)
            addView(chkIrcSsl)

            addView(UiComponents.compactButton(activity, "SALVA TUTTE LE CONFIGURAZIONI (eMule / uTorrent / mIRC)", "#0284C7") {
                prefs.edit()
                    .putInt("search_lang_pref", langSpinner.selectedItemPosition)
                    .putString("custom_torrent_sites", torrentSitesInput.text.toString().trim())
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
                Toast.makeText(activity, "Tutte le impostazioni sono state salvate!", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

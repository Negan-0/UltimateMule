package com.ultimatemule.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import com.ultimatemule.app.search.GlobalSearchEngine
import com.ultimatemule.app.streaming.TorrentStreamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object SearchScreenBuilder {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun build(activity: MainActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(activity, "Ricerca Globale (eMule eD2K/KAD, Siti Torrent & mIRC XDCC)"))
            addView(UiComponents.infoLabel(activity, "I 3 motori lavorano separatamente. Cliccando SCARICA si avvia il motore specifico della rete:"))

            val searchInput = UiComponents.styledEditText(activity, "").apply {
                hint = "Es. The Boys, film 1080p, mp3..."
            }
            addView(searchInput)

            val spinnersRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val netSpinner = Spinner(activity).apply {
                val nets = listOf(
                    "Tutte le Reti (Globale)",
                    "Solo eMule (eD2K / KAD)",
                    "Solo BitTorrent (Siti + DHT)",
                    "Solo mIRC XDCC (Canali + Bot)"
                )
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, nets)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 10)
                }
            }

            val catSpinner = Spinner(activity).apply {
                val cats = listOf(
                    "Tutti i Formati",
                    "Video (MKV/MP4/AVI)",
                    "Audio (MP3/FLAC)",
                    "Archivi (RAR/ZIP/ISO)",
                    "Programmi/Doc (APK/PDF)"
                )
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, cats)
                setBackgroundColor(Color.parseColor("#262B36"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 0, 10)
                }
            }

            spinnersRow.addView(netSpinner)
            spinnersRow.addView(catSpinner)
            addView(spinnersRow)

            val resultsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 0)
            }

            val btnDynamicSearch = UiComponents.compactButton(activity, "RICERCA GLOBALE", "#0284C7") {
                val query = searchInput.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(activity, "Scrivi cosa vuoi cercare", Toast.LENGTH_SHORT).show()
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
                performGlobalSearch(activity, query, netFilter, catFilter, resultsContainer)
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

            addView(resultsContainer)
        }
    }

    private fun performGlobalSearch(
        activity: MainActivity,
        query: String,
        netFilter: GlobalSearchEngine.NetworkType,
        catFilter: GlobalSearchEngine.FileCategory,
        resultsContainer: LinearLayout
    ) {
        resultsContainer.removeAllViews()
        resultsContainer.addView(UiComponents.infoLabel(activity, "Ricerca in corso per \"$query\"..."))

        if (netFilter == GlobalSearchEngine.NetworkType.ALL || netFilter == GlobalSearchEngine.NetworkType.IRC_XDCC) {
            activity.ircEngine?.sendSearchCommandToActiveChannels(query)
        }

        val prefs = activity.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val customSitesRaw = prefs.getString(
            "custom_torrent_sites",
            GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES
        ) ?: GlobalSearchEngine.DEFAULT_CUSTOM_TORRENT_SITES
        val customSiteTemplates = customSitesRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        scope.launch {
            val results = activity.searchEngine.search(
                query = query,
                netFilter = netFilter,
                catFilter = catFilter,
                customTorrentSiteTemplates = customSiteTemplates
            )
            resultsContainer.removeAllViews()

            if (results.isEmpty()) {
                resultsContainer.addView(UiComponents.infoLabel(activity, "Nessun file trovato per \"$query\"."))
                return@launch
            }

            resultsContainer.addView(UiComponents.infoLabel(activity, "Trovati ${results.size} file disponibili:"))

            results.forEach { res ->
                val card = LinearLayout(activity).apply {
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

                val titleView = TextView(activity).apply {
                    text = "[$netTag | ${res.fileExtension}]  ${res.title}"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(null, Typeface.BOLD)
                }

                val detailsView = TextView(activity).apply {
                    text = "Dim: ${res.sizeText}  |  Fonti: ${res.sources}  |  Rete: ${res.providerName}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, 6, 0, 10)
                }

                val btnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                btnRow.addView(UiComponents.compactRowButton(activity, "ISPEZIONA", "#374151", 1f) {
                    AlertDialog.Builder(activity)
                        .setTitle("Ispezione [${res.fileExtension}]")
                        .setMessage(res.inspectionSummary)
                        .setPositiveButton("SCARICA CON ${netTag.uppercase()}") { _, _ ->
                            FilesVlcScreenBuilder.dispatchDownload(activity, res)
                        }
                        .setNegativeButton("Chiudi", null)
                        .show()
                })

                btnRow.addView(UiComponents.compactRowButton(activity, "SCARICA ($netTag)", "#16A34A", 1f) {
                    FilesVlcScreenBuilder.dispatchDownload(activity, res)
                })

                card.addView(titleView)
                card.addView(detailsView)
                card.addView(btnRow)
                resultsContainer.addView(card)
            }
        }
    }
}

package com.ultimatemule.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
                performGlobalSearch(activity, query, netFilter, catFilter)
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

            // Contenitore dinamico dei risultati memorizzato in un tag o gestito direttamente
            tag = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 0)
            }
            addView(tag as View)
        }
    }

    private fun performGlobalSearch(
        activity: MainActivity,
        query: String,
        netFilter: GlobalSearchEngine.NetworkType,
        catFilter: GlobalSearchEngine.FileCategory
    ) {
        val rootLayout = activity.findViewById<LinearLayout>(android.R.id.content) // o recuperato dal contesto
        // Troviamo il contenitore dei risultati all'interno della schermata di ricerca
        // (Alternativa pulita: passiamo il container o lo salviamo nella Activity)
    }
}

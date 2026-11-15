package com.ultimatemule.app.ui

import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.MainActivity

object TorrentScreenBuilder {

    lateinit var torrentStatusText: TextView
    lateinit var torrentProgressBar: ProgressBar

    fun build(activity: MainActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(UiComponents.sectionTitle(activity, "Aggiungi Link Magnet / URL .torrent / InfoHash"))
            val directInput = UiComponents.styledEditText(activity, "").apply {
                hint = "magnet:?xt=urn:btih:... oppure URL .torrent"
                minLines = 2
            }
            addView(directInput)

            addView(UiComponents.compactButton(activity, "AVVIA SCARICAMENTO TORRENT", "#0284C7") {
                val input = directInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    FilesVlcScreenBuilder.activeDownloadBoxInFiles?.visibility = android.view.View.VISIBLE
                    activity.torrentEngine.startStream(input)
                    Toast.makeText(activity, "Scaricamento BitTorrent avviato!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Inserisci un link Magnet o Torrent", Toast.LENGTH_SHORT).show()
                }
            })

            addView(UiComponents.sectionTitle(activity, "Stato Motore BitTorrent"))
            torrentStatusText = UiComponents.infoLabel(activity, "Stato: Inattivo")
            torrentProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            addView(torrentStatusText)
            addView(torrentProgressBar)

            val ctrlRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ctrlRow.addView(UiComponents.compactRowButton(activity, "FERMA (MANTIENI)", "#374151", 1f) {
                activity.torrentEngine.stopKeepFiles()
            })
            ctrlRow.addView(UiComponents.compactRowButton(activity, "ELIMINA CACHE", "#991B1B", 1f) {
                activity.torrentEngine.stopAndClearCache()
            })
            addView(ctrlRow)
        }
    }
}

package com.ultimatemule.app.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.MainActivity
import com.ultimatemule.app.ed2k.Ed2kKadManager
import com.ultimatemule.app.ed2k.Ed2kTransferState
import com.ultimatemule.app.search.GlobalSearchEngine
import com.ultimatemule.app.streaming.TorrentStreamState
import java.io.File

object FilesVlcScreenBuilder {

    var activeEd2kBoxInFiles: LinearLayout? = null
    lateinit var activeEd2kTitleInFiles: TextView
    lateinit var activeEd2kProgressInFiles: ProgressBar
    lateinit var activeEd2kPreviewHolder: LinearLayout

    var activeDownloadBoxInFiles: LinearLayout? = null
    lateinit var activeDownloadTitleInFiles: TextView
    lateinit var activeDownloadProgressInFiles: ProgressBar
    lateinit var activeDownloadPreviewHolderInFiles: LinearLayout
    lateinit var filesListContainer: LinearLayout

    fun build(activity: MainActivity): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            // --- BOX 1: MOTORE EMULE ---
            activeEd2kBoxInFiles = LinearLayout(activity).apply {
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

            activeEd2kBoxInFiles?.addView(TextView(activity).apply {
                text = "MOTORE EMULE (eD2K / KAD)"
                textSize = 12f
                setTextColor(Color.parseColor("#4ADE80"))
                setTypeface(null, Typeface.BOLD)
            })

            activeEd2kTitleInFiles = UiComponents.infoLabel(activity, "Nessun trasferimento eMule attivo.")
            activeEd2kProgressInFiles = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            activeEd2kPreviewHolder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val ed2kCtrlRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            ed2kCtrlRow.addView(UiComponents.compactRowButton(activity, "PAUSA / MANTIENI .PART", "#374151", 1f) {
                Ed2kKadManager.stopCurrentDownload(deletePart = false)
                refreshFiles(activity)
            })
            ed2kCtrlRow.addView(UiComponents.compactRowButton(activity, "ANNULLA ED ELIMINA", "#991B1B", 1f) {
                Ed2kKadManager.stopCurrentDownload(deletePart = true)
                activeEd2kPreviewHolder.removeAllViews()
                refreshFiles(activity)
            })

            activeEd2kBoxInFiles?.addView(activeEd2kTitleInFiles)
            activeEd2kBoxInFiles?.addView(activeEd2kProgressInFiles)
            activeEd2kBoxInFiles?.addView(activeEd2kPreviewHolder)
            activeEd2kBoxInFiles?.addView(ed2kCtrlRow)
            addView(activeEd2kBoxInFiles)

            // --- BOX 2: MOTORE BITTORRENT ---
            activeDownloadBoxInFiles = LinearLayout(activity).apply {
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

            activeDownloadBoxInFiles?.addView(TextView(activity).apply {
                text = "MOTORE BITTORRENT (DHT / MAGNET)"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, Typeface.BOLD)
            })

            activeDownloadTitleInFiles = UiComponents.infoLabel(activity, "Nessun download BitTorrent attivo.")
            activeDownloadProgressInFiles = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }

            activeDownloadPreviewHolderInFiles = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val dlCtrlRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            dlCtrlRow.addView(UiComponents.compactRowButton(activity, "PAUSA / MANTIENI", "#374151", 1f) {
                activity.torrentEngine.stopKeepFiles()
                refreshFiles(activity)
            })
            dlCtrlRow.addView(UiComponents.compactRowButton(activity, "ANNULLA ED ELIMINA", "#991B1B", 1f) {
                activity.torrentEngine.stopAndClearCache()
                activeDownloadPreviewHolderInFiles.removeAllViews()
                refreshFiles(activity)
            })

            activeDownloadBoxInFiles?.addView(activeDownloadTitleInFiles)
            activeDownloadBoxInFiles?.addView(activeDownloadProgressInFiles)
            activeDownloadBoxInFiles?.addView(activeDownloadPreviewHolderInFiles)
            activeDownloadBoxInFiles?.addView(dlCtrlRow)
            addView(activeDownloadBoxInFiles)

            addView(UiComponents.sectionTitle(activity, "Archivio File Scaricati (Incoming) e Incompleti (Temp)"))

            addView(UiComponents.compactButton(activity, "AGGIORNA ELENCO FILE", "#2563EB") {
                refreshFiles(activity)
            })

            filesListContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 0)
            }
            addView(filesListContainer)
        }
    }

    fun dispatchDownload(activity: MainActivity, res: GlobalSearchEngine.SearchResult) {
        val baseDir = File(UiComponents.getBaseMulePath(activity))
        val tempDir = File(baseDir, "Temp").apply { mkdirs() }
        val incomingDir = File(baseDir, "Incoming").apply { mkdirs() }

        when (res.network) {
            GlobalSearchEngine.NetworkType.ED2K_KAD -> {
                activeEd2kBoxInFiles?.visibility = View.VISIBLE
                activity.ed2kManager.startEd2kDownload(
                    ed2kLink = res.actionPayload,
                    tempDir = tempDir,
                    incomingDir = incomingDir
                )
                Toast.makeText(activity, "Avviato nel motore eMule (eD2K/KAD): ${res.title}", Toast.LENGTH_LONG).show()
            }
            GlobalSearchEngine.NetworkType.TORRENT -> {
                activeDownloadBoxInFiles?.visibility = View.VISIBLE
                activity.torrentEngine.startStream(res.actionPayload)
                Toast.makeText(activity, "Avviato nel motore BitTorrent: ${res.title}", Toast.LENGTH_LONG).show()
            }
            GlobalSearchEngine.NetworkType.IRC_XDCC -> {
                IrcScreenBuilder.startIrcAutomatedDownloadInBackground(activity, res.ircServer, res.ircChannel, res.ircBot, res.ircPack)
                Toast.makeText(activity, "Avviato nel motore mIRC XDCC: Pacchetto #${res.ircPack} da ${res.ircBot}", Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    fun updateEd2kState(activity: MainActivity, state: Ed2kTransferState) {
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
                        UiComponents.compactButton(activity, "ANTEPRIMA LIBVLC (.PART)", "#0284C7") {
                            UiComponents.openFullscreenVlcDialog(activity, activity.vlcManager, state.partFile)
                        }
                    )
                }
            }
            is Ed2kTransferState.Completed -> {
                activeEd2kProgressInFiles.progress = 100
                activeEd2kTitleInFiles.text = "Completato in Incoming: ${state.fileName}"
                activeEd2kPreviewHolder.removeAllViews()
                activeEd2kPreviewHolder.addView(
                    UiComponents.compactButton(activity, "APRI CON LIBVLC", "#16A34A") {
                        UiComponents.openFullscreenVlcDialog(activity, activity.vlcManager, state.finalFile)
                    }
                )
                refreshFiles(activity)
            }
            is Ed2kTransferState.Error -> {
                activeEd2kTitleInFiles.text = state.message
            }
        }
    }

    fun updateTorrentState(activity: MainActivity, state: TorrentStreamState) {
        when (state) {
            is TorrentStreamState.Idle -> {
                activeDownloadTitleInFiles.text = "Nessun download BitTorrent attivo."
                activeDownloadProgressInFiles.progress = 0
                TorrentScreenBuilder.torrentStatusText.text = "Stato: Inattivo"
                TorrentScreenBuilder.torrentProgressBar.progress = 0
            }
            is TorrentStreamState.Buffering -> {
                val info = "${state.torrentTitle}\n${state.statusText} | ${state.downloadSpeedKbps} KB/s | Peer: ${state.peers}"
                activeDownloadProgressInFiles.progress = state.progressPercent
                activeDownloadTitleInFiles.text = info
                TorrentScreenBuilder.torrentProgressBar.progress = state.progressPercent
                TorrentScreenBuilder.torrentStatusText.text = info
            }
            is TorrentStreamState.Ready -> {
                val readyMsg = "Pronto per l'anteprima: ${state.videoFile.name}"
                activeDownloadProgressInFiles.progress = 100
                activeDownloadTitleInFiles.text = readyMsg
                TorrentScreenBuilder.torrentProgressBar.progress = 100
                TorrentScreenBuilder.torrentStatusText.text = readyMsg

                activeDownloadPreviewHolderInFiles.removeAllViews()
                activeDownloadPreviewHolderInFiles.addView(
                    UiComponents.compactButton(activity, "APRI ANTEPRIMA LIBVLC", "#16A34A") {
                        UiComponents.openFullscreenVlcDialog(activity, activity.vlcManager, state.videoFile)
                    }
                )
                refreshFiles(activity)
            }
            is TorrentStreamState.Error -> {
                activeDownloadTitleInFiles.text = state.message
                TorrentScreenBuilder.torrentStatusText.text = state.message
            }
        }
    }

    fun refreshFiles(activity: MainActivity) {
        filesListContainer.removeAllViews()
        val base = File(UiComponents.getBaseMulePath(activity))
        val tempFiles = File(base, "Temp").walkTopDown().filter { it.isFile }.toList()
        val incomingFiles = File(base, "Incoming").walkTopDown().filter { it.isFile }.toList()
        val allFiles = (tempFiles + incomingFiles).sortedByDescending { it.lastModified() }

        if (allFiles.isEmpty()) {
            filesListContainer.addView(UiComponents.infoLabel(activity, "Nessun file presente in Temp o Incoming."))
            return
        }

        allFiles.forEach { file ->
            val isPart = file.parentFile?.name == "Temp" || file.name.endsWith(".part")
            val statusBadge = if (isPart) "TEMP (.PART)" else "INCOMING"
            val ext = file.extension.uppercase().ifBlank { "FILE" }

            val rowCard = LinearLayout(activity).apply {
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

            rowCard.addView(TextView(activity).apply {
                text = "[$ext]  ${file.name}"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })

            rowCard.addView(TextView(activity).apply {
                text = "$statusBadge  |  ${file.length() / 1024} KB"
                textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 4, 0, 8)
            })

            val btnRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(UiComponents.compactRowButton(activity, "ANTEPRIMA VLC", "#0284C7", 1f) {
                UiComponents.openFullscreenVlcDialog(activity, activity.vlcManager, file)
            })
            btnRow.addView(UiComponents.compactRowButton(activity, "APRI", "#16A34A", 1f) {
                UiComponents.openFileDirectly(activity, activity.vlcManager, file)
            })
            btnRow.addView(UiComponents.compactRowButton(activity, "ELIMINA", "#991B1B", 1f) {
                confirmAndDeleteFile(activity, file)
            })

            rowCard.addView(btnRow)
            filesListContainer.addView(rowCard)
        }
    }

    private fun confirmAndDeleteFile(activity: MainActivity, file: File) {
        AlertDialog.Builder(activity)
            .setTitle("Elimina File")
            .setMessage("Eliminare definitivamente:\n${file.name}?")
            .setPositiveButton("ELIMINA") { _, _ ->
                activity.vlcManager.stopAndDetach()
                if (file.delete()) Toast.makeText(activity, "Eliminato", Toast.LENGTH_SHORT).show()
                refreshFiles(activity)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}

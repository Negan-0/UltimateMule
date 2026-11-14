package com.ultimatemule.app.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * Motore di Anteprima Video basato su LibVLC.
 * Configurato per non bloccarsi se il file .part ha dei pezzi (chunk) ancora mancanti.
 */
class VlcPreviewPlayer(context: Context, private val surfaceView: SurfaceView) {

    private val libVlc: LibVLC
    private val mediaPlayer: MediaPlayer

    init {
        val options = arrayListOf(
            "--file-caching=2500",
            "--network-caching=2500",
            "--no-drop-late-frames",
            "--no-skip-frames"
        )
        libVlc = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVlc)
    }

    fun playFileOrPart(file: File) {
        stop()
        val vout = mediaPlayer.vlcVout
        vout.setVideoView(surfaceView)
        vout.attachViews()

        val media = Media(libVlc, Uri.fromFile(file)).apply {
            addOption(":file-caching=2500")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    fun stop() {
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer.vlcVout.detachViews()
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        mediaPlayer.release()
        libVlc.release()
    }
}

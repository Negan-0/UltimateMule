package com.ultimatemule.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ultimatemule.app.R
import com.ultimatemule.app.player.VlcPlayerManager
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

object UiComponents {

    fun styledEditText(context: Context, initial: String): EditText {
        return EditText(context).apply {
            setText(initial)
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.parseColor("#1E2430"))
            setPadding(18, 12, 18, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 4, 0, 8)
            layoutParams = lp
        }
    }

    fun compactButton(context: Context, label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(16, 12, 16, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 6, 0, 6)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    fun compactRowButton(
        context: Context,
        label: String,
        hexColor: String,
        weight: Float,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            textSize = 10f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(14, 12, 14, 12)
            val lp = if (weight > 0f) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lp.setMargins(4, 4, 4, 4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        }
    }

    fun infoLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 2, 0, 4)
        }
    }

    fun twoColumnFields(
        context: Context,
        label1: String,
        input1: EditText,
        label2: String,
        input2: EditText
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val col1 = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 0)
                }
                addView(infoLabel(context, label1))
                addView(input1)
            }
            val col2 = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 0, 0)
                }
                addView(infoLabel(context, label2))
                addView(input2)
            }
            addView(col1)
            addView(col2)
        }
    }

    fun createBottomNavItem(
        context: Context,
        iconRes: Int,
        label: String,
        index: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(4, 14, 4, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onSelect(index) }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(44, 44)
        }

        val text = TextView(context).apply {
            this.text = label
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }

        item.addView(icon)
        item.addView(text)
        return item
    }

    fun getBaseMulePath(context: Context): String {
        val prefs = context.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)
        val def = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
        return prefs.getString("custom_download_path", def) ?: def
    }

    fun verifyAndCreateFolders(context: Context, basePath: String) {
        if (Environment.isExternalStorageManager()) {
            val baseDir = File(basePath)
            File(baseDir, "Incoming").mkdirs()
            File(baseDir, "Temp").mkdirs()
            File(baseDir, "Config").mkdirs()
            Toast.makeText(context, "Cartelle attive in: ${baseDir.absolutePath}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openFullscreenVlcDialog(activity: Activity, vlcManager: VlcPlayerManager, file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(activity, "File ancora a 0 byte, attendi qualche secondo.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181C24"))
            setPadding(20, 12, 20, 12)
        }

        topBar.addView(TextView(activity).apply {
            text = file.name
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        topBar.addView(compactRowButton(activity, "CHIUDI", "#991B1B", 0f) {
            vlcManager.stopAndDetach()
            dialog.dismiss()
        })
        root.addView(topBar)

        val videoContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val vlcLayout = LayoutInflater.from(activity)
            .inflate(R.layout.vlc_player_container, videoContainer, false) as VLCVideoLayout
        videoContainer.addView(vlcLayout)
        root.addView(videoContainer)

        val audioStatus = TextView(activity).apply {
            text = "Riproduzione LibVLC: ${file.name}"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(20, 10, 20, 10)
        }
        root.addView(audioStatus)

        val bottomControls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#181C24"))
            setPadding(10, 8, 10, 8)
        }

        bottomControls.addView(compactRowButton(activity, "PLAY/PAUSA", "#2563EB", 1f) { vlcManager.togglePlayPause() })
        bottomControls.addView(compactRowButton(activity, "-15s", "#374151", 1f) { vlcManager.seekBy(-15_000L) })
        bottomControls.addView(compactRowButton(activity, "+15s", "#374151", 1f) { vlcManager.seekBy(15_000L) })
        bottomControls.addView(compactRowButton(activity, "16:9", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "16:9") })
        bottomControls.addView(compactRowButton(activity, "FILL", "#374151", 1f) { vlcManager.applyAspectRatioAndScale(vlcLayout, "FILL") })

        root.addView(bottomControls)

        dialog.setContentView(root)
        dialog.setOnDismissListener { vlcManager.stopAndDetach() }

        dialog.setOnShowListener {
            vlcLayout.postDelayed({
                vlcManager.attachAndPrepare(
                    layout = vlcLayout,
                    streamUrl = file.absolutePath,
                    onAudioTracksUpdated = { _, _, info ->
                        activity.runOnUiThread { audioStatus.text = "Traccia Audio: $info" }
                    },
                    onError = {
                        activity.runOnUiThread { audioStatus.text = "Formato non video o header ancora incompleto." }
                    }
                )
            }, 200L)
        }

        dialog.show()
    }

    fun openFileDirectly(activity: Activity, vlcManager: VlcPlayerManager, file: File) {
        val ext = file.extension.lowercase()
        val videoExts = setOf("mp4", "mkv", "avi", "ts", "webm", "mov", "m4v", "flv", "mpg", "mpeg", "part")
        if (ext in videoExts) {
            openFullscreenVlcDialog(activity, vlcManager, file)
            return
        }

        try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Apri ${file.name}"))
        } catch (_: Exception) {
            Toast.makeText(activity, "Nessuna app trovata per aprire .$ext", Toast.LENGTH_SHORT).show()
        }
    }
}

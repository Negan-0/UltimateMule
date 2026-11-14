package com.ultimatemule.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import java.io.File

class OnboardingWizard(private val activity: Activity) {

    private var isWizardDialogShowing = false

    fun checkAndRunNextStep() {
        if (isWizardDialogShowing || activity.isFinishing) return

        val prefs = activity.getSharedPreferences("UltimateMulePrefs", Context.MODE_PRIVATE)

        // PASSO 1: Accesso completo a tutti i file (Android 11 - Android 16+)
        if (!Environment.isExternalStorageManager()) {
            isWizardDialogShowing = true
            AlertDialog.Builder(activity)
                .setTitle("Passo 1 di 3: Sblocco Memoria")
                .setMessage("Benvenuto in UltimateMule.\nConcedi l'accesso ai file per salvare e leggere liberamente le cartelle Incoming, Temp e Config nella memoria interna.")
                .setCancelable(false)
                .setPositiveButton("SBLOCCA ORA") { _, _ ->
                    isWizardDialogShowing = false
                    requestAllFilesAccess(activity)
                }
                .show()
            return
        }

        // PASSO 2: Scelta e creazione della cartella Incoming / Temp / Config
        if (!prefs.getBoolean("wizard_folder_configured", false)) {
            isWizardDialogShowing = true
            val defaultPath = File(Environment.getExternalStorageDirectory(), "UltimateMule").absolutePath
            val input = UiComponents.styledEditText(
                activity,
                prefs.getString("custom_download_path", defaultPath) ?: defaultPath
            )
            AlertDialog.Builder(activity)
                .setTitle("Passo 2 di 3: Cartella di Salvataggio")
                .setMessage("Scegli o conferma il percorso dove creare le cartelle Incoming, Temp e Config:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("CONFERMA E CREA") { _, _ ->
                    val chosen = input.text.toString().trim().ifEmpty { defaultPath }
                    prefs.edit()
                        .putString("custom_download_path", chosen)
                        .putBoolean("wizard_folder_configured", true)
                        .apply()
                    UiComponents.verifyAndCreateFolders(activity, chosen)
                    isWizardDialogShowing = false
                    checkAndRunNextStep()
                }
                .show()
            return
        }

        // PASSO 3: Sblocco Ottimizzazione Batteria (Fondamentale per mIRC, KAD e Torrent in background)
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(activity.packageName)
        if (!isIgnoringBattery && !prefs.getBoolean("wizard_battery_prompted", false)) {
            isWizardDialogShowing = true
            AlertDialog.Builder(activity)
                .setTitle("Passo 3 di 3: Sblocco Batteria Background")
                .setMessage("Per evitare che Android disconnetta i server mIRC, la coda eMule e i download Torrent a schermo spento, consenti l'esecuzione senza restrizioni batteria.")
                .setCancelable(false)
                .setPositiveButton("SBLOCCA BATTERIA") { _, _ ->
                    prefs.edit().putBoolean("wizard_battery_prompted", true).apply()
                    isWizardDialogShowing = false
                    requestDisableBatteryOptimization(activity)
                }
                .setNegativeButton("IGNORA") { _, _ ->
                    prefs.edit().putBoolean("wizard_battery_prompted", true).apply()
                    isWizardDialogShowing = false
                    checkAndRunNextStep()
                }
                .show()
            return
        }

        // PASSO 4: Permesso Notifiche per il Servizio P2P in primo piano
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("wizard_notif_prompted", false)
            ) {
                prefs.edit().putBoolean("wizard_notif_prompted", true).apply()
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    companion object {
        fun requestAllFilesAccess(activity: Activity) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${activity.packageName}")
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }

        fun requestDisableBatteryOptimization(activity: Activity) {
            try {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }
}

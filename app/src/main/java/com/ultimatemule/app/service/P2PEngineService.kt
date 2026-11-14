package com.ultimatemule.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ultimatemule.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class P2PEngineService : Service() {

    private val serviceJob = SupervisorJob()
    val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val CHANNEL_ID = "ULTIMATE_MULE_LIVE_CHANNEL"
        const val NOTIF_ID = 1001
        const val ACTION_UPDATE_STATUS = "com.ultimatemule.app.UPDATE_STATUS"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun updateNotificationStatus(context: Context, statusText: String) {
            val intent = Intent(context, P2PEngineService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startProtectedForeground("Reti KAD | Server eD2K | IRC | Torrent pronte")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
        if (!newText.isNullOrBlank()) {
            startProtectedForeground(newText)
        }
        return START_STICKY
    }

    private fun startProtectedForeground(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stato Coda e Download UltimateMule",
                NotificationManager.IMPORTANCE_DEFAULT // Visibile su Android 16!
            ).apply {
                description = "Mostra lo stato di connessione KAD, Server e velocità di download"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐴 UltimateMule P2P Attivo")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

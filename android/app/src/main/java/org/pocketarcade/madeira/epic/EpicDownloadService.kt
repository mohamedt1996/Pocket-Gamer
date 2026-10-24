// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.ui.MadeiraActivity

class EpicDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val model get() = AppGraph.instance.epic
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(): Notification {
        val progress = model.progress.value
        val percent = progress?.let { if (it.total > 0) ((it.done * 100) / it.total).toInt().coerceIn(0, 100) else 0 } ?: 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MadeiraActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, EpicDownloadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "epic-downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Epic Games download").setContentText(if (progress == null) "Preparing…" else "$percent% · ${progress.file}")
            .setProgress(100, percent, progress == null).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") { model.cancelDownload(); job?.cancel(); stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val app = intent?.getStringExtra("app") ?: return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("epic-downloads", "Epic downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(41, notification())
        job = scope.launch {
            val updates = launch { while (true) { delay(1000); manager.notify(41, notification()) } }
            try { model.download(app) }
            finally { updates.cancel(); stopForeground(true); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
}

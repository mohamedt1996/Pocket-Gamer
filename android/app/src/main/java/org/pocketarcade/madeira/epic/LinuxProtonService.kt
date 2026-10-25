// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps an optional Box64/Proton session alive while its X11 window is in front. */
class LinuxProtonService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { active.set(false); process?.destroy(); stopSelf(); return START_NOT_STICKY }
        val plan = intent?.getStringExtra(PLAN)?.let(LinuxProton.Plan::fromJson) ?: return START_NOT_STICKY
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        val channelId = "linux-proton"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Linux Proton", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, LinuxProtonService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(plan.title).setContentText("Linux Proton session running")
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Stop", stop).build()
        startForeground(8136, notice)
        executor.execute {
            if (!active.get()) return@execute
            val log = File(plan.logFile)
            val status = File(log.parentFile, "status.json")
            try {
                log.parentFile!!.mkdirs()
                log.createNewFile()
                log.setReadable(false, false)
                log.setReadable(true, true)
                val builder = ProcessBuilder(plan.command).directory(File(plan.workingDir))
                    .redirectErrorStream(true).redirectOutput(log)
                val env = builder.environment()
                env.remove("LD_PRELOAD")
                env.remove("LD_LIBRARY_PATH") // x86-64 paths belong only in BOX64_LD_LIBRARY_PATH.
                env.putAll(plan.environment)
                status.writeText(JSONObject().put("state", "running").toString())
                process = builder.start()
                val result = process!!.waitFor()
                status.writeText(JSONObject().put("state", "exited").put("exitCode", result).toString())
            } catch (e: Exception) {
                status.parentFile?.mkdirs()
                status.writeText(JSONObject().put("state", "failed")
                    .put("reason", e.javaClass.simpleName)
                    .put("message", e.message?.take(240) ?: "Proton could not start").toString())
            } finally {
                process = null
                active.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        active.set(false)
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
    companion object {
        const val PLAN = "linux_proton_plan"
        const val STOP = "linux_proton_stop"
    }
}

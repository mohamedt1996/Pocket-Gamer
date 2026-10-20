// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// The app's object graph. Everything is created lazily: the :wine process runs
// this Application too and needs almost none of it.

package org.pocketarcade.madeira

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.model.AppSettings
import org.pocketarcade.madeira.model.DockModel
import org.pocketarcade.madeira.model.LocalGames
import org.pocketarcade.madeira.model.SteamAccount
import org.pocketarcade.madeira.model.SteamGameConfigs
import org.pocketarcade.madeira.model.SteamLibrary
import org.pocketarcade.madeira.model.SteamPrefix
import org.pocketarcade.madeira.model.WineMono
import org.pocketarcade.madeira.platform.OkHttpCmTransport
import org.pocketarcade.madeira.platform.PrefsStore
import org.pocketarcade.madeira.platform.TokenVault
import org.pocketarcade.madeira.steam.CmServerList
import org.pocketarcade.madeira.steam.DepotDownloader
import org.pocketarcade.madeira.steam.DownloadProgress
import org.pocketarcade.madeira.steam.LibraryFetcher
import org.pocketarcade.madeira.steam.SteamAuthApi
import org.pocketarcade.madeira.steam.SteamCloud
import org.pocketarcade.madeira.steam.SteamLog
import org.pocketarcade.madeira.steam.SteamSession
import org.pocketarcade.madeira.steam.UrlConnectionHttp
import org.pocketarcade.madeira.steam.Zstd
import java.io.File
import java.security.SecureRandom

class MadeiraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

class AppGraph private constructor(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val deviceName: String = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()
    val http by lazy { UrlConnectionHttp() }
    val prefs by lazy { PrefsStore(context, "steam") }
    val settings by lazy { AppSettings(context) }
    val vault by lazy { TokenVault(context) }
    val account by lazy { SteamAccount(vault, SteamAuthApi(http), deviceName, scope) }
    val session by lazy {
        SteamSession({ OkHttpCmTransport() }, CmServerList(http, prefs), { account.credentials() }, deviceName)
    }
    val fetcher by lazy { LibraryFetcher(session) }
    val downloader by lazy { DepotDownloader(session, http) }
    val prefix by lazy { SteamPrefix(context) }
    val steamConfigs by lazy { SteamGameConfigs(context) }
    val cloud by lazy {
        SteamCloud(session, fetcher, http, File(context.getExternalFilesDir(null) ?: context.filesDir, "Steam Cloud Backups"),
            PrefsStore(context, "steam-cloud"), { cloudClientID() }, deviceName)
    }
    val library by lazy {
        SteamLibrary(context, scope, account, session, fetcher, downloader, cloud, prefix, steamConfigs, settings).also { lib ->
            lib.onDownloadsChanged = { active, progress -> DownloadService.update(context, active, progress, active?.let { lib.game(it)?.name }) }
        }
    }
    val localGames by lazy { LocalGames(context) }
    val dock by lazy { DockModel(context, http, prefix, vault) }
    val mono by lazy { WineMono(context, http, BuildConfig.WINE_VARIANT) }
    val runtime by lazy { PortRuntime(context) }

    private fun cloudClientID(): Long {
        val key = "cloud-client-id"
        prefs.get(key)?.toLongOrNull()?.takeIf { it != 0L }?.let { return it }
        val fresh = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
        prefs.put(key, fresh.toString())
        return fresh
    }

    companion object {
        lateinit var instance: AppGraph
            private set

        fun init(app: Application) {
            instance = AppGraph(app)
            SteamLog.sink = { Log.i("steam", it); AppLog.add(it) }
            Zstd.load("madeira_steam")
        }
    }
}

/** The app's recent diagnostic events, for Settings › Logs. Credential-free by SteamLog's rule. */
object AppLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
    @Synchronized fun add(text: String) {
        lines.addLast(format.format(java.util.Date()) + "  " + text)
        while (lines.size > MAX) lines.removeFirst()
    }
    @Synchronized fun snapshot(): List<String> = lines.toList()
}

/** Keeps downloads going while the app is in the background, with their progress in a notification. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Steam download"
        val percent = intent?.getIntExtra("percent", -1) ?: -1
        val text = intent?.getStringExtra("text") ?: ""
        if (intent?.getBooleanExtra("stop", false) == true) { stopForeground(true); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION, notification(this, title, text, percent))
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 7
        private var lastPercent = -2
        private var running = false

        private fun notification(context: Context, title: String, text: String, percent: Int): Notification {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, org.pocketarcade.madeira.ui.MadeiraActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .apply { if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true) }
                .build()
        }

        /** Called by the library whenever the active download or its progress changes. */
        fun update(context: Context, active: Int?, progress: DownloadProgress?, name: String?) {
            if (active == null) {
                if (running) {
                    running = false; lastPercent = -2
                    try { context.startService(Intent(context, DownloadService::class.java).putExtra("stop", true)) } catch (_: Exception) {}
                }
                return
            }
            val percent = progress?.let { if (it.totalBytes > 0) (it.fraction * 100).toInt() else -1 } ?: -1
            if (running && percent == lastPercent) return
            lastPercent = percent
            val text = when (progress?.phase) {
                DownloadProgress.Phase.DOWNLOADING -> "$percent%" + if ((progress.bytesPerSecond) > 0) " · ${"%.1f".format(progress.bytesPerSecond / 1e6)} MB/s" else ""
                DownloadProgress.Phase.FINISHING -> "Finishing…"
                else -> "Preparing…"
            }
            val intent = Intent(context, DownloadService::class.java).putExtra("title", name ?: "Steam download").putExtra("percent", percent).putExtra("text", text)
            try { if (running) context.startService(intent) else context.startForegroundService(intent); running = true } catch (_: Exception) {}
        }
    }
}

/** Starts a library game's session after the checks the app makes (sign-in, Dock, saves). */
object Launcher {
    fun start(context: Context, spec: org.pocketarcade.madeira.session.LaunchSpec) {
        val graph = AppGraph.instance
        graph.scope.launch {
            if (spec.steamAppID != 0) graph.library.sessionStarting()
            org.pocketarcade.madeira.session.LaunchSpec.start(context, spec)
        }
    }
}

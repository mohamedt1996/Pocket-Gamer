// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Keeps a running game's :wine process at foreground priority. Without it, a game
// left for a moment (a notification, another app) is an ordinary background
// process, and Android reclaims it quickly on phones with little memory: the
// game, and anything unsaved, is gone when the player comes back.

package org.pocketarcade.madeira.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

class GameSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "Game"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running game", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1,
            Intent().setClassName(this, "org.winehq.wine.WineActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Running. Tap to return to the game.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION, notification)
        // The service lives in the :wine process: it ends when the session's process does.
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIFICATION = 8
        private const val EXTRA_TITLE = "title"

        @JvmStatic
        fun start(context: Context, title: String?) {
            try {
                context.startForegroundService(Intent(context, GameSessionService::class.java).putExtra(EXTRA_TITLE, title ?: ""))
            } catch (e: Exception) {
                Log.w("madeira", "session service not started", e)
            }
        }
    }
}

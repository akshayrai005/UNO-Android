package com.uno.game.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.uno.game.R

/**
 * Foreground service that keeps the Socket.IO connection alive when the app
 * is minimized (e.g. sharing the room code via WhatsApp/SMS).
 *
 * Usage:
 *   Start: SocketKeepAliveService.start(context)
 *   Stop:  SocketKeepAliveService.stop(context)
 */
class SocketKeepAliveService : Service() {

    companion object {
        private const val TAG            = "SocketKeepAlive"
        private const val CHANNEL_ID     = "uno_socket_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            val intent = Intent(context, SocketKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, SocketKeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "SocketKeepAliveService started — keeping connection alive")

        // Make sure socket is connected
        if (!SocketManager.isConnected()) {
            SocketManager.connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by system
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SocketKeepAliveService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Srivi Fun Cards — Game Session",
                NotificationManager.IMPORTANCE_LOW   // silent, no sound
            ).apply {
                description = "Keeps your game connection alive"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Srivi Fun Cards 🃏")
            .setContentText("Game session active — tap to return")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}

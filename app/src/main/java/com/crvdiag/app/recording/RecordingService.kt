package com.crvdiag.app.recording

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
import androidx.core.app.ServiceCompat
import com.crvdiag.app.MainActivity
import com.crvdiag.app.R

/**
 * Keeps the app alive while a drive is being recorded (screen off, other app in front).
 * It does no work itself: polling and file writing run in the app; this only holds the
 * foreground status Android requires for a long-running connection to the OBD adapter.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Drive recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a drive log is being recorded"
                },
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle("Recording a drive log")
                .setContentText("Engine data is saved in the background. Tap to open CR-V Diag.")
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}

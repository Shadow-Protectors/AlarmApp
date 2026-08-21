package com.shadowprotectors.alarmapp.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.shadowprotectors.alarmapp.MainActivity
import com.shadowprotectors.alarmapp.R
import com.shadowprotectors.alarmapp.ui.AlarmTriggerActivity

object NotificationHelper {

    const val CHANNEL_TRACKING_ID = "travel_alarm_tracking_channel"
    const val CHANNEL_ALARM_ID = "travel_alarm_critical_channel"

    const val NOTIFICATION_TRACKING_ID = 1001
    const val NOTIFICATION_ALARM_ID = 1002

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Ongoing Tracking Channel (Low importance so it doesn't beep every 5 seconds)
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING_ID,
                "Travel Alarm Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows continuous destination tracking and distance remaining"
                setShowBadge(false)
            }

            // 2. Critical Alarm Channel (High importance with sound and heads-up banner)
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Destination Arrival Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency wake-up alarms when approaching your destination"
                enableVibration(true)
                setShowBadge(true)
            }

            manager.createNotificationChannel(trackingChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    fun buildTrackingNotification(
        context: Context,
        destinationName: String,
        distanceKm: Double,
        etaMinutes: Int?,
        stopIntent: PendingIntent
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val etaText = if (etaMinutes != null) " • ETA: ~${etaMinutes}m" else ""
        val contentText = String.format("Distance: %.2f km%s", distanceKm, etaText)

        return NotificationCompat.Builder(context, CHANNEL_TRACKING_ID)
            .setContentTitle("Tracking to $destinationName")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_alarm, "Stop Tracking", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun buildAlarmNotification(
        context: Context,
        destinationName: String,
        distanceKm: Double,
        dismissIntent: PendingIntent
    ): Notification {
        val fullScreenIntent = Intent(context, AlarmTriggerActivity::class.java).apply {
            putExtra(AlarmTriggerActivity.EXTRA_DEST_NAME, destinationName)
            putExtra(AlarmTriggerActivity.EXTRA_DISTANCE_KM, distanceKm)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            1,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
            .setContentTitle("🚨 Arriving at $destinationName!")
            .setContentText(String.format("You are %.2f km away. Wake up and prepare your luggage!", distanceKm))
            .setSmallIcon(R.drawable.ic_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(R.drawable.ic_alarm, "Dismiss Alarm", dismissIntent)
            .build()
    }
}

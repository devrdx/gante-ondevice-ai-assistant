package com.gante.gante_app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class AdaptiveAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val actionFire = "com.gante.gante_app.ADAPTIVE_FIRE"
        const val actionComplete = "com.gante.gante_app.ADAPTIVE_COMPLETE"
        private const val gentleChannel = "gante_adaptive_gentle_v1"
        private const val mediumChannel = "gante_adaptive_medium_v1"
        private const val strongChannel = "gante_adaptive_strong_v1"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarmId", -1)
        if (intent.action == actionComplete) {
            if (alarmId >= 0) AdaptiveAlarmScheduler.completePlan(context, alarmId)
            return
        }
        if (alarmId < 0 || AdaptiveAlarmScheduler.isCompleted(context, alarmId)) return

        val isFinal = intent.getBooleanExtra("isFinal", false)
        if (!isFinal && userWasRecentlyActive(context)) {
            AdaptiveAlarmScheduler.recordSuppressed(context, alarmId)
            return
        }
        postNotification(context, intent)
        AdaptiveAlarmScheduler.recordDelivered(context)
    }

    private fun userWasRecentlyActive(context: Context): Boolean {
        if (!AdaptiveAlarmScheduler.hasUsageAccess(context)) return false
        val now = System.currentTimeMillis()
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(now - 2 * 60_000L, now) ?: return false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val activeEvent = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (activeEvent && event.packageName != context.packageName) return true
        }
        return false
    }

    private fun postNotification(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannels(manager)
        val notificationId = intent.getIntExtra("notificationId", 0)
        val alarmId = intent.getIntExtra("alarmId", 0)
        val label = intent.getStringExtra("label") ?: "Gante alarm"
        val offset = intent.getIntExtra("offset", 0)
        val isFinal = intent.getBooleanExtra("isFinal", false)
        val channel = when {
            isFinal -> strongChannel
            offset > 10 -> gentleChannel
            else -> mediumChannel
        }
        val openApp = PendingIntent.getActivity(
            context,
            alarmId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = PendingIntent.getBroadcast(
            context,
            alarmId,
            Intent(context, AdaptiveAlarmReceiver::class.java).apply {
                action = actionComplete
                putExtra("alarmId", alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when {
            isFinal -> "$label starts now."
            offset > 10 -> "$label is in $offset minutes."
            else -> "Get ready for $label. $offset minutes remaining."
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Gante · $label")
            .setContentText(body)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(!isFinal)
            .addAction(Notification.Action.Builder(null, "Done", complete).build())
        if (isFinal) {
            builder.setFullScreenIntent(openApp, true).setOngoing(true)
        }
        val notification = builder.build()
        if (isFinal) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager.notify(notificationId, notification)
    }

    private fun createChannels(manager: NotificationManager) {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        val gentle = NotificationChannel(
            gentleChannel,
            "Adaptive gentle reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Quiet early reminders that can be suppressed when you are active" }
        val medium = NotificationChannel(
            mediumChannel,
            "Adaptive wake-up alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { setSound(alarmSound, alarmAttributes) }
        val strong = NotificationChannel(
            strongChannel,
            "Adaptive final alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { setSound(alarmSound, alarmAttributes) }
        manager.createNotificationChannels(listOf(gentle, medium, strong))
    }
}

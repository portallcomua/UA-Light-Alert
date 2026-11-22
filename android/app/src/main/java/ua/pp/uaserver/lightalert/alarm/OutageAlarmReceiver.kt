package ua.pp.uaserver.lightalert.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import ua.pp.uaserver.lightalert.R
import ua.pp.uaserver.lightalert.alert.AlertAudioPlayer
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.PrefsStore
import ua.pp.uaserver.lightalert.ui.AlertActivity
import ua.pp.uaserver.lightalert.ui.MainActivity

class OutageAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ScheduleAlarmScheduler.ensureNotificationChannels(context)
        val prefs = PrefsStore(context)

        val type = intent.getStringExtra(EXTRA_TYPE) ?: "off"
        val eventTimeMillis = intent.getLongExtra(EXTRA_EVENT_TIME_MILLIS, System.currentTimeMillis() + 5 * 60_000L)
        val eventTimeFmt = intent.getStringExtra(EXTRA_EVENT_TIME_FMT) ?: "--:--"
        val region = intent.getStringExtra(EXTRA_REGION) ?: prefs.selectedRegion
        val group = intent.getStringExtra(EXTRA_GROUP) ?: prefs.selectedGroup
        val eventKey = intent.getStringExtra(EXTRA_EVENT_KEY) ?: ""

        if (eventKey.isNotBlank()) {
            prefs.lastAlertedEventKey = eventKey
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UALightAlert:OutageWakeLock"
        )
        wakeLock.acquire(15_000L)

        val cleanGroup = group.replace(Regex("^GPV", RegexOption.IGNORE_CASE), "")
        val title = if (type == "off") {
            "Увага! Вимкнення світла о $eventTimeFmt"
        } else {
            "Очікується увімкнення світла о $eventTimeFmt"
        }
        val body = "Черга $cleanGroup · Натисніть, щоб відкрити графік"

        val autoOpen = prefs.getUserOverrides().autoOpenAlertScreen

        val alertIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_EVENT_TIME_MILLIS, eventTimeMillis)
            putExtra(EXTRA_EVENT_TIME_FMT, eventTimeFmt)
            putExtra(EXTRA_REGION, region)
            putExtra(EXTRA_GROUP, group)
            putExtra(AlertActivity.EXTRA_AUTO_PLAY, autoOpen)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            4710,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            4711,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ScheduleAlarmScheduler.CHANNEL_URGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(if (autoOpen) fullScreenPendingIntent else contentPendingIntent)

        if (autoOpen) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ScheduleAlarmScheduler.ALERT_NOTIFICATION_ID, builder.build())

        if (autoOpen) {
            try {
                context.startActivity(alertIntent)
            } catch (_: Exception) {
                // Background activity launch may be restricted on Android 10+; FullScreenIntent handles it.
            }
            ScheduleAlarmScheduler.rescheduleNextAlarm(context)
            if (wakeLock.isHeld) wakeLock.release()
        } else {
            val pendingResult = goAsync()
            val apiClient = ApiClient(context, prefs)
            val effective = prefs.resolveEffectiveSettings(apiClient.getCachedSiteConfig())
            val player = AlertAudioPlayer(context.applicationContext)
            var finished = false
            val safetyStop = Runnable {
                if (!finished) {
                    finished = true
                    player.stop()
                    ScheduleAlarmScheduler.rescheduleNextAlarm(context)
                    if (wakeLock.isHeld) wakeLock.release()
                    pendingResult.finish()
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(safetyStop, 18_000L)
            player.playAlert(
                type = type,
                eventTime = eventTimeFmt,
                groupLabel = group,
                settings = effective,
                ignoreQuietHours = false
            ) {
                if (!finished) {
                    finished = true
                    ScheduleAlarmScheduler.rescheduleNextAlarm(context)
                    if (wakeLock.isHeld) wakeLock.release()
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_EVENT_TIME_MILLIS = "extra_event_time_millis"
        const val EXTRA_EVENT_TIME_FMT = "extra_event_time_fmt"
        const val EXTRA_REGION = "extra_region"
        const val EXTRA_GROUP = "extra_group"
        const val EXTRA_EVENT_KEY = "extra_event_key"
    }
}

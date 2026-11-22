package ua.pp.uaserver.lightalert.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.PrefsStore
import ua.pp.uaserver.lightalert.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ScheduleAlarmScheduler {

    const val CHANNEL_URGENT = "ua_light_alert_urgent_v1"
    const val ALERT_NOTIFICATION_ID = 4701

    private const val SYNC_WORK_NAME = "ua_light_alert_periodic_sync"
    private const val ALARM_REQUEST_CODE = 4700

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_URGENT)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_URGENT,
                    "Екстрені голосові сповіщення",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Автоматичне відкриття графіка та голосове попередження"
                    enableVibration(true)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun rescheduleNextAlarm(context: Context) {
        ensureNotificationChannels(context)
        val prefs = PrefsStore(context)
        val region = prefs.selectedRegion
        val group = prefs.selectedGroup
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (region.isBlank() || group.isBlank()) {
            cancelExistingAlarm(context, alarmManager)
            return
        }

        val scheduleJson = prefs.getScheduleJson(region)
        val (groupsObj, _) = ApiClient.getLatestGroupsObject(scheduleJson)
        val groupObj = groupsObj?.optJSONObject(group)
        val nextEvent = ApiClient.findNextOutageEvent(groupObj)

        if (nextEvent == null) {
            cancelExistingAlarm(context, alarmManager)
            return
        }

        val apiClient = ApiClient(context, prefs)
        val siteConfig = apiClient.getCachedSiteConfig()
        val effective = prefs.resolveEffectiveSettings(siteConfig)

        val noticeMs = effective.noticeMinutes * 60_000L
        var triggerAt = nextEvent.eventTimeMillis - noticeMs
        val now = System.currentTimeMillis()
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nextEvent.eventTimeMillis))
        val eventKey = "${dateStamp}_${region}_${group}_${nextEvent.type}_${nextEvent.eventTimeFormatted}"

        if (triggerAt <= now) {
            // If we're already inside the notice window and haven't alerted for this event yet, fire right away.
            if (nextEvent.eventTimeMillis > now && prefs.lastAlertedEventKey != eventKey) {
                triggerAt = now + 2_000L
            } else {
                // Already passed/alerted; wait until next sync or schedule next cycle.
                cancelExistingAlarm(context, alarmManager)
                return
            }
        }

        val intent = Intent(context, OutageAlarmReceiver::class.java).apply {
            putExtra(OutageAlarmReceiver.EXTRA_TYPE, nextEvent.type)
            putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_MILLIS, nextEvent.eventTimeMillis)
            putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_FMT, nextEvent.eventTimeFormatted)
            putExtra(OutageAlarmReceiver.EXTRA_REGION, region)
            putExtra(OutageAlarmReceiver.EXTRA_GROUP, group)
            putExtra(OutageAlarmReceiver.EXTRA_EVENT_KEY, eventKey)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getActivity(
            context,
            ALARM_REQUEST_CODE + 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelExistingAlarm(context: Context, alarmManager: AlarmManager) {
        val intent = Intent(context, OutageAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
        }
    }
}

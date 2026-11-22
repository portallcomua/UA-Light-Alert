package ua.pp.uaserver.lightalert.alarm

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.PrefsStore

class ScheduleSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val prefs = PrefsStore(applicationContext)
        val api = ApiClient(applicationContext, prefs)

        api.fetchSiteConfig()
        val region = prefs.selectedRegion
        if (region.isNotBlank()) {
            api.fetchRegionScheduleJson(region)
        }
        ScheduleAlarmScheduler.rescheduleNextAlarm(applicationContext)
        return Result.success()
    }
}

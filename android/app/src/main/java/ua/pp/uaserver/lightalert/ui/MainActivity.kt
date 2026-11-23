package ua.pp.uaserver.lightalert.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONObject
import ua.pp.uaserver.lightalert.BuildConfig
import ua.pp.uaserver.lightalert.alarm.ScheduleAlarmScheduler
import ua.pp.uaserver.lightalert.data.AdItem
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.NextOutageEvent
import ua.pp.uaserver.lightalert.data.PrefsStore
import ua.pp.uaserver.lightalert.data.SiteConfig
import ua.pp.uaserver.lightalert.databinding.ActivityMainBinding
import ua.pp.uaserver.lightalert.update.AppUpdateController
import ua.pp.uaserver.lightalert.update.AppUpdateInfo
import ua.pp.uaserver.lightalert.update.AppUpdater
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: PrefsStore
    private lateinit var api: ApiClient
    private lateinit var updateController: AppUpdateController
    private val adapter = ScheduleAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var siteConfig: SiteConfig = SiteConfig()
    private var currentGroupsObj: JSONObject? = null
    private var groupIds: List<String> = emptyList()
    private var currentNextEvent: NextOutageEvent? = null
    private var lastTrackedAdKey: String = ""
    private var suppressingRegionCallback = false
    private var suppressingGroupCallback = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionBanner()
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            updateCountdownUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.mainRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (14 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, sys.top + pad, pad, sys.bottom + pad)
            insets
        }

        prefs = PrefsStore(this)
        api = ApiClient(this, prefs)
        updateController = AppUpdateController(this, executor)

        ScheduleAlarmScheduler.ensureNotificationChannels(this)
        ScheduleAlarmScheduler.schedulePeriodicSync(this)

        b.rvSchedule.layoutManager = LinearLayoutManager(this)
        b.rvSchedule.adapter = adapter

        b.swipeRefresh.setOnRefreshListener {
            loadAllData()
            checkAppUpdate(force = true)
        }

        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        b.btnGrantPermissions.setOnClickListener {
            requestMissingPermissions()
        }

        b.spinnerRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressingRegionCallback) return
                val region = siteConfig.regions.getOrNull(position)?.id ?: return
                if (region != prefs.selectedRegion) {
                    prefs.selectedRegion = region
                    loadRegionScheduleAndAds(region)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.spinnerGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressingGroupCallback) return
                val selected = groupIds.getOrNull(position) ?: return
                if (selected != prefs.selectedGroup) {
                    prefs.selectedGroup = selected
                    applySelectedGroup(selected)
                    loadAdsForSelection()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        siteConfig = api.getCachedSiteConfig()
        bindSiteConfig(siteConfig)
        loadCachedScheduleImmediately()
        loadAdsForSelection()
        loadAllData()
        checkAppUpdate(force = false)
    }

    override fun onResume() {
        super.onResume()
        updateController.onResume()
        updatePermissionBanner()
        siteConfig = api.getCachedSiteConfig()
        bindSiteConfig(siteConfig)
        updateCountdownUi()
        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.post(tickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(tickerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun checkAppUpdate(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - prefs.lastUpdateCheckMillis) < 30 * 60_000L) {
            return
        }
        prefs.lastUpdateCheckMillis = now

        executor.execute {
            when (val res = updateController.updater.checkForUpdate()) {
                is AppUpdater.CheckResult.UpdateAvailable -> {
                    mainHandler.post { showUpdateBanner(res.info) }
                }
                is AppUpdater.CheckResult.UpToDate -> {
                    mainHandler.post { b.cardAppUpdate.visibility = View.GONE }
                }
                is AppUpdater.CheckResult.Error -> {
                    // Silent on background check.
                }
            }
        }
    }

    private fun showUpdateBanner(info: AppUpdateInfo) {
        if (isFinishing || isDestroyed) return
        b.cardAppUpdate.visibility = View.VISIBLE
        b.tvAppUpdateTitle.text = "Доступна нова версія v. ${info.version}"
        b.btnAppUpdate.setOnClickListener {
            updateController.startDownloadAndInstall(info)
        }
        if (prefs.dismissedUpdateVersion != info.version) {
            updateController.showUpdateDialog(info) {
                prefs.dismissedUpdateVersion = info.version
            }
        }
    }

    private fun updatePermissionBanner() {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add("сповіщення")
        }

        if (!ScheduleAlarmScheduler.canScheduleExactAlarms(this)) {
            missing.add("точні будильники")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                missing.add("повноекранне відкриття")
            }
        }

        if (missing.isEmpty()) {
            b.cardPermissions.visibility = View.GONE
        } else {
            b.cardPermissions.visibility = View.VISIBLE
            b.tvPermissionSummary.text = "Надайте дозвіл: ${missing.joinToString(", ")}, щоб графік і голос вмикалися навіть при заблокованому екрані."
        }
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ScheduleAlarmScheduler.canScheduleExactAlarms(this)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    private fun loadAllData() {
        b.swipeRefresh.isRefreshing = true
        executor.execute {
            val cfg = api.fetchSiteConfig()
            val region = prefs.selectedRegion.ifBlank { cfg.regions.firstOrNull()?.id ?: "kyiv" }
            prefs.selectedRegion = region
            val scheduleRaw = api.fetchRegionScheduleJson(region)
            mainHandler.post {
                siteConfig = cfg
                bindSiteConfig(cfg)
                applyScheduleJson(scheduleRaw)
                loadAdsForSelection()
                b.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadCachedScheduleImmediately() {
        val cached = prefs.getScheduleJson(prefs.selectedRegion)
        if (cached.isNotBlank()) {
            applyScheduleJson(cached)
        }
    }

    private fun loadRegionScheduleAndAds(region: String) {
        b.swipeRefresh.isRefreshing = true
        executor.execute {
            val raw = api.fetchRegionScheduleJson(region)
            mainHandler.post {
                applyScheduleJson(raw)
                loadAdsForSelection()
                b.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun bindSiteConfig(cfg: SiteConfig) {
        val cleanVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        b.tvSiteTitle.text = "Графік відключень v. $cleanVersion"
        val effective = prefs.resolveEffectiveSettings(cfg)

        if (cfg.pro.monetizationEnabled) {
            b.tvProBadge.visibility = View.VISIBLE
            b.tvProBadge.text = when (cfg.pro.status) {
                "licensed" -> "Pro активовано"
                "trial" -> "Тріал Pro: лишилось ${cfg.pro.trialDaysLeft} дн."
                else -> "Базовий режим (${cfg.settings.noticeMinutes} хв)"
            }
        } else {
            b.tvProBadge.visibility = View.GONE
        }

        b.tvNoticeSummary.text = "Попередження за ${effective.noticeMinutes} хв (${modeLabel(effective.alertMode)})."

        val names = cfg.regions.map { it.name }
        val regAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        suppressingRegionCallback = true
        b.spinnerRegion.adapter = regAdapter
        val idx = cfg.regions.indexOfFirst { it.id == prefs.selectedRegion }.coerceAtLeast(0)
        b.spinnerRegion.setSelection(idx, false)
        suppressingRegionCallback = false
    }

    private fun modeLabel(mode: String): String {
        return when (mode) {
            "speech" -> "тільки голос"
            "audio" -> "тільки MP3/запис"
            else -> "звук + голос"
        }
    }

    private fun applyScheduleJson(raw: String) {
        val (groupsObj, updated) = ApiClient.getLatestGroupsObject(raw)
        currentGroupsObj = groupsObj
        groupIds = ApiClient.extractGroupIds(groupsObj)

        b.tvScheduleDate.text = if (updated.isNotBlank()) {
            "Оновлено: ${formatUpdatedDate(updated)}"
        } else {
            ""
        }

        if (groupIds.isEmpty()) {
            b.tvEmptySchedule.visibility = View.VISIBLE
            b.tvEmptySchedule.text = "Не вдалося завантажити черги для обраного регіону."
            adapter.submitList(emptyList())
            currentNextEvent = null
            updateCountdownUi()
            return
        }

        b.tvEmptySchedule.visibility = View.GONE
        val labels = groupIds.map { it.replace(Regex("^GPV", RegexOption.IGNORE_CASE), "Черга ") }
        val groupAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        suppressingGroupCallback = true
        b.spinnerGroup.adapter = groupAdapter

        val savedGroup = prefs.selectedGroup
        val targetGroup = if (groupIds.contains(savedGroup)) savedGroup else groupIds.first()
        prefs.selectedGroup = targetGroup
        val idx = groupIds.indexOf(targetGroup).coerceAtLeast(0)
        b.spinnerGroup.setSelection(idx, false)
        suppressingGroupCallback = false

        applySelectedGroup(targetGroup)
    }

    private fun applySelectedGroup(groupId: String) {
        val groupObj = currentGroupsObj?.optJSONObject(groupId)
        val rows = ApiClient.buildHalfHourRows(groupObj)
        adapter.submitList(rows)
        currentNextEvent = ApiClient.findNextOutageEvent(groupObj)
        updateCountdownUi()
        ScheduleAlarmScheduler.rescheduleNextAlarm(this)
    }

    private fun updateCountdownUi() {
        val next = currentNextEvent
        if (next == null) {
            b.tvCurrentStatusBadge.text = "⏳ Наступна зміна статусу"
            b.tvNextEventTitle.text = "Оберіть область і чергу"
            b.tvCountdown.text = "--:--:--"
            return
        }

        val hasLightNow = next.currentStatus == "yes"
        b.tvCurrentStatusBadge.text = if (hasLightNow) {
            "⏳ Зараз: світло є · Наступна зміна"
        } else {
            "⏳ Зараз: світла немає · Наступна зміна"
        }

        b.tvNextEventTitle.text = if (next.type == "off") {
            "Вимкнення за графіком о ${next.eventTimeFormatted}"
        } else {
            "Увімкнення за графіком о ${next.eventTimeFormatted}"
        }

        val diffSec = ((next.eventTimeMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        val hrs = diffSec / 3600L
        val mins = (diffSec % 3600L) / 60L
        val secs = diffSec % 60L
        b.tvCountdown.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    }

    private fun loadAdsForSelection() {
        val region = prefs.selectedRegion
        val group = prefs.selectedGroup

        executor.execute {
            val ads = api.fetchAds(region, group)
            val ad: AdItem? = ads.firstOrNull()
            val bannerBitmap = if (ad != null && ad.imageUrl.isNotBlank()) {
                api.fetchBitmap(ad.imageUrl)
            } else {
                null
            }

            mainHandler.post {
                if (ad == null) {
                    b.cardAd.visibility = View.GONE
                    return@post
                }

                b.cardAd.visibility = View.VISIBLE

                if (bannerBitmap != null) {
                    b.ivAdBanner.setImageBitmap(bannerBitmap)
                    b.ivAdBanner.visibility = View.VISIBLE
                } else {
                    b.ivAdBanner.visibility = View.GONE
                }

                val caption = ad.text.ifBlank { if (bannerBitmap == null) ad.altText else "" }
                if (caption.isNotBlank()) {
                    b.tvAdLabel.visibility = View.VISIBLE
                    b.tvAdLabel.text = if (ad.name.isNotBlank()) ad.name.uppercase(Locale.getDefault()) else "ПАРТНЕР"
                    b.tvAdText.visibility = View.VISIBLE
                    b.tvAdText.text = caption
                    b.btnAdAction.visibility = if (ad.primaryUrl.isNotBlank()) View.VISIBLE else View.GONE
                } else {
                    b.tvAdLabel.visibility = View.GONE
                    b.tvAdText.visibility = View.GONE
                    b.btnAdAction.visibility = View.GONE
                }

                val clickListener = View.OnClickListener {
                    if (ad.primaryUrl.isNotBlank()) {
                        executor.execute { api.trackAd("click", region, group) }
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.primaryUrl)))
                    }
                }
                b.ivAdBanner.setOnClickListener(clickListener)
                b.btnAdAction.setOnClickListener(clickListener)
                b.cardAd.setOnClickListener(clickListener)

                val trackKey = "${region}_${group}_${ad.imageUrl.hashCode()}_${ad.text.hashCode()}"
                if (lastTrackedAdKey != trackKey) {
                    lastTrackedAdKey = trackKey
                    executor.execute { api.trackAd("impression", region, group) }
                }
            }
        }
    }

    private fun formatUpdatedDate(iso: String): String {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val parser = SimpleDateFormat(p, Locale.US).apply {
                    if (p.endsWith("'Z'")) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }
                val date = parser.parse(iso)
                if (date != null) {
                    val out = SimpleDateFormat("dd.MM.yyyy, HH:mm:ss", Locale("uk", "UA"))
                    return out.format(date)
                }
            } catch (_: Exception) {
            }
        }
        return iso.replace("T", " ").take(19)
    }
}

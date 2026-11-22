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
import ua.pp.uaserver.lightalert.R
import ua.pp.uaserver.lightalert.alarm.OutageAlarmReceiver
import ua.pp.uaserver.lightalert.alarm.ScheduleAlarmScheduler
import ua.pp.uaserver.lightalert.data.AdItem
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.NextOutageEvent
import ua.pp.uaserver.lightalert.data.PrefsStore
import ua.pp.uaserver.lightalert.data.SiteConfig
import ua.pp.uaserver.lightalert.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: PrefsStore
    private lateinit var api: ApiClient
    private val adapter = ScheduleAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var siteConfig: SiteConfig = SiteConfig()
    private var currentGroupsObj: JSONObject? = null
    private var groupIds: List<String> = emptyList()
    private var currentNextEvent: NextOutageEvent? = null
    private var currentAd: AdItem? = null
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
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, sys.top + pad, pad, sys.bottom + pad)
            insets
        }

        prefs = PrefsStore(this)
        api = ApiClient(this, prefs)

        ScheduleAlarmScheduler.ensureNotificationChannels(this)
        ScheduleAlarmScheduler.schedulePeriodicSync(this)

        b.rvSchedule.layoutManager = LinearLayoutManager(this)
        b.rvSchedule.adapter = adapter

        b.swipeRefresh.setOnRefreshListener {
            loadAllData()
        }

        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        b.btnTestAlert.setOnClickListener {
            val activeGroup = prefs.selectedGroup.ifBlank { "GPV1.1" }
            val testTime = SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + 5 * 60_000L))
            val i = Intent(this, AlertActivity::class.java).apply {
                putExtra(OutageAlarmReceiver.EXTRA_TYPE, "off")
                putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_MILLIS, System.currentTimeMillis() + 5 * 60_000L)
                putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_FMT, testTime)
                putExtra(OutageAlarmReceiver.EXTRA_REGION, prefs.selectedRegion)
                putExtra(OutageAlarmReceiver.EXTRA_GROUP, activeGroup)
                putExtra(AlertActivity.EXTRA_AUTO_PLAY, true)
                putExtra(AlertActivity.EXTRA_IS_TEST, true)
            }
            startActivity(i)
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
        loadAllData()
    }

    override fun onResume() {
        super.onResume()
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
                b.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun bindSiteConfig(cfg: SiteConfig) {
        b.tvSiteTitle.text = cfg.siteName
        val effective = prefs.resolveEffectiveSettings(cfg)

        val proText = when {
            !cfg.pro.monetizationEnabled -> "Синхронізовано · усі налаштування відкриті"
            cfg.pro.status == "licensed" -> "Pro активовано · попередження за ${effective.noticeMinutes} хв"
            cfg.pro.status == "trial" -> "Тріал Pro: лишилось ${cfg.pro.trialDaysLeft} дн."
            else -> "Базовий режим · попередження як на сайті (${cfg.settings.noticeMinutes} хв)"
        }
        b.tvProBadge.text = proText
        b.tvNoticeSummary.text = "Попередження спрацює автоматично за ${effective.noticeMinutes} хв (${modeLabel(effective.alertMode)})."

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

        if (updated.isNotBlank()) {
            b.tvScheduleDate.text = formatUpdatedDate(updated)
        } else {
            b.tvScheduleDate.text = ""
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
        loadAdsForSelection()
    }

    private fun applySelectedGroup(groupId: String) {
        val groupObj = currentGroupsObj?.optJSONObject(groupId)
        val rows = ApiClient.buildHourRows(groupObj)
        adapter.submitList(rows)
        currentNextEvent = ApiClient.findNextOutageEvent(groupObj)
        updateCountdownUi()
        ScheduleAlarmScheduler.rescheduleNextAlarm(this)
    }

    private fun updateCountdownUi() {
        val next = currentNextEvent
        if (next == null) {
            b.tvCurrentStatusBadge.text = "ДАНІ ОНОВЛЮЮТЬСЯ"
            b.tvNextEventTitle.text = "Оберіть регіон і чергу"
            b.tvCountdown.text = "--:--:--"
            return
        }

        val hasLightNow = next.currentStatus == "yes"
        b.tvCurrentStatusBadge.text = if (hasLightNow) "ЗАРАЗ: СВІТЛО Є" else "ЗАРАЗ: СВІТЛА НЕМАЄ"
        b.tvCurrentStatusBadge.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasLightNow) R.color.status_on_fg else R.color.status_off_fg
            )
        )

        b.tvNextEventTitle.text = if (next.type == "off") {
            "Наступне вимкнення о ${next.eventTimeFormatted}"
        } else {
            "Наступне увімкнення о ${next.eventTimeFormatted}"
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
        if (region.isBlank() || group.isBlank()) return

        executor.execute {
            val ads = api.fetchAds(region, group)
            val ad = ads.firstOrNull()
            mainHandler.post {
                currentAd = ad
                if (ad == null) {
                    b.cardAd.visibility = View.GONE
                } else {
                    b.cardAd.visibility = View.VISIBLE
                    b.tvAdLabel.text = if (ad.name.isNotBlank()) ad.name.uppercase(Locale.getDefault()) else "ПАРТНЕРСЬКА ПРОПОЗИЦІЯ"
                    b.tvAdText.text = ad.text
                    b.btnAdAction.visibility = if (ad.primaryUrl.isNotBlank()) View.VISIBLE else View.GONE

                    val clickListener = View.OnClickListener {
                        if (ad.primaryUrl.isNotBlank()) {
                            executor.execute { api.trackAd("click", region, group) }
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ad.primaryUrl)))
                        }
                    }
                    b.btnAdAction.setOnClickListener(clickListener)
                    b.cardAd.setOnClickListener(clickListener)

                    val trackKey = "${region}_${group}_${ad.text.hashCode()}"
                    if (lastTrackedAdKey != trackKey) {
                        lastTrackedAdKey = trackKey
                        executor.execute { api.trackAd("impression", region, group) }
                    }
                }
            }
        }
    }

    private fun formatUpdatedDate(iso: String): String {
        return try {
            iso.replace("T", " ").take(16)
        } catch (_: Exception) {
            iso
        }
    }
}

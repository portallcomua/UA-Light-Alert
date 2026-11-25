package ua.pp.uaserver.lightalert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ua.pp.uaserver.lightalert.R
import ua.pp.uaserver.lightalert.alarm.OutageAlarmReceiver
import ua.pp.uaserver.lightalert.alarm.ScheduleAlarmScheduler
import ua.pp.uaserver.lightalert.alert.AlertAudioPlayer
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.PrefsStore
import ua.pp.uaserver.lightalert.data.SiteConfig
import ua.pp.uaserver.lightalert.data.UserOverrides
import ua.pp.uaserver.lightalert.databinding.ActivitySettingsBinding
import ua.pp.uaserver.lightalert.update.AppUpdateController
import ua.pp.uaserver.lightalert.update.AppUpdater
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: PrefsStore
    private lateinit var api: ApiClient
    private lateinit var previewPlayer: AlertAudioPlayer
    private lateinit var updateController: AppUpdateController

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var siteConfig: SiteConfig = SiteConfig()
    private var workingOverrides: UserOverrides = UserOverrides()
    private var suppressingSourceRadio = false

    private var mediaRecorder: MediaRecorder? = null
    private var recordingKind: String? = null // "off" | "on"
    private var pendingRecordKind: String? = null

    private val noticeValues = listOf(-1, 1, 3, 5, 10, 15, 30)

    private val pickOffAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handlePickedAudio("off", uri)
    }

    private val pickOnAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handlePickedAudio("on", uri)
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val kind = pendingRecordKind
        pendingRecordKind = null
        if (granted && kind != null) {
            toggleVoiceRecording(kind)
        } else if (!granted) {
            Toast.makeText(this, "Потрібен дозвіл на мікрофон для запису голосу.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.settingsRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, sys.top + pad, pad, sys.bottom + pad)
            insets
        }

        prefs = PrefsStore(this)
        api = ApiClient(this, prefs)
        previewPlayer = AlertAudioPlayer(this)
        updateController = AppUpdateController(this, executor)

        b.btnBack.setOnClickListener { finish() }

        siteConfig = api.getCachedSiteConfig()
        workingOverrides = prefs.getUserOverrides()

        populateUi()
        refreshConfigFromServer()
    }

    override fun onResume() {
        super.onResume()
        updateController.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopActiveRecording(saveResult = false)
        previewPlayer.stop()
        executor.shutdownNow()
    }

    private fun refreshConfigFromServer() {
        executor.execute {
            val fresh = api.fetchSiteConfig()
            mainHandler.post {
                siteConfig = fresh
                bindProCardAndLockState()
            }
        }
    }

    private fun populateUi() {
        b.switchAutoOpen.isChecked = workingOverrides.autoOpenAlertScreen
        b.switchAutoOpen.setOnCheckedChangeListener { _, isChecked ->
            workingOverrides = workingOverrides.copy(autoOpenAlertScreen = isChecked)
            prefs.saveUserOverrides(workingOverrides)
        }

        b.btnTestAlert.setOnClickListener {
            collectFormIntoOverrides()
            prefs.saveUserOverrides(workingOverrides)
            val activeGroup = prefs.selectedGroup.ifBlank { "GPV1.1" }
            val testTime = SimpleDateFormat("HH:mm", Locale.US)
                .format(Date(System.currentTimeMillis() + 5 * 60_000L))
            val intent = Intent(this, AlertActivity::class.java).apply {
                putExtra(OutageAlarmReceiver.EXTRA_TYPE, "off")
                putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_MILLIS, System.currentTimeMillis() + 5 * 60_000L)
                putExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_FMT, testTime)
                putExtra(OutageAlarmReceiver.EXTRA_REGION, prefs.selectedRegion)
                putExtra(OutageAlarmReceiver.EXTRA_GROUP, activeGroup)
                putExtra(AlertActivity.EXTRA_AUTO_PLAY, true)
                putExtra(AlertActivity.EXTRA_IS_TEST, true)
            }
            startActivity(intent)
        }

        b.tvAppVersionInfo.text = "Версія додатка: v. ${updateController.updater.getCurrentVersionName()}"
        b.btnCheckAppUpdate.setOnClickListener {
            b.btnCheckAppUpdate.isEnabled = false
            b.tvUpdateStatus.text = "Перевіряємо оновлення…"
            executor.execute {
                val res = updateController.updater.checkForUpdate()
                mainHandler.post {
                    b.btnCheckAppUpdate.isEnabled = true
                    when (res) {
                        is AppUpdater.CheckResult.UpdateAvailable -> {
                            b.tvUpdateStatus.text = "Знайдено нову версію v. ${res.info.version}!"
                            updateController.showUpdateDialog(res.info)
                        }
                        is AppUpdater.CheckResult.UpToDate -> {
                            b.tvUpdateStatus.text = "У вас встановлена остання версія v. ${res.currentVersion}."
                            Toast.makeText(this, "Оновлень поки немає.", Toast.LENGTH_SHORT).show()
                        }
                        is AppUpdater.CheckResult.Error -> {
                            b.tvUpdateStatus.text = "Помилка перевірки: ${res.message}"
                        }
                    }
                }
            }
        }

        val site = siteConfig.settings
        val noticeLabels = listOf(
            "Стандарт (за ${site.noticeMinutes} хв)",
            "За 1 хвилину",
            "За 3 хвилини",
            "За 5 хвилин",
            "За 10 хвилин",
            "За 15 хвилин",
            "За 30 хвилин"
        )
        b.spinnerNoticeMinutes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, noticeLabels)
        val noticeIdx = noticeValues.indexOf(workingOverrides.noticeMinutesOverride).coerceAtLeast(0)
        b.spinnerNoticeMinutes.setSelection(noticeIdx, false)

        suppressingSourceRadio = true
        when (workingOverrides.alertSourceMode) {
            "mp3" -> b.rbSourceMp3.isChecked = true
            "tts" -> b.rbSourceTts.isChecked = true
            else -> b.rbSourceStandard.isChecked = true
        }
        suppressingSourceRadio = false
        updateSourceSectionVisibility()

        b.rgAlertSource.setOnCheckedChangeListener { _, checkedId ->
            if (suppressingSourceRadio) return@setOnCheckedChangeListener
            if (!ensureProAllowed()) {
                suppressingSourceRadio = true
                b.rbSourceStandard.isChecked = true
                suppressingSourceRadio = false
                updateSourceSectionVisibility()
                return@setOnCheckedChangeListener
            }
            val newMode = when (checkedId) {
                R.id.rbSourceMp3 -> "mp3"
                R.id.rbSourceTts -> "tts"
                else -> "standard"
            }
            workingOverrides = workingOverrides.copy(alertSourceMode = newMode)
            updateSourceSectionVisibility()
        }

        b.etVoiceTextOff.setText(workingOverrides.customVoiceTextOff)
        b.etVoiceTextOn.setText(workingOverrides.customVoiceTextOn)
        b.etQuietStart.setText(workingOverrides.quietStartOverride)
        b.etQuietEnd.setText(workingOverrides.quietEndOverride)

        val effectiveRate = if (workingOverrides.voiceRateOverride > 0f) {
            workingOverrides.voiceRateOverride.coerceIn(0.7f, 1.3f)
        } else {
            site.voiceRate.coerceIn(0.7f, 1.3f)
        }
        val snappedRate = (Math.round(effectiveRate * 20f) / 20f).coerceIn(0.7f, 1.3f)
        b.sliderVoiceRate.value = snappedRate
        b.tvVoiceRateLabel.text = String.format(Locale.US, "Швидкість голосу: %.2fx", snappedRate)
        b.sliderVoiceRate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                workingOverrides = workingOverrides.copy(voiceRateOverride = value)
                b.tvVoiceRateLabel.text = String.format(Locale.US, "Швидкість голосу: %.2fx", value)
            }
        }

        updateAudioStatusLabels()
        bindProCardAndLockState()

        b.btnPickOffAudio.setOnClickListener {
            if (!ensureProAllowed()) return@setOnClickListener
            pickOffAudioLauncher.launch(arrayOf("audio/*"))
        }
        b.btnPickOnAudio.setOnClickListener {
            if (!ensureProAllowed()) return@setOnClickListener
            pickOnAudioLauncher.launch(arrayOf("audio/*"))
        }

        b.btnRecordOffVoice.setOnClickListener {
            if (!ensureProAllowed()) return@setOnClickListener
            toggleVoiceRecording("off")
        }
        b.btnRecordOnVoice.setOnClickListener {
            if (!ensureProAllowed()) return@setOnClickListener
            toggleVoiceRecording("on")
        }

        b.btnClearOffAudio.setOnClickListener {
            workingOverrides = workingOverrides.copy(customOffAudioUri = "", customOffAudioLabel = "")
            updateAudioStatusLabels()
        }
        b.btnClearOnAudio.setOnClickListener {
            workingOverrides = workingOverrides.copy(customOnAudioUri = "", customOnAudioLabel = "")
            updateAudioStatusLabels()
        }

        b.btnPreviewStdOff.setOnClickListener { playPreview("off") }
        b.btnPreviewStdOn.setOnClickListener { playPreview("on") }
        b.btnPreviewMp3Off.setOnClickListener { playPreview("off") }
        b.btnPreviewMp3On.setOnClickListener { playPreview("on") }
        b.btnPreviewTtsOff.setOnClickListener { playPreview("off") }
        b.btnPreviewTtsOn.setOnClickListener { playPreview("on") }

        b.btnSaveSettings.setOnClickListener {
            if (!ensureProAllowed()) {
                prefs.saveUserOverrides(UserOverrides(autoOpenAlertScreen = b.switchAutoOpen.isChecked))
                Toast.makeText(this, "Збережено базові налаштування.", Toast.LENGTH_SHORT).show()
                finish()
                return@setOnClickListener
            }
            collectFormIntoOverrides()
            prefs.saveUserOverrides(workingOverrides)
            ScheduleAlarmScheduler.rescheduleNextAlarm(this)
            Toast.makeText(this, "Налаштування збережено!", Toast.LENGTH_SHORT).show()
            finish()
        }

        b.btnResetOverrides.setOnClickListener {
            prefs.resetCustomOverrides()
            workingOverrides = prefs.getUserOverrides()
            populateUi()
            ScheduleAlarmScheduler.rescheduleNextAlarm(this)
            Toast.makeText(this, "Відновлено стандартні налаштування.", Toast.LENGTH_SHORT).show()
        }

        b.btnActivateLicense.setOnClickListener {
            val code = b.etLicenseCode.text?.toString()?.trim().orEmpty()
            if (code.isBlank()) {
                Toast.makeText(this, "Введіть ключ активації.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            b.btnActivateLicense.isEnabled = false
            executor.execute {
                val (ok, msg) = api.activateLicense(code)
                val updatedCfg = api.getCachedSiteConfig()
                mainHandler.post {
                    b.btnActivateLicense.isEnabled = true
                    siteConfig = updatedCfg
                    bindProCardAndLockState()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ok) b.etLicenseCode.setText("")
                }
            }
        }
    }

    private fun playPreview(kind: String) {
        collectFormIntoOverrides()
        prefs.saveUserOverrides(workingOverrides)
        val eff = prefs.resolveEffectiveSettings(siteConfig)
        val time = if (kind == "off") "20:00" else "22:00"
        if (workingOverrides.alertSourceMode == "mp3" &&
            workingOverrides.customOffAudioUri.isBlank() &&
            workingOverrides.customOnAudioUri.isBlank()
        ) {
            Toast.makeText(this, "MP3 не обрано — звучить стандарт додатка.", Toast.LENGTH_SHORT).show()
        } else if (workingOverrides.alertSourceMode == "tts" &&
            workingOverrides.customVoiceTextOff.isBlank() &&
            workingOverrides.customVoiceTextOn.isBlank()
        ) {
            Toast.makeText(this, "Власний текст порожній — звучить стандарт додатка.", Toast.LENGTH_SHORT).show()
        }
        previewPlayer.playAlert(kind, time, prefs.selectedGroup, eff, ignoreQuietHours = true)
    }

    private fun updateSourceSectionVisibility() {
        val mode = when (b.rgAlertSource.checkedRadioButtonId) {
            R.id.rbSourceMp3 -> "mp3"
            R.id.rbSourceTts -> "tts"
            else -> "standard"
        }
        b.layoutSourceStandard.visibility = if (mode == "standard") View.VISIBLE else View.GONE
        b.layoutSourceMp3.visibility = if (mode == "mp3") View.VISIBLE else View.GONE
        b.layoutSourceTts.visibility = if (mode == "tts") View.VISIBLE else View.GONE
    }

    private fun bindProCardAndLockState() {
        val pro = siteConfig.pro
        if (!pro.monetizationEnabled) {
            b.cardPro.visibility = View.GONE
            setOverridesEnabled(true)
            return
        }

        b.cardPro.visibility = View.VISIBLE
        b.tvDeviceCode.text = "Код пристрою: ${prefs.getDeviceId()}"
        b.tvProNote.text = pro.paymentNote.ifBlank {
            "Власні MP3, запис голосу та персональний час попередження — ${pro.priceLabel}."
        }

        when (pro.status) {
            "licensed" -> {
                b.tvProStatus.text = "Pro-версія активована (${pro.licenseCode})"
                setOverridesEnabled(true)
            }
            "trial" -> {
                b.tvProStatus.text = "Безкоштовний тріал Pro: лишилось ${pro.trialDaysLeft} дн."
                setOverridesEnabled(true)
            }
            else -> {
                b.tvProStatus.text = "Пробний період завершився (${pro.priceLabel})"
                setOverridesEnabled(false)
            }
        }

        if (pro.paymentUrl.isNotBlank() && pro.status != "licensed") {
            b.btnPayPro.visibility = View.VISIBLE
            b.btnPayPro.text = "Сплатити (${pro.priceLabel})"
            b.btnPayPro.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pro.paymentUrl)))
            }
        } else {
            b.btnPayPro.visibility = View.GONE
        }
    }

    private fun setOverridesEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.48f
        b.layoutCustomOverrides.alpha = alpha
        b.spinnerNoticeMinutes.isEnabled = enabled
        b.rbSourceStandard.isEnabled = enabled
        b.rbSourceMp3.isEnabled = enabled
        b.rbSourceTts.isEnabled = enabled
        b.etVoiceTextOff.isEnabled = enabled
        b.etVoiceTextOn.isEnabled = enabled
        b.etQuietStart.isEnabled = enabled
        b.etQuietEnd.isEnabled = enabled
        b.sliderVoiceRate.isEnabled = enabled
    }

    private fun ensureProAllowed(): Boolean {
        if (siteConfig.pro.hasProAccess) return true
        Toast.makeText(
            this,
            "Тріал завершився. Введіть ключ активації або користуйтесь стандартними сповіщеннями додатка.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun collectFormIntoOverrides() {
        val noticeIdx = b.spinnerNoticeMinutes.selectedItemPosition
        val noticeVal = noticeValues.getOrElse(noticeIdx) { -1 }

        val sourceMode = when (b.rgAlertSource.checkedRadioButtonId) {
            R.id.rbSourceMp3 -> "mp3"
            R.id.rbSourceTts -> "tts"
            else -> "standard"
        }

        workingOverrides = workingOverrides.copy(
            autoOpenAlertScreen = b.switchAutoOpen.isChecked,
            noticeMinutesOverride = noticeVal,
            alertSourceMode = sourceMode,
            customVoiceTextOff = b.etVoiceTextOff.text?.toString()?.trim().orEmpty(),
            customVoiceTextOn = b.etVoiceTextOn.text?.toString()?.trim().orEmpty(),
            quietStartOverride = b.etQuietStart.text?.toString()?.trim().orEmpty(),
            quietEndOverride = b.etQuietEnd.text?.toString()?.trim().orEmpty()
        )
    }

    private fun handlePickedAudio(kind: String, uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        val label = uri.lastPathSegment?.substringAfterLast('/') ?: "Обраний аудіофайл"
        workingOverrides = if (kind == "off") {
            workingOverrides.copy(
                alertSourceMode = "mp3",
                customOffAudioUri = uri.toString(),
                customOffAudioLabel = label
            )
        } else {
            workingOverrides.copy(
                alertSourceMode = "mp3",
                customOnAudioUri = uri.toString(),
                customOnAudioLabel = label
            )
        }
        updateAudioStatusLabels()
    }

    private fun toggleVoiceRecording(kind: String) {
        if (recordingKind != null) {
            val activeKind = recordingKind!!
            stopActiveRecording(saveResult = true)
            if (activeKind == kind) return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecordKind = kind
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val dir = File(filesDir, "custom_voices").apply { mkdirs() }
        val file = File(dir, if (kind == "off") "voice_off.m4a" else "voice_on.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            recordingKind = kind
            updateRecordButtons()
            Toast.makeText(this, "Запис голосу почався… Натисніть ще раз, щоб зупинити.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Не вдалося запустити запис: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopActiveRecording(saveResult: Boolean) {
        val kind = recordingKind ?: return
        val recorder = mediaRecorder
        mediaRecorder = null
        recordingKind = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        if (saveResult) {
            val dir = File(filesDir, "custom_voices")
            val file = File(dir, if (kind == "off") "voice_off.m4a" else "voice_on.m4a")
            if (file.exists() && file.length() > 0L) {
                val uriStr = Uri.fromFile(file).toString()
                workingOverrides = if (kind == "off") {
                    workingOverrides.copy(
                        alertSourceMode = "mp3",
                        customOffAudioUri = uriStr,
                        customOffAudioLabel = "Запис з мікрофона"
                    )
                } else {
                    workingOverrides.copy(
                        alertSourceMode = "mp3",
                        customOnAudioUri = uriStr,
                        customOnAudioLabel = "Запис з мікрофона"
                    )
                }
                Toast.makeText(this, "Голосовий запис збережено!", Toast.LENGTH_SHORT).show()
            }
        }
        updateRecordButtons()
        updateAudioStatusLabels()
    }

    private fun updateRecordButtons() {
        b.btnRecordOffVoice.text = if (recordingKind == "off") "⏹ Зупинити" else "🎙 Наговорити"
        b.btnRecordOnVoice.text = if (recordingKind == "on") "⏹ Зупинити" else "🎙 Наговорити"
    }

    private fun updateAudioStatusLabels() {
        b.tvOffAudioStatus.text = if (workingOverrides.customOffAudioUri.isNotBlank()) {
            "Обрано: ${workingOverrides.customOffAudioLabel.ifBlank { "аудіофайл" }}"
        } else {
            "MP3 не обрано (працюватиме стандарт)"
        }

        b.tvOnAudioStatus.text = if (workingOverrides.customOnAudioUri.isNotBlank()) {
            "Обрано: ${workingOverrides.customOnAudioLabel.ifBlank { "аудіофайл" }}"
        } else {
            "MP3 не обрано (працюватиме стандарт)"
        }
    }
}

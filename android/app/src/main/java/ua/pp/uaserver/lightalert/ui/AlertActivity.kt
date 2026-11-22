package ua.pp.uaserver.lightalert.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
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
import ua.pp.uaserver.lightalert.databinding.ActivityAlertBinding
import java.util.Locale

class AlertActivity : AppCompatActivity() {

    private lateinit var b: ActivityAlertBinding
    private lateinit var player: AlertAudioPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var eventTimeMillis: Long = 0L

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val leftSec = ((eventTimeMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            val m = leftSec / 60L
            val s = leftSec % 60L
            b.tvAlertCountdown.text = String.format(Locale.US, "Залишилось: %02d:%02d", m, s)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.alertRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, sys.top + pad, pad, sys.bottom + pad)
            insets
        }

        player = AlertAudioPlayer(this)

        val type = intent.getStringExtra(OutageAlarmReceiver.EXTRA_TYPE) ?: "off"
        eventTimeMillis = intent.getLongExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_MILLIS, System.currentTimeMillis() + 300_000L)
        val eventTimeFmt = intent.getStringExtra(OutageAlarmReceiver.EXTRA_EVENT_TIME_FMT) ?: "--:--"
        val region = intent.getStringExtra(OutageAlarmReceiver.EXTRA_REGION) ?: ""
        val group = intent.getStringExtra(OutageAlarmReceiver.EXTRA_GROUP) ?: ""
        val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, true)
        val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)

        val prefs = PrefsStore(this)
        val api = ApiClient(this, prefs)
        val cfg = api.getCachedSiteConfig()
        val effective = prefs.resolveEffectiveSettings(cfg)

        val regionName = cfg.regions.firstOrNull { it.id == region }?.name ?: region
        val cleanGroup = group.replace(Regex("^GPV", RegexOption.IGNORE_CASE), "")

        if (type == "off") {
            b.tvAlertBadge.text = if (isTest) "ТЕСТ ПОПЕРЕДЖЕННЯ" else "ВІДКЛЮЧЕННЯ СВІТЛА"
            b.tvAlertBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_off_bg))
            b.tvAlertBadge.setTextColor(ContextCompat.getColor(this, R.color.status_off_fg))
            b.tvAlertTitle.text = "Очікується відключення світла"
            b.tvAlertSubtitle.text = effective.voiceTextOff
        } else {
            b.tvAlertBadge.text = if (isTest) "ТЕСТ ПОПЕРЕДЖЕННЯ" else "ВІДНОВЛЕННЯ СВІТЛА"
            b.tvAlertBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_on_bg))
            b.tvAlertBadge.setTextColor(ContextCompat.getColor(this, R.color.status_on_fg))
            b.tvAlertTitle.text = "Очікується увімкнення світла"
            b.tvAlertSubtitle.text = effective.voiceTextOn
        }

        b.tvAlertTime.text = eventTimeFmt
        b.tvAlertGroup.text = listOf(regionName, if (cleanGroup.isNotBlank()) "Черга $cleanGroup" else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        b.btnStopAudio.setOnClickListener {
            dismissAlert()
            finish()
        }

        b.btnOpenSchedule.setOnClickListener {
            dismissAlert()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
            finish()
        }

        if (autoPlay) {
            player.playAlert(
                type = type,
                eventTime = eventTimeFmt,
                groupLabel = group,
                settings = effective,
                ignoreQuietHours = isTest
            )
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(countdownRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
    }

    private fun dismissAlert() {
        player.stop()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ScheduleAlarmScheduler.ALERT_NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_AUTO_PLAY = "extra_auto_play"
        const val EXTRA_IS_TEST = "extra_is_test"
    }
}

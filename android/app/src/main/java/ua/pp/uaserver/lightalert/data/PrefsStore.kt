package ua.pp.uaserver.lightalert.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class PrefsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ua_light_alert_prefs", Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "AND-" + UUID.randomUUID().toString()
            .replace("-", "")
            .uppercase()
            .take(12)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    var selectedRegion: String
        get() = prefs.getString(KEY_REGION, "kyiv") ?: "kyiv"
        set(value) = prefs.edit().putString(KEY_REGION, value).apply()

    var selectedGroup: String
        get() = prefs.getString(KEY_GROUP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROUP, value).apply()

    var lastAlertedEventKey: String
        get() = prefs.getString(KEY_LAST_ALERTED_EVENT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ALERTED_EVENT, value).apply()

    var cachedConfigJson: String
        get() = prefs.getString(KEY_CACHED_CONFIG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_CONFIG, value).apply()

    var cachedAdsJson: String
        get() = prefs.getString(KEY_CACHED_ADS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_ADS, value).apply()

    var lastUpdateCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    var dismissedUpdateVersion: String
        get() = prefs.getString(KEY_DISMISSED_UPDATE_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, value).apply()

    fun saveScheduleJson(region: String, json: String) {
        prefs.edit().putString("schedule_$region", json).apply()
    }

    fun getScheduleJson(region: String): String {
        return prefs.getString("schedule_$region", "") ?: ""
    }

    fun getUserOverrides(): UserOverrides {
        return UserOverrides(
            autoOpenAlertScreen = prefs.getBoolean(KEY_AUTO_OPEN_SCREEN, true),
            noticeMinutesOverride = prefs.getInt(KEY_NOTICE_MIN_OVERRIDE, -1),
            alertModeOverride = prefs.getString(KEY_ALERT_MODE_OVERRIDE, "") ?: "",
            customOffAudioUri = prefs.getString(KEY_CUSTOM_OFF_URI, "") ?: "",
            customOnAudioUri = prefs.getString(KEY_CUSTOM_ON_URI, "") ?: "",
            customOffAudioLabel = prefs.getString(KEY_CUSTOM_OFF_LABEL, "") ?: "",
            customOnAudioLabel = prefs.getString(KEY_CUSTOM_ON_LABEL, "") ?: "",
            customVoiceTextOff = prefs.getString(KEY_CUSTOM_TEXT_OFF, "") ?: "",
            customVoiceTextOn = prefs.getString(KEY_CUSTOM_TEXT_ON, "") ?: "",
            voiceRateOverride = prefs.getFloat(KEY_VOICE_RATE_OVERRIDE, -1f),
            quietStartOverride = prefs.getString(KEY_QUIET_START_OVERRIDE, "") ?: "",
            quietEndOverride = prefs.getString(KEY_QUIET_END_OVERRIDE, "") ?: ""
        )
    }

    fun saveUserOverrides(overrides: UserOverrides) {
        prefs.edit()
            .putBoolean(KEY_AUTO_OPEN_SCREEN, overrides.autoOpenAlertScreen)
            .putInt(KEY_NOTICE_MIN_OVERRIDE, overrides.noticeMinutesOverride)
            .putString(KEY_ALERT_MODE_OVERRIDE, overrides.alertModeOverride)
            .putString(KEY_CUSTOM_OFF_URI, overrides.customOffAudioUri)
            .putString(KEY_CUSTOM_ON_URI, overrides.customOnAudioUri)
            .putString(KEY_CUSTOM_OFF_LABEL, overrides.customOffAudioLabel)
            .putString(KEY_CUSTOM_ON_LABEL, overrides.customOnAudioLabel)
            .putString(KEY_CUSTOM_TEXT_OFF, overrides.customVoiceTextOff)
            .putString(KEY_CUSTOM_TEXT_ON, overrides.customVoiceTextOn)
            .putFloat(KEY_VOICE_RATE_OVERRIDE, overrides.voiceRateOverride)
            .putString(KEY_QUIET_START_OVERRIDE, overrides.quietStartOverride)
            .putString(KEY_QUIET_END_OVERRIDE, overrides.quietEndOverride)
            .apply()
    }

    fun resetCustomOverrides() {
        val currentAutoOpen = getUserOverrides().autoOpenAlertScreen
        saveUserOverrides(UserOverrides(autoOpenAlertScreen = currentAutoOpen))
    }

    fun resolveEffectiveSettings(siteConfig: SiteConfig): EffectiveAlertSettings {
        val site = siteConfig.settings
        val proAllowed = siteConfig.pro.hasProAccess
        val user = getUserOverrides()

        if (!proAllowed) {
            return EffectiveAlertSettings(
                noticeMinutes = site.noticeMinutes.coerceIn(1, 60),
                alertMode = site.alertMode,
                customOffAudioUri = "",
                customOnAudioUri = "",
                remoteOffAudioUrl = site.soundOffUrl,
                remoteOnAudioUrl = site.soundOnUrl,
                voiceRate = site.voiceRate.coerceIn(0.7f, 1.3f),
                voicePitch = site.voicePitch.coerceIn(0.8f, 1.2f),
                voiceTextOff = site.voiceTextOff,
                voiceTextOn = site.voiceTextOn,
                voiceVolume = site.voiceVolume,
                soundVolume = site.soundVolume,
                alertRepeat = site.alertRepeat,
                quietStart = site.quietStart,
                quietEnd = site.quietEnd
            )
        }

        val noticeMinutes = if (user.noticeMinutesOverride > 0) {
            user.noticeMinutesOverride.coerceIn(1, 60)
        } else {
            site.noticeMinutes.coerceIn(1, 60)
        }

        val alertMode = if (user.alertModeOverride.isNotBlank()) {
            user.alertModeOverride
        } else {
            site.alertMode
        }

        val voiceRate = if (user.voiceRateOverride > 0f) {
            user.voiceRateOverride.coerceIn(0.7f, 1.3f)
        } else {
            site.voiceRate.coerceIn(0.7f, 1.3f)
        }

        val quietStart = if (user.quietStartOverride.isNotBlank() || user.quietEndOverride.isNotBlank()) {
            user.quietStartOverride
        } else {
            site.quietStart
        }

        val quietEnd = if (user.quietStartOverride.isNotBlank() || user.quietEndOverride.isNotBlank()) {
            user.quietEndOverride
        } else {
            site.quietEnd
        }

        return EffectiveAlertSettings(
            noticeMinutes = noticeMinutes,
            alertMode = alertMode,
            customOffAudioUri = user.customOffAudioUri,
            customOnAudioUri = user.customOnAudioUri,
            remoteOffAudioUrl = site.soundOffUrl,
            remoteOnAudioUrl = site.soundOnUrl,
            voiceRate = voiceRate,
            voicePitch = site.voicePitch.coerceIn(0.8f, 1.2f),
            voiceTextOff = user.customVoiceTextOff.ifBlank { site.voiceTextOff },
            voiceTextOn = user.customVoiceTextOn.ifBlank { site.voiceTextOn },
            voiceVolume = site.voiceVolume,
            soundVolume = site.soundVolume,
            alertRepeat = site.alertRepeat,
            quietStart = quietStart,
            quietEnd = quietEnd
        )
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_REGION = "selected_region"
        private const val KEY_GROUP = "selected_group"
        private const val KEY_LAST_ALERTED_EVENT = "last_alerted_event_key"
        private const val KEY_CACHED_CONFIG = "cached_config_json"
        private const val KEY_CACHED_ADS = "cached_ads_json"
        private const val KEY_AUTO_OPEN_SCREEN = "auto_open_alert_screen"
        private const val KEY_NOTICE_MIN_OVERRIDE = "notice_min_override"
        private const val KEY_ALERT_MODE_OVERRIDE = "alert_mode_override"
        private const val KEY_CUSTOM_OFF_URI = "custom_off_audio_uri"
        private const val KEY_CUSTOM_ON_URI = "custom_on_audio_uri"
        private const val KEY_CUSTOM_OFF_LABEL = "custom_off_audio_label"
        private const val KEY_CUSTOM_ON_LABEL = "custom_on_audio_label"
        private const val KEY_CUSTOM_TEXT_OFF = "custom_voice_text_off"
        private const val KEY_CUSTOM_TEXT_ON = "custom_voice_text_on"
        private const val KEY_VOICE_RATE_OVERRIDE = "voice_rate_override"
        private const val KEY_QUIET_START_OVERRIDE = "quiet_start_override"
        private const val KEY_QUIET_END_OVERRIDE = "quiet_end_override"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_millis"
        private const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
    }
}

package ua.pp.uaserver.lightalert.data

data class RegionItem(
    val id: String,
    val name: String
)

data class SiteAlertSettings(
    val noticeMinutes: Int = 5,
    val alertMode: String = "both", // "both" | "speech" | "audio"
    val soundOffUrl: String = "",
    val soundOnUrl: String = "",
    val voiceEnabled: Boolean = true,
    val voiceGender: String = "female",
    val voiceRate: Float = 0.95f,
    val voicePitch: Float = 1.0f,
    val voiceTextOff: String = "Увага! За графіком очікується відключення електроенергії.",
    val voiceTextOn: String = "Увага! За графіком очікується відновлення електропостачання.",
    val voiceVolume: Int = 80,
    val soundVolume: Int = 80,
    val alertRepeat: Int = 0,
    val quietStart: String = "",
    val quietEnd: String = ""
)

data class ProStatus(
    val monetizationEnabled: Boolean = false,
    val hasProAccess: Boolean = true,
    val status: String = "free", // "free" | "trial" | "licensed" | "expired"
    val trialDaysTotal: Int = 30,
    val trialDaysLeft: Int = 30,
    val priceLabel: String = "100 грн (один раз)",
    val paymentUrl: String = "",
    val paymentNote: String = "",
    val licenseCode: String = ""
)

data class SiteConfig(
    val pluginVersion: String = "4.9.0",
    val siteName: String = "Графік відключень",
    val scheduleUrl: String = "https://uaserver.pp.ua/svitlo/",
    val regions: List<RegionItem> = defaultRegions(),
    val settings: SiteAlertSettings = SiteAlertSettings(),
    val pro: ProStatus = ProStatus()
) {
    companion object {
        fun defaultRegions(): List<RegionItem> = listOf(
            RegionItem("kyiv", "Київ"),
            RegionItem("kyiv-region", "Київська область"),
            RegionItem("odesa", "Одеська область"),
            RegionItem("dnipro", "Дніпропетровська область"),
            RegionItem("lviv", "Львівська область"),
            RegionItem("kharkiv", "Харківська область")
        )
    }
}

data class AdItem(
    val name: String,
    val text: String,
    val imageUrl: String,
    val altText: String,
    val primaryUrl: String
)

data class UserOverrides(
    val autoOpenAlertScreen: Boolean = true,
    val noticeMinutesOverride: Int = -1, // -1 = standard app/site setting
    val alertSourceMode: String = "standard", // "standard" | "mp3" | "tts"
    val alertModeOverride: String = "",
    val customOffAudioUri: String = "",
    val customOnAudioUri: String = "",
    val customOffAudioLabel: String = "",
    val customOnAudioLabel: String = "",
    val customVoiceTextOff: String = "",
    val customVoiceTextOn: String = "",
    val voiceRateOverride: Float = -1f, // < 0 = standard
    val quietStartOverride: String = "",
    val quietEndOverride: String = ""
)

data class EffectiveAlertSettings(
    val noticeMinutes: Int,
    val alertMode: String,
    val customOffAudioUri: String,
    val customOnAudioUri: String,
    val remoteOffAudioUrl: String,
    val remoteOnAudioUrl: String,
    val voiceRate: Float,
    val voicePitch: Float,
    val voiceTextOff: String,
    val voiceTextOn: String,
    val voiceVolume: Int,
    val soundVolume: Int,
    val alertRepeat: Int,
    val quietStart: String,
    val quietEnd: String
)

data class HalfHourSlotRow(
    val slotIndex: Int, // 0..47
    val timeRangeLabel: String, // "00:00 — 00:30"
    val status: String?, // "yes" | "no" | null
    val isCurrentSlot: Boolean
)

data class NextOutageEvent(
    val type: String, // "off" | "on"
    val eventTimeMillis: Long,
    val eventTimeFormatted: String,
    val currentStatus: String // "yes" | "no"
)

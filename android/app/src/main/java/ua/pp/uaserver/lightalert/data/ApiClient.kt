package ua.pp.uaserver.lightalert.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import ua.pp.uaserver.lightalert.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

class ApiClient(private val context: Context, private val prefs: PrefsStore) {

    fun fetchSiteConfig(): SiteConfig {
        val url = "${BuildConfig.SITE_BASE_URL}/wp-json/ua-light-alert/v1/config" +
            "?device_id=${URLEncoder.encode(prefs.getDeviceId(), "UTF-8")}" +
            "&app_version=${URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")}"

        return try {
            val raw = httpGet(url)
            prefs.cachedConfigJson = raw
            val cfg = parseSiteConfig(raw)
            cacheRemoteAudioFile(cfg.settings.soundOffUrl, "site_off")
            cacheRemoteAudioFile(cfg.settings.soundOnUrl, "site_on")
            cfg
        } catch (_: Exception) {
            getCachedSiteConfig()
        }
    }

    fun getCachedSiteConfig(): SiteConfig {
        val raw = prefs.cachedConfigJson
        if (raw.isBlank()) return SiteConfig()
        return try {
            parseSiteConfig(raw)
        } catch (_: Exception) {
            SiteConfig()
        }
    }

    fun fetchAds(region: String, group: String): List<AdItem> {
        val url = "${BuildConfig.SITE_BASE_URL}/wp-json/ua-light-alert/v1/ads" +
            "?region=${URLEncoder.encode(region, "UTF-8")}" +
            "&group=${URLEncoder.encode(group, "UTF-8")}"

        return try {
            val raw = httpGet(url)
            prefs.cachedAdsJson = raw
            parseAds(raw)
        } catch (_: Exception) {
            val cached = prefs.cachedAdsJson
            if (cached.isNotBlank()) {
                try {
                    parseAds(cached)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    fun trackAd(type: String, region: String, group: String) {
        try {
            val payload = JSONObject().apply {
                put("type", type)
                put("region", region)
                put("group", group)
            }.toString()
            httpPostJson("${BuildConfig.SITE_BASE_URL}/wp-json/ua-light-alert/v1/ads/track", payload)
        } catch (_: Exception) {
        }
    }

    fun activateLicense(code: String): Pair<Boolean, String> {
        return try {
            val payload = JSONObject().apply {
                put("device_id", prefs.getDeviceId())
                put("code", code.trim())
            }.toString()
            val response = httpPostJson("${BuildConfig.SITE_BASE_URL}/wp-json/ua-light-alert/v1/license/activate", payload)
            val json = JSONObject(response)
            val success = json.optBoolean("success", false)
            val message = json.optString("message", if (success) "Активовано!" else "Не вдалося активувати")
            if (success) {
                fetchSiteConfig()
            }
            Pair(success, message)
        } catch (e: Exception) {
            Pair(false, "Помилка з'єднання з сервером: ${e.message ?: "невідомо"}")
        }
    }

    fun fetchRegionScheduleJson(region: String): String {
        val encoded = URLEncoder.encode(region, "UTF-8")
        val url = "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/$encoded.json"
        return try {
            val raw = httpGet(url)
            prefs.saveScheduleJson(region, raw)
            raw
        } catch (_: Exception) {
            prefs.getScheduleJson(region)
        }
    }

    fun getCachedAudioFile(remoteUrl: String, prefix: String): File? {
        if (remoteUrl.isBlank()) return null
        val hash = sha1Short(remoteUrl)
        val dir = File(context.filesDir, "audio_cache")
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.name.startsWith("${prefix}_$hash") && it.length() > 0L }
    }

    private fun cacheRemoteAudioFile(remoteUrl: String, prefix: String) {
        if (remoteUrl.isBlank()) return
        try {
            val hash = sha1Short(remoteUrl)
            val dir = File(context.filesDir, "audio_cache")
            if (!dir.exists()) dir.mkdirs()
            val existing = dir.listFiles()?.firstOrNull { it.name.startsWith("${prefix}_$hash") && it.length() > 0L }
            if (existing != null) return

            val ext = when {
                remoteUrl.contains(".wav", ignoreCase = true) -> "wav"
                remoteUrl.contains(".m4a", ignoreCase = true) -> "m4a"
                remoteUrl.contains(".ogg", ignoreCase = true) -> "ogg"
                else -> "mp3"
            }
            val target = File(dir, "${prefix}_${hash}.$ext")
            val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun sha1Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun parseSiteConfig(raw: String): SiteConfig {
        val root = JSONObject(raw)
        val regionsJson = root.optJSONArray("regions")
        val regions = mutableListOf<RegionItem>()
        if (regionsJson != null) {
            for (i in 0 until regionsJson.length()) {
                val item = regionsJson.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) {
                    regions.add(RegionItem(id, name))
                }
            }
        }

        val s = root.optJSONObject("settings") ?: JSONObject()
        val settings = SiteAlertSettings(
            noticeMinutes = s.optInt("notice_minutes", 5),
            alertMode = s.optString("alert_mode", "both"),
            soundOffUrl = s.optString("sound_off_url", ""),
            soundOnUrl = s.optString("sound_on_url", ""),
            voiceEnabled = s.optBoolean("voice_enabled", true),
            voiceGender = s.optString("voice_gender", "female"),
            voiceRate = s.optDouble("voice_rate", 0.95).toFloat(),
            voicePitch = s.optDouble("voice_pitch", 1.0).toFloat(),
            voiceTextOff = s.optString(
                "voice_text_off",
                "Увага! За графіком очікується відключення електроенергії."
            ),
            voiceTextOn = s.optString(
                "voice_text_on",
                "Увага! За графіком очікується відновлення електропостачання."
            ),
            voiceVolume = s.optInt("voice_volume", 80),
            soundVolume = s.optInt("sound_volume", 80),
            alertRepeat = s.optInt("alert_repeat", 0),
            quietStart = s.optString("quiet_start", ""),
            quietEnd = s.optString("quiet_end", "")
        )

        val p = root.optJSONObject("pro") ?: JSONObject()
        val pro = ProStatus(
            monetizationEnabled = p.optBoolean("monetization_enabled", false),
            hasProAccess = p.optBoolean("has_pro_access", true),
            status = p.optString("status", "free"),
            trialDaysTotal = p.optInt("trial_days_total", 30),
            trialDaysLeft = p.optInt("trial_days_left", 30),
            priceLabel = p.optString("price_label", "100 грн (один раз)"),
            paymentUrl = p.optString("payment_url", ""),
            paymentNote = p.optString("payment_note", ""),
            licenseCode = p.optString("license_code", "")
        )

        return SiteConfig(
            pluginVersion = root.optString("plugin_version", "4.8.0"),
            siteName = "Графік відключень",
            scheduleUrl = root.optString("schedule_url", "${BuildConfig.SITE_BASE_URL}/svitlo/"),
            regions = if (regions.isNotEmpty()) regions else SiteConfig.defaultRegions(),
            settings = settings,
            pro = pro
        )
    }

    fun fetchBitmap(urlStr: String): Bitmap? {
        if (urlStr.isBlank()) return null
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAds(raw: String): List<AdItem> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("ads") ?: return emptyList()
        val result = mutableListOf<AdItem>()
        val imgSrcRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val imgAltRegex = Regex("""<img[^>]+alt=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val html = obj.optString("html", "")
            val text = obj.optString("text", "").trim()
            val imageUrl = obj.optString("image_url", "").trim().ifBlank {
                imgSrcRegex.find(html)?.groupValues?.getOrNull(1).orEmpty().trim()
            }
            val altText = obj.optString("alt", "").trim().ifBlank {
                imgAltRegex.find(html)?.groupValues?.getOrNull(1).orEmpty().trim()
            }
            val primaryUrl = obj.optString("primary_url", "").trim().ifBlank {
                hrefRegex.find(html)?.groupValues?.getOrNull(1).orEmpty().trim()
            }

            if (imageUrl.isNotBlank() || text.isNotBlank() || altText.isNotBlank()) {
                result.add(
                    AdItem(
                        name = obj.optString("name", ""),
                        text = text,
                        imageUrl = imageUrl,
                        altText = altText,
                        primaryUrl = primaryUrl
                    )
                )
            }
        }
        return result
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun getLatestGroupsObject(scheduleJson: String): Pair<JSONObject?, String> {
            if (scheduleJson.isBlank()) return Pair(null, "")
            return try {
                val root = JSONObject(scheduleJson)
                val fact = root.optJSONObject("fact") ?: return Pair(null, "")
                val dataObj = fact.optJSONObject("data") ?: return Pair(null, "")
                val keys = mutableListOf<String>()
                val it = dataObj.keys()
                while (it.hasNext()) keys.add(it.next())
                if (keys.isEmpty()) return Pair(null, "")
                keys.sort()
                val latestKey = keys.last()
                val updated = root.optString("lastUpdated", "")
                Pair(dataObj.optJSONObject(latestKey), updated)
            } catch (_: Exception) {
                Pair(null, "")
            }
        }

        fun extractGroupIds(groupsObj: JSONObject?): List<String> {
            if (groupsObj == null) return emptyList()
            val list = mutableListOf<String>()
            val it = groupsObj.keys()
            while (it.hasNext()) list.add(it.next())
            list.sort()
            return list
        }

        fun normalizeHalfHourStatus(sourceStatus: String?, half: Int): String? {
            return when (sourceStatus) {
                "yes", "no" -> sourceStatus
                "first" -> if (half == 0) "no" else "yes"
                "second" -> if (half == 0) "yes" else "no"
                else -> null
            }
        }

        fun buildHalfHourRows(groupObj: JSONObject?): List<HalfHourSlotRow> {
            if (groupObj == null) return emptyList()
            val cal = Calendar.getInstance()
            val currentSlot = cal.get(Calendar.HOUR_OF_DAY) * 2 + if (cal.get(Calendar.MINUTE) >= 30) 1 else 0
            val rows = mutableListOf<HalfHourSlotRow>()
            for (slot in 0 until 48) {
                val hourKey = ((slot / 2) + 1).toString()
                val raw = groupObj.optString(hourKey, "")
                val half = slot % 2
                val startMinutes = slot * 30
                val endMinutes = (startMinutes + 30) % 1440
                val range = String.format(
                    Locale.US,
                    "%02d:%02d — %02d:%02d",
                    startMinutes / 60,
                    startMinutes % 60,
                    endMinutes / 60,
                    endMinutes % 60
                )
                rows.add(
                    HalfHourSlotRow(
                        slotIndex = slot,
                        timeRangeLabel = range,
                        status = normalizeHalfHourStatus(raw, half),
                        isCurrentSlot = (slot == currentSlot)
                    )
                )
            }
            return rows
        }

        fun findNextOutageEvent(groupObj: JSONObject?): NextOutageEvent? {
            if (groupObj == null) return null
            val nowCal = Calendar.getInstance()
            val currentSlot = nowCal.get(Calendar.HOUR_OF_DAY) * 2 +
                if (nowCal.get(Calendar.MINUTE) >= 30) 1 else 0
            val currentKey = ((currentSlot / 2) + 1).toString()
            val currentStatus = normalizeHalfHourStatus(groupObj.optString(currentKey, ""), currentSlot % 2)
                ?: return null

            for (offset in 1..48) {
                val slot = (currentSlot + offset) % 48
                val key = ((slot / 2) + 1).toString()
                val status = normalizeHalfHourStatus(groupObj.optString(key, ""), slot % 2) ?: continue
                if (status != currentStatus) {
                    val eventCal = Calendar.getInstance().apply {
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        val totalMinutes = (currentSlot + offset) * 30
                        add(Calendar.MINUTE, totalMinutes)
                    }
                    val formatted = String.format(
                        Locale.US,
                        "%02d:%02d",
                        eventCal.get(Calendar.HOUR_OF_DAY),
                        eventCal.get(Calendar.MINUTE)
                    )
                    val type = if (status == "no") "off" else "on"
                    return NextOutageEvent(
                        type = type,
                        eventTimeMillis = eventCal.timeInMillis,
                        eventTimeFormatted = formatted,
                        currentStatus = currentStatus
                    )
                }
            }
            return null
        }
    }
}

package ua.pp.uaserver.lightalert.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import ua.pp.uaserver.lightalert.data.ApiClient
import ua.pp.uaserver.lightalert.data.EffectiveAlertSettings
import java.io.File
import java.util.Calendar
import java.util.Locale

class AlertAudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var stopped = false

    fun playAlert(
        type: String,
        eventTime: String,
        groupLabel: String,
        settings: EffectiveAlertSettings,
        ignoreQuietHours: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        stop()
        stopped = false

        if (!ignoreQuietHours && isWithinQuietHours(settings.quietStart, settings.quietEnd)) {
            onComplete?.invoke()
            return
        }

        val customUriStr = if (type == "off") settings.customOffAudioUri else settings.customOnAudioUri
        val remoteUrl = if (type == "off") settings.remoteOffAudioUrl else settings.remoteOnAudioUrl
        val prefix = if (type == "off") "site_off" else "site_on"
        val rawText = if (type == "off") settings.voiceTextOff else settings.voiceTextOn

        val mode = settings.alertMode
        val shouldPlayAudio = mode == "both" || mode == "audio"
        val shouldSpeak = mode == "both" || mode == "speech"

        val apiClient = ApiClient(context, ua.pp.uaserver.lightalert.data.PrefsStore(context))
        val cachedRemoteFile = apiClient.getCachedAudioFile(remoteUrl, prefix)

        val soundVol = (settings.soundVolume.coerceIn(0, 100) / 100f)
        val totalRepeats = settings.alertRepeat.coerceIn(0, 3)

        fun playCycle(remainingRepeats: Int) {
            if (stopped) {
                onComplete?.invoke()
                return
            }

            val afterSpeech: () -> Unit = {
                if (!stopped && remainingRepeats > 0) {
                    mainHandler.postDelayed({ playCycle(remainingRepeats - 1) }, 500L)
                } else {
                    onComplete?.invoke()
                }
            }

            val startSpeechOrFinish: () -> Unit = {
                if (!stopped && shouldSpeak) {
                    val normalized = UkrainianSpeechNormalizer.normalize(rawText, eventTime, groupLabel)
                    speakUkrainian(normalized, settings, afterSpeech)
                } else {
                    afterSpeech()
                }
            }

            if (shouldPlayAudio) {
                val played = startAudioTrack(
                    customUriStr = customUriStr,
                    cachedFile = cachedRemoteFile,
                    remoteUrl = remoteUrl,
                    fallbackSystemTone = (mode == "audio" && customUriStr.isBlank() && cachedRemoteFile == null && remoteUrl.isBlank()),
                    volume = soundVol,
                    onFinished = startSpeechOrFinish
                )
                if (!played) {
                    startSpeechOrFinish()
                }
            } else {
                startSpeechOrFinish()
            }
        }

        playCycle(totalRepeats)
    }

    private fun startAudioTrack(
        customUriStr: String,
        cachedFile: File?,
        remoteUrl: String,
        fallbackSystemTone: Boolean,
        volume: Float,
        onFinished: () -> Unit
    ): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(attrs)
            when {
                customUriStr.isNotBlank() -> player.setDataSource(context, Uri.parse(customUriStr))
                cachedFile != null && cachedFile.exists() -> player.setDataSource(cachedFile.absolutePath)
                remoteUrl.isNotBlank() -> player.setDataSource(remoteUrl)
                fallbackSystemTone -> {
                    val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    player.setDataSource(context, tone)
                }
                else -> {
                    player.release()
                    return false
                }
            }
            player.setVolume(volume, volume)
            player.setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
                if (!stopped) onFinished()
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
                if (!stopped) onFinished()
                true
            }
            player.setOnPreparedListener { mp ->
                if (stopped) {
                    mp.release()
                    return@setOnPreparedListener
                }
                mp.start()
            }
            mediaPlayer = player
            player.prepareAsync()
            true
        } catch (_: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun speakUkrainian(
        text: String,
        settings: EffectiveAlertSettings,
        onFinished: () -> Unit
    ) {
        val sentences = UkrainianSpeechNormalizer.splitSentences(text)
        if (sentences.isEmpty()) {
            onFinished()
            return
        }

        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            if (stopped || status != TextToSpeech.SUCCESS) {
                if (!stopped) onFinished()
                return@TextToSpeech
            }
            val ukLocale = Locale("uk", "UA")
            engine.language = ukLocale
            selectBestUkrainianVoice(engine)?.let { engine.voice = it }
            engine.setSpeechRate(settings.voiceRate.coerceIn(0.7f, 1.3f))
            engine.setPitch(settings.voicePitch.coerceIn(0.8f, 1.2f))

            var index = 0
            fun speakNext() {
                if (stopped || index >= sentences.size) {
                    onFinished()
                    return
                }
                val utteranceId = "ua_alert_${System.currentTimeMillis()}_$index"
                engine.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    index++
                    mainHandler.postDelayed({ speakNext() }, 120L)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { onFinished() }
                }
            })

            mainHandler.post { speakNext() }
        }
        tts = engine
    }

    private fun selectBestUkrainianVoice(engine: TextToSpeech): Voice? {
        return try {
            val voices = engine.voices ?: return null
            voices.filter { it.locale.language.equals("uk", ignoreCase = true) }
                .maxByOrNull { v ->
                    var score = v.quality
                    val lower = v.name.lowercase(Locale.US)
                    if (lower.contains("network") || lower.contains("neural") || lower.contains("wavenet")) {
                        score += 500
                    }
                    if (!v.isNetworkConnectionRequired) score += 200
                    score
                }
        } catch (_: Exception) {
            null
        }
    }

    fun isWithinQuietHours(startStr: String, endStr: String): Boolean {
        val startMin = parseHhMmToMinutes(startStr) ?: return false
        val endMin = parseHhMmToMinutes(endStr) ?: return false
        if (startMin == endMin) return false
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMin < endMin) {
            nowMin in startMin until endMin
        } else {
            nowMin >= startMin || nowMin < endMin
        }
    }

    private fun parseHhMmToMinutes(raw: String): Int? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null

        tts?.let { engine ->
            try {
                engine.stop()
                engine.shutdown()
            } catch (_: Exception) {
            }
        }
        tts = null
    }
}

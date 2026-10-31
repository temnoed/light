package com.example.light.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Встроенный тест детектора бита: телефон сам играет серию басовых ударов
 * через динамик, а приложение в это время слушает микрофон.
 *
 * Так можно проверить весь тракт «микрофон → БПФ → детектор» без колонки
 * и без реальной музыки: результат не зависит от внешних условий.
 */
class BeatSelfTest(context: Context) {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Играет [clicks] ударов с темпом [bpm]. Блокирует вызывающий поток
     * примерно на [clicks] * 60000/bpm мс + хвост.
     *
     * @return true, если удалось создать и проиграть дорожку.
     */
    fun playClicks(clicks: Int = CLICKS, bpm: Int = BPM): Boolean {
        val intervalMs = 60_000.0 / bpm.coerceIn(40, 240)
        val clickDurationMs = CLICK_MS
        val tailMs = 400.0
        val totalMs = intervalMs * (clicks - 1) + clickDurationMs + tailMs
        val totalSamples = (SAMPLE_RATE * totalMs / 1000.0).toInt()

        val buffer = ShortArray(totalSamples)
        val click = renderClick()
        for (n in 0 until clicks) {
            val start = (SAMPLE_RATE * (n * intervalMs / 1000.0)).toInt()
            for (i in click.indices) {
                val idx = start + i
                if (idx >= buffer.size) break
                val mixed = buffer[idx] + click[i]
                buffer[idx] = mixed
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Throwable) {
            return false
        }

        var played = false
        val originalVolume = currentMediaVolume()
        var boosted = false
        try {
            // Тест должен быть слышен даже при низкой громкости медиа.
            boosted = ensureAudibleVolume()
            routeToSpeaker(track)
            val written = track.write(buffer, 0, buffer.size)
            if (written <= 0) return false

            // Без этого на некоторых прошивках дорожка играет в наушники.
            @Suppress("DEPRECATION")
            runCatching {
                audioManager?.let { track.setStereoVolume(1f, 1f) }
            }
            track.play()
            played = true
            Thread.sleep(totalMs.toLong())
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (boosted && originalVolume != null) {
                runCatching {
                    audioManager?.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        originalVolume,
                        0
                    )
                }
            }
        }
        return played
    }

    private fun currentMediaVolume(): Int? = runCatching {
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
    }.getOrNull()

    /** Поднимает громкость медиа до ~80 %, если она ниже. Возвращает true, если меняли. */
    private fun ensureAudibleVolume(): Boolean {
        val manager = audioManager ?: return false
        return runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = max
            if (current < target) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Удар: короткий питч-свип 170 → 50 Гц с быстрой атакой плюс щелчок.
     * Свип даёт энергию во всей басовой полосе, а щелчок помогает даже
     * слабому динамику телефона воспроизвести удар.
     */
    private fun renderClick(): ShortArray {
        val n = (SAMPLE_RATE * CLICK_MS / 1000.0).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        var noise = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            // 180 → 80 Гц: свип остаётся в басовой полосе и в пределах того,
            // что способен воспроизвести динамик телефона.
            val freq = 80.0 + 100.0 * exp(-t / 0.060)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val body = exp(-t / 0.120) * sin(phase)
            // очень короткий широкополосный щелчок на атаке
            noise = 0.55 * noise + 0.45 * (Random.nextDouble() * 2.0 - 1.0)
            val click = if (t < 0.008) (1.0 - t / 0.008) * noise else 0.0
            val v = 0.95 * body + 0.35 * click
            out[i] = (v.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun routeToSpeaker(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val speaker = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return
        runCatching { track.preferredDevice = speaker }
    }

    companion object {
        const val CLICKS = 16
        const val BPM = 120
        private const val SAMPLE_RATE = 44_100
        private const val CLICK_MS = 260.0
    }
}

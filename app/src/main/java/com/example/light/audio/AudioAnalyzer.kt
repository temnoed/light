package com.example.light.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Один кадр аудиоанализа.
 *
 * @param rms громкость кадра (0..1)
 * @param bass энергия баса 35..170 Гц (0..1)
 * @param toneHz доминирующая частота тона, 0 — тон не найден
 * @param brightness целевая яркость вспышки по тону (0..1, сглаженная)
 * @param beatPulse импульс бита, затухает за ~100 мс
 * @param beats счётчик битов с момента запуска
 */
data class AudioFrame(
    val rms: Float = 0f,
    val bass: Float = 0f,
    val toneHz: Float = 0f,
    val brightness: Float = 0f,
    val beatPulse: Float = 0f,
    val beats: Int = 0,
    val gain: Float = 1f,
    val mid: Float = 0f
)

/**
 * Режим детектора бита.
 *
 * [KICK] — только сильный удар (бочка): высокий порог по амплитуде, длинный
 * минимальный интервал. Вспышек меньше, но они строго по акцентам.
 *
 * [RHYTHM] — любой бас, включая бас-гитару: низкий порог, но с блокировкой
 * темпа, чтобы один удар не превращался в серию вспышек.
 */
enum class BeatMode {
    KICK,
    RHYTHM
}

/**
 * Анализ потока PCM 16 бит: БПФ, детектор бита по низким частотам и
 * определение тона, который переводится в яркость вспышки.
 *
 * Класс не потокобезопасен: его вызывает только поток чтения AudioRecord.
 */
class AudioAnalyzer(
    private val sampleRate: Int = 44_100,
    private val fftSize: Int = 2048
) {
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
    }
    private val windowGain = window.sum() / 2f

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mags = FloatArray(fftSize / 2)

    private val ring = ShortArray(fftSize)
    private var ringPos = 0

    // История общего уровня громкости (RMS) — основа детектора бита.
    private val levelHistory = FloatArray(HISTORY_SIZE)
    private var levelHistoryPos = 0
    private var levelHistoryCount = 0

    private var lastBeatMs = 0L
    private var prevLevel = 0f
    private var prevMid = 0f
    private var noiseFloor = 0.005f
    // АРУ: непрерывная автоматическая регулировка усиления.
    private var peakRms = 0.01f
    private var gain = 1f
    // Тишина: если RMS ниже порога несколько кадров подряд — не считаем биты.
    private var silenceFrames = 0
    private val SILENCE_THRESHOLD = 0.015f
    private val SILENCE_FRAMES_LIMIT = 20
    private var beatPulse = 0f
    private var brightness = 0f
    private var lastFrameMs = 0L
    private var beats = 0

    /** Бас в момент последнего удара — нужен для гистерезиса. */
    private var lastBeatLevel = 0f

    /** false — удар уже засчитан, ждём спада баса, чтобы не ловить хвост. */
    private var armed = true

    private val binHz = sampleRate.toFloat() / fftSize

    private val bassLo = (35f / binHz).roundToInt().coerceAtLeast(1)
    private val bassHi = (170f / binHz).roundToInt().coerceIn(bassLo, fftSize / 2 - 2)
    private val toneLo = (60f / binHz).roundToInt().coerceAtLeast(1)
    private val toneHi = (1800f / binHz).roundToInt().coerceIn(toneLo, fftSize / 2 - 2)

    // СЧ-полоса (800–2000 Гц) для тихих барабанов/гитары.
    private val midLo = (800f / binHz).roundToInt().coerceAtLeast(1)
    private val midHi = (2000f / binHz).roundToInt().coerceIn(midLo, fftSize / 2 - 2)

    /** 0.4..3.0 — насколько легко срабатывает детектор бита. */
    var sensitivity: Float = 4f

    /** 0..1 — насколько плавно яркость следует за тоном. */
    var smoothing: Float = 0.4f

    /** Режим детектора бита, переключается тумблером в настройках. */
    var beatMode: BeatMode = BeatMode.KICK

    fun reset() {
        ring.fill(0)
        ringPos = 0
        levelHistory.fill(0f)
        levelHistoryPos = 0
        levelHistoryCount = 0
        lastBeatMs = 0L
        prevLevel = 0f
        prevMid = 0f
        noiseFloor = 0.005f
        peakRms = 0.01f
        gain = 1f
        beatPulse = 0f
        brightness = 0f
        lastFrameMs = 0L
        beats = 0
        lastBeatLevel = 0f
        armed = true
    }

    fun process(samples: ShortArray, length: Int, nowMs: Long): AudioFrame {
        val n = min(length, fftSize)
        if (n <= 0) return AudioFrame()

        for (i in 0 until n) {
            ring[ringPos] = samples[i]
            ringPos++
            if (ringPos == fftSize) ringPos = 0
        }

        var sumSquares = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / n).toFloat()

        var order = ringPos
        for (i in 0 until fftSize) {
            val v = ring[order] / 32768f
            re[i] = v * window[i]
            im[i] = 0f
            order++
            if (order == fftSize) order = 0
        }
        fft.transform(re, im)

        for (i in 0 until fftSize / 2) {
            mags[i] = hypot(re[i], im[i]) / windowGain
        }

        val dt = if (lastFrameMs == 0L) 23f else (nowMs - lastFrameMs).toFloat().coerceIn(5f, 200f)
        lastFrameMs = nowMs

        // --- Бас (для индикатора/тона) ---
        var bassEnergy = 0.0
        for (b in bassLo..bassHi) bassEnergy += mags[b].toDouble() * mags[b]
        val bass = (sqrt(bassEnergy) * 2.0).toFloat().coerceIn(0f, 1f)

        // --- Тишина: не считаем биты, если сигнал слишком тихий ---
        if (rms < SILENCE_THRESHOLD) {
            silenceFrames++
        } else {
            silenceFrames = 0
        }
        val isSilent = silenceFrames >= SILENCE_FRAMES_LIMIT

        // --- АРУ (непрерывное): нормирует громкость ---
        peakRms = if (rms > peakRms) rms else peakRms + (rms - peakRms) * 0.05f
        val targetGain = (0.12f / peakRms.coerceAtLeast(0.001f)).coerceIn(1f, 6f)
        gain += (targetGain - gain) * 0.05f
        val level = (rms * gain).coerceIn(0f, 1f)

        // Бит ищется по общему уровню громкости (RMS), а не по басу.
        val meanLevel = meanLevel()
        noiseFloor += if (level < noiseFloor) {
            (level - noiseFloor) * 0.08f
        } else {
            (level - noiseFloor) * 0.002f
        }

        // СЧ-полоса (800–2000 Гц): атаки барабанов/гитары на тихой музыке.
        var midEnergy = 0.0
        for (b in midLo..midHi) midEnergy += mags[b].toDouble() * mags[b]
        val mid = (sqrt(midEnergy) * 2.0).toFloat().coerceIn(0f, 1f)
        val midLevel = (mid * gain).coerceIn(0f, 1f)
        val midOnset = midLevel - prevMid

        val sensitivityNow = sensitivity.coerceIn(0.2f, 5f)
        val base = max(noiseFloor * 1.7f, meanLevel * 1.15f)
        val strict = beatMode == BeatMode.KICK
        val threshold = if (strict) {
            base / sensitivityNow + 0.012f
        } else {
            base / sensitivityNow + 0.010f
        }
        val onset = level - prevLevel
        val onsetGate = if (strict) {
            (0.006f / sensitivityNow).coerceAtLeast(0.003f)
        } else {
            (0.003f / sensitivityNow).coerceAtLeast(0.0015f)
        }
        val midOnsetGate = onsetGate * 0.8f
        val absoluteGate = if (strict) KICK_GATE_LEVEL else GATE_LEVEL
        val midAbsoluteGate = absoluteGate * 0.6f
        val hysteresis = if (strict) 0.72f else 0.55f
        val minInterval = if (strict) KICK_MIN_BEAT_INTERVAL_MS else MIN_BEAT_INTERVAL_MS

        if (!armed) {
            // Re-arming uses raw RMS (not amplified) to avoid AGC keeping level high.
            if (rms < lastBeatLevel * hysteresis) armed = true
        }

        var beat = false
        if (!isSilent) {
            // Бит срабатывает, если хотя бы один канал (общий уровень ИЛИ СЧ-атака) превысил порог.
            val generalBeat = armed && onset > onsetGate && level > threshold && level > absoluteGate
            val midBeat = armed && midOnset > midOnsetGate && midLevel > midAbsoluteGate
            if ((generalBeat || midBeat) && nowMs - lastBeatMs > minInterval) {
                beat = true
                armed = false
                lastBeatLevel = max(level, midLevel)
                lastBeatMs = nowMs
                beats++
            }
        } else {
            armed = true
        }
        prevLevel = level
        prevMid = midLevel
        pushLevel(level)

        beatPulse *= exp(-dt / 90f)
        if (beat) beatPulse = 1f

        // --- Тон ---
        var peakBin = -1
        var peakMag = 0f
        var bandSum = 0f
        for (b in toneLo..toneHi) {
            val m = mags[b]
            bandSum += m
            if (m > peakMag) {
                peakMag = m
                peakBin = b
            }
        }
        val bandMean = bandSum / (toneHi - toneLo + 1)

        var toneHz = 0f
        if (rms > 0.008f && peakBin > 0 && peakMag > 0.002f && peakMag > bandMean * 2.0f) {
            var shift = 0f
            val a = mags[peakBin - 1]
            val b0 = mags[peakBin]
            val c = mags[peakBin + 1]
            val denom = a - 2f * b0 + c
            if (abs(denom) > 1e-6f) {
                shift = (0.5f * (a - c) / denom).coerceIn(-0.5f, 0.5f)
            }
            toneHz = (peakBin + shift) * binHz
        }

        // --- Тон -> яркость (логарифмическая шкала 80..1400 Гц) ---
        val target = if (toneHz > 0f) {
            val norm = (ln(toneHz / 80f) / ln(1400f / 80f)).coerceIn(0f, 1f)
            val loudness = (rms * 6f).coerceIn(0f, 1f)
            0.12f + 0.88f * norm * (0.6f + 0.4f * loudness)
        } else {
            0f
        }

        val tau = if (target > brightness) 8f + 40f * smoothing else 70f + 180f * smoothing
        val alpha = 1f - exp(-dt / tau)
        brightness += (target - brightness) * alpha

        return AudioFrame(
            rms = rms,
            bass = bass,
            toneHz = toneHz,
            brightness = brightness.coerceIn(0f, 1f),
            beatPulse = beatPulse.coerceIn(0f, 1f),
            beats = beats,
            gain = gain,
            mid = mid
        )
    }

    private fun pushLevel(value: Float) {
        levelHistory[levelHistoryPos] = value
        levelHistoryPos = (levelHistoryPos + 1) % HISTORY_SIZE
        if (levelHistoryCount < HISTORY_SIZE) levelHistoryCount++
    }

    private fun meanLevel(): Float {
        if (levelHistoryCount == 0) return 0f
        var sum = 0f
        for (i in 0 until levelHistoryCount) sum += levelHistory[i]
        return sum / levelHistoryCount
    }

    private companion object {
        const val HISTORY_SIZE = 40
        const val MIN_BEAT_INTERVAL_MS = 220L
        const val KICK_MIN_BEAT_INTERVAL_MS = 350L
        const val GATE_LEVEL = 0.008f
        const val KICK_GATE_LEVEL = 0.015f
    }
}

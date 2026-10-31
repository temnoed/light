package com.example.light.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Проверка DSP без телефона: синтетические удары, чистый тон и ровный бас.
 */
class AudioAnalyzerTest {

    private val rate = 44_100
    private val chunk = 1024

    @Test
    fun detectsKicksAt90Bpm() {
        val beats = countKicks(bpm = 90, seconds = 12.0)
        println("90 BPM → поймано $beats из 18")
        assertTrue("90 BPM: $beats", beats in 16..19)
    }

    @Test
    fun detectsKicksAt120Bpm() {
        val beats = countKicks(bpm = 120, seconds = 10.0)
        println("120 BPM → поймано $beats из 20")
        assertTrue("120 BPM: $beats", beats in 18..21)
    }

    @Test
    fun detectsKicksAt140Bpm() {        val beats = countKicks(bpm = 140, seconds = 10.0)
        println("140 BPM → поймано $beats из 23")
        assertTrue("140 BPM: $beats", beats in 21..24)
    }

    @Test
    fun sustainedBassDoesNotRepeat() {
        val analyzer = AudioAnalyzer(rate, 2048)
        val samples = ShortArray(rate * 3) { i ->
            (0.4 * sin(2.0 * PI * 90.0 * i / rate) * 32767.0).toInt().toShort()
        }
        var frame = AudioFrame()
        var pos = 0
        while (pos + chunk <= samples.size) {
            frame = analyzer.process(
                samples.copyOfRange(pos, pos + chunk),
                chunk,
                (pos + chunk) * 1000L / rate
            )
            pos += chunk
        }
        println("ровный бас 3 с → битов ${frame.beats}")
        assertTrue("ровный бас не должен давать серию битов: ${frame.beats}", frame.beats <= 1)
    }

    @Test
    fun findsPureTone() {
        val analyzer = AudioAnalyzer(rate, 2048)
        val samples = ShortArray(rate) { i ->
            (0.3 * sin(2.0 * PI * 440.0 * i / rate) * 32767.0).toInt().toShort()
        }

        var frame = AudioFrame()
        var pos = 0
        while (pos + chunk <= samples.size) {
            frame = analyzer.process(
                samples.copyOfRange(pos, pos + chunk),
                chunk,
                (pos + chunk) * 1000L / rate
            )
            pos += chunk
        }

        assertEquals(440.0, frame.toneHz.toDouble(), 3.0)
        assertTrue("яркость: ${frame.brightness}", frame.brightness > 0.4f)
    }

    @Test
    fun silenceStaysDark() {
        val analyzer = AudioAnalyzer(rate, 2048)
        var frame = AudioFrame()
        var pos = 0
        while (pos + chunk <= rate) {
            frame = analyzer.process(
                ShortArray(chunk),
                chunk,
                (pos + chunk) * 1000L / rate
            )
            pos += chunk
        }

        assertEquals(0, frame.beats)
        assertEquals(0f, frame.brightness, 0.001f)
    }

    /**
     * Так микрофон C31 слышит удары собственного динамика: пик баса всего
     * около 0.10, а шум — около 0.008 RMS. Детектор обязан ловить их без
     * ложных срабатываний.
     */
    @Test
    fun detectsQuietClicksAtMicLevel() {
        val analyzer = AudioAnalyzer(rate, 2048)
        val bpm = 120
        val interval = 60.0 / bpm
        val total = (rate * 8.0).toInt()
        val samples = ShortArray(total)
        val random = java.util.Random(7)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / rate
            val since = t % interval
            val freq = 90.0 + 70.0 * exp(-since / 0.050)
            phase += 2.0 * PI * freq / rate
            val body = exp(-since / 0.080) * sin(phase)
            val noise = (random.nextDouble() * 2.0 - 1.0) * 0.008
            val v = 0.06 * body + noise
            samples[i] = (v.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
        }

        var frame = AudioFrame()
        var pos = 0
        var peakBass = 0f
        while (pos < total) {
            val n = minOf(chunk, total - pos)
            val now = (pos + n) * 1000L / rate
            frame = analyzer.process(samples.copyOfRange(pos, pos + n), n, now)
            if (frame.bass > peakBass) peakBass = frame.bass
            pos += n
        }

        println("тихие удары: пик баса $peakBass, поймано ${frame.beats} из 16")
        assertTrue("пик баса слишком мал: $peakBass", peakBass > 0.05f)
        assertTrue("тихие удары: ${frame.beats}", frame.beats in 14..17)
    }

    /**
     * Быстрая бас-линия: ноты каждые 250 мс. «Живой бас» вспыхивает на каждую,
     * «бочка» — заметно реже и ровнее, без дребезга на каждой ноте.
     */
    @Test
    fun kickModeIsStricterThanRhythmMode() {
        val rhythm = countKicks(bpm = 240, seconds = 8.0, mode = BeatMode.RHYTHM)
        val kick = countKicks(bpm = 240, seconds = 8.0, mode = BeatMode.KICK)
        println("одна и та же линия: живой бас $rhythm, бочка $kick")
        assertTrue("живой бас должен ловить больше: $rhythm vs $kick", rhythm > kick)
        assertTrue("бочка должна быть заметно строже: $kick vs $rhythm", kick <= rhythm * 3 / 4)
    }

    @Test
    fun rhythmModeDetectsFastBassLine() {
        // Восьмые при 120 BPM = 250 мс: детектор обязан их различать.
        val beats = countKicks(bpm = 240, seconds = 8.0, mode = BeatMode.RHYTHM)
        println("240 BPM (восьмые 250 мс) → поймано $beats из 32")
        assertTrue("240 BPM: $beats", beats in 26..33)
    }

    private fun countKicks(
        bpm: Int,
        seconds: Double,
        mode: BeatMode = BeatMode.KICK
    ): Int {
        val analyzer = AudioAnalyzer(rate, 2048).apply { beatMode = mode }
        val interval = 60.0 / bpm
        val total = (rate * seconds).toInt()
        val samples = ShortArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / rate
            // удар в начале каждого интервала: свип 160 → 50 Гц с затуханием
            val since = t % interval
            val freq = 50.0 + 110.0 * exp(-since / 0.045)
            phase += 2.0 * PI * freq / rate
            val env = exp(-since / 0.11)
            val v = 0.8 * env * sin(phase)
            samples[i] = (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }

        var frame = AudioFrame()
        var pos = 0
        while (pos < total) {
            val n = minOf(chunk, total - pos)
            val now = (pos + n) * 1000L / rate
            frame = analyzer.process(samples.copyOfRange(pos, pos + n), n, now)
            pos += n
        }
        return frame.beats
    }
}

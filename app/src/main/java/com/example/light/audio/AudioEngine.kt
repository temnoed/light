package com.example.light.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Читает микрофон блоками по 1024 сэмпла (~23 мс) и отдаёт результат анализа.
 *
 * Остановка сделана максимально надёжной: AudioRecord освобождается и самим
 * потоком чтения, и страховочным потоком, поэтому микрофон не остаётся
 * занятым, даже если остановка пришла в момент запуска.
 */
class AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var analyzer = AudioAnalyzer(SAMPLE_RATE, FFT_SIZE)

    private val _frame = MutableStateFlow(AudioFrame())
    val frame: StateFlow<AudioFrame> = _frame.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var session: Session? = null

    private var sensitivityValue = 4f
    private var smoothingValue = 0.4f
    private var beatModeValue = BeatMode.KICK

    /**
     * Отладочная запись микрофона в сырой PCM 16 бит (моно, частота сессии).
     * Нужна для замеров вне приложения: записали комнату → разобрали на ПК.
     */
    var recordFile: java.io.File? = null

    private var recordStream: java.io.FileOutputStream? = null
    private var recordBytes = 0L

    /** Вызывается из потока чтения на каждый кадр. */
    @Volatile
    var onFrame: ((AudioFrame) -> Unit)? = null

    var sensitivity: Float
        get() = sensitivityValue
        set(value) {
            sensitivityValue = value
            analyzer.sensitivity = value
        }

    var smoothing: Float
        get() = smoothingValue
        set(value) {
            smoothingValue = value
            analyzer.smoothing = value
        }

    var beatMode: BeatMode
        get() = beatModeValue
        set(value) {
            beatModeValue = value
            analyzer.beatMode = value
        }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lock) {
            if (job != null) return true
            _error.value = null

            val rate = pickSampleRate()
            if (rate == null) {
                _error.value = "Микрофон не поддерживает запись в нужном формате"
                return false
            }

            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = createRecorder(rate, max(minBuffer * 2, CHUNK_SIZE * 8)) ?: run {
                _error.value = "Не удалось открыть микрофон"
                return false
            }

            val fresh = AudioAnalyzer(rate, FFT_SIZE).apply {
                sensitivity = sensitivityValue
                smoothing = smoothingValue
                beatMode = beatModeValue
            }
            analyzer = fresh

            val created = Session(recorder)
            session = created
            recordStream?.let { runCatching { it.close() } }
            recordStream = recordFile?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    java.io.FileOutputStream(file)
                }.getOrNull()
            }
            recordBytes = 0L
            job = scope.launch { readLoop(created) }
            return true
        }
    }

    fun stop() {
        val current: Session?
        synchronized(lock) {
            current = session
            session = null
            job?.cancel()
            job = null
        }
        if (current != null) {
            // Останавливаем запись, чтобы разблокировать read().
            runCatching { current.recorder.stop() }
            scheduleFallbackRelease(current)
        }
        _frame.value = AudioFrame()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /** Сбрасывает счётчик битов и историю — перед калибровкой/тестом. */
    fun resetAnalysis() {
        analyzer.reset()
        _frame.value = AudioFrame()
    }

    private fun pickSampleRate(): Int? {
        for (rate in intArrayOf(SAMPLE_RATE, FALLBACK_SAMPLE_RATE)) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (min > 0) return rate
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(rate: Int, bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED
        )
        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (t: Throwable) {
                null
            }
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "микрофон: source=$source, ${rate} Гц, буфер $bufferSize")
                return recorder
            }
            runCatching { recorder?.release() }
        }
        return null
    }

    private suspend fun readLoop(session: Session) {
        val recorder = session.recorder
        try {
            if (session.released.get()) return
            recorder.startRecording()
            if (session.released.get()) return
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _error.value = "Микрофон не начал запись"
                return
            }
            val buffer = ShortArray(CHUNK_SIZE)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (session.released.get()) break
                when {
                    read > 0 -> {
                        val result = analyzer.process(
                            buffer,
                            read,
                            SystemClock.elapsedRealtime()
                        )
                        _frame.value = result
                        onFrame?.invoke(result)
                        writeRecording(buffer, read)
                    }
                    read < 0 -> {
                        _error.value = "Ошибка чтения микрофона ($read)"
                        break
                    }
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (!session.released.get()) {
                _error.value = t.message ?: "Ошибка аудио"
            }
        } finally {
            releaseSession(session)
            // Не обнуляем кадры, если уже запущена новая сессия.
            val stillCurrent = synchronized(lock) { this.session === session }
            if (stillCurrent) _frame.value = AudioFrame()
        }
    }

    /**
     * Страховка: если поток чтения ещё не успел дойти до finally (например,
     * остановка пришла сразу после start), освобождаем запись сами.
     */
    private fun scheduleFallbackRelease(session: Session) {
        Thread({
            SystemClock.sleep(250)
            releaseSession(session)
        }, "light-audio-release").apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseSession(session: Session) {
        if (!session.released.compareAndSet(false, true)) return
        runCatching { session.recorder.stop() }
        runCatching { session.recorder.release() }
        runCatching {
            recordStream?.flush()
            recordStream?.close()
        }
        recordStream = null
    }

    private fun writeRecording(buffer: ShortArray, read: Int) {
        val stream = recordStream ?: return
        if (recordBytes >= MAX_RECORD_BYTES) return
        val samples = minOf(read, ((MAX_RECORD_BYTES - recordBytes) / 2).toInt())
        if (samples <= 0) return
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = buffer[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        runCatching { stream.write(bytes) }
        recordBytes += samples * 2L
    }

    private class Session(val recorder: AudioRecord) {
        val released = AtomicBoolean(false)
    }

    private companion object {
        const val TAG = "Light"
        const val SAMPLE_RATE = 44_100
        const val FALLBACK_SAMPLE_RATE = 48_000
        const val FFT_SIZE = 2048
        const val CHUNK_SIZE = 1024
        // 30 секунд отладочной записи при 44.1 кГц — около 2.6 МБ.
        const val MAX_RECORD_BYTES = 44_100L * 2 * 30
    }
}

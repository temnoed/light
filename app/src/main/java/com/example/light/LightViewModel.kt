package com.example.light

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.light.audio.AudioEngine
import com.example.light.audio.AudioFrame
import com.example.light.audio.BeatSelfTest
import com.example.light.torch.TorchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlin.math.roundToInt

data class Settings(
    val sensitivity: Float = 4f,
    val screenSensitivity: Float = 2f
)

/** Результат встроенного теста бита (телефон сам играет удары). */
data class BeatTestState(
    val running: Boolean = false,
    val total: Int = BeatSelfTest.CLICKS,
    val caught: Int = 0,
    val bpm: Int = 0,
    val peakRms: Float = 0f,
    val peakBass: Float = 0f,
    val result: String? = null
)

data class LightUiState(
    val torchRunning: Boolean = false,
    val screenRunning: Boolean = false,
    val frame: AudioFrame = AudioFrame(),
    val torch: TorchController.Info = TorchController.Info(),
    val settings: Settings = Settings(),
    val beatTest: BeatTestState = BeatTestState(),
    val error: String? = null
) {
    /** Включён хотя бы один источник света. */
    val running: Boolean get() = torchRunning || screenRunning
}

private data class CoreState(
    val torchRunning: Boolean,
    val screenRunning: Boolean,
    val frame: AudioFrame,
    val torch: TorchController.Info,
    val settings: Settings,
    val error: String?
)

class LightViewModel(application: Application) : AndroidViewModel(application) {

    private val torch = TorchController(application)
    private val audio = AudioEngine()
    private val beatSelfTest = BeatSelfTest(application)

    private val _settings = MutableStateFlow(Settings())
    private val _runningTorch = MutableStateFlow(false)
    private val _runningScreen = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _beatTest = MutableStateFlow(BeatTestState())

    private var beatJob: Job? = null
    private var lastLoggedBeat = 0
    private var lastLevelLogMs = 0L
    private var debugRecord = false

    private val sources = combine(_runningTorch, _runningScreen) { torch, screen ->
        torch to screen
    }

    private val core: StateFlow<CoreState> = combine(
        sources,
        audio.frame,
        torch.info,
        _settings,
        _error
    ) { (torchRunning, screenRunning), frame, torchInfo, settings, error ->
        CoreState(torchRunning, screenRunning, frame, torchInfo, settings, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoreState(false, false, AudioFrame(), torch.info.value, Settings(), null)
    )

    val state: StateFlow<LightUiState> = combine(core, _beatTest) { c, test ->
        LightUiState(
            torchRunning = c.torchRunning,
            screenRunning = c.screenRunning,
            frame = c.frame,
            torch = c.torch,
            settings = c.settings,
            beatTest = test,
            error = c.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LightUiState(torch = torch.info.value)
    )

    init {
        applySettings(_settings.value)
        torch.refreshInfo()

        audio.onFrame = { frame ->
            torch.setLevels(
                brightness = frame.brightness,
                beat = frame.beatPulse
            )
            // Удобно для замеров с adb logcat: каждый пойманный удар и его бас.
            if (frame.beats > lastLoggedBeat) {
                lastLoggedBeat = frame.beats
                val bass = "%.3f".format(frame.bass)
                val gain = "%.2f".format(frame.rms)
                Log.d(
                    "Light",
                    "beat=${frame.beats} bass=$bass tone=${frame.toneHz.toInt()}Hz gain=$gain"
                )
            }
            // Раз в секунду пишем уровень сигнала — так видно, слышит ли микрофон.
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelLogMs >= 1_000L) {
                lastLevelLogMs = now
                val bass = "%.3f".format(frame.bass)
                val gain = "%.3f".format(frame.rms)
                Log.d("Light", "level rms=$gain bass=$bass tone=${frame.toneHz.toInt()}")
            }
        }

        viewModelScope.launch {
            audio.error.collect { message ->
                if (message != null) {
                    _error.value = message
                    if (_runningTorch.value || _runningScreen.value) {
                        stop()
                    }
                }
            }
        }

        torch.onError = { message ->
            _error.value = message
            stop()
        }
    }

    fun toggleTorch() {
        if (_runningTorch.value) stopTorch() else startTorch()
    }

    fun toggleScreen() {
        if (_runningScreen.value) stopScreen() else startScreen()
    }

    fun startTorch() {
        if (_beatTest.value.running) return
        _error.value = null
        if (!ensureAudio()) return
        applySettings(_settings.value)
        torch.start()
        _runningTorch.value = true
    }

    fun startScreen() {
        if (_beatTest.value.running) return
        _error.value = null
        if (!ensureAudio()) return
        _runningScreen.value = true
    }

    fun stopTorch() {
        _runningTorch.value = false
        torch.stop()
        stopAudioIfIdle()
    }

    fun stopScreen() {
        _runningScreen.value = false
        stopAudioIfIdle()
    }

    /**
     * Полная остановка: микрофон, фонарик, экранный свет, встроенный тест.
     * Вызывается при уходе приложения в фон и при закрытии.
     */
    fun stop() {
        _runningTorch.value = false
        _runningScreen.value = false
        cancelBeatTest()
        torch.stop()
        audio.stop()
    }

    fun setSensitivity(value: Float) {
        _settings.update { it.copy(sensitivity = value) }
        applySettings(_settings.value)
    }

    fun setScreenSensitivity(value: Float) {
        _settings.update { it.copy(screenSensitivity = value) }
    }

    /** Включает отладочную запись микрофона (см. recordFile в AudioEngine). */
    fun setDebugRecord(enabled: Boolean) {
        debugRecord = enabled
    }

    fun reportNoMicrophonePermission() {
        _error.value = "Нет доступа к микрофону. Разрешите его в настройках приложения."
    }

    fun testTorch() {
        if (_runningTorch.value || _runningScreen.value || _beatTest.value.running) return
        torch.selfTest()
    }

    /**
     * Встроенный тест бита: 16 ударов в темпе 120 BPM играет динамик телефона,
     * микрофон в это время слушает и считает пойманные удары. Показывает,
     * сколько ударов детектор услышал и какой темп измерил.
     */
    fun runBeatTest() {
        if (_runningTorch.value || _runningScreen.value || _beatTest.value.running) return
        _error.value = null
        lastLoggedBeat = 0
        _beatTest.value = BeatTestState(running = true)

        beatJob = viewModelScope.launch {
            if (!audio.start()) {
                _beatTest.value = BeatTestState(
                    result = audio.error.value ?: "Не удалось запустить микрофон"
                )
                return@launch
            }

            val marks = mutableListOf<Long>()
            var collector: Job? = null
            var peakRms = 0f
            var peakBass = 0f
            try {
                // Даём микрофону и анализатору прогреться.
                delay(600)
                val baseline = audio.frame.value.beats
                var lastSeen = baseline
                collector = launch {
                    audio.frame.collect { f ->
                        if (f.rms > peakRms) peakRms = f.rms
                        if (f.bass > peakBass) peakBass = f.bass
                        if (f.beats > lastSeen) {
                            lastSeen = f.beats
                            marks += SystemClock.elapsedRealtime()
                        }
                    }
                }

                val played = runInterruptible(Dispatchers.IO) { beatSelfTest.playClicks() }
                delay(500)
                collector.cancel()

                val caught = (audio.frame.value.beats - baseline)
                    .coerceIn(0, BeatSelfTest.CLICKS)
                val bpm = measureBpm(marks)
                val rmsText = "%.3f".format(peakRms)
                val bassText = "%.3f".format(peakBass)
                Log.i(
                    "Light",
                    "тест бита: поймано $caught/${BeatSelfTest.CLICKS}, " +
                        "темп $bpm, макс. громкость $rmsText, макс. бас $bassText"
                )
                _beatTest.value = BeatTestState(
                    running = false,
                    total = BeatSelfTest.CLICKS,
                    caught = caught,
                    bpm = bpm,
                    peakRms = peakRms,
                    peakBass = peakBass,
                    result = if (!played) {
                        "Не удалось воспроизвести удары через динамик"
                    } else {
                        verdict(caught)
                    }
                )
            } finally {
                collector?.cancel()
                audio.stop()
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private fun cancelBeatTest() {
        beatJob?.cancel()
        beatJob = null
        if (_beatTest.value.running || _beatTest.value.result != null) {
            _beatTest.value = BeatTestState()
        }
    }

    /** Медианный интервал между пойманными ударами → BPM. */
    private fun measureBpm(marks: List<Long>): Int {
        if (marks.size < 2) return 0
        val intervals = marks.zipWithNext { a, b -> b - a }
            .filter { it in 150..2_000 }
            .sorted()
        if (intervals.isEmpty()) return 0
        val median = intervals[intervals.size / 2]
        return (60_000.0 / median).roundToInt()
    }

    private fun verdict(caught: Int): String {
        val total = BeatSelfTest.CLICKS
        return when {
            caught >= total - 2 -> "Отлично: детектор уверенно ловит бас."
            caught >= total * 3 / 4 -> "Хорошо. Можно поднять чувствительность для запаса."
            caught >= total / 2 -> "Средне: поднимите чувствительность или громкость."
            else -> "Слабо: проверьте громкость телефона и чувствительность."
        }
    }

    /**
     * Включает микрофон, если он ещё не работает. Возвращает false, если
     * открыть его не удалось.
     */
    private fun ensureAudio(): Boolean {
        // Отладочная запись: файл лежит в /sdcard/Android/data/com.example.light/files/
        audio.recordFile = if (debugRecord) {
            java.io.File(getApplication<Application>().getExternalFilesDir(null), "last.raw")
        } else {
            null
        }
        if (!audio.start()) {
            _error.value = audio.error.value ?: "Не удалось запустить микрофон"
            return false
        }
        return true
    }

    /** Гасим микрофон, когда не горит ни фонарик, ни экран. */
    private fun stopAudioIfIdle() {
        if (!_runningTorch.value && !_runningScreen.value) audio.stop()
    }

    private fun applySettings(settings: Settings) {
        audio.sensitivity = settings.sensitivity
    }

    override fun onCleared() {
        beatJob?.cancel()
        torch.release()
        audio.release()
        super.onCleared()
    }
}

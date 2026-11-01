package com.example.light.torch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Управление вспышкой.
 *
 * На Android 13+ (API 33) и устройствах, где драйвер сообщает
 * FLASH_INFO_STRENGTH_MAXIMUM_LEVEL > 1, используется аппаратная
 * регулировка яркости. Если уровней нет, вспышка работает только вкл/выкл
 * на полной яркости — никакой ШИМ: частое переключение LED даёт заметное
 * раздражающее мерцание и греет светодиод.
 */
class TorchController(context: Context) {

    enum class Mode { NONE, HARDWARE, ONOFF }

    data class Info(
        val available: Boolean = false,
        val mode: Mode = Mode.NONE,
        val maxStrength: Int = 1,
        val defaultStrength: Int = 1,
        val cameraId: String = "—",
        val message: String = "Проверка вспышки…"
    )

    private val manager = context.applicationContext
        .getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info.asStateFlow()

    /** Ошибка, после которой поток управления вспышкой остановлен. */
    var onError: ((String) -> Unit)? = null

    @Volatile private var cameraId: String? = null
    @Volatile private var mode = Mode.NONE
    @Volatile private var maxStrength = 1
    @Volatile private var defaultStrength = 1

    @Volatile private var brightness = 0f
    @Volatile private var beat = 0f

    /** 0..1 — чувствительность вспышки к сигналу микрофона. */
    @Volatile var sensitivity: Float = 1f
    @Volatile private var running = false
    @Volatile private var failed = false

    private var worker: Thread? = null
    private var lastHardwareLevel = -1
    private var lastOnOff = false

    /** Уровни, которые приходят из аудиоанализа ~43 раза в секунду. */
    fun setLevels(brightness: Float, beat: Float) {
        this.brightness = brightness.coerceIn(0f, 1f)
        this.beat = beat.coerceIn(0f, 1f)
    }

    fun start(): Boolean {
        detect()
        if (mode == Mode.NONE) return false
        if (running) return true

        failed = false
        lastHardwareLevel = -1
        lastOnOff = false
        running = true
        worker = Thread({ loop() }, "light-torch").apply {
            isDaemon = true
            start()
        }
        applyOff()
        return true
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(600) } }
        worker = null
        brightness = 0f
        beat = 0f
        applyOff()
    }

    fun release() = stop()

    /** Обновляет сведения о вспышке, не включая её. */
    fun refreshInfo() = detect()

    /**
     * Короткий тест: на устройстве с аппаратной яркостью перебирает все
     * уровни, иначе просто мигает вспышкой. Не запускается, пока свет горит.
     */
    fun selfTest() {
        if (running) return
        detect()
        if (mode == Mode.NONE) {
            onError?.invoke("Вспышка не найдена")
            return
        }

        failed = false
        Thread({
            val id = cameraId ?: return@Thread
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxStrength > 1) {
                    for (level in 1..maxStrength) {
                        manager?.turnOnTorchWithStrengthLevel(id, level)
                        SystemClock.sleep(700)
                    }
                } else {
                    repeat(3) {
                        manager?.setTorchMode(id, true)
                        SystemClock.sleep(350)
                        manager?.setTorchMode(id, false)
                        SystemClock.sleep(250)
                    }
                }
            } catch (t: Throwable) {
                fail(t)
            } finally {
                runCatching { manager?.setTorchMode(id, false) }
            }
        }, "light-torch-test").apply { isDaemon = true }.start()
    }

    private fun detect() {
        val cameraManager = manager
        if (cameraManager == null) {
            _info.value = Info(message = "Сервис камеры недоступен")
            return
        }

        var foundId: String? = null
        var strength = 1
        var defaultLevel = 1
        var characteristicsError: String? = null

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    foundId = id
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        strength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                        defaultLevel = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1
                    }
                    break
                }
            }
        } catch (t: Throwable) {
            characteristicsError = t.message
        }

        cameraId = foundId ?: "0"
        maxStrength = strength.coerceAtLeast(1)
        defaultStrength = defaultLevel.coerceIn(1, maxStrength)
        mode = when {
            foundId != null && maxStrength > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                Mode.HARDWARE
            foundId != null -> Mode.ONOFF
            characteristicsError != null -> Mode.ONOFF
            else -> Mode.NONE
        }

        val message = statusMessage()

        _info.value = Info(
            available = mode != Mode.NONE,
            mode = mode,
            maxStrength = maxStrength,
            defaultStrength = defaultStrength,
            cameraId = cameraId ?: "—",
            message = message
        )
    }

    private fun statusMessage(): String = when (mode) {
        Mode.NONE -> "Вспышка не найдена"
        Mode.HARDWARE -> "Аппаратная яркость: $maxStrength уровней"
        Mode.ONOFF -> "Постоянная яркость: вспышки на бит"
    }

    /** Уровень 0..1 для аппаратной яркости: тон + всплеск на бит. */
    private fun effectiveLevel(): Float = max(brightness, beat)

    private fun loop() {
        while (running && !failed) {
            when (mode) {
                Mode.HARDWARE -> hardwareTick()
                Mode.ONOFF -> onOffTick()
                Mode.NONE -> return
            }
        }
        applyOff()
    }

    private fun hardwareTick() {
        val id = cameraId ?: return
        val desired = effectiveLevel()
        val level = if (desired < 0.02f) {
            0
        } else {
            (1 + desired * (maxStrength - 1)).roundToInt().coerceIn(1, maxStrength)
        }
        if (level != lastHardwareLevel) {
            if (level <= 0) {
                applyOff()
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxStrength > 1) {
                        manager?.turnOnTorchWithStrengthLevel(id, level)
                    } else {
                        manager?.setTorchMode(id, true)
                    }
                } catch (t: Throwable) {
                    fail(t)
                }
            }
            lastHardwareLevel = level
        }
        SystemClock.sleep(40)
    }

    private fun onOffTick() {
        val id = cameraId ?: return
        val on = beat >= FLASH_THRESHOLD
        if (on != lastOnOff) {
            setTorch(id, on)
            lastOnOff = on
        }
        SystemClock.sleep(10)
    }

    private fun setTorch(id: String, on: Boolean) {
        if (failed) return
        try {
            manager?.setTorchMode(id, on)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun applyOff() {
        val id = cameraId ?: return
        try {
            // Для выключения вспышки отдельного API нет — используем setTorchMode.
            manager?.setTorchMode(id, false)
        } catch (_: Throwable) {
            // Вспышка уже недоступна — гасить нечего.
        }
    }

    private fun fail(t: Throwable) {
        if (failed) return
        failed = true
        running = false
        applyOff()
        val message = when (t) {
            is SecurityException -> "Нет доступа к камере — разрешите его в настройках"
            else -> t.localizedMessage ?: "Не удалось управлять вспышкой"
        }
        onError?.invoke(message)
    }

    private companion object {
        /** Порог импульса бита, при котором вспышка зажигается. */
        const val FLASH_THRESHOLD = 0.35f
    }
}

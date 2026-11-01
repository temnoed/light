package com.example.light.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.light.BeatTestState
import com.example.light.LightUiState
import com.example.light.torch.TorchController
import com.example.light.ui.theme.Amber
import com.example.light.ui.theme.AmberDeep
import com.example.light.ui.theme.Cyan
import com.example.light.ui.theme.Muted
import com.example.light.ui.theme.SurfaceInk
import kotlin.math.roundToInt

@Composable
fun LightScreen(
    state: LightUiState,
    onTorch: () -> Unit,
    onScreen: () -> Unit,
    onScreenSensitivity: (Float) -> Unit,
    onTestTorch: () -> Unit,
    onDismissError: () -> Unit
) {
    val active = state.running || state.beatTest.running
    val pulse by animateFloatAsState(
        targetValue = if (active) state.frame.beatPulse else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "beatPulse"
    )
    val level = if (active) state.frame.brightness else 0f
    // Инерция экранного света: зажигается ~в 2 раза медленнее и гаснет плавнее,
    // чтобы экран «переливался» в такт биту, а не дёргался.
    val screenLevelState = remember { mutableStateOf(0f) }
    val screenPulseState = remember { mutableStateOf(0f) }
    val levelState = rememberUpdatedState(level)
    // Инерция цвета: цвет меняется плавнее (в 2 раза) чем яркость.
    val smoothedToneState = remember { mutableStateOf(0f) }
    val toneState = rememberUpdatedState(if (active) state.frame.toneHz else 0f)
    val pulseState = rememberUpdatedState(pulse)
    // Экран зажигается на каждый второй бит (в 2 раза реже фонарика).
    val gatedPulseState = remember { mutableStateOf(0f) }
    val lastPulseState = remember { mutableStateOf(0f) }
    val beatCounterState = remember { mutableStateOf(0) }
    if (pulse > 0.5f && lastPulseState.value <= 0.5f) {
        beatCounterState.value += 1
        gatedPulseState.value = if (beatCounterState.value % 2 == 0) 1f else 0f
    }
    if (pulse < 0.3f) {
        gatedPulseState.value = 0f
    }
    lastPulseState.value = pulse
    LaunchedEffect(Unit) {
        var last = SystemClock.elapsedRealtime()
        while (isActive) {
            delay(16)
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last).coerceIn(1L, 100L).toFloat()
            last = now
            val lTarget = levelState.value
            val pTarget = gatedPulseState.value
            val lTau = if (lTarget > screenLevelState.value) 480f else 200f
            val pTau = if (pTarget > screenPulseState.value) 320f else 220f
            screenLevelState.value += (lTarget - screenLevelState.value) * (1f - kotlin.math.exp(-dt / lTau))
            screenPulseState.value += (pTarget - screenPulseState.value) * (1f - kotlin.math.exp(-dt / pTau))
            val tTarget = toneState.value
            val tTau = if (tTarget > smoothedToneState.value) 400f else 600f
            smoothedToneState.value += (tTarget - smoothedToneState.value) * (1f - kotlin.math.exp(-dt / tTau))
        }
    }
    val screenLevel = screenLevelState.value
    val screenPulse = screenPulseState.value
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1018), Color(0xFF04060A))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.28f)
            val radius = size.minDimension * (0.60f + 0.12f * pulse)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Amber.copy(alpha = 0.06f + 0.30f * pulse + 0.08f * level),
                        AmberDeep.copy(alpha = 0.04f + 0.08f * level),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        if (state.screenRunning) {
            // Экранный свет: яркость по уровню + вспышка на бит (каждый второй),
            // цвет зависит от доминирующей частоты: низкие → красный, высокие → фиолетовый.
            val overlay = ((0.55f * screenLevel + (0.20f / 6f) * screenPulse) * state.settings.screenSensitivity).coerceIn(0f, 0.85f)
            val toneHz = smoothedToneState.value
            val screenColor = if (toneHz > 0f) {
                val norm = (kotlin.math.ln(toneHz / 60f) / kotlin.math.ln(1800f / 60f)).coerceIn(0f, 1f)
                val hue = norm * 270f // 0 красный → 270 фиолетовый
                Color.hsv(hue, 0.85f, 1f)
            } else {
                Color.White
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(screenColor.copy(alpha = overlay))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LightButton(
                    label = "Фонарик",
                    running = state.torchRunning,
                    enabled = !state.beatTest.running,
                    onClick = onTorch,
                    modifier = Modifier.weight(1f)
                )
                LightButton(
                    label = "Экран",
                    running = state.screenRunning,
                    enabled = !state.beatTest.running,
                    onClick = onScreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            SensitivityCard(
                label = "Чувствительность экрана",
                value = state.settings.screenSensitivity,
                range = 0.2f..5f,
                display = { "×${String.format("%.1f", it)}" },
                onValueChange = onScreenSensitivity
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorCard(
                    message = message,
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    onDismiss = onDismissError
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Светодиод греется — не держите фонарик включённым часами.",
                color = Muted.copy(alpha = 0.75f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )

            Spacer(Modifier.height(12.dp))

            CollapsibleDiagnosticsCard(
                info = state.torch,
                testEnabled = !state.running,
                onTest = onTestTorch
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Meter(label: String, value: Float, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(
                text = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.07f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.55f), color)
                        )
                    )
            )
        }
    }
}

@Composable
private fun LightButton(
    label: String,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Amber else Color.White.copy(alpha = 0.07f),
            contentColor = if (running) Color(0xFF1A1200) else Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = Muted.copy(alpha = 0.5f)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (running) "включён" else "выключен",
                fontSize = 10.sp,
                color = if (running) Color(0xFF1A1200).copy(alpha = 0.65f) else Muted
            )
        }
    }
}

@Composable
private fun SensitivityCard(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceInk)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SliderRow(
                title = label,
                value = value,
                range = range,
                display = display(value),
                onValueChange = onValueChange
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, color = Color(0xFFC7D0DC), fontSize = 13.sp)
            Text(
                text = display,
                color = Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
private fun CollapsibleDiagnosticsCard(
    info: TorchController.Info,
    testEnabled: Boolean,
    onTest: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceInk)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ДИАГНОСТИКА ВСПЫШКИ",
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (expanded) "свернуть ▴" else "развернуть ▾",
                    color = Muted.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
            if (expanded) {
                DiagRow("Устройство", "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagRow("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                DiagRow("Камера", info.cameraId)
                DiagRow(
                    "Макс. яркость",
                    if (info.mode == TorchController.Mode.HARDWARE) {
                        "${info.maxStrength} ур. (по умолч. ${info.defaultStrength})"
                    } else {
                        "1 ур. — не поддерживается"
                    }
                )
                DiagRow(
                    "Режим",
                    when (info.mode) {
                        TorchController.Mode.HARDWARE -> "аппаратный"
                        TorchController.Mode.ONOFF -> "вкл/выкл, без ШИМ"
                        TorchController.Mode.NONE -> "нет"
                    }
                )

                Text(
                    text = if (info.mode == TorchController.Mode.HARDWARE) {
                        "Драйвер камеры поддерживает несколько уровней тока — используется " +
                            "turnOnTorchWithStrengthLevel (Android 13)."
                    } else {
                        "Android 13 сам по себе не даёт яркость: нужен драйвер с " +
                            "FLASH_INFO_STRENGTH_MAXIMUM_LEVEL > 1. Если пришло 1, LED умеет " +
                            "только вкл/выкл — вспышка горит на полной яркости, мерцания нет."
                    },
                    color = Muted.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                TextButton(onClick = onTest, enabled = testEnabled) {
                    Text(
                        "Тест вспышки",
                        color = if (testEnabled) Amber else Muted.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = Color(0xFFD5DCE6),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1315)),
        border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Проблема",
                color = Color(0xFFFF9A9A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(text = message, color = Color(0xFFFFD5D5), fontSize = 12.sp, lineHeight = 16.sp)
            Row {
                TextButton(onClick = onOpenSettings) {
                    Text("Настройки", color = Amber, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Скрыть", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

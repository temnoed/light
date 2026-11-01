package com.example.light

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.light.ui.LightScreen
import com.example.light.ui.theme.LightTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Отладочный режим: `adb shell am start -n com.example.light/.MainActivity --ez record true`
        // пишет 30 секунд микрофона в files/last.raw для разбора на ПК.
        viewModel.setDebugRecord(intent?.getBooleanExtra("record", false) == true)
        setContent {
            LightTheme {
                LightApp(viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Ушли с экрана (домой, в другое приложение, погас экран) — гасим всё.
        viewModel.stop()
    }

    override fun onStop() {
        super.onStop()
        // Микрофон и вспышка не должны работать в фоне.
        viewModel.stop()
    }

    override fun onDestroy() {
        viewModel.stop()
        super.onDestroy()
    }
}

@Composable
private fun LightApp(viewModel: LightViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            action?.invoke()
        } else {
            viewModel.reportNoMicrophonePermission()
        }
    }

    fun runWithPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    KeepScreenOn(state.running || state.beatTest.running)

    LightScreen(
        state = state,
        onTorch = {
            if (state.torchRunning) {
                viewModel.stopTorch()
            } else {
                runWithPermission { viewModel.startTorch() }
            }
        },
        onScreen = {
            if (state.screenRunning) {
                viewModel.stopScreen()
            } else {
                runWithPermission { viewModel.startScreen() }
            }
        },
        onScreenSensitivity = viewModel::setScreenSensitivity,
        onTestTorch = viewModel::testTorch,
        onDismissError = viewModel::dismissError
    )
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

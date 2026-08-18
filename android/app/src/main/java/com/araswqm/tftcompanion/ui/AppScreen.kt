package com.araswqm.tftcompanion.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

/** Kök ekran: duruma göre Loading / Onboarding / Main / Preview gösterir. */
@Composable
fun AppScreen(viewModel: AppViewModel) {
    val state by viewModel.ui.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.messages.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val screen = state.screen) {
                is AppViewModel.Screen.Loading -> LoadingView()
                is AppViewModel.Screen.Onboarding -> OnboardingScreen(
                    viewModel = viewModel,
                )
                is AppViewModel.Screen.Main -> MainScreen(
                    state = state,
                    onModeChange = viewModel::setMode,
                    onPickMedia = viewModel::onMediaPicked,
                    onStartWatching = viewModel::startWatching,
                    onSpinToggle = viewModel::setSpinEnabled,
                    onSaveEsp32 = viewModel::saveEsp32Settings,
                    onSaveNfc = viewModel::saveNfcSettings,
                    onOpenNls = viewModel::openNotificationSettings,
                    onConnect = viewModel::connect,
                    onEnterChargeMode = viewModel::enterChargingMode,
                    isNlsGranted = viewModel.isNotificationAccessGranted(),
                )
                is AppViewModel.Screen.Preview -> PreviewScreen(
                    preview = screen.preview,
                    fileName = screen.fileName,
                    sizeKb = screen.sizeKb,
                    working = state.working,
                    progressLabel = state.progressLabel,
                    onSend = viewModel::confirmManualSend,
                    onBack = viewModel::backToMain,
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

package com.araswqm.tftcompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.araswqm.tftcompanion.ui.AppScreen
import com.araswqm.tftcompanion.ui.AppViewModel
import com.araswqm.tftcompanion.ui.theme.TftCompanionTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory(application)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // API 33+: ön plan hizmet bildirimi için izin iste
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TftCompanionTheme {
                AppScreen(viewModel)
            }
        }
    }
}

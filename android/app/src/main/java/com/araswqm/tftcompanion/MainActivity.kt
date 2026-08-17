package com.araswqm.tftcompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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

    // API 33+: WifiNetworkSpecifier yakın çevre izni gerektirir (Android 16'da
    // yerel ağ erişimi bu izinle yönetilir). API 29-32'de ise yakın çevre Wi-Fi
    // taraması için konum izni istenir.
    private val nearbyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("MainActivity", "Yakın çevre izni sonucu: granted=$granted")
    }

    private fun needsPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

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
        // ESP32'ye programatik Wi-Fi bağlantısı için gereken izni iste
        needsPermission()?.let { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                nearbyPermission.launch(perm)
            }
        }
        setContent {
            TftCompanionTheme {
                AppScreen(viewModel)
            }
        }
    }
}

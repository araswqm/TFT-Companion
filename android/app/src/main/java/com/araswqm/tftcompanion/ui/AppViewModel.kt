package com.araswqm.tftcompanion.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Network
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.araswqm.tftcompanion.R
import com.araswqm.tftcompanion.convert.MediaPreparer
import com.araswqm.tftcompanion.data.AppSettings
import com.araswqm.tftcompanion.data.MediaMode
import com.araswqm.tftcompanion.data.SettingsRepository
import com.araswqm.tftcompanion.media.MediaWatchService
import com.araswqm.tftcompanion.media.NowPlaying
import com.araswqm.tftcompanion.media.NowPlayingBus
import com.araswqm.tftcompanion.net.Esp32Api
import com.araswqm.tftcompanion.net.WifiConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Uygulamanın tek ViewModel'i. Durum StateFlow ile yayılır:
 *  - Otomatik mod: NowPlayingBus'tan gelen medya 1 sn debounce ile ESP32'ye gönderilir
 *    (collectLatest + delay = debounce, önceki bekleyen gönderim iptal edilir).
 *  - Manuel mod: GetContent ile seçilen medya önizlenir ve gönderilir.
 *  - Bağlantı: WifiNetworkSpecifier ile ESP32 ağına bağlanır; soketler yalnızca
 *    o Network'e bağlanır (mobil internet korunur).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AppViewModel"
        private const val AUTO_DEBOUNCE_MS = 1000L      // 800ms–1.5s aralığında
        private const val DEFAULT_MAX_BYTES = 700 * 1024L // status alınamazsa kullanılan varsayılan
    }

    // ---- Durumlar ----

    sealed interface Screen {
        data object Loading : Screen
        data object Onboarding : Screen
        data object Main : Screen
        data class Preview(
            val preview: Bitmap?,
            val fileName: String,
            val sizeKb: Int,
        ) : Screen
    }

    enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR }

    data class UiState(
        val screen: Screen = Screen.Loading,
        val mode: MediaMode = MediaMode.AUTO,
        val settings: AppSettings = AppSettings(),
        val nowPlaying: NowPlaying? = null,
        val connState: ConnState = ConnState.IDLE,
        val lastError: String? = null,
        val working: Boolean = false,          // hazırlama/gönderme sürüyor
        val progress: Float = -1f,             // 0..1 veya -1 (belirsiz)
        val progressLabel: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    // ---- Bileşenler ----

    private val settingsRepository: SettingsRepository =
        (application as com.araswqm.tftcompanion.TftCompanionApp).settingsRepository
    private val preparer = MediaPreparer(application)
    private val wifiConnector = WifiConnector(application)

    private var api: Esp32Api? = null
    private var boundNetwork: Network? = null
    private var manualDraft: ManualDraft? = null

    private data class ManualDraft(
        val uri: android.net.Uri,
        val contentType: String?,
        val prepared: MediaPreparer.PreparedFile,
    )

    init {
        // Ayarlar + mod -> UI durumu
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _ui.update { it.copy(settings = settings, mode = settings.mode) }
            }
        }

        // Bildirim dinleyici izni + ilk ekran
        viewModelScope.launch {
            delay(300)
            val firstRun = isNotificationAccessGranted() == false
            _ui.update {
                it.copy(screen = if (firstRun) Screen.Onboarding else Screen.Main)
            }
        }

        // Otomatik mod medya akışı: debounce + gönderim
        viewModelScope.launch {
            NowPlayingBus.flow.collectLatest { np ->
                Log.d(TAG, "Yeni medya: '${np.title}' (debounce $AUTO_DEBOUNCE_MS ms)")
                _ui.update { it.copy(nowPlaying = np) }
                delay(AUTO_DEBOUNCE_MS)  // yeni medya gelirse önceki iptal olur
                if (_ui.value.mode == MediaMode.AUTO) {
                    handleAutoNowPlaying(np)
                }
            }
        }
    }

    // ------------------------------------------------------------ Otomatik

    private suspend fun handleAutoNowPlaying(np: NowPlaying) {
        val art = np.albumArt ?: run {
            Log.d(TAG, "Albüm kapağı yok, atlanıyor")
            return
        }
        // Büyük durum güncellemesi: gönderim hazırlığı
        _ui.update { it.copy(working = true, progressLabel = "Şarkı kapağı hazırlanıyor…") }
        try {
            // ESP32 boş alanını öğren
            val maxBytes = queryMaxBytes()
            val prepared = withContext(Dispatchers.Default) {
                preparer.prepareBitmap(art, maxBytes)
            }
            Log.d(TAG, "Otomatik kapak hazır: ${prepared.fileName} (${prepared.sizeKb} KB)")
            uploadPrepared(prepared)
        } catch (e: Exception) {
            Log.w(TAG, "Otomatik gönderim hatası: ${e.message}")
            showError("Otomatik gönderim başarısız: ${e.message}")
        } finally {
            _ui.update { it.copy(working = false, progress = -1f, progressLabel = null) }
        }
    }

    // ------------------------------------------------------------ Manuel

    /** Kullanıcı bir dosya seçti: önizleme üret, Preview ekranına geç. */
    fun onMediaPicked(uri: android.net.Uri, contentType: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(working = true, progressLabel = "Önizleme oluşturuluyor…") }
            try {
                val preview = preparer.previewFrame(uri, contentType)
                val maxBytes = queryMaxBytes()
                val prepared = withContext(Dispatchers.Default) {
                    preparer.prepare(uri, contentType, maxBytes) { done, _ ->
                        _ui.update {
                            it.copy(
                                working = true,
                                progressLabel = "Dönüştürülüyor… $done kare",
                            )
                        }
                    }
                }
                manualDraft = ManualDraft(uri, contentType, prepared)
                _ui.update {
                    it.copy(
                        screen = Screen.Preview(
                            preview = preview,
                            fileName = prepared.fileName,
                            sizeKb = prepared.sizeKb,
                        ),
                        working = false,
                        progressLabel = null,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Medya hazırlama hatası: ${e.message}")
                showError("Medya hazırlanamadı: ${e.message}")
                _ui.update { it.copy(working = false, progressLabel = null) }
            }
        }
    }

    fun confirmManualSend() {
        val draft = manualDraft ?: return
        viewModelScope.launch {
            _ui.update { it.copy(working = true, progressLabel = "ESP32'ye gönderiliyor…") }
            try {
                uploadPrepared(draft.prepared)
                showMessage("Gönderildi: ${draft.prepared.fileName}")
            } catch (e: Exception) {
                showError("Gönderim başarısız: ${e.message}")
            } finally {
                _ui.update { it.copy(working = false, progress = -1f, progressLabel = null) }
            }
        }
    }

    fun backToMain() {
        manualDraft = null
        _ui.update { it.copy(screen = Screen.Main) }
    }

    // ------------------------------------------------------------ Bağlantı

    private suspend fun queryMaxBytes(): Long {
        val api = ensureApi() ?: return DEFAULT_MAX_BYTES
        return withContext(Dispatchers.IO) {
            api.getStatus(_ui.value.settings.baseUrl)?.freeSpace
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_BYTES
        }
    }

    /**
     * ESP32 ağına bağlan ve Esp32Api döndür. Ağ zaten bağlıysa mevcut api kullanılır.
     * Bağlantı sırasında UI'da CONNECTING durumu gösterilir.
     */
    private suspend fun ensureApi(): Esp32Api? {
        api?.let { return it }
        val settings = _ui.value.settings
        _ui.update { it.copy(connState = ConnState.CONNECTING) }
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            wifiConnector.connect(
                ssid = settings.ssid,
                password = settings.password,
                onConnected = { network ->
                    Log.d(TAG, "ESP32 ağı bağlı: $network")
                    boundNetwork = network
                    val newApi = Esp32Api(network)
                    api = newApi
                    _ui.update { it.copy(connState = ConnState.CONNECTED) }
                    if (cont.isActive) cont.resumeWith(Result.success(newApi))
                },
                onUnavailable = { msg ->
                    Log.w(TAG, "Bağlantı başarısız: $msg")
                    _ui.update { it.copy(connState = ConnState.ERROR) }
                    if (cont.isActive) cont.resumeWith(Result.success<Esp32Api?>(null))
                },
                onOpenWifiSettings = {
                    // API < 29: kullanıcıyı ayarlara yönlendir
                    openWifiSettings()
                    if (cont.isActive) cont.resumeWith(Result.success<Esp32Api?>(null))
                },
            )
            cont.invokeOnCancellation { wifiConnector.disconnect() }
        }
    }

    private fun openWifiSettings() {
        val context = getApplication<Application>()
        try {
            val intent = android.content.Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showError("Wi-Fi ayarları açılamadı")
        }
    }

    private suspend fun uploadPrepared(prepared: MediaPreparer.PreparedFile) {
        val api = ensureApi() ?: throw IllegalStateException("ESP32'ye bağlanılamadı")
        val base = _ui.value.settings.baseUrl
        val resp = withContext(Dispatchers.IO) {
            api.upload(base, prepared.bytes, prepared.fileName)
        }
        Log.d(TAG, "Upload cevabı: $resp")
    }

    // ------------------------------------------------------------ Mod

    fun setMode(mode: MediaMode) {
        viewModelScope.launch {
            settingsRepository.setMode(mode)
            // Otomatik mod açıksa izleme servisini başlat, kapalıysa durdur
            val ctx = getApplication<Application>()
            if (mode == MediaMode.AUTO) {
                startWatchService(ctx)
            } else {
                ctx.stopService(android.content.Intent(ctx, MediaWatchService::class.java))
            }
        }
    }

    fun saveEsp32Settings(ssid: String, password: String, ip: String, port: Int) {
        viewModelScope.launch {
            settingsRepository.setEsp32(ssid, password, ip, port)
            showMessage("ESP32 ayarları kaydedildi")
        }
    }

    fun startWatching() {
        startWatchService(getApplication())
    }

    fun setOnboardingDone() {
        _ui.update { it.copy(screen = Screen.Main) }
    }

    // ------------------------------------------------------------ İzinler

    fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            "enabled_notification_listeners",
        )
        val pkg = getApplication<Application>().packageName
        val expected = ComponentName(pkg, "com.araswqm.tftcompanion.media.MediaNotificationListener").flattenToString()
        val result = enabled?.split(":")?.any { it.equals(expected, ignoreCase = true) } == true
        Log.d(TAG, "Bildirim dinleyici izni: $result")
        return result
    }

    fun openNotificationSettings() {
        try {
            val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            showError("Bildirim ayarları açılamadı")
        }
    }

    // ------------------------------------------------------------ Mesajlar

    private fun startWatchService(ctx: Context) {
        try {
            val intent = android.content.Intent(ctx, MediaWatchService::class.java)
            ctx.startForegroundService(intent)
            Log.d(TAG, "MediaWatchService başlatıldı")
        } catch (e: Exception) {
            Log.w(TAG, "Hizmet başlatılamadı: ${e.message}")
            showError("Medya izleme başlatılamadı: ${e.message}")
        }
    }

    fun consumeMessage() {
        _messages.update { null }
    }

    private fun showError(msg: String) {
        _ui.update { it.copy(lastError = msg) }
        _messages.update { msg }
    }

    private fun showMessage(msg: String) {
        _messages.update { msg }
    }
}

package com.araswqm.tftcompanion.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
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
import com.araswqm.tftcompanion.net.NfcServerApi
import com.araswqm.tftcompanion.net.WifiConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
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
        // NetworkCapabilities.NET_CAPABILITY_LOCAL gizli bir API'dir (public
        // android.jar'da yok). Yerel-ağ (WifiNetworkSpecifier) bağlantılarını ayırt
        // etmek için ham değeri kullanılır; elle kurulan bağlantılarda bu bayrak yoktur.
        private const val NET_CAPABILITY_LOCAL = 19
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

    // Kalıcı ayarların senkron okunabilir (StateFlow) kopyası. ensureApi() bunu
    // kullanır ki soğuk başlangıçta _ui.value.settings henüz doldurulmamış olsa
    // bile bağlantı eski/boş ayarlarla yapılmasın.
    private val settingsState: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val preparer = MediaPreparer(application)
    private val wifiConnector = WifiConnector(application)
    // NFC sunucusu ("şu an çalan şarkı" sayfası) istemcisi. İnternete gider,
    // ESP32 ağına bind edilmez (ayrı sınıf, ayrı OkHttpClient).
    private val nfcServerApi = NfcServerApi()

    private var api: Esp32Api? = null
    private var boundNetwork: Network? = null
    private var manualDraft: ManualDraft? = null
    private var manualNetWarned = false  // elle ESP32'ye bağlanma uyarısı oturumda bir kez

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

        // Ana ekran açıldığında ESP32'ye otomatik bağlan. Bu, kullanıcı "fotoğraf
        // seç ve gönder" demeden durum kartının "ESP32 bağlı" göstermesini sağlar.
        // connect() zaten CONNECTED/CONNECTING durumlarını yinelenen bağlantıya
        // karşı korur, bu yüzden Preview'dan dönüşlerde yeniden tetiklenmesi zararsızdır.
        // Otomatik modda izleme servisi de burada başlatılır: yeni kurulumda
        // kullanıcı modu hiç değiştirmezse setMode hiç çalışmaz ve şarkı asla
        // algılanmaz. Ekran ön plandayken (Main) başlatılır ki startForegroundService
        // güvenle çağrılabilsin; zaten çalışan serviste tekrar çağrı zararsızdır.
        viewModelScope.launch {
            _ui.map { it.screen }
                .distinctUntilChanged()
                .collect { screen ->
                    if (screen == Screen.Main) {
                        connect()
                        if (_ui.value.mode == MediaMode.AUTO) {
                            startWatchService(getApplication())
                        }
                    }
                }
        }

        // Otomatik mod medya akışı: debounce + gönderim
        viewModelScope.launch {
            // AYNI şarkının tekrar eden emisyonlarını ele: müzik uygulamaları
            // ilerleme çubuğunu güncellemek için bildirimi saniyede bir yeniler,
            // MediaNotificationListener her güncellemede NowPlayingBus'a aynı
            // şarkıyı yeniden basar. Dedup olmasa collectLatest her seferinde
            // yavaş GIF hazırlığını iptal eder ve gönderim asla tamamlanmaz
            // ("şarkı kapağı hazırlanıyor" gidip gelir, ekrana hiçbir şey gelmez).
            // Anahtar: başlık + sanatçı + kapağın olup olmaması (önce kapaksız,
            // sonra kapaklı iki emisyonun ikisi de geçsin; aynı şarkının
            // sadece-kapak değişmeyen tekrarları elensin).
            NowPlayingBus.flow
                .distinctUntilChangedBy { Triple(it.title, it.artist, it.albumArt != null) }
                .collectLatest { np ->
                // Yarış düzeltmesi: MediaSessionWatcher (kapak yüklenmeden önce
                // null yayınlayabilir) ve MediaNotificationListener aynı şarkı için
                // aynı anda farklı emisyonlar üretebilir. Kapak zaten yüklendiyse
                // null-kapak emisyonu onu ezmesin — aynı şarkının kapsız haliyle
                // değiştirilmez (yoksa otomatik gönderim kapağı kaybeder).
                val current = _ui.value.nowPlaying
                val effective = if (np.albumArt == null &&
                    current?.title == np.title &&
                    current.artist == np.artist &&
                    current.albumArt != null
                ) {
                    current
                } else {
                    np
                }
                Log.d(TAG, "Yeni medya: '${effective.title}' (debounce $AUTO_DEBOUNCE_MS ms)")
                // Boş başlık = müzik durdu -> ekranda "şu an çalan yok" göster (null).
                _ui.update {
                    it.copy(nowPlaying = if (effective.title.isBlank()) null else effective)
                }
                // NFC sunucusu push: yalnızca AUTO modda, debounce'dan ÖNCE bağımsız
                // launch ile (temizleme anında gitmeli; iptal edilemez). Boş başlık
                // sunucudaki şarkıyı siler. ESP32 gönderimi için kapağa ihtiyaç yok —
                // sadece başlık+sanatçı ile internetten POST edilir.
                if (_ui.value.mode == MediaMode.AUTO) {
                    pushToNfcServer(effective)
                }
                delay(AUTO_DEBOUNCE_MS)  // yeni medya gelirse önceki iptal olur
                if (_ui.value.mode == MediaMode.AUTO) {
                    handleAutoNowPlaying(effective, spin = settingsState.value.spinEnabled)
                }
            }
        }
    }

    // ------------------------------------------------------------ Otomatik

    private suspend fun handleAutoNowPlaying(np: NowPlaying, spin: Boolean) {
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
                // settings.spinEnabled'e göre: plak animasyonu (MJPEG) ya da düz tek kare kapak
                if (spin) preparer.prepareBitmap(art, maxBytes)
                else preparer.prepareCover(art, maxBytes)
            }
            Log.d(TAG, "Otomatik kapak hazır: ${prepared.fileName} (${prepared.sizeKb} KB)")
            uploadPrepared(prepared)
        } catch (e: CancellationException) {
            // Yeni bir şarkı geldiğinde collectLatest önceki gönderimi iptal eder;
            // bu beklenen bir davranıştır, hata olarak gösterilmemeli.
            Log.d(TAG, "Otomatik gönderim iptal edildi (yeni şarkı öncelikli)")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Otomatik gönderim hatası: ${e.message}")
            showError("Otomatik gönderim başarısız: ${e.message}")
        } finally {
            _ui.update { it.copy(working = false, progress = -1f, progressLabel = null) }
        }
    }

    /**
     * "Şu an çalan şarkı"yı NFC sunucusuna bildir. NFC ayarları boşsa (kullanıcı
     * yapılandırmadıysa) hiçbir şey yapılmaz. Boş başlık -> sunucudaki mevcut şarkı
     * temizlenir. Best-effort: başarısızlık yalnızca loglanır, kullanıcıya hata
     * gösterilmez (NFC tag'i isteğe bağlı bir eklentidir).
     */
    private fun pushToNfcServer(np: NowPlaying) {
        val settings = settingsState.value
        if (settings.nfcServerUrl.isBlank()) return
        viewModelScope.launch {
            val ok = nfcServerApi.pushNowPlaying(
                baseUrl = settings.nfcServerUrl,
                token = settings.nfcServerToken,
                title = np.title,
                artist = np.artist,
            )
            Log.d(TAG, "NFC push ${if (ok) "OK" else "BAŞARISIZ"}: '${np.title}' — ${np.artist}")
        }
    }

    // ------------------------------------------------------------ Manuel

    /** Kullanıcı bir dosya seçti: önizleme üret, Preview ekranına geç. */
    fun onMediaPicked(uri: android.net.Uri, contentType: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(working = true, progressLabel = "Önizleme oluşturuluyor…") }
            try {
                val preview = preparer.previewFrame(uri, contentType)
                // Önizlemede AĞA BAĞLANMA: queryMaxBytes() ESP32'ye bağlantı ister ve
                // izin duvarına takılıp hata/çökme üretebilir. Hazırlık varsayılan
                // boyutla yapılır; gerçek boş alan gönderim anında sorgulanır.
                val prepared = withContext(Dispatchers.Default) {
                    preparer.prepare(uri, contentType, DEFAULT_MAX_BYTES) { done, _ ->
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
            } catch (e: CancellationException) {
                throw e   // beklenen iptal — hata olarak gösterilmemeli
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
                // Gerçek boş alanı öğren; önizleme hazırlığı varsayılan boyutla
                // yapıldıysa ve dosya büyükse burada yeniden boyutlandır.
                val maxBytes = queryMaxBytes()
                val prepared = if (draft.prepared.bytes.size.toLong() > maxBytes) {
                    _ui.update { it.copy(progressLabel = "Boyut uyarlanıyor…") }
                    withContext(Dispatchers.Default) {
                        preparer.prepare(draft.uri, draft.contentType, maxBytes) { done, _ ->
                            _ui.update {
                                it.copy(progressLabel = "Boyut uyarlanıyor… $done kare")
                            }
                        }
                    }
                } else {
                    draft.prepared
                }
                uploadPrepared(prepared)
                showMessage("Gönderildi: ${prepared.fileName}")
            } catch (e: CancellationException) {
                throw e   // beklenen iptal — hata olarak gösterilmemeli
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
            api.getStatus(settingsState.value.baseUrl)?.freeSpace
                ?.takeIf { it > 0 }
                ?: DEFAULT_MAX_BYTES
        }
    }

    /**
     * ESP32'ye şimdi bağlan — durum kartındaki "ESP32'ye Bağlan" butonundan ve
     * ana ekran açılışında otomatik olarak çağrılır. Zaten bağlıyken veya
     * bağlanma sürerken yinelenen istek yapmaz.
     */
    fun connect() {
        val state = _ui.value
        if (state.connState == ConnState.CONNECTING || state.connState == ConnState.CONNECTED) return
        if (state.working) return  // gönderim sürerken bağlantıyı bozma
        viewModelScope.launch { ensureApi() }
    }

    /**
     * ESP32 ağına bağlan ve Esp32Api döndür. Ağ zaten bağlıysa mevcut api kullanılır.
     * Bağlantı sırasında UI'da CONNECTING durumu gösterilir.
     */
    private suspend fun ensureApi(): Esp32Api? {
        api?.let { return it }
        val settings = settingsState.value
        _ui.update { it.copy(connState = ConnState.CONNECTING) }
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            wifiConnector.connect(
                ssid = settings.ssid,
                password = settings.password,
                onConnected = { network ->
                    Log.d(TAG, "ESP32 ağı bağlı: $network")
                    // Kullanıcı Ayarlar'dan ESP32 ağına elle bağlandıysa WifiConnector o
                    // ağı yeniden kullanır (specifier bağlantısı kurulamaz). Böyle bir
                    // ağda NET_CAPABILITY_LOCAL yoktur; telefon interneti kaybeder.
                    // Uyarıyı oturumda bir kez göster, OEM ROM'larda yanlış pozitif
                    // ihtimaline karşı sinir bozucu olmasın.
                    if (!manualNetWarned) {
                        manualNetWarned = true
                        val cm = getApplication<Application>()
                            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val isLocal = runCatching {
                            cm.getNetworkCapabilities(network)
                                ?.hasCapability(NET_CAPABILITY_LOCAL) == true
                        }.getOrDefault(false)
                        if (!isLocal) {
                            showMessage(
                                "ESP32'ye elle bağlısınız — telefonun interneti kesik olabilir. " +
                                    "Wi-Fi Ayarları'ndan bu ağdan çıkıp 'Bağlan' butonunu kullanın."
                            )
                        }
                    }
                    boundNetwork = network
                    val newApi = Esp32Api(network)
                    api = newApi
                    _ui.update { it.copy(connState = ConnState.CONNECTED) }
                    if (cont.isActive) cont.resumeWith(Result.success(newApi))
                },
                onUnavailable = { msg ->
                    Log.w(TAG, "Bağlantı başarısız: $msg")
                    showError(msg)
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
        val base = settingsState.value.baseUrl
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
                // NFC etkinse şu an çalan şarkıyı (varsa) sunucuya yeniden bildir —
                // izleme yeni başladı, watcher hemen mevcut şarkıyı zaten basar ama
                // beklemeden güvenli olsun. Boş başlık gönderilmez (temizleme değil).
                val np = _ui.value.nowPlaying
                if (np != null && np.title.isNotBlank()) pushToNfcServer(np)
            } else {
                ctx.stopService(android.content.Intent(ctx, MediaWatchService::class.java))
                // MANUAL mod: artık izlenmiyor -> sunucudaki "şu an çalan şarkı"yı temizle
                pushToNfcServer(NowPlaying("", "", null, "manual"))
            }
        }
    }

    /**
     * "Plak animasyonu" switch'i: ayarı kalıcı yaz ve O AN çalan şarkı varsa
     * yeni ayarla HEMEN yeniden gönder (şarkı değişmesini bekleme). Yeni spin
     * değeri parametreyle iletilir — DataStore yazımı ile akış emisyonu
     * arasındaki yarış, _ui.value.settings'ten okumaya göre daha güvenilirdir.
     */
    fun setSpinEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSpinEnabled(enabled)
            val np = _ui.value.nowPlaying ?: return@launch
            if (_ui.value.mode == MediaMode.AUTO) {
                handleAutoNowPlaying(np, spin = enabled)
            }
        }
    }

    fun saveEsp32Settings(ssid: String, password: String, ip: String, port: Int) {
        viewModelScope.launch {
            settingsRepository.setEsp32(ssid, password, ip, port)
            showMessage("ESP32 ayarları kaydedildi")
        }
    }

    fun saveNfcSettings(url: String, token: String) {
        viewModelScope.launch {
            settingsRepository.setNfcServer(url, token)
            showMessage("NFC sunucusu ayarları kaydedildi")
        }
    }

    // ------------------------------------------------------------ Şarj modu

    /**
     * ESP32'yi şarj moduna al: tüm fonksiyonlar kapanır, cihaz güç kesilip
     * gelene kadar (switch) derin uykuda kalır. ESP32 cevabı gönderip kendini
     * kapattığı için başarılı yanıt sonrası bağlantı durumu sıfırlanır — ağ
     * düşer, sonraki connect() sıfırdan bağlanır.
     */
    fun enterChargingMode() {
        viewModelScope.launch {
            val esp32 = ensureApi() ?: run {
                showError("ESP32'ye bağlanılamadı — şarj modu tetiklenemedi")
                return@launch
            }
            _ui.update { it.copy(working = true, progressLabel = "ESP32 kapatılıyor…") }
            val ok = esp32.enterChargingMode(settingsState.value.baseUrl)
            _ui.update { it.copy(working = false, progressLabel = null) }
            if (ok) {
                // ESP32 kendini kapattı; eski api/network artık geçersiz.
                // Local değişkene 'api' adını vermediğimiz için buradaki 'api'
                // doğrudan sınıf alanına (var api) çözümlenir — shadow yok.
                api = null
                boundNetwork = null
                _ui.update { it.copy(connState = ConnState.IDLE) }
                showMessage("Şarj modu etkin. ESP32 kapalı — şarj bittiğinde switch'i kapatıp açın.")
            } else {
                showError("Şarj modu isteği yanıtsız kaldı. ESP32 açıksa tekrar deneyin.")
            }
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

# VinylTag — ESP32-C6 Anahtarlık Eşlik Uygulaması

ESP32-C6 tabanlı, 128×128 ST7735 TFT ekranlı anahtarlığa albüm kapağı, resim, GIF ve video (MJPEG) gönderen bağımsız bir Android uygulaması (Kotlin + Jetpack Compose).

## Nasıl çalışır

1. **Otomatik Şarkı Kapağı modu:** Uygulama `NotificationListenerService` + `MediaSessionManager` ile sistemdeki herhangi bir müzik uygulamasını (Spotify, YouTube Music, vb.) dinler. Şarkı değişiminde albüm kapağını 128×128 JPEG'e dönüştürür, 1 sn debounce ile ESP32'ye gönderir.
2. **Manuel Medya modu:** Galeriden görüntü/GIF/video seçilir, 128×128 önizleme ve tahmini boyut gösterilir, "Gönder" ile ESP32'ye yüklenir.
3. **Ağ:** Telefon ESP32'nin softAP'ine (`ESP32-TFT`) `WifiNetworkSpecifier` ile bağlanır. Bağlantı **mobil interneti kesmez** — sadece ESP32'ye giden istekler bu WiFi ağı üzerinden (`Network.getSocketFactory()` ile bağlanmış OkHttpClient) gider.
4. **ESP32:** Medyayı `/upload` (multipart, `image` alanı) ile alır. Sunucu sürekli açıktır (upload sonrası WiFi kapatılmaz). `GET /status` → `{"freeSpace":<byte>,"currentMedia":"gif|jpeg|mjpeg|none","uptime":<ms>}` döndürür; Android bu endpoint'ten boş alanı öğrenip JPEG kalitesini / videonun uzunluğunu otomatik ayarlar.

## Android uygulamasını kurma

APK'yı [Actions'tan](https://github.com/araswqm/VinylTag/actions) indirin (en son başarılı çalışmanın **app-debug.apk** artifact'i) veya `android/` klasöründen derleyin:

```
cd android
./gradlew assembleDebug
```

APK çıktısı: `android/app/build/outputs/apk/debug/app-debug.apk`

### İlk kurulumda verilecek izinler

1. **Bildirim erişimi (Notification Access)** — Otomatik mod için zorunlu. Açılış ekranından "Bildirim iznini etkinleştir" → Ayarlar > Bildirim erişimi > **VinylTag Medya Dinleyici**.
2. **Bildirimler (Android 13+)**, **Yakındaki cihazlar (WiFi)** ve konum — otomatik WiFi bağlantısı ve ön plan hizmet bildirimi için.

> Not: Yerel ağ erişimi için konum izni zorunludur (Android 11+). İzinlerle ilgili sorun yaşarsanız "İzlemeyi başlat / yeniden dene"ye basın.

## ESP32'ye flashlama

Kod: `firmware/esp32_c6_tft_media_player/esp32_c6_tft_media_player.ino`

Gerekli kütüphaneler (değişmedi):

- **TFT_eSPI** (Cincinnatu fork) — `User_Setup.h` içindeki ST7735 128×128 tanımları dosyanın başında yer alıyor
- **AnimatedGIF**
- **JPEGDecoder**
- **LittleFS** (ESP32 core ile gelir)

Arduino IDE'de `esp32-c6` kart paketini kurun, LittleFS partition şemasıyla yükleyin (`Tools > Partition Scheme > "Default 4MB with spiffs"` veya benzeri, flash 4 MB üzeri). Seri port seçip **Upload** edin.

Güç verildiğinde ekranda WiFi bilgileri görünür: SSID `ESP32-TFT`, şifre `12345678`, IP `192.168.4.1`, port `80`. Bu değerler uygulamanın **ESP32 Ayarları** ekranından değiştirilebilir (firmware'de de güncellenmesi gerekir).

## Yapı

```
android/    — Android uygulaması (Kotlin + Compose, tamamen bağımsız)
firmware/   — ESP32-C6 Arduino kodu (.ino)
```

Android tarafı öne çıkanlar:

- `media/` — sistem geneli şu an çalan medya izleme (MediaSession + NotificationListener), debounce akışı
- `convert/` — 128×128 center-crop, GIF→MJPEG, video→MJPEG (MediaCodec), JPEG kalite ayarı, LittleFS boyut kontrolü
- `net/` — `WifiNetworkSpecifier` + `Network.getSocketFactory()` ile izole bağlantı, ESP32 API istemcisi (`/status`, `/upload`)
- `ui/` — Compose ekranları (açılış izni, ana ekran, önizleme) ve ViewModel

Bu proje Meld/Metrolist'ten tamamen bağımsızdır.

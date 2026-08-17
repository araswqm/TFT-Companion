# Görev: ESP32-C6 TFT Anahtarlık için Android Eşlik Uygulaması

Sıfırdan yeni bir Android uygulaması (Kotlin + Jetpack Compose, Meld/Metrolist projesiyle hiçbir ilgisi yok, bağımsız bir proje) geliştirmeni istiyorum. Bu uygulama, elimdeki bir ESP32-C6 tabanlı, 128×128 ST7735 TFT ekranlı anahtarlığa (aşağıda tam kodu var) kablosuz olarak medya (albüm kapağı, resim, gif, video) gönderecek.

## Donanım ve mevcut ESP32 kodu

ESP32 şu an aşağıdaki Arduino (C++) kodunu çalıştırıyor. Bu kodu **uygulamayla en uyumlu şekilde çalışacak hale getirmek için sen düzenleyebilirsin** — yani ESP32 tarafı sabit değil, Android uygulamasıyla birlikte optimize edilecek iki parçadan biri.

```cpp
/*
 * ESP32-C6 Super Mini — TFT Media Player
 * Desteklenen formatlar : JPEG · GIF (animasyonlu) · MJPEG (≥20 FPS video)
 * Ekran               : 128×128 ST7735 (SPI)
 * Kütüphaneler        : TFT_eSPI (Cincinnatu fork), AnimatedGIF, JPEGDecoder, LittleFS
 *
 * ── User_Setup.h içinde tanımlanması gereken makrolar ──────────────────
 *   #define USER_SETUP_INFO "ST7735 128x128 - ESP32-C6 Super Mini"
 *   #define USER_SETUP_LOADED
 *   #define ST7735_DRIVER
 *   #define ST7735_GREENTAB
 *   #define TFT_WIDTH   128
 *   #define TFT_HEIGHT  128
 *   #define TFT_CS    14
 *   #define TFT_DC    19
 *   #define TFT_RST   20
 *   #define TFT_MOSI   4
 *   #define TFT_SCLK   7
 *   #define TFT_MISO  -1
 *   #define USE_FSPI_PORT
 *   #define SPI_FREQUENCY  27000000
 *   #define LOAD_GLCD
 *   #define LOAD_FONT2
 * ───────────────────────────────────────────────────────────────────────
 *
 * ÖNEMLİ: ESP32-C6'da TFT_eSPI SPI'yi otomatik başlatamıyor.
 *          setup() içinde tft.init() öncesine SPI.begin() eklenmiştir.
 */

// ═══════════════════════════════════════════════════════════════════════
//  BAĞIMLILIKLAR
// ═══════════════════════════════════════════════════════════════════════
#include <WiFi.h>
#include <WebServer.h>
#include <SPI.h>
#include <TFT_eSPI.h>
#include <LittleFS.h>
#include <JPEGDecoder.h>
#include <Adafruit_NeoPixel.h>
#include <AnimatedGIF.h>

// ═══════════════════════════════════════════════════════════════════════
//  SABİTLER & DONANIM
// ═══════════════════════════════════════════════════════════════════════
#define PIN_LED    8
#define NUM_PIXELS 1

#define SCREEN_W  128
#define SCREEN_H  128

#define MJPEG_FRAME_MS  50

// ═══════════════════════════════════════════════════════════════════════
//  GLOBAL NESNELER
// ═══════════════════════════════════════════════════════════════════════
TFT_eSPI          tft;
Adafruit_NeoPixel led(NUM_PIXELS, PIN_LED, NEO_GRB + NEO_KHZ800);

const char* ssid     = "ESP32-TFT";
const char* password = "12345678";
WebServer   server(80);

enum MediaType { MEDIA_NONE, MEDIA_JPEG, MEDIA_GIF, MEDIA_MJPEG };
MediaType currentMedia = MEDIA_NONE;

// GIF
AnimatedGIF gif;
File        gifFile;
bool        gifPlaying    = false;
int         gifOffX = 0, gifOffY = 0;
float       gifScale      = 1.0f;
unsigned long gifLastFrame = 0;
int         gifFrameDelay = 0;

// MJPEG
bool        mjpegPlaying    = false;
File        mjpegFile;
uint8_t*    mjpegBuf        = nullptr;
size_t      mjpegBufSize    = 0;
unsigned long mjpegLastFrame = 0;

// Web sunucu kapatma
bool shutdownRequested = false;
bool serverStopped     = false;

// ═══════════════════════════════════════════════════════════════════════
//  YARDIMCI — Bellek yönetimi
// ═══════════════════════════════════════════════════════════════════════
static void freeMedia() {
  gifPlaying = false;
  gif.close();

  mjpegPlaying = false;
  if (mjpegFile) mjpegFile.close();
  if (mjpegBuf) { free(mjpegBuf); mjpegBuf = nullptr; mjpegBufSize = 0; }

  currentMedia = MEDIA_NONE;
}

// ═══════════════════════════════════════════════════════════════════════
//  GIF CALLBACK'LERİ
// ═══════════════════════════════════════════════════════════════════════
static File gifCbFile;

void* GIFOpenFile(const char* fname, int32_t* pSize) {
  gifCbFile = LittleFS.open(fname, "r");
  if (gifCbFile) { *pSize = gifCbFile.size(); return &gifCbFile; }
  return nullptr;
}
void GIFCloseFile(void* pHandle) {
  File* f = static_cast<File*>(pHandle);
  if (f && *f) f->close();
}
int32_t GIFReadFile(GIFFILE* pFile, uint8_t* pBuf, int32_t iLen) {
  File* f = static_cast<File*>(pFile->fHandle);
  int32_t toRead = min((int32_t)(pFile->iSize - pFile->iPos), iLen);
  if (toRead <= 0) return 0;
  toRead = f->read(pBuf, toRead);
  pFile->iPos = f->position();
  return toRead;
}
int32_t GIFSeekFile(GIFFILE* pFile, int32_t iPosition) {
  File* f = static_cast<File*>(pFile->fHandle);
  f->seek(iPosition);
  pFile->iPos = f->position();
  return pFile->iPos;
}

void GIFDraw(GIFDRAW* pDraw) {
  int y = gifOffY + (int)((pDraw->iY + pDraw->y) * gifScale);
  if (y < 0 || y >= SCREEN_H) return;

  uint16_t* pal = (uint16_t*)pDraw->pPalette;
  uint8_t*  s   = pDraw->pPixels;

  static uint16_t lineBuf[SCREEN_W];
  int outW = 0;
  int startX = -1;

  for (int x = 0; x < pDraw->iWidth && outW < SCREEN_W; x++) {
    int drawX = gifOffX + (int)((pDraw->iX + x) * gifScale);
    if (drawX < 0 || drawX >= SCREEN_W) continue;
    if (startX < 0) startX = drawX;
    lineBuf[outW++] = pal[s[x]];
  }

  if (outW > 0 && startX >= 0) {
    tft.pushImage(startX, y, outW, 1, lineBuf);
  }
}

// ═══════════════════════════════════════════════════════════════════════
//  GIF BAŞLAT
// ═══════════════════════════════════════════════════════════════════════
void startGif(const char* filename) {
  freeMedia();
  tft.fillScreen(TFT_BLACK);

  gif.begin(GIF_PALETTE_RGB565_BE);
  if (!gif.open(filename, GIFOpenFile, GIFCloseFile, GIFReadFile, GIFSeekFile, GIFDraw)) {
    tft.println("GIF acilamadi");
    return;
  }

  int gW = gif.getCanvasWidth();
  int gH = gif.getCanvasHeight();
  gifScale = min((float)SCREEN_W / gW, (float)SCREEN_H / gH);
  if (gifScale > 1.0f) gifScale = 1.0f;
  gifOffX = (SCREEN_W - (int)(gW * gifScale)) / 2;
  gifOffY = (SCREEN_H - (int)(gH * gifScale)) / 2;

  gifLastFrame  = millis();
  gifFrameDelay = 0;
  gifPlaying    = true;
  currentMedia  = MEDIA_GIF;
  Serial.println("GIF baslatildi.");
}

// ═══════════════════════════════════════════════════════════════════════
//  JPEG GÖSTERİM
// ═══════════════════════════════════════════════════════════════════════
void drawJpeg(const char* filename) {
  freeMedia();

  File f = LittleFS.open(filename, "r");
  if (!f) { Serial.println("JPEG acilamadi"); return; }

  if (!JpegDec.decodeSdFile(f)) {
    Serial.println("JPEG decode hatasi");
    f.close(); return;
  }

  int jpW = JpegDec.width, jpH = JpegDec.height;
  float sc = min((float)SCREEN_W / jpW, (float)SCREEN_H / jpH);
  int offX = (SCREEN_W  - (int)(jpW * sc)) / 2;
  int offY = (SCREEN_H - (int)(jpH * sc)) / 2;

  tft.fillScreen(TFT_BLACK);
  tft.startWrite();

  while (JpegDec.read()) {
    uint16_t* pImg = JpegDec.pImage;
    int mcuW = JpegDec.MCUWidth,  mcuH = JpegDec.MCUHeight;
    int mcuX = JpegDec.MCUx * mcuW, mcuY = JpegDec.MCUy * mcuH;
    int valW = min(mcuW, jpW - mcuX), valH = min(mcuH, jpH - mcuY);
    if (valW <= 0 || valH <= 0) continue;

    for (int row = 0; row < valH; row++) {
      int jy = mcuY + row;
      if (sc < 1.0f && (jy % (int)(1.0f / sc) != 0)) continue;
      int dy = offY + (int)(jy * sc);
      if (dy < 0 || dy >= SCREEN_H) continue;

      static uint16_t rowBuf[SCREEN_W];
      int cnt = 0, startDx = -1;
      for (int col = 0; col < valW; col++) {
        int jx = mcuX + col;
        if (sc < 1.0f && (jx % (int)(1.0f / sc) != 0)) continue;
        int dx = offX + (int)(jx * sc);
        if (dx < 0 || dx >= SCREEN_W) continue;
        if (startDx < 0) startDx = dx;
        uint16_t px = pImg[row * mcuW + col];
        rowBuf[cnt++] = (px >> 8) | (px << 8);
      }
      if (cnt > 0 && startDx >= 0)
        tft.pushImage(startDx, dy, cnt, 1, rowBuf);
    }
  }
  tft.endWrite();
  f.close();
  currentMedia = MEDIA_JPEG;
  Serial.println("JPEG gosterildi.");
}

// ═══════════════════════════════════════════════════════════════════════
//  MJPEG OYNATICI
// ═══════════════════════════════════════════════════════════════════════
bool mjpegFindNextFrame(File& f, size_t* outStart, size_t* outLen) {
  int b0 = -1, b1 = -1;
  while (f.available()) {
    b0 = b1;
    b1 = f.read();
    if (b0 == 0xFF && b1 == 0xD8) break;
  }
  if (!f.available() && !(b0 == 0xFF && b1 == 0xD8)) return false;

  size_t start = f.position() - 2;

  b0 = -1; b1 = -1;
  while (f.available()) {
    b0 = b1;
    b1 = f.read();
    if (b0 == 0xFF && b1 == 0xD9) break;
  }
  if (!(b0 == 0xFF && b1 == 0xD9)) return false;

  *outStart = start;
  *outLen   = f.position() - start;
  return true;
}

void startMjpeg(const char* filename) {
  freeMedia();
  tft.fillScreen(TFT_BLACK);

  mjpegFile = LittleFS.open(filename, "r");
  if (!mjpegFile) { Serial.println("MJPEG acilamadi"); return; }

  mjpegBufSize = 32768;
  mjpegBuf = (uint8_t*)malloc(mjpegBufSize);
  if (!mjpegBuf) { Serial.println("MJPEG heap hatasi"); mjpegFile.close(); return; }

  mjpegLastFrame = millis();
  mjpegPlaying   = true;
  currentMedia   = MEDIA_MJPEG;
  Serial.println("MJPEG baslatildi.");
}

bool mjpegPlayFrame() {
  size_t fStart, fLen;

  if (!mjpegFindNextFrame(mjpegFile, &fStart, &fLen)) {
    mjpegFile.seek(0);
    return false;
  }

  if (fLen > mjpegBufSize) {
    free(mjpegBuf);
    mjpegBufSize = fLen + 512;
    mjpegBuf = (uint8_t*)malloc(mjpegBufSize);
    if (!mjpegBuf) { Serial.println("MJPEG realloc hatasi"); mjpegPlaying = false; return false; }
  }

  mjpegFile.seek(fStart);
  size_t read = mjpegFile.read(mjpegBuf, fLen);
  if (read != fLen) return false;

  if (!JpegDec.decodeArray(mjpegBuf, fLen)) return true;

  int jpW = JpegDec.width, jpH = JpegDec.height;
  float sc = min((float)SCREEN_W / jpW, (float)SCREEN_H / jpH);
  if (sc > 1.0f) sc = 1.0f;
  int offX = (SCREEN_W  - (int)(jpW * sc)) / 2;
  int offY = (SCREEN_H - (int)(jpH * sc)) / 2;

  tft.startWrite();
  while (JpegDec.read()) {
    uint16_t* pImg = JpegDec.pImage;
    int mcuW = JpegDec.MCUWidth,  mcuH = JpegDec.MCUHeight;
    int mcuX = JpegDec.MCUx * mcuW, mcuY = JpegDec.MCUy * mcuH;
    int valW = min(mcuW, jpW - mcuX), valH = min(mcuH, jpH - mcuY);
    if (valW <= 0 || valH <= 0) continue;

    for (int row = 0; row < valH; row++) {
      int jy   = mcuY + row;
      int dy   = offY + (int)(jy * sc);
      if (dy < 0 || dy >= SCREEN_H) continue;

      static uint16_t rowBuf[SCREEN_W];
      int cnt = 0, startDx = -1;
      for (int col = 0; col < valW; col++) {
        int jx = mcuX + col;
        int dx = offX + (int)(jx * sc);
        if (dx < 0 || dx >= SCREEN_W) continue;
        if (startDx < 0) startDx = dx;
        uint16_t px = pImg[row * mcuW + col];
        rowBuf[cnt++] = (px >> 8) | (px << 8);
      }
      if (cnt > 0 && startDx >= 0)
        tft.pushImage(startDx, dy, cnt, 1, rowBuf);
    }
  }
  tft.endWrite();
  return true;
}

// ═══════════════════════════════════════════════════════════════════════
//  WEB SUNUCUSU
// ═══════════════════════════════════════════════════════════════════════
void handleRoot() {
  const char* html = R"rawliteral(
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ESP32-C6 TFT</title>
<style>
  body{font-family:Arial;text-align:center;margin-top:40px;background:#1a1a2e;color:#eee}
  h1{color:#e94560}
  input,button{padding:10px;margin:10px;font-size:15px;border-radius:6px;border:none}
  button{background:#e94560;color:#fff;cursor:pointer}
  button:hover{background:#c73652}
  .info{color:#aaa;font-size:13px;margin-top:8px}
  .status{color:#ffa500;margin-top:20px;min-height:24px}
</style></head><body>
<h1>ESP32-C6 Media Player</h1>
<p>JPEG, GIF veya MJPEG video yukleyin</p>
<p class="info">MJPEG hazırlama: <code>ffmpeg -i video.mp4 -vf "scale=128:128" -q:v 6 -r 20 output.mjpeg</code></p>
<form id="frm" method="POST" action="/upload" enctype="multipart/form-data">
  <input type="file" name="image" accept="image/jpeg,image/gif,video/x-mjpeg,.mjpeg" required><br>
  <button type="submit">Yukle ve Oynat</button>
</form>
<div class="status" id="st"></div>
<script>
document.getElementById('frm').addEventListener('submit',function(e){
  e.preventDefault();
  var fd=new FormData(e.target);
  document.getElementById('st').innerText='Yukleniyor...';
  fetch('/upload',{method:'POST',body:fd})
    .then(r=>r.text())
    .then(t=>{document.getElementById('st').innerText=t;})
    .catch(()=>{document.getElementById('st').innerText='Baglanti kesildi (sunucu kapandi).';});
});
</script></body></html>)rawliteral";
  server.send(200, "text/html", html);
}

void handleUpload() {
  HTTPUpload& upload = server.upload();
  static File    uploadFile;
  static String  uploadPath;
  static bool    uploadOk;

  if (upload.status == UPLOAD_FILE_START) {
    String fn = upload.filename;
    fn.toLowerCase();

    if (fn.endsWith(".gif"))        uploadPath = "/uploaded.gif";
    else if (fn.endsWith(".mjpeg") ||
             fn.endsWith(".mjpg"))  uploadPath = "/uploaded.mjpeg";
    else                            uploadPath = "/uploaded.jpg";

    if (LittleFS.exists("/uploaded.jpg"))   LittleFS.remove("/uploaded.jpg");
    if (LittleFS.exists("/uploaded.gif"))   LittleFS.remove("/uploaded.gif");
    if (LittleFS.exists("/uploaded.mjpeg")) LittleFS.remove("/uploaded.mjpeg");

    size_t freeBytes = LittleFS.totalBytes() - LittleFS.usedBytes();
    Serial.printf("LittleFS bos: %u B\n", (unsigned)freeBytes);

    uploadFile = LittleFS.open(uploadPath, "w");
    uploadOk   = (bool)uploadFile;
    if (!uploadOk) Serial.println("Dosya olusturulamadi");

  } else if (upload.status == UPLOAD_FILE_WRITE) {
    if (uploadOk && uploadFile) {
      if (uploadFile.write(upload.buf, upload.currentSize) != upload.currentSize) {
        Serial.println("Yazma hatasi, iptal ediliyor");
        uploadOk = false;
        uploadFile.close();
        LittleFS.remove(uploadPath);
      }
    }

  } else if (upload.status == UPLOAD_FILE_END) {
    if (uploadOk && uploadFile) {
      uploadFile.close();
      Serial.printf("Yuklendi: %s (%u B)\n", uploadPath.c_str(), (unsigned)upload.totalSize);

      if      (uploadPath == "/uploaded.gif")   startGif(uploadPath.c_str());
      else if (uploadPath == "/uploaded.mjpeg") startMjpeg(uploadPath.c_str());
      else                                      drawJpeg(uploadPath.c_str());

      server.send(200, "text/plain",
        "Tamam! Oynatma basladi. Ag kısa sürede kapanıyor.");
      shutdownRequested = true;
    } else {
      server.send(500, "text/plain",
        "Hata: Dosya yazılamadi (LittleFS dolu olabilir).");
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════
//  SETUP
// ═══════════════════════════════════════════════════════════════════════
void setup() {
  led.begin();
  led.setPixelColor(0, 0);
  led.show();

  Serial.begin(115200);
  delay(300);

  // ESP32-C6'da TFT_eSPI SPI'yi otomatik başlatamıyor, elle başlatmak gerekiyor
  SPI.begin(7, -1, 4, 14); // SCLK, MISO, MOSI, CS

  tft.init();
  tft.setRotation(0);
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextSize(1);
  tft.setCursor(4, 10);
  tft.println("Baslatiliyor...");

  if (!LittleFS.begin(true)) {
    Serial.println("LittleFS mount hatasi");
    tft.println("FS HATASI!");
  }

  gif.begin(GIF_PALETTE_RGB565_BE);

  WiFi.softAP(ssid, password);
  WiFi.softAPConfig(IPAddress(192,168,4,1),
                    IPAddress(192,168,4,1),
                    IPAddress(255,255,255,0));

  server.on("/", HTTP_GET, handleRoot);
  server.on("/upload", HTTP_POST, []{ server.send(200); }, handleUpload);
  server.begin();

  tft.fillScreen(TFT_BLACK);
  tft.setCursor(4, 4);
  tft.println("WiFi: ESP32-TFT");
  tft.println("Sifre: 12345678");
  tft.println("IP: 192.168.4.1");
  tft.println("Port: 80");
  Serial.println("Hazir — http://192.168.4.1");
}

// ═══════════════════════════════════════════════════════════════════════
//  LOOP
// ═══════════════════════════════════════════════════════════════════════
void loop() {
  if (shutdownRequested) {
    delay(350);
    server.stop();
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    serverStopped     = true;
    shutdownRequested = false;
    Serial.println("Ag kapatildi. Sadece medya aktif.");
  }

  if (!serverStopped) server.handleClient();

  if (gifPlaying) {
    unsigned long now = millis();
    if (now - gifLastFrame >= (unsigned long)gifFrameDelay) {
      int delayMs = 0;
      int rc = gif.playFrame(false, &delayMs);
      gifFrameDelay = (delayMs > 0) ? delayMs : 10;
      gifLastFrame  = now;
      if (rc == 0) gif.reset();
    }
  }

  if (mjpegPlaying) {
    unsigned long now = millis();
    if (now - mjpegLastFrame >= MJPEG_FRAME_MS) {
      bool hasMore = mjpegPlayFrame();
      mjpegLastFrame = millis();
      if (!hasMore) {
        mjpegFile.seek(0);
      }
    }
  }

  delay(1);
}
```

**ESP32 kod değişikliği için önemli not:** Şu anki kod her upload sonrası WiFi'yi tamamen kapatıyor. Bizim senaryomuzda (otomatik, sık sık şarkı kapağı güncellemesi) bu **uygun değil** — her güncellemede ESP32'nin WiFi'yi yeniden açması gerekir ki bu hem yavaş hem de telefon tarafında her seferinde yeniden bağlanma sorunu yaratır. Bu davranışı, ESP32'nin WiFi/HTTP sunucusunu **sürekli açık** tutacak, sadece gerektiğinde (yeni medya geldiğinde) günceleyecek şekilde değiştir. Pil/güç tüketimi bir öncelik değil (anahtarlık sürekli USB güç kaynağına bağlı kabul edilebilir, aksini belirtmedim ama varsayım olarak böyle davran).

## Android uygulamasının yapması gerekenler

### 1. Sistem geneli "şu an çalan medya" izleme (herhangi bir uygulamadan)

- `NotificationListenerService` iznini kullanarak (kullanıcıdan `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` ile bildirim erişimi izni istenecek), `MediaSessionManager.getActiveSessions(ComponentName)` üzerinden aktif medya oturumlarını al.
- Her `MediaController` için `MediaController.Callback.onMetadataChanged()` dinleyerek şarkı değiştiğinde tetiklenen bir akış kur.
- `MediaMetadata.METADATA_KEY_ALBUM_ART` (Bitmap) veya yoksa `METADATA_KEY_ALBUM_ART_URI` üzerinden albüm kapağını çek. İkisi de yoksa (bazı uygulamalar sağlamayabilir) uygulama içinde bir "kapak bulunamadı" placeholder göster, ESP32'ye hiçbir şey gönderme.
- Bu mekanizma **herhangi bir uygulamadan** (Spotify, YouTube Music, Meld, vs.) çalışmalı — belirli bir paket adına bağımlı olma. Birden fazla uygulama aynı anda medya oturumu açık tutuyorsa (nadir ama olabilir), en son `onPlaybackStateChanged` ile `STATE_PLAYING` durumuna geçen oturumu öncelikli say.

### 2. Debounce / gecikme mantığı

- Şarkı değişimlerinde ESP32'ye anında değil, **kısa bir debounce (öneri: 800ms–1.5sn)** ile gönder — hızlı şarkı atlamalarında (kullanıcı "sonraki" tuşuna art arda basarsa) gereksiz/yarım kalan gönderimler olmasın. Yeni bir değişiklik geldiğinde önceki bekleyen gönderimi iptal et (debounce, throttle değil — yani sadece en sonuncusu gönderilsin).

### 3. Mod seçimi: "Otomatik Kapak" / "Manuel Medya"

- Basit bir Compose ana ekranı: üstte iki modlu bir seçici (segmented button ya da tab) — **"Otomatik Şarkı Kapağı"** ve **"Manuel Medya"**.
- **Otomatik Şarkı Kapağı** modundayken yukarıdaki (1) ve (2) mantığı aktif, her şarkı değişiminde kapak otomatik ESP32'ye gider.
- **Manuel Medya** modundayken otomatik gönderim durur (şarkı değişse bile ESP32'ye bir şey gitmez), kullanıcı elle bir medya seçip gönderebilir.
- Mod, uygulama kapatılıp açılsa da hatırlanmalı (basit bir `DataStore` ya da `SharedPreferences` yeterli, Meld'deki gibi ağır bir yapı kurma, bu ayrı ve küçük bir proje).

### 4. Manuel medya gönderimi + önizleme ekranı

- Kullanıcı `ActivityResultContracts.GetContent()` ile galeriden resim/gif/video seçebilsin.
- Seçilen medya için **görsel bir önizleme ekranı** göster: medyanın 128×128 ESP32 ekranına nasıl sığacağını (letterbox/crop, en-boy oranı korunarak ortalanmış halde) gösteren bir Compose önizleme kutusu. Kullanıcı göndermeden önce bunu görebilmeli.
- Önizleme ekranında en azından şunlar olsun: medyanın 128×128'e nasıl kırpılacağı/sığacağı görseli, dosya boyutu tahmini (özellikle video için, ESP32'nin LittleFS depolama sınırını aşmaması için), ve bir "Gönder" onay butonu.

### 5. Otomatik codec/format dönüştürme (kritik kısım)

Kullanıcı ham/büyük bir medya seçtiğinde (örn. telefonun kamerasıyla çekilmiş 4K bir video, ya da yüksek çözünürlüklü bir GIF), uygulama bunu **göndermeden önce ESP32'nin işleyebileceği formata otomatik dönüştürmeli**:

- **Statik resim (JPEG/PNG vb.)** → 128×128'e resize + merkeze kırp (aspect ratio korunarak), JPEG olarak encode et (Android'in `Bitmap.compress(Bitmap.CompressFormat.JPEG, quality, ...)` yeterli, ek kütüphane gerekmez).
- **GIF** → Bunun için Android'in yerleşik `ImageDecoder`/`Movie` API'leri animasyonlu GIF'i frame'lere ayırmakta zayıf kalabilir; bunun yerine `Glide` ya da benzeri bir kütüphaneyle GIF'i frame'lere ayır, her frame'i 128×128'e resize et, ESP32'nin AnimatedGIF kütüphanesinin okuyabileceği bir GIF89a formatında yeniden encode et. Eğer bu iş Android tarafında pratik değilse (GIF encode için iyi bir yerleşik API yok), alternatif olarak GIF'i **MJPEG'e dönüştürüp ESP32'ye MJPEG olarak göndermeyi** değerlendir — ESP32 kodu zaten MJPEG oynatabiliyor, tek format hattı üzerinden gitmek işleri basitleştirebilir. Bu kararı sen ver, hangisi daha az bağımlılıkla daha sağlam çalışıyorsa.
- **Video (mp4 vb.)** → `MediaCodec`/`MediaExtractor` (Android'in yerleşik donanım hızlandırmalı video decode API'si) ile videoyu decode et, her frame'i 128×128'e resize et, JPEG'e encode et, ESP32'nin beklediği MJPEG formatına (ardışık `FFD8...FFD9` JPEG frame'leri) paketleyerek gönder. Hedef FPS'i ESP32 kodundaki `MJPEG_FRAME_MS` (şu an 50ms = 20 FPS) ile eşleştir; bu değeri de ESP32 kodunda parametrik/ayarlanabilir hale getirebilirsin ki uygulama tarafından "şu FPS'te gönderiyorum" bilgisini iletmek mümkün olsun (opsiyonel iyileştirme, zorunlu değil).
- Dönüştürme işlemi sırasında kullanıcıya bir ilerleme göstergesi (progress indicator) göster — özellikle video dönüştürme birkaç saniye sürebilir.
- Dönüştürülen medyanın LittleFS'e sığacağından emin olmak için (ESP32'nin flash/LittleFS boyutu sınırlı), dönüştürme sırasında bir üst boyut sınırı koy (örn. birkaç yüz KB — ESP32'nin gerçek boş alanını `/status` gibi bir endpoint'ten öğrenip ona göre kaliteyi otomatik ayarlamak ideal olur, ESP32 tarafına bunun için basit bir JSON status endpoint'i ekleyebilirsin).

### 6. ESP32 ile iletişim (network mimarisi)

- Telefon, ESP32'nin softAP ağına (WiFi) bağlanacak. Bu bağlantı sırasında **telefonun mobil internet erişimi kesilmemeli**.
- Bunu garanti etmek için `ConnectivityManager.bindProcessToNetwork()` kullanma — bunun yerine, **sadece ESP32'ye giden HTTP isteklerini** WiFi ağı üzerinden yönlendirecek şekilde `ConnectivityManager.requestNetwork()` ile spesifik olarak WiFi `Network` nesnesini alıp, o `Network.openConnection()` / `Network.bindSocket()` üzerinden ESP32'ye giden soketi bağla. Uygulamanın geri kalan trafiğine (varsa) dokunma, sadece ESP32 iletişimi için kullanılan `OkHttpClient`/bağlantıyı bu spesifik `Network`'e bağla.
- ESP32'nin AP'sine bağlanma/bağlantıyı doğrulama işini `WifiNetworkSpecifier` (Android 10+ için önerilen, kullanıcıdan SSID/şifre manuel girmesini istemeden programatik WiFi bağlantısı) ile yap; SSID (`ESP32-TFT`) ve şifre (`12345678`) ESP32 kodundaki sabit değerlerle eşleşmeli — bunları uygulama tarafında da bir ayarlar ekranından değiştirilebilir yap (ESP32 kodunu değiştirirsen SSID/şifre farklı olabilir, hardcode etme, kullanıcı ayarlardan girebilsin).
- ESP32 tarafında (kod düzenlemesi): mevcut `/upload` endpoint'i büyük ölçüde korunabilir, ama artık **sürekli açık** bir sunucu olduğu için:
  - Yeni bir `GET /status` endpoint'i ekle → JSON döndürsün: `{"freeSpace": <bytes>, "currentMedia": "gif"|"jpeg"|"mjpeg"|"none", "uptime": <ms>}`. Bu, Android tarafının gönderim öncesi boş alanı kontrol etmesini sağlar.
  - Mevcut `/upload` davranışını koru (multipart form-data upload, uzantıya göre otomatik format algılama) ama **upload sonrası WiFi'yi kapatma satırlarını kaldır** (`shutdownRequested` mantığını devre dışı bırak ya da tamamen çıkar).
  - HTML upload formu (`handleRoot()`) tarayıcıdan manuel test için kalabilir, dokunmana gerek yok, sadece opsiyonel referans olarak dursun.

### 7. Genel prensipler

- Bu proje Meld ile **hiçbir kod paylaşmıyor**, sıfırdan bağımsız bir Android projesi. Paket adını `com.<kullanıcı>.tftcompanion` gibi bağımsız bir isim yap.
- Jetpack Compose + Material3 kullan, modern Android geliştirme pratiklerine (ViewModel, StateFlow, Hilt opsiyonel — küçük proje olduğu için Hilt şart değil, basit tutulabilir) uy.
- `NotificationListenerService` izni olmadan uygulama çalışamayacağı için, ilk açılışta kullanıcıyı net bir şekilde bilgilendiren bir onboarding/izin ekranı olsun ("Bu uygulamanın çalışması için Bildirim Erişimi izni gerekiyor çünkü..." gibi bir açıklamayla birlikte `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`'e yönlendiren bir buton).
- Hata durumlarını (ESP32'ye bağlanılamıyor, WiFi ağı bulunamıyor, upload başarısız oldu vb.) kullanıcıya sessizce değil, açık bir şekilde (Snackbar/Toast) göster — bu Meld'deki gibi büyük bir kullanıcı kitlesine değil sana özel bir araç olduğu için, hata ayıklamayı kolaylaştıracak şekilde log'lar da (Logcat, `Log.d`) bol tutulabilir.
- ESP32 kodundaki değişiklikleri yaparken orijinal kodun genel yapısını (fonksiyon isimleri, callback yapısı) olabildiğince koru, tamamen yeniden yazma — sadece "sürekli açık sunucu" ve "/status endpoint'i" gibi gerekli değişiklikleri ekle.

## Teslim edilecekler

1. Android uygulamasının tam kaynak kodu (Android Studio projesi olarak açılabilir yapıda).
2. Güncellenmiş ESP32 (`.ino`) kodu.
3. Kısa bir README: uygulamanın nasıl kurulacağı, ESP32'ye nasıl flashlanacağı (mevcut kütüphane bağımlılıkları aynı kalıyor: TFT_eSPI, AnimatedGIF, JPEGDecoder, LittleFS), ve ilk kurulumda hangi izinlerin verilmesi gerektiği.

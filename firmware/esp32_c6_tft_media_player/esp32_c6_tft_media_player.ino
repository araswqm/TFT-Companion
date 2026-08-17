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
 *
 * NOT: Web sunucusu sürekli açık tutulur (upload sonrası WiFi kapatılmaz).
 *      Android eşlik uygulaması otomatik kapak gönderimi için sunucunun
 *      her an ulaşılabilir olmasını bekler. Güç tüketimi öncelik değildir.
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
// Android uygulaması gönderim öncesi boş alanı / mevcut medyayı buradan öğrenir.
void handleStatus() {
  size_t freeBytes = LittleFS.totalBytes() - LittleFS.usedBytes();

  const char* mediaStr = "none";
  switch (currentMedia) {
    case MEDIA_JPEG:  mediaStr = "jpeg";  break;
    case MEDIA_GIF:   mediaStr = "gif";   break;
    case MEDIA_MJPEG: mediaStr = "mjpeg"; break;
    default:          mediaStr = "none";  break;
  }

  String json = "{";
  json += "\"freeSpace\":";
  json += (unsigned long)freeBytes;
  json += ",\"currentMedia\":\"";
  json += mediaStr;
  json += "\",\"uptime\":";
  json += (unsigned long)millis();
  json += "}";

  server.send(200, "application/json", json);
}

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
    .catch(()=>{document.getElementById('st').innerText='Baglanti hatasi.';});
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
        "Tamam! Oynatma basladi.");
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
  server.on("/status", HTTP_GET, handleStatus);
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
  // Sunucu sürekli açık — shutdown mantığı kaldırıldı (Android otomatik gönderim).
  server.handleClient();

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

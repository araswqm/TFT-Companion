package com.araswqm.tftcompanion.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.araswqm.tftcompanion.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.droidsonroids.gif.GifDrawable

/** İlerleme geri çağrısı: (tamamlanan, toplam) — toplam video/GIF'te -1 olabilir. */
typealias Progress = (done: Int, total: Int) -> Unit

/**
 * Her tür medyayı ESP32 128x128 ST7735 ekranına uygun formata dönüştürür.
 *
 *  - Görüntü (JPEG/PNG)      -> 128x128 center-crop, JPEG
 *  - GIF                    -> frame'ler ayrıştırılıp 128x128 JPEG -> MJPEG paketi
 *  - Video (mp4/webm vb.)   -> MediaCodec/MediaExtractor ile decode, 128x128,
 *                              20 FPS (MJPEG_FRAME_MS=50) JPEG -> MJPEG paketi
 *
 * MJPEG = ardışık FFD8..FFD9 JPEG frame'lerinin birleşimi. LittleFS boyut
 * sınırına uyulması için JPEG kalitesi otomatik düşürülür.
 */
class MediaPreparer(private val context: Context) {

    /** vinyl_overlay.png 128x128'e kırpılmış halde bir kez yüklenir (önbellekli). */
    private val vinylOverlay: Bitmap? by lazy {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.vinyl_overlay)
        }.getOrNull()?.let { centerCrop(it, SCREEN_SIZE) }
    }

    companion object {
        private const val TAG = "MediaPreparer"
        const val SCREEN_SIZE = 128
        private const val MAX_FRAMES = 120      // 20 FPS => ~6 saniyelik döngü
        private const val JPEG_MIN_QUALITY = 25
        private const val JPEG_START_QUALITY = 90

        // Otomatik mod plak animasyonu: kapak sabit vinyl_overlay'in arkasında
        // saat yönünün tersine (negatif açı) döner. Kare sayısı LittleFS boyutuna
        // uyması için azaltılabilir; dönüş hızı sabit kalsın diye tur süresi
        // korunur (SPIN_REVOLUTION_MS / frameCount => kare başına süre).
        private const val SPIN_FRAME_COUNT = 24        // başlangıç: 24 kare
        private const val SPIN_MIN_FRAME_COUNT = 6     // en az 6 kare (görsel kalite)
        private const val SPIN_REVOLUTION_MS = 2400    // bir tam tur = 2.4 s
        // AnimatedGIF'ın GIFParseInfo'su dosyayı 255 baytlık ilk okuma penceresiyle
        // işler; yerel renk tablosu (LCT) bu pencereye sığmalı. LCT = 3 * renk sayısı
        // bayt + koddan sonra ucCodeStart pencerenin içinde kalmalı => en fazla ~72
        // renk, güvenli sınır 64 (2^6). 64+ renkte LCT pencerenin dışına taşar,
        // palet/ucCodeStart çöp okunur ve GIF "sadece üst kısım + glitchli" çözülür.
        private const val SPIN_START_COLORS = 64
        private const val SPIN_MIN_COLORS = 64         // palet bu kadar küçülürse dur
    }

    data class PreparedFile(
        val bytes: ByteArray,
        val fileName: String,        // ".jpg" | ".mjpg" | ".gif"
        val frameMs: Int = 0,        // MJPEG için ESP32 frame aralığı (50ms)
    ) {
        val sizeKb: Int get() = bytes.size / 1024
    }

    /**
     * URI'deki medyayı türüne göre hazırlar.
     * @param maxBytes ESP32 LittleFS boş alanı (kalite buna göre ayarlanır)
     */
    suspend fun prepare(
        uri: Uri,
        contentType: String?,
        maxBytes: Long,
        onProgress: Progress,
    ): PreparedFile = withContext(Dispatchers.Default) {
        val type = contentType?.lowercase()?.substringBefore(';') ?: ""
        Log.d(TAG, "prepare() contentType=$type maxBytes=$maxBytes")

        when {
            type == "image/gif" -> prepareGif(uri, maxBytes)
            type == "image/jpeg" || type == "image/png" || type == "image/webp"
                || type.startsWith("image/") -> prepareImage(uri, maxBytes)

            type == "video/x-mjpeg" || type.endsWith(".mjpeg") || type == "video/x-mjpeg"
                || type.startsWith("video/") -> prepareVideo(uri, maxBytes, onProgress)

            else -> {
                // Uzantıdan tahmin et
                val name = uri.lastPathSegment?.lowercase().orEmpty()
                when {
                    name.endsWith(".gif") -> prepareGif(uri, maxBytes)
                    name.endsWith(".mjpeg") || name.endsWith(".mjpg") ->
                        prepareVideo(uri, maxBytes, onProgress)
                    else -> prepareImage(uri, maxBytes)
                }
            }
        }
    }

    /** Hazırlanan içeriğin ilk karesini 128x128 önizleme olarak döndürür. */
    suspend fun previewFrame(uri: Uri, contentType: String?): Bitmap? = withContext(Dispatchers.Default) {
        val type = contentType?.lowercase()?.substringBefore(';') ?: ""
        try {
            when {
                type == "image/gif" || uri.lastPathSegment?.lowercase()?.endsWith(".gif") == true ->
                    decodeGifFirstFrame(uri)

                type.startsWith("video/") || uri.lastPathSegment?.lowercase()?.endsWith(".mjpeg") == true
                    || uri.lastPathSegment?.lowercase()?.endsWith(".mjpg") == true ->
                    decodeVideoFirstFrame(uri)

                else -> decodeSampled(uri, SCREEN_SIZE * 2)?.let { centerCrop(it, SCREEN_SIZE) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Önizleme oluşturulamadı: ${e.message}")
            null
        }
    }

    /**
     * Otomatik mod: albüm kapağını vinyl_overlay arkasında dönen animasyonlu
     * GIF'e çevirir (plak etkisi). Overlay yüklenemezse eski davranışa (tek
     * kare JPEG) düşer.
     */
    suspend fun prepareBitmap(src: Bitmap, maxBytes: Long): PreparedFile = withContext(Dispatchers.Default) {
        val overlay = vinylOverlay
        if (overlay == null) {
            Log.w(TAG, "vinyl_overlay çözülemedi, tek kare JPEG'e düşülüyor")
            val square = centerCrop(src, SCREEN_SIZE)
            val quality = fitQuality(square, maxBytes)
            val jpeg = encodeJpeg(square, quality)
            Log.d(TAG, "Albüm kapağı -> JPEG kalite=$quality ${jpeg.size} bayt")
            return@withContext PreparedFile(jpeg, "cover.jpg")
        }
        prepareSpinGif(src, overlay, maxBytes)
    }

    // ---------------------------------------------------- Plak animasyonu

    /**
     * Albüm kapağını overlay arkasında CCW döndürüp GIF'e paketler. Boyut
     * LittleFS sınırını aşarsa kare sayısı (24 -> 12 -> 6) küçültülerek yeniden
     * dener. Palet sabittir: AnimatedGIF'ın 255-bayt okuma penceresi 64 rengin
     * üzerinde LCT'i okuyamaz (yukarıdaki SPIN_START_COLORS yorumuna bakın).
     */
    private fun prepareSpinGif(src: Bitmap, overlay: Bitmap, maxBytes: Long): PreparedFile {
        val art = centerCrop(src, SCREEN_SIZE)
        val frameCounts = intArrayOf(
            SPIN_FRAME_COUNT,
            (SPIN_FRAME_COUNT / 2).coerceAtLeast(SPIN_MIN_FRAME_COUNT),
            SPIN_MIN_FRAME_COUNT,
        )
        val colorSets = intArrayOf(SPIN_START_COLORS)

        var best: ByteArray? = null
        for (fc in frameCounts) {
            val delayCs = (SPIN_REVOLUTION_MS / fc / 10).coerceAtLeast(1)
            val frames = renderSpinFrames(art, overlay, fc)
            for (colors in colorSets) {
                val gif = GifEncoder.encode(frames, SCREEN_SIZE, SCREEN_SIZE, delayCs, colors)
                if (gif.size <= maxBytes) {
                    Log.d(TAG, "Plak GIF hazır: ${fc} kare, ${colors} renk, ${gif.size} bayt")
                    return PreparedFile(gif, "cover.gif")
                }
                best = gif
            }
        }
        val smallest = best ?: throw IllegalStateException("GIF üretilemedi")
        Log.w(TAG, "Plak GIF ${smallest.size} bayt ile sınıra sığmıyor; en küçük aday gönderiliyor")
        return PreparedFile(smallest, "cover.gif")
    }

    /**
     * Kareleri üretir: kapak her karede negatif açıyla (CCW) döndürülür, üstüne
     * sabit overlay SRC_OVER ile bindirilir. Negatif açı = Android'in y-aşağı
     * canvas'ında saat yönünün tersi dönüş.
     */
    private fun renderSpinFrames(art: Bitmap, overlay: Bitmap, frameCount: Int): List<IntArray> {
        val frames = ArrayList<IntArray>(frameCount)
        val paint = Paint().apply { isFilterBitmap = true }
        val stepDeg = 360f / frameCount
        val cx = SCREEN_SIZE / 2f
        val cy = SCREEN_SIZE / 2f
        val px = IntArray(SCREEN_SIZE * SCREEN_SIZE)

        for (i in 0 until frameCount) {
            val frame = Bitmap.createBitmap(SCREEN_SIZE, SCREEN_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.rotate(-i * stepDeg, cx, cy)   // kapak CCW döner
            canvas.drawBitmap(art, 0f, 0f, paint)
            canvas.rotate(i * stepDeg, cx, cy)    // dönüşü geri al: overlay sabit
            canvas.drawBitmap(overlay, 0f, 0f, null)
            frame.getPixels(px, 0, SCREEN_SIZE, 0, 0, SCREEN_SIZE, SCREEN_SIZE)
            frame.recycle()
            frames.add(px.copyOf())
        }
        return frames
    }

    // ---------------------------------------------------------------- Görüntü

    private fun prepareImage(uri: Uri, maxBytes: Long): PreparedFile {
        val src = decodeSampled(uri, 2048) ?: throw IllegalStateException("Görüntü çözülemedi")
        val square = centerCrop(src, SCREEN_SIZE)
        val quality = fitQuality(square, maxBytes)
        val jpeg = encodeJpeg(square, quality)
        return PreparedFile(jpeg, "cover.jpg")
    }

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    // ---------------------------------------------------------------- GIF

    private fun prepareGif(uri: Uri, maxBytes: Long): PreparedFile {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("GIF okunamadı")
        // Kullanıcı GIF seçtiyse GIF olarak KALSIN — MJPEG'e çevirme. ESP32 zaten
        // AnimatedGIF ile GIF oynatıyor (startGif). Boyut LittleFS'e sığıyorsa
        // orijinal GIF olduğu gibi gönderilir; sığmıyorsa MJPEG'e düşülür.
        if (bytes.size <= maxBytes) {
            Log.d(TAG, "GIF passthrough: ${bytes.size} bayt (sınır $maxBytes)")
            return PreparedFile(bytes, "cover.gif")
        }
        val gif = GifDrawable(bytes)
        return try {
            val frames = renderGifFrames(gif)
            Log.d(TAG, "GIF ${gif.numberOfFrames} kare -> MJPEG ${frames.size} kare")
            buildMjpeg(frames, maxBytes)
        } finally {
            gif.recycle()
        }
    }

    private fun renderGifFrames(gif: GifDrawable): List<Bitmap> {
        // start() çağrılmaz: animasyon döngüsü seekToFrame ile yarışmasın diye
        // kareler manuel (seekToFrame + draw) çizilir.
        val w = gif.intrinsicWidth
        val h = gif.intrinsicHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("GIF boyutu geçersiz")
        val canvas = Canvas()
        val frames = mutableListOf<Bitmap>()
        var rendered = 0

        for (i in 0 until gif.numberOfFrames) {
            if (rendered >= MAX_FRAMES) break
            gif.seekToFrame(i)
            // Kare süresine göre kopya sayısı (ESP32 sabit 50ms/FPS kare hızında oynatır)
            val durationMs = max(1, gif.getFrameDuration(i)) // int ms
            val copies = min(MAX_FRAMES - rendered, ((durationMs + 49) / 50).coerceAtLeast(1))
            repeat(copies) {
                val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                canvas.setBitmap(frame)
                canvas.drawColor(Color.WHITE) // şeffaf GIF kareleri için zemin
                // GifDrawable bir Drawable'dır; Canvas'a draw() ile çizilir (Bitmap değil).
                gif.setBounds(0, 0, w, h)
                gif.draw(canvas)
                frames.add(centerCrop(frame, SCREEN_SIZE))
                if (frame.width != SCREEN_SIZE) frame.recycle()
                rendered++
                if (rendered >= MAX_FRAMES) return@repeat
            }
        }
        return frames
    }

    private fun decodeGifFirstFrame(uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // BitmapFactory GIF'in ilk karesini verir (önceki kareleri saymaz)
            decodeSampledStream(stream, SCREEN_SIZE * 2)?.let { centerCrop(it, SCREEN_SIZE) }
        }

    // ---------------------------------------------------------------- Video

    private fun prepareVideo(uri: Uri, maxBytes: Long, onProgress: Progress): PreparedFile {
        val frames = mutableListOf<Bitmap>()
        val extractor = MediaExtractor()
        val codec = createVideoDecoder(uri, extractor) ?: throw IllegalStateException("Video çözülemedi")
        try {
            val format = extractor.getTrackFormat(0)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val info = MediaCodec.BufferInfo()

            codec.start()
            var inputDone = false
            var outputDone = false
            var frameCount = 0
            val timeout = 20_000L

            // İlk eşitleme karesine atla (garbage frame üretmesin)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!outputDone && frameCount < MAX_FRAMES) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(timeout)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeout)
                when {
                    outIndex >= 0 -> {
                        val image: Image? = codec.getOutputImage(outIndex)
                        if (image != null) {
                            image.use {
                                if (it.planes.size >= 3 && info.size > 0) {
                                    val decoded = yuvToBitmapScaled(it, 256, rotation)
                                    frames.add(centerCrop(decoded, SCREEN_SIZE))
                                    frameCount++
                                    onProgress(frameCount, -1)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && codec.dequeueOutputBuffer(info, 0) == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // çıkış kalmadı
                            outputDone = true
                        }
                    }
                    else -> Unit
                }
            }
            codec.stop()
        } finally {
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (frames.isEmpty()) throw IllegalStateException("Video frame'i üretilemedi")
        Log.d(TAG, "Video -> MJPEG ${frames.size} kare")
        return buildMjpeg(frames, maxBytes)
    }

    private fun createVideoDecoder(uri: Uri, extractor: MediaExtractor): MediaCodec? {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            extractor.setDataSource(fd.fileDescriptor)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    return MediaCodec.createDecoderByType(mime).apply {
                        configure(format, null, null, 0)
                    }
                }
            }
        }
        return null
    }

    private fun decodeVideoFirstFrame(uri: Uri): Bitmap? {
        val extractor = MediaExtractor()
        val codec = createVideoDecoder(uri, extractor) ?: return null
        return try {
            val format = extractor.getTrackFormat(0)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val info = MediaCodec.BufferInfo()
            codec.start()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var frame: Bitmap? = null
            var inputDone = false
            var guard = 0
            while (frame == null && guard < 200) {
                guard++
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(20_000L)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 20_000L)
                if (outIndex >= 0) {
                    codec.getOutputImage(outIndex)?.use {
                        if (it.planes.size >= 3 && info.size > 0) {
                            frame = centerCrop(yuvToBitmapScaled(it, 256, rotation), SCREEN_SIZE)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }
            codec.stop()
            frame
        } catch (e: Exception) {
            Log.w(TAG, "Video önizleme hatası: ${e.message}")
            null
        } finally {
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    // --------------------------------------------------------- MJPEG paketleme

    private fun buildMjpeg(frames: List<Bitmap>, maxBytes: Long): PreparedFile {
        var quality = JPEG_START_QUALITY
        var out = packFrames(frames, quality)
        while (out.size > maxBytes && quality > JPEG_MIN_QUALITY) {
            quality -= 10
            out = packFrames(frames, quality)
        }
        Log.d(TAG, "MJPEG hazır: ${frames.size} kare, kalite=$quality, ${out.size} bayt")
        return PreparedFile(out, "clip.mjpg", frameMs = 50)
    }

    private fun packFrames(frames: List<Bitmap>, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in frames) out.write(encodeJpeg(f, quality))
        return out.toByteArray()
    }

    // -------------------------------------------------------------- Yardımcılar

    /** 128x128'e sığması için JPEG kalitesini boyut limitine göre ayarlar. */
    private fun fitQuality(bmp: Bitmap, maxBytes: Long): Int {
        var q = JPEG_START_QUALITY
        while (q > JPEG_MIN_QUALITY) {
            if (encodeJpeg(bmp, q).size <= maxBytes) return q
            q -= 10
        }
        return JPEG_MIN_QUALITY
    }

    private fun encodeJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Kareye center-crop: ölçekle -> ortadan kırp. Kaynak bitmap'e dokunulmaz. */
    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        if (src.width == size && src.height == size) return src
        val scale = max(size / src.width.toFloat(), size / src.height.toFloat())
        val scaledW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = (scaledW - size) / 2
        val y = (scaledH - size) / 2
        val crop = Bitmap.createBitmap(scaled, x, y, size, size)
        if (scaled != src) scaled.recycle()
        return crop
    }

    /** YUV_420_888 -> belirtilen boyuta örneklenmiş RGB bitmap. */
    private fun yuvToBitmapScaled(image: Image, targetSide: Int, rotation: Int): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val yPix = yPlane.pixelStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride

        val crop = image.cropRect
        val sw = crop.width()
        val sh = crop.height()
        val scaledW = (targetSide * sw / max(sw, sh)).coerceAtLeast(1)
        val scaledH = (targetSide * sh / max(sw, sh)).coerceAtLeast(1)

        // Rotasyon sonrası boyutlar
        val rotatedW = if (rotation == 90 || rotation == 270) scaledH else scaledW
        val rotatedH = if (rotation == 90 || rotation == 270) scaledW else scaledH
        val bmp = Bitmap.createBitmap(rotatedW, rotatedH, Bitmap.Config.ARGB_8888)
        val px = IntArray(rotatedW * rotatedH)

        // Sanal kaynak koordinatı (cropRect'e göre offset'li)
        val baseX = crop.left
        val baseY = crop.top

        for (ry in 0 until rotatedH) {
            for (rx in 0 until rotatedW) {
                // önce (rx,ry) -> rotasyonsuz (sx,sy) -> sonra kaynak piksel
                val (ux, uy) = unrotate(rx, ry, rotation, scaledW, scaledH)
                val srcX = (ux * sw / scaledW + baseX).coerceIn(0, sw + baseX - 1)
                val srcY = (uy * sh / scaledH + baseY).coerceIn(0, sh + baseY - 1)

                val y = yBuf.get(srcY * yRow + srcX * yPix).toInt() and 0xFF
                val u = uBuf.get((srcY / 2) * uRow + (srcX / 2) * uPix).toInt() and 0xFF
                val v = vBuf.get((srcY / 2) * vRow + (srcX / 2) * vPix).toInt() and 0xFF
                px[ry * rotatedW + rx] = yuvToRgb(y, u, v)
            }
        }
        bmp.setPixels(px, 0, rotatedW, 0, 0, rotatedW, rotatedH)
        return bmp
    }

    private fun unrotate(x: Int, y: Int, rot: Int, w: Int, h: Int): Pair<Int, Int> = when (rot % 360) {
        // (tx,ty) hedef -> (sx,sy) kaynak dönüşümü
        90 -> Pair(y, w - 1 - x)          // 90° saat yönü
        180 -> Pair(w - 1 - x, h - 1 - y)
        270 -> Pair(h - 1 - y, x)         // 270° saat yönü
        else -> Pair(x, y)
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        var r = (298 * c + 409 * e + 128) shr 8
        var g = (298 * c - 100 * d - 208 * e + 128) shr 8
        var b = (298 * c + 516 * d + 128) shr 8
        r = r.coerceIn(0, 255)
        g = g.coerceIn(0, 255)
        b = b.coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun decodeSampledStream(stream: InputStream, maxSide: Int): Bitmap? {
        val buf = stream.readBytes()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(buf, 0, buf.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(buf, 0, buf.size, o)
    }
}

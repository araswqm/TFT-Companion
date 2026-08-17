package com.araswqm.tftcompanion.convert

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Kendi içinde GIF89a kodlayıcı — bağımlılık yok.
 *
 * Neden bağımlılık yok? AWT tabanlı kodlayıcılar (Square gifencoder,
 * animated-gif-lib) Android'de çalışmaz; NDK tabanlı olanlar (android-ndk-gif)
 * JNI + ABI yükü getirir. Proje zaten piksel işlemini elle yapıyor
 * ([MediaPreparer.yuvToBitmapScaled] gibi), bu yüzden LZW + medyan-kesim
 * (median-cut) paletleme buraya uygun.
 *
 * Üretilen GIF: her kare tam kare (128x128) opak görüntü + yerel renk tablosu,
 * her kare için GCE süresi, sonda döngü (trailer). Firmware'deki AnimatedGIF
 * (bitbank2) ile uyumludur: kareler tam kare ve opak olduğu için disposal
 * işlemi sonuca etki etmez.
 */
object GifEncoder {

    private const val MIN_CODE_SIZE = 8        // 256 renge kadar
    private const val CLEAR_CODE = 1 shl MIN_CODE_SIZE      // 256
    private const val EOI_CODE = CLEAR_CODE + 1             // 257
    private const val MAX_TABLE = 4096                      // 2^12 (LZW sınırı)

    /**
     * Kareleri animasyonlu GIF olarak paketler.
     *
     * @param frames ARGB piksel dizileri (her biri width*height uzunluğunda, opak)
     * @param delayCentis kare başına süre (1/100 sn; ESP32 AnimatedGIF bunu ms'e çevirir)
     * @param maxColors kare başına renk tablosu üst sınırı (8..256)
     */
    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        delayCentis: Int,
        maxColors: Int = 256,
    ): ByteArray {
        require(frames.isNotEmpty()) { "En az bir kare gerekli" }
        require(maxColors in 8..256) { "maxColors 8..256 olmalı" }
        val out = ByteArrayOutputStream()

        // --- Header ---
        out.write('G'.code); out.write('I'.code); out.write('F'.code)
        out.write('8'.code); out.write('9'.code); out.write('a'.code)

        // --- Logical Screen Descriptor ---
        // GCT bayrağı 1 ama 2 girişli (siyah/beyaz): tüm kareler yerel palete
        // sahip olduğundan bu yalnızca güvenlik yedeğidir.
        writeU16(out, width)
        writeU16(out, height)
        out.write(0xF0)                       // GCT var, 8 bit renk çözünürlüğü, 2 giriş
        out.write(0)                          // arka plan rengi (kullanılmıyor)
        out.write(0)                          // piksel en-boy oranı (0 = belirsiz)
        out.write(0x00); out.write(0x00); out.write(0x00)   // siyah, beyaz
        out.write(0xFF); out.write(0xFF); out.write(0xFF)

        val frameSize = width * height
        for (frame in frames) {
            require(frame.size == frameSize) { "Kare boyutu width*height olmalı" }

            // --- Graphic Control Extension: süre + disposal=1 (yerinde bırak) ---
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0x04)                   // disposal=1, saydamlık yok
            writeU16(out, delayCentis)
            out.write(0)                      // saydam renk indeksi (yok)
            out.write(0)                      // blok sonu

            // --- Medyan-kesim paletleme ---
            val palette = medianCut(frame, maxColors)
            val indices = mapToPalette(frame, palette)

            // --- Image Descriptor: tam kare, yerel renk tablosu ---
            out.write(0x2C)
            writeU16(out, 0); writeU16(out, 0)
            writeU16(out, width); writeU16(out, height)
            val bits = highestBit(palette.size - 1)     // LCT boyutu = 2^(bits+1)
            out.write(0x80 or bits)

            // --- Local Color Table (2^(bits+1) giriş, kalanlar siyahla doldurulur) ---
            val tableSize = 1 shl (bits + 1)
            for (i in 0 until tableSize) {
                val c = if (i < palette.size) palette[i] else 0
                out.write((c shr 16) and 0xFF)
                out.write((c shr 8) and 0xFF)
                out.write(c and 0xFF)
            }

            // --- Görüntü verisi: minimum kod boyutu + LZW alt blokları ---
            out.write(MIN_CODE_SIZE)
            out.write(lzwEncode(indices))
        }

        out.write(0x3B)                       // trailer (GIF sonu, döngü buradan döner)
        return out.toByteArray()
    }

    // ------------------------------------------------------- LZW sıkıştırma

    /**
     * Renk indekslerini GIF LZW kodlarına çevirir ve çıktıyı alt bloklara böler.
     *
     * Kod genişliği "ertelenmiş değişim" kuralıyla büyür: bir sonraki boş kod
     * 2^codeSize'a ulaştığında genişlik artar. Bu, giflib/AnimatedGIF gibi
     * referans çözücülerle birebir aynı anda gerçekleşir; aksi halde GIF bozulur.
     * Sözlük 4096'ya (2^12) dolduğunda CLEAR kodlanıp sözlük sıfırlanır.
     */
    private fun lzwEncode(indexed: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dict = HashMap<Int, Int>(MAX_TABLE)
        var codeSize = MIN_CODE_SIZE + 1      // 9
        var nextCode = CLEAR_CODE + 2         // 258 (ilk veri kodu)
        var bitBuf = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)   // LSB-ilk bit paketleme
            bitCount += codeSize
            while (bitCount >= 8) {
                out.write(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        emit(CLEAR_CODE)
        var curr = indexed[0]
        for (i in 1 until indexed.size) {
            val prev = curr
            val ch = indexed[i]
            val key = (prev shl 8) or ch        // önek (kod) + karakter, çakışmasız
            val mapped = dict[key]
            if (mapped != null) {
                curr = mapped
            } else {
                emit(curr)
                if (nextCode < MAX_TABLE) {
                    dict[key] = nextCode++
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(CLEAR_CODE)
                    dict.clear()
                    nextCode = CLEAR_CODE + 2
                    codeSize = MIN_CODE_SIZE + 1
                }
                curr = ch
            }
        }
        emit(curr)
        emit(EOI_CODE)
        if (bitCount > 0) out.write(bitBuf and 0xFF)

        // Alt bloklara böl (maks 255 bayt/blok + blok sonu 0x00)
        val data = out.toByteArray()
        val blocks = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            val len = min(255, data.size - pos)
            blocks.write(len)
            blocks.write(data, pos, len)
            pos += len
        }
        blocks.write(0)
        return blocks.toByteArray()
    }

    // ----------------------------------------------------- Medyan-kesim palet

    /** RGB'yi [target] renge kadar azaltan palet döndürür. Alfa yok sayılır (opak kareler). */
    private fun medianCut(argb: IntArray, target: Int): IntArray {
        // Medyan kesim için örnekleme (her 4. piksel): 16k piksel için yeterli
        val sampled = IntArray((argb.size + 3) / 4)
        var n = 0
        var i = 0
        while (i < argb.size) {
            sampled[n++] = argb[i] and 0xFFFFFF
            i += 4
        }
        val unique = sampled.toHashSet().toIntArray()
        if (unique.size <= target) return unique

        var boxes = mutableListOf(ColorBox(sampled))
        while (boxes.size < target) {
            // En geniş kanal aralığına sahip, bölünebilir kutuyu seç
            var best: ColorBox? = null
            var bestRange = 0
            for (b in boxes) {
                val rng = b.maxRange()
                if (rng > bestRange) { bestRange = rng; best = b }
            }
            val b = best ?: break             // bölünecek kutu kalmadı
            val split = b.split()
            val idx = boxes.indexOf(b)
            boxes[idx] = split.first
            boxes.add(split.second)
        }
        return IntArray(boxes.size) { boxes[it].average() }
    }

    /** Pikselleri palete en yakın renk indeksine eşler (Öklid mesafesi). */
    private fun mapToPalette(argb: IntArray, palette: IntArray): IntArray {
        val out = IntArray(argb.size)
        val p = palette.size
        for (i in argb.indices) {
            val r = (argb[i] shr 16) and 0xFF
            val g = (argb[i] shr 8) and 0xFF
            val b = argb[i] and 0xFF
            var best = 0
            var bestD = Int.MAX_VALUE
            for (j in 0 until p) {
                val dr = r - ((palette[j] shr 16) and 0xFF)
                val dg = g - ((palette[j] shr 8) and 0xFF)
                val db = b - (palette[j] and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < bestD) { bestD = d; best = j }
            }
            out[i] = best
        }
        return out
    }

    /** Medyan-kesim kutusu: gerçek (yinelenen) renkleri tutar. */
    private class ColorBox(val pixels: IntArray) {
        fun maxRange(): Int {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0
            for (c in pixels) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
            return maxOf(rMax - rMin, gMax - gMin, bMax - bMin)
        }

        /** En geniş kanaldan medyan değere göre ikiye böler. */
        fun split(): Pair<ColorBox, ColorBox> {
            var rMin = 255; var rMax = 0
            var gMin = 255; var gMax = 0
            var bMin = 255; var bMax = 0
            for (c in pixels) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
            val chan = when {
                rMax - rMin >= gMax - gMin && rMax - rMin >= bMax - bMin -> 16
                gMax - gMin >= bMax - bMin -> 8
                else -> 0
            }
            val sorted = pixels.sortedWith(compareBy<Int> { (it shr chan) and 0xFF })
            val midVal = (sorted[sorted.size / 2] shr chan) and 0xFF
            var cut = 0
            while (cut < sorted.size && ((sorted[cut] shr chan) and 0xFF) <= midVal) cut++
            if (cut == 0) cut = 1
            if (cut == sorted.size) cut = sorted.size / 2   // tek renk kutu güvenliği
            val first = IntArray(cut) { sorted[it] }
            val second = IntArray(sorted.size - cut) { sorted[cut + it] }
            return ColorBox(first) to ColorBox(second)
        }

        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L
            for (c in pixels) {
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = pixels.size
            return (((r / n).toInt() and 0xFF) shl 16) or
                (((g / n).toInt() and 0xFF) shl 8) or
                ((b / n).toInt() and 0xFF)
        }
    }

    // ----------------------------------------------------------- Yardımcılar

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    /** n'den küçük en büyük 2^b - 1 eşiğini veren üs: highestBit(255)=7, highestBit(0)=0 */
    private fun highestBit(n: Int): Int {
        var v = n
        var b = 0
        while (v > 1) { v = v shr 1; b++ }
        return b
    }
}

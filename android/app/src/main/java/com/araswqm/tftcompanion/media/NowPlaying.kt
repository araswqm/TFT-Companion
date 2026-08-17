package com.araswqm.tftcompanion.media

import android.graphics.Bitmap

// Şu an çalan medyaya dair özet bilgi.
// Album art null olabilir - 128x128 yer tutucu döndürülür.
data class NowPlaying(
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val source: String,
) {
    val isUsable: Boolean get() = albumArt != null
}

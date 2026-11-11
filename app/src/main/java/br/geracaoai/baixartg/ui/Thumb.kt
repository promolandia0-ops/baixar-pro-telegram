package br.geracaoai.baixartg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.core.Net
import br.geracaoai.baixartg.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object ThumbCache {
    private val mem = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(mem) { mem.get(url) }?.asImageBitmap() ?: try {
            Net.http.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).await().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                synchronized(mem) { mem.put(url, b) }
                b.asImageBitmap()
            }
        } catch (_: Exception) { null }
    }
}

@Composable
fun Thumb(url: String?, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = if (url.isNullOrBlank()) null else ThumbCache.load(url) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp!!, "miniatura", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Movie, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

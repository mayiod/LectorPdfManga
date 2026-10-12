package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Miniatura = primera página del PDF / primera imagen del CBZ. Se guarda en memoria mientras la app vive. */
object Thumbnails {
    private const val W = 180
    private const val H = 252
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    private val cache = LruCache<String, ImageBitmap>(60)
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gate = Semaphore(3) // máximo 3 miniaturas generándose a la vez

    /** Devuelve la miniatura si ya está en caché (para mostrarla al instante al volver a la lista). */
    fun peek(uri: Uri): ImageBitmap? = cache.get(uri.toString())

    suspend fun load(context: Context, uri: Uri, name: String): ImageBitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        if (key in failed) return null

        val result = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (name.endsWith(".cbz", ignoreCase = true)) cbzCover(context, uri)
                    else pdfCover(context, uri)
                }.getOrNull()
            }
        }
        if (result != null) cache.put(key, result) else failed.add(key)
        return result
    }

    private fun pdfCover(context: Context, uri: Uri): ImageBitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            val renderer = PdfRenderer(it)
            try {
                if (renderer.pageCount == 0) return null
                val page = renderer.openPage(0)
                try {
                    val scale = minOf(W / page.width.toFloat(), H / page.height.toFloat())
                    val bw = (page.width * scale).toInt().coerceAtLeast(1)
                    val bh = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix().apply { setScale(scale, scale) }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp.asImageBitmap()
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    /**
     * Lee el ZIP en secuencia y toma la primera imagen en el orden en que está guardada en el archivo
     * (así no hay que copiar ni recorrer todo el CBZ). Casi siempre coincide con la portada.
     */
    private fun cbzCover(context: Context, uri: Uri): ImageBitmap? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: return null
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || e.name.startsWith("__MACOSX") || name.startsWith(".")) continue
                    if (name.substringAfterLast('.', "").lowercase() !in IMAGE_EXT) continue
                    return decodeScaled(zis.readBytes())
                }
            }
        }
        return null
    }

    private fun decodeScaled(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val s = minOf(W / bmp.width.toFloat(), H / bmp.height.toFloat())
        if (s >= 1f) return bmp.asImageBitmap()
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled.asImageBitmap()
    }
}

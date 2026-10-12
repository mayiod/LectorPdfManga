package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Un CBZ es un ZIP de imágenes. Se copia una vez al caché de la app (ZipFile necesita un archivo
 * real para acceso aleatorio) y se borra al cerrar. Cada página es una imagen del ZIP.
 */
class CbzBook private constructor(
    private val file: File,
    private val zip: ZipFile,
    private val entries: List<ZipEntry>
) : BookSource {

    override val pageCount: Int = entries.size

    override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap =
        withContext(Dispatchers.IO) {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            val entry = entries[index]

            // 1) leer solo el tamaño
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext out.asImageBitmap()

            // 2) decodificar reducida si la imagen es mucho mayor que la pantalla
            val fit = minOf(width.toFloat() / bounds.outWidth, height.toFloat() / bounds.outHeight)
            var sample = 1
            while (fit * sample * 2 <= 1f) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext out.asImageBitmap()

            // 3) ajustar (contain) y centrar sobre fondo blanco
            val s = minOf(width.toFloat() / src.width, height.toFloat() / src.height)
            val m = Matrix().apply {
                setScale(s, s)
                postTranslate((width - src.width * s) / 2f, (height - src.height * s) / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            out.asImageBitmap()
        }

    override fun close() {
        runCatching { zip.close() }
        runCatching { file.delete() }
    }

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

        suspend fun open(context: Context, uri: Uri): CbzBook = withContext(Dispatchers.IO) {
            // limpiar restos de lecturas anteriores
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("cbz_") }
                ?.forEach { it.delete() }

            val tmp = File(context.cacheDir, "cbz_${System.nanoTime()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("No se pudo abrir el archivo")

                val zip = ZipFile(tmp)
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { e ->
                        val name = e.name.substringAfterLast('/')
                        !e.name.startsWith("__MACOSX") &&
                            !name.startsWith(".") &&
                            name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
                    }
                    .sortedWith(Comparator { a, b -> naturalCompare(a.name, b.name) })
                    .toList()
                if (entries.isEmpty()) {
                    zip.close()
                    error("El archivo no contiene imágenes")
                }
                CbzBook(tmp, zip, entries)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }
    }
}

/** Orden natural: "pag2" va antes de "pag10". */
private fun naturalCompare(a: String, b: String): Int {
    val token = Regex("\\d+|\\D+")
    val pa = token.findAll(a.lowercase()).map { it.value }.toList()
    val pb = token.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]
        val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0')
            val ny = y.trimStart('0')
            if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return pa.size - pb.size
}

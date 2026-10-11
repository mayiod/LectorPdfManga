package com.example.lectorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Abre un PDF (content:// o file://) y renderiza páginas ajustadas (contain) a un tamaño dado. */
class PdfBook private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val pageCount: Int = renderer.pageCount

    // PdfRenderer no es thread-safe y solo permite una página abierta a la vez.
    private val mutex = Mutex()

    suspend fun render(index: Int, width: Int, height: Int): ImageBitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val page = renderer.openPage(index)
                try {
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val scale = minOf(width / page.width.toFloat(), height / page.height.toFloat())
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(
                            (width - page.width * scale) / 2f,
                            (height - page.height * scale) / 2f
                        )
                    }
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.asImageBitmap()
                } finally {
                    page.close()
                }
            }
        }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): PdfBook = withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("No se pudo abrir el archivo")
            try {
                PdfBook(pfd, PdfRenderer(pfd))
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    }
}

package com.example.lectorpdf

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap

/** Cualquier "libro" que se pueda leer: PDF, CBZ, etc. Para agregar otro formato, impleméntalo aquí. */
interface BookSource {
    val pageCount: Int
    suspend fun render(index: Int, width: Int, height: Int): ImageBitmap
    fun close()
}

/** Elige el lector según la extensión del archivo. */
suspend fun openBook(context: Context, uri: Uri, fileName: String): BookSource =
    if (fileName.endsWith(".cbz", ignoreCase = true)) CbzBook.open(context, uri)
    else PdfBook.open(context, uri)

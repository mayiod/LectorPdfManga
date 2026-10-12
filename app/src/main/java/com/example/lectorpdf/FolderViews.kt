package com.example.lectorpdf

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Botón "Ver" de la barra superior, con el menú de vistas (como el del explorador de Windows). */
@Composable
fun ViewMenu(current: FolderView, onSelect: (FolderView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Cambiar vista")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                FolderView.LIST to "Lista",
                FolderView.DETAILS to "Detalles",
                FolderView.GRID_LARGE to "Iconos grandes",
                FolderView.GRID_MEDIUM to "Iconos medianos",
                FolderView.GRID_SMALL to "Iconos pequeños"
            ).forEach { (view, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (view == current) Icon(Icons.Default.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onSelect(view)
                        open = false
                    }
                )
            }
        }
    }
}

/** Carpeta: icono. PDF/CBZ: miniatura de la primera página (con icono mientras carga o si falla). */
@Composable
fun EntryThumb(e: Entry, modifier: Modifier = Modifier, emojiSize: TextUnit = 26.sp) {
    val context = LocalContext.current
    val uri = e.file.uri
    Box(modifier, contentAlignment = Alignment.Center) {
        if (e.isDir) {
            Text("📁", fontSize = emojiSize)
        } else {
            val thumb by produceState<ImageBitmap?>(Thumbnails.peek(uri), uri) {
                value = Thumbnails.load(context, uri, e.name)
            }
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(if (e.name.endsWith(".cbz", ignoreCase = true)) "📚" else "📄", fontSize = emojiSize)
            }
        }
    }
}

/** Vista "Lista": miniatura pequeña + nombre. */
@Composable
fun ListRow(e: Entry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { EntryThumb(e, Modifier.size(width = 48.dp, height = 68.dp), 26.sp) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Vista "Detalles": miniatura más grande + nombre + tipo, tamaño y fecha de modificación. */
@Composable
fun DetailsRow(e: Entry, onClick: () -> Unit) {
    val context = LocalContext.current
    val info by produceState("", e.file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { describe(context, e) }.getOrDefault("") }
    }
    ListItem(
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (info.isNotEmpty()) Text(info, maxLines = 1) },
        leadingContent = { EntryThumb(e, Modifier.size(width = 64.dp, height = 90.dp), 34.sp) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun describe(context: Context, e: Entry): String {
    val modified = e.file.lastModified()
    val date = if (modified > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(modified)) else null
    val parts = if (e.isDir) {
        listOf("Carpeta", date)
    } else {
        val type = if (e.name.endsWith(".cbz", ignoreCase = true)) "CBZ" else "PDF"
        listOf(type, Formatter.formatShortFileSize(context, e.file.length()), date)
    }
    return parts.filterNotNull().joinToString(" · ")
}

/** Vistas de cuadrícula (iconos grandes / medianos / pequeños). */
@Composable
fun GridCell(e: Entry, view: FolderView, onClick: () -> Unit) {
    val emoji = when (view) {
        FolderView.GRID_LARGE -> 64.sp
        FolderView.GRID_MEDIUM -> 44.sp
        else -> 30.sp
    }
    val small = view == FolderView.GRID_SMALL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EntryThumb(e, Modifier.fillMaxWidth().aspectRatio(0.72f), emoji)
        Text(
            e.name,
            maxLines = if (small) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

package com.example.lectorpdf

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    uri: Uri,
    title: String,
    pageTurnMode: PageTurnMode,
    viewMode: ViewMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val double = viewMode == ViewMode.DOUBLE

    var book by remember { mutableStateOf<PdfBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Página "canónica" (índice 0-based). En modo libro la unidad es el pliego que la contiene.
    var page by rememberSaveable(uri.toString()) { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var renderedSize by remember { mutableStateOf(IntSize.Zero) }
    val images = remember { mutableStateMapOf<Int, ImageBitmap>() }

    // Mantener la pantalla encendida mientras se lee
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Abrir / cerrar el PDF
    LaunchedEffect(uri) {
        runCatching { PdfBook.open(context, uri) }
            .onSuccess { book = it }
            .onFailure { error = "No se pudo abrir este PDF (¿dañado o protegido con contraseña?)" }
    }
    DisposableEffect(uri) { onDispose { book?.close() } }

    // Renderizar las páginas necesarias; liberar las lejanas.
    // Una página: ancho completo. Libro: cada página a medio ancho.
    LaunchedEffect(book, size, page, double) {
        val b = book ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val target = IntSize(if (double) size.width / 2 else size.width, size.height)
        if (renderedSize != target) {
            images.clear()
            renderedSize = target
        }
        val u = (page + 1) / 2 // pliego actual (modo libro)
        val needed = if (double) (2 * u - 3)..(2 * u + 2) else (page - 1)..(page + 2)
        val keep = if (double) (2 * u - 4)..(2 * u + 3) else (page - 2)..(page + 2)
        val wanted = needed
            .filter { it in 0 until b.pageCount }
            .sortedBy { abs(it - page) }
        for (i in wanted) {
            if (i !in images) images[i] = b.render(i, target.width, target.height)
        }
        images.keys.filter { it !in keep }.forEach { images.remove(it) }
    }

    val err = error
    val b = book
    val unit = if (double) (page + 1) / 2 else page

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        when {
            err != null -> Text(
                err,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )

            b == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            else -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { size = it }
                ) {
                    PageTurnView(
                        pageCount = b.pageCount,
                        doublePage = double,
                        currentUnit = unit,
                        mode = pageTurnMode,
                        pageImage = { images[it] },
                        onUnitChange = { u -> page = if (double) maxOf(0, 2 * u - 1) else u },
                        onCenterTap = { showControls = !showControls }
                    )
                }

                if (showControls) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                        tonalElevation = 3.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                            }
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        tonalElevation = 3.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val label = if (double) {
                                listOfNotNull(
                                    (2 * unit - 1).takeIf { it >= 0 },
                                    (2 * unit).takeIf { it < b.pageCount }
                                ).joinToString("-") { (it + 1).toString() }
                            } else {
                                (page + 1).toString()
                            }
                            Text("$label / ${b.pageCount}")
                            if (b.pageCount > 1) {
                                Slider(
                                    value = page.toFloat(),
                                    onValueChange = { page = it.roundToInt() },
                                    valueRange = 0f..(b.pageCount - 1).toFloat()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

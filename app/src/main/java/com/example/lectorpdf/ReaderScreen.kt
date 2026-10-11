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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var book by remember { mutableStateOf<PdfBook?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by rememberSaveable(uri.toString()) { mutableIntStateOf(0) }
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

    // Renderizar la página actual y las vecinas; liberar las lejanas
    LaunchedEffect(book, size, current) {
        val b = book ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        if (renderedSize != size) {
            images.clear()
            renderedSize = size
        }
        val wanted = (current - 1..current + 2)
            .filter { it in 0 until b.pageCount }
            .sortedBy { abs(it - current) }
        for (i in wanted) {
            if (i !in images) images[i] = b.render(i, size.width, size.height)
        }
        images.keys.filter { abs(it - current) > 2 }.forEach { images.remove(it) }
    }

    val err = error
    val b = book

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
                        currentPage = current,
                        mode = pageTurnMode,
                        pageImage = { images[it] },
                        onPageChange = { current = it },
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
                            Text("${current + 1} / ${b.pageCount}")
                            if (b.pageCount > 1) {
                                Slider(
                                    value = current.toFloat(),
                                    onValueChange = { current = it.roundToInt() },
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

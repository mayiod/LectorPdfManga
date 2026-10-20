package com.example.lectorpdf

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ReaderScreen(
    uri: Uri,
    title: String,
    pageTurnMode: PageTurnMode,
    viewMode: ViewMode,
    readingDirection: ReadingDirection,
    stripPosition: StripPosition,
    initialPage: Int,
    onPageSave: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Modo automático: retrato = una página, paisaje = dos páginas (se decide con el tamaño real
    // del área de lectura, así reacciona al girar el dispositivo sin reiniciar la actividad).
    val double = when (viewMode) {
        ViewMode.SINGLE -> false
        ViewMode.DOUBLE -> true
        ViewMode.AUTO -> size.width > size.height
    }
    val rtl = readingDirection == ReadingDirection.RTL

    var book by remember { mutableStateOf<BookSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Página "canónica" (índice 0-based). En modo libro la unidad es el pliego que la contiene.
    var page by rememberSaveable(uri.toString()) { mutableIntStateOf(initialPage) }
    var showControls by remember { mutableStateOf(false) }
    var renderedSize by remember { mutableStateOf(IntSize.Zero) }
    val images = remember { mutableStateMapOf<Int, ImageBitmap>() }
    val controller = remember { PageTurnController() }
    val pinned = remember { mutableSetOf<Int>() } // páginas que no se deben liberar (salto en curso)

    // Mantener la pantalla encendida mientras se lee
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Abrir / cerrar el archivo
    LaunchedEffect(uri) {
        runCatching { openBook(context, uri, title) }
            .onSuccess { book = it }
            .onFailure { error = "No se pudo abrir este archivo (¿dañado o protegido con contraseña?)" }
    }
    DisposableEffect(uri) { onDispose { book?.close() } }

    // Si el archivo cambió y ahora tiene menos páginas, ajustar la página guardada
    LaunchedEffect(book) {
        book?.let { page = page.coerceIn(0, maxOf(0, it.pageCount - 1)) }
    }

    // Guardar la última página leída (con una pequeña espera para no escribir en cada cambio)
    val latestPage by rememberUpdatedState(page)
    LaunchedEffect(page) {
        delay(400)
        onPageSave(page)
    }
    DisposableEffect(Unit) { onDispose { onPageSave(latestPage) } }

    // Renderizar las páginas necesarias; liberar las lejanas.
    // Una página: ancho completo. Libro: cada página a medio ancho.
    LaunchedEffect(book, size, page, double) {
        val b = book ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        pinned.clear()
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
        images.keys.filter { it !in keep && it !in pinned }.forEach { images.remove(it) }
    }

    val err = error
    val b = book
    val unit = if (double) (page + 1) / 2 else page
    val thumbs = remember(book) { PageThumbs() }

    // Salto desde la barra de páginas: primero se cargan las páginas destino y luego se anima un solo paso rápido
    fun jumpToPage(target: Int) {
        val bk = book ?: return
        val u = if (double) (target + 1) / 2 else target
        val rs = renderedSize
        if (u == unit || rs.width == 0) return
        scope.launch {
            val pages = (if (double) listOf(2 * u - 1, 2 * u) else listOf(u))
                .filter { it in 0 until bk.pageCount }
            pinned.clear()
            pinned.addAll(pages)
            for (p in pages) {
                if (p !in images) images[p] = bk.render(p, rs.width, rs.height)
            }
            controller.jumpToUnit(u, cornerTop = stripPosition == StripPosition.BOTTOM)
        }
    }

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
                        rtl = rtl,
                        currentUnit = unit,
                        mode = pageTurnMode,
                        controller = controller,
                        pageImage = { images[it] },
                        onUnitChange = { u -> page = if (double) maxOf(0, 2 * u - 1) else u },
                        onCenterTap = { showControls = !showControls }
                    )
                }

                // Arriba: barra con el título (y debajo la barra de páginas si se configuró arriba)
                AnimatedVisibility(
                    visible = showControls,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically { -it },
                    exit = slideOutVertically { -it }
                ) {
                    Column {
                        Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
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
                        if (stripPosition == StripPosition.TOP) {
                            PageStrip(b, thumbs, page, double, rtl, onJump = { jumpToPage(it) })
                        }
                    }
                }

                // Abajo: barra de páginas (si se configuró abajo)
                if (stripPosition == StripPosition.BOTTOM) {
                    AnimatedVisibility(
                        visible = showControls,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it }
                    ) {
                        PageStrip(b, thumbs, page, double, rtl, onJump = { jumpToPage(it) })
                    }
                }
            }
        }
    }
}

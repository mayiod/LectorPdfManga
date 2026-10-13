package com.example.lectorpdf

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class JumpRequest(val unit: Int, val cornerTop: Boolean)

/** Permite pedirle a PageTurnView que salte a otra página con una animación rápida. */
class PageTurnController {
    internal var request by mutableStateOf<JumpRequest?>(null)

    fun jumpToUnit(unit: Int, cornerTop: Boolean) {
        request = JumpRequest(unit, cornerTop)
    }
}

/** Miniaturas de la barra de páginas (se conservan mientras el libro esté abierto). */
class PageThumbs {
    val cache = LruCache<Int, ImageBitmap>(150)
    val gate = Semaphore(2) // máximo 2 miniaturas generándose a la vez
}

/**
 * Barra de páginas: tira de miniaturas en orden, con la página actual resaltada.
 * Se desliza con el dedo (con inercia) y al tocar una miniatura se salta a esa página.
 * En modo derecha a izquierda (rtl) la tira va al revés.
 */
@Composable
fun PageStrip(
    book: BookSource,
    thumbs: PageThumbs,
    page: Int,
    double: Boolean,
    rtl: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = book.pageCount
    val unit = (page + 1) / 2
    val listState = rememberLazyListState()

    // Dejar la página actual a la vista
    LaunchedEffect(page, count) { listState.scrollToItem((page - 3).coerceAtLeast(0)) }

    val label = if (double) {
        listOfNotNull(
            (2 * unit - 1).takeIf { it >= 0 },
            (2 * unit).takeIf { it < count }
        ).joinToString("-") { (it + 1).toString() }
    } else {
        (page + 1).toString()
    }

    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.padding(top = 6.dp, bottom = 8.dp)) {
            Text(
                "$label / $count",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            LazyRow(
                state = listState,
                reverseLayout = rtl,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                items(count = count, key = { it }) { i ->
                    val selected = if (double) (i == 2 * unit - 1 || i == 2 * unit) else i == page
                    PageThumb(book, thumbs, i, selected) { onJump(i) }
                }
            }
        }
    }
}

@Composable
private fun PageThumb(
    book: BookSource,
    thumbs: PageThumbs,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val thumb by produceState<ImageBitmap?>(thumbs.cache.get(index), index, book) {
        if (value == null) {
            val bmp = try {
                thumbs.gate.withPermit { book.render(index, 120, 170) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                thumbs.cache.put(index, bmp)
                value = bmp
            }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 68.dp)
                .border(
                    width = if (selected) 2.dp else 0.5.dp,
                    color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                )
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

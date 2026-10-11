package com.example.lectorpdf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Estado de una página "en movimiento".
 * pageIdx: página que se levanta (la de abajo es pageIdx + 1).
 * t: 0 = página plana, 1 = página ya pasada.
 * lift: desplazamiento vertical del dedo (solo modo esquina).
 */
private data class Turn(
    val pageIdx: Int,
    val cornerTop: Boolean,
    val t: Float,
    val lift: Float
)

@Composable
fun PageTurnView(
    pageCount: Int,
    currentPage: Int,
    mode: PageTurnMode,
    pageImage: (Int) -> ImageBitmap?,
    onPageChange: (Int) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var turn by remember { mutableStateOf<Turn?>(null) }
    var animating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val page by rememberUpdatedState(currentPage)
    val count by rememberUpdatedState(pageCount)
    val turnMode by rememberUpdatedState(mode)
    val changePage by rememberUpdatedState(onPageChange)
    val centerTap by rememberUpdatedState(onCenterTap)
    val images by rememberUpdatedState(pageImage)

    fun animateTurn(from: Turn, toOne: Boolean) {
        scope.launch {
            animating = true
            val target = if (toOne) 1f else 0f
            val ms = (abs(target - from.t) * 380f).toInt().coerceAtLeast(120)
            animate(
                initialValue = from.t,
                targetValue = target,
                animationSpec = tween(ms, easing = FastOutSlowInEasing)
            ) { v, _ -> turn = from.copy(t = v) }
            // forward: toOne => avanzó. backward (pageIdx = actual-1): toOne => se canceló.
            changePage(if (toOne) from.pageIdx + 1 else from.pageIdx)
            turn = null
            animating = false
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // ---- Arrastre ----
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var active = false
                var decided = false
                var forward = true
                var cornerTop = false
                var startY = 0f
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { pos ->
                        tracker.resetTracking()
                        startY = pos.y
                        dx = 0f
                        dy = 0f
                        decided = false
                        active = !animating
                    },
                    onDrag = { change, delta ->
                        if (active) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            dx += delta.x
                            dy += delta.y
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (!decided && abs(dx) > viewConfiguration.touchSlop) {
                                forward = dx < 0              // izquierda = siguiente, derecha = anterior
                                cornerTop = startY < h / 2f   // mitad superior/inferior => esquina
                                decided = true
                                if ((forward && page >= count - 1) || (!forward && page <= 0)) {
                                    active = false
                                }
                            }
                            if (active && decided) {
                                val t = if (forward) -dx / w else 1f - dx / w
                                turn = Turn(
                                    pageIdx = if (forward) page else page - 1,
                                    cornerTop = cornerTop,
                                    t = t.coerceIn(0f, 1f),
                                    lift = dy.coerceIn(-0.4f * h, 0.4f * h)
                                )
                            }
                        }
                    },
                    onDragEnd = {
                        val cur = turn
                        if (active && decided && cur != null) {
                            val vx = tracker.calculateVelocity().x
                            val toOne = when {
                                vx < -1200f -> true
                                vx > 1200f -> false
                                else -> if (forward) cur.t > 0.3f else cur.t > 0.7f
                            }
                            animateTurn(cur, toOne)
                        }
                        active = false
                    },
                    onDragCancel = {
                        val cur = turn
                        if (active && decided && cur != null) animateTurn(cur, toOne = !forward)
                        active = false
                    }
                )
            }
            // ---- Toques: bordes = cambiar página, centro = mostrar/ocultar controles ----
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (animating) return@detectTapGestures
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val top = pos.y < h / 2f
                    val lift = if (top) 0.22f * h else -0.22f * h
                    when {
                        pos.x > w * 0.65f && page < count - 1 ->
                            animateTurn(Turn(page, top, 0f, lift), toOne = true)
                        pos.x < w * 0.35f && page > 0 ->
                            animateTurn(Turn(page - 1, top, 1f, lift), toOne = false)
                        pos.x in (w * 0.35f)..(w * 0.65f) -> centerTap()
                    }
                }
            }
    ) {
        val cur = turn
        when {
            cur == null -> drawPage(images(page))
            turnMode == PageTurnMode.SLIDE ->
                drawSlide(images(cur.pageIdx), images(cur.pageIdx + 1), cur.t)
            else -> drawCurl(images(cur.pageIdx), images(cur.pageIdx + 1), cur)
        }
    }
}

private fun DrawScope.drawPage(img: ImageBitmap?) {
    if (img == null) {
        drawRect(Color.White)
    } else {
        drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}

/** Modo "deslizar": la página se mueve completa, sin doblez. */
private fun DrawScope.drawSlide(top: ImageBitmap?, under: ImageBitmap?, t: Float) {
    val w = size.width
    val x = -w * t
    drawPage(under)
    val sw = 24.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent),
            startX = w + x,
            endX = w + x + sw
        ),
        topLeft = Offset(w + x, 0f),
        size = Size(sw, size.height)
    )
    translate(left = x) { drawPage(top) }
}

/**
 * Modo "esquina": la esquina C sigue al punto P. La línea de doblez es la mediatriz de C-P;
 * la parte de la página del lado de C se oculta y se dibuja reflejada (el reverso) sobre la página.
 */
private fun DrawScope.drawCurl(top: ImageBitmap?, under: ImageBitmap?, turn: Turn) {
    val w = size.width
    val h = size.height
    val t = turn.t
    if (t <= 0.001f) { drawPage(top); return }
    if (t >= 0.999f) { drawPage(under); return }

    val c = Offset(w, if (turn.cornerTop) 0f else h)
    val lift = turn.lift * (1f - t) * (t * 6f).coerceAtMost(1f)
    val p = Offset(w - 2f * w * t, c.y + lift)
    val d = p - c
    val n = d / d.getDistance()           // normal de la línea de doblez (apunta de C hacia P)
    val m = (c + p) / 2f                  // punto de la línea de doblez
    val perp = Offset(-n.y, n.x)
    val big = (w + h) * 3f

    fun halfPlane(side: Float) = Path().apply {
        val a = m + perp * big
        val b = m - perp * big
        val off = n * (big * side)
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(b.x + off.x, b.y + off.y)
        lineTo(a.x + off.x, a.y + off.y)
        close()
    }

    val removed = halfPlane(-1f)   // lado de la esquina: se ve la página de abajo
    val visible = halfPlane(1f)    // lado que queda de la página actual
    val shadow = Brush.linearGradient(
        colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent),
        start = m,
        end = m - n * 28.dp.toPx()
    )

    // 1) página de abajo + sombra del doblez
    drawPage(under)
    clipPath(removed) { drawRect(shadow) }

    // 2) parte visible de la página actual
    clipPath(visible) { drawPage(top) }

    // 3) solapa: reflejo de la zona oculta respecto a la línea de doblez
    val angle = Math.toDegrees(atan2(perp.y, perp.x).toDouble()).toFloat()
    clipRect(0f, 0f, w, h) {
        withTransform({
            translate(m.x, m.y)
            rotate(angle, Offset.Zero)
            scale(1f, -1f, Offset.Zero)
            rotate(-angle, Offset.Zero)
            translate(-m.x, -m.y)
        }) {
            clipPath(removed) {
                drawPage(top)
                drawRect(Color(0xFFEFEFEF).copy(alpha = 0.88f)) // reverso del papel
                drawRect(shadow)
            }
        }
    }
}

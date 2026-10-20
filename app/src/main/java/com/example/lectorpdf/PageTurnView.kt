package com.example.lectorpdf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Zoom máximo permitido con dos dedos. */
private const val MAX_ZOOM = 5f

/**
 * Una "unidad" es lo que se ve en pantalla: una página (modo una página)
 * o un pliego de dos páginas (modo libro abierto).
 *
 * Pliego k = páginas (2k-1 | 2k). El pliego 0 muestra solo la portada.
 *
 * Turn: una hoja (unitIdx) que se levanta dejando ver lo que hay debajo (underIdx).
 * Normalmente underIdx = unitIdx + 1; en un salto desde la barra de páginas es la página destino.
 * t: 0 = plana, 1 = ya pasada. lift: desplazamiento vertical del dedo, como fracción del alto.
 */
private data class Turn(
    val unitIdx: Int,
    val underIdx: Int,
    val cornerTop: Boolean,
    val t: Float,
    val lift: Float
)

/**
 * Toda la lógica de dibujo trabaja como si se leyera de izquierda a derecha.
 * En modo derecha a izquierda (rtl) la escena completa se espeja y cada página
 * se vuelve a espejar sobre sí misma para que se lea normal.
 */
private class BookView(
    val double: Boolean,
    val rtl: Boolean,
    val pageCount: Int,
    val background: Color,
    val image: (Int) -> ImageBitmap?
) {
    fun left(u: Int) = when {
        !double -> -1
        rtl && u == 0 -> 0
        else -> 2 * u - 1
    }

    fun right(u: Int) = when {
        !double -> u
        rtl && u == 0 -> -1
        else -> 2 * u
    }
}

@Composable
fun PageTurnView(
    pageCount: Int,
    doublePage: Boolean,
    rtl: Boolean,
    currentUnit: Int,
    mode: PageTurnMode,
    controller: PageTurnController,
    pageImage: (Int) -> ImageBitmap?,
    onUnitChange: (Int) -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var turn by remember { mutableStateOf<Turn?>(null) }
    var animating by remember { mutableStateOf(false) }
    // Zoom con dos dedos: escala (> 1) y desplazamiento de la página mostrada.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    val unit by rememberUpdatedState(currentUnit)
    val units by rememberUpdatedState(if (doublePage) pageCount / 2 + 1 else pageCount)
    val pages by rememberUpdatedState(pageCount)
    val dbl by rememberUpdatedState(doublePage)
    val isRtl by rememberUpdatedState(rtl)
    val turnMode by rememberUpdatedState(mode)
    val changeUnit by rememberUpdatedState(onUnitChange)
    val centerTap by rememberUpdatedState(onCenterTap)
    val images by rememberUpdatedState(pageImage)
    val background = MaterialTheme.colorScheme.background

    fun animateTurn(from: Turn, toOne: Boolean, fullMs: Float = 380f) {
        scope.launch {
            animating = true
            val target = if (toOne) 1f else 0f
            val ms = (abs(target - from.t) * fullMs).toInt().coerceAtLeast(100)
            animate(
                initialValue = from.t,
                targetValue = target,
                animationSpec = tween(ms, easing = FastOutSlowInEasing)
            ) { v, _ -> turn = from.copy(t = v) }
            // Al terminar, la unidad visible es la de abajo (si la hoja se pasó) o la propia hoja (si volvió).
            changeUnit(if (toOne) from.underIdx else from.unitIdx)
            turn = null
            animating = false
        }
    }

    // Saltos pedidos desde la barra de páginas: una sola animación rápida hasta la página elegida
    val request = controller.request
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        controller.request = null
        val to = request.unit
        if (!animating && to != unit && to in 0 until units) {
            val lift = if (request.cornerTop) 0.22f else -0.22f
            if (to > unit) {
                animateTurn(Turn(unit, to, request.cornerTop, 0f, lift), toOne = true, fullMs = 240f)
            } else {
                animateTurn(Turn(to, unit, request.cornerTop, 1f, lift), toOne = false, fullMs = 240f)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // ---- Un dedo: pasar página. Dos dedos: zoom + desplazamiento ----
            .pointerInput(Unit) {
                // El zoom nunca deja huecos: el desplazamiento queda limitado al área visible
                fun clampPan() {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val mx = w * (zoom - 1f) / 2f
                    val my = h * (zoom - 1f) / 2f
                    pan = if (mx <= 0f || my <= 0f) Offset.Zero
                    else Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    var startY = down.position.y
                    var dx = 0f
                    var dy = 0f
                    var decided = false
                    var forward = true
                    var cornerTop = false
                    var turnActive = !animating
                    var pinching = false
                    var lastCentroid = down.position
                    var lastSpread = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2) {
                            // ---- Pinch: zoom con dos dedos (y desplazamiento con ellos) ----
                            val pts = event.changes.filter { it.pressed }.map { it.position }
                            val c = Offset(
                                pts.sumOf { it.x.toDouble() }.toFloat() / pts.size,
                                pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
                            )
                            // Separación respecto al centro; con 2 dedos es exactamente su distancia
                            val s = 2f * (pts.sumOf { (it - c).getDistance().toDouble() } / pts.size).toFloat()
                            if (!pinching) {
                                pinching = true
                                turnActive = false
                                if (!animating) turn = null // el giro en curso se cancela: la página queda plana
                                lastCentroid = c
                                lastSpread = s
                            } else if (!animating && lastSpread > 1f && s > 1f) {
                                zoom = (zoom * (s / lastSpread)).coerceIn(1f, MAX_ZOOM)
                                pan += c - lastCentroid
                                clampPan()
                                lastCentroid = c
                                lastSpread = s
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val ch = event.changes.first { it.pressed }
                        if (pinching) {
                            // Tras un pinch no se pasa de página hasta levantar todos los dedos
                            ch.consume()
                            continue
                        }

                        val delta = ch.position - ch.previousPosition
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        // En derecha a izquierda el movimiento horizontal se invierte
                        dx += delta.x * (if (isRtl) -1f else 1f)
                        dy += delta.y

                        if (!turnActive) continue
                        if (!decided && abs(dx) > viewConfiguration.touchSlop) {
                            forward = dx < 0                          // siguiente = hacia el lado del lomo opuesto
                            cornerTop = startY < size.height / 2f     // mitad superior/inferior => esquina
                            decided = true
                            if ((forward && unit >= units - 1) || (!forward && unit <= 0)) {
                                turnActive = false
                            }
                        }
                        if (decided) {
                            ch.consume()
                            if (turnActive) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val t = if (forward) -dx / w else 1f - dx / w
                                turn = Turn(
                                    unitIdx = if (forward) unit else unit - 1,
                                    underIdx = if (forward) unit + 1 else unit,
                                    cornerTop = cornerTop,
                                    t = t.coerceIn(0f, 1f),
                                    lift = (dy / h).coerceIn(-0.4f, 0.4f)
                                )
                            }
                        }
                    }

                    // ---- Fin del gesto: la hoja se pasa o vuelve a su sitio ----
                    val cur = turn
                    if (!pinching && turnActive && decided && cur != null && !animating) {
                        val vx = tracker.calculateVelocity().x * (if (isRtl) -1f else 1f)
                        val toOne = when {
                            vx < -1200f -> true
                            vx > 1200f -> false
                            else -> if (forward) cur.t > 0.3f else cur.t > 0.7f
                        }
                        animateTurn(cur, toOne)
                    }
                }
            }
            // ---- Toques: bordes = cambiar página, centro = mostrar/ocultar controles ----
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (animating) return@detectTapGestures
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val px = if (isRtl) w - pos.x else pos.x
                    val top = pos.y < h / 2f
                    val lift = if (top) 0.22f else -0.22f
                    when {
                        px > w * 0.65f && unit < units - 1 ->
                            animateTurn(Turn(unit, unit + 1, top, 0f, lift), toOne = true)
                        px < w * 0.35f && unit > 0 ->
                            animateTurn(Turn(unit - 1, unit, top, 1f, lift), toOne = false)
                        px in (w * 0.35f)..(w * 0.65f) -> centerTap()
                    }
                }
            }
    ) {
        val book = BookView(dbl, isRtl, pages, background) { images(it) }
        val cur = turn
        val w = size.width
        val h = size.height
        val z = zoom
        val off = pan
        val drawAll: DrawScope.() -> Unit = {
            when {
                cur == null -> drawUnit(book, unit)
                turnMode == PageTurnMode.SLIDE -> drawSlide(book, cur.unitIdx, cur.underIdx, cur.t)
                else -> drawCurl(book, cur)
            }
        }
        // Zoom/desplazamiento con dos dedos; se recorta al área visible de la página.
        clipRect(0f, 0f, w, h) {
            withTransform({
                translate(off.x, off.y)
                scale(z, z, Offset(w / 2f, h / 2f))
            }) {
                if (isRtl) {
                    withTransform({
                        translate(w, 0f)
                        scale(-1f, 1f, Offset.Zero)
                    }) { drawAll() }
                } else {
                    drawAll()
                }
            }
        }
    }
}

/** Dibuja una página en la franja [x, x + width]. Si el índice no existe, no dibuja nada. */
private fun DrawScope.drawHalf(book: BookView, page: Int, x: Float, width: Float) {
    if (page < 0 || page >= book.pageCount) return
    val img = book.image(page)
    if (img == null) {
        drawRect(Color.White, topLeft = Offset(x, 0f), size = Size(width, size.height))
        return
    }
    val dstOffset = IntOffset(x.roundToInt(), 0)
    val dstSize = IntSize(width.roundToInt(), size.height.roundToInt())
    if (book.rtl) {
        // La escena completa está espejada: se espeja la página sobre sí misma para que se lea normal
        val cx = x + width / 2f
        withTransform({
            translate(cx, 0f)
            scale(-1f, 1f, Offset.Zero)
            translate(-cx, 0f)
        }) {
            drawImage(img, dstOffset = dstOffset, dstSize = dstSize)
        }
    } else {
        drawImage(img, dstOffset = dstOffset, dstSize = dstSize)
    }
}

/** Dibuja la unidad completa: una página, o las dos páginas del pliego. */
private fun DrawScope.drawUnit(book: BookView, u: Int) {
    if (book.double) {
        val half = size.width / 2f
        drawHalf(book, book.left(u), 0f, half)
        drawHalf(book, book.right(u), half, half)
    } else {
        drawHalf(book, book.right(u), 0f, size.width)
    }
}

/** Modo "deslizar": la unidad completa se mueve, sin doblez. */
private fun DrawScope.drawSlide(book: BookView, u: Int, under: Int, t: Float) {
    val w = size.width
    val x = -w * t
    drawUnit(book, under)
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
    translate(left = x) {
        drawRect(book.background) // para que no se transparente la unidad de abajo
        drawUnit(book, u)
    }
}

/**
 * Modo "esquina". La hoja que se levanta es la página derecha (en modo libro, solo la mitad derecha).
 * La esquina C sigue al punto P; la línea de doblez es la mediatriz de C-P.
 * La parte del lado de C se oculta y se dibuja reflejada (el reverso) sobre la página.
 * En modo libro el reverso es la página izquierda de la unidad de abajo y termina sobre el lomo.
 */
private fun DrawScope.drawCurl(book: BookView, turn: Turn) {
    val w = size.width
    val h = size.height
    val t = turn.t
    val u = turn.unitIdx
    val under = turn.underIdx
    if (t <= 0.001f) { drawUnit(book, u); return }
    if (t >= 0.999f) { drawUnit(book, under); return }

    val sheetW = if (book.double) w / 2f else w
    val sheetL = w - sheetW

    val c = Offset(w, if (turn.cornerTop) 0f else h)
    val lift = turn.lift * h * (1f - t) * (t * 6f).coerceAtMost(1f)
    val p = Offset(w - 2f * sheetW * t, c.y + lift)
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

    val removed = halfPlane(-1f)   // lado de la esquina: se ve lo que hay debajo
    val visible = halfPlane(1f)    // lado que queda de la hoja
    val shadow = Brush.linearGradient(
        colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent),
        start = m,
        end = m - n * 28.dp.toPx()
    )

    // 1) fondo: página izquierda actual (solo libro) y página derecha de abajo, con sombra del doblez
    if (book.double) drawHalf(book, book.left(u), 0f, w / 2f)
    drawHalf(book, book.right(under), sheetL, sheetW)
    clipRect(sheetL, 0f, w, h) { clipPath(removed) { drawRect(shadow) } }

    // 2) parte de la hoja que sigue visible
    clipPath(visible) { drawHalf(book, book.right(u), sheetL, sheetW) }

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
            clipRect(sheetL, 0f, w, h) {
                clipPath(removed) {
                    if (book.double) {
                        // reverso real: página izquierda de la unidad de abajo, espejada respecto al lomo
                        withTransform({
                            translate(w / 2f, 0f)
                            scale(-1f, 1f, Offset.Zero)
                            translate(-w / 2f, 0f)
                        }) {
                            drawHalf(book, book.left(under), 0f, w / 2f)
                        }
                    } else {
                        // una página: se transparenta el frente como reverso del papel
                        drawHalf(book, book.right(u), sheetL, sheetW)
                        drawRect(Color(0xFFEFEFEF).copy(alpha = 0.88f))
                    }
                    drawRect(shadow)
                }
            }
        }
    }
}

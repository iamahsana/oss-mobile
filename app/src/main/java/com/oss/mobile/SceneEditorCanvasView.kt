package com.oss.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight PixelLab-style scene editing surface.
 *
 * This is an editor control surface, not a video preview. It draws a schematic representation
 * of each source and edits the same normalized SceneDocument consumed by the live renderer.
 */
class SceneEditorCanvasView(context: Context) : View(context) {
    private enum class Gesture { NONE, DRAG, RESIZE, ROTATE, PAN, PINCH }

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 20, 29) }
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 37, 49) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 190, 220, 238)
        strokeWidth = density
    }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 52, 116, 145)
        style = Paint.Style.FILL
    }
    private val layerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(133, 215, 239)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 221, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 137, 171)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 221, 255)
        strokeWidth = 1.5f * density
    }

    private var document = SceneDocument(id = "editor", name = "Scene")
    private var selectedLayerId: String? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTransform: SceneTransform? = null
    private var startPointerAngle = 0f
    private var startRotation = 0f
    private var moved = false

    var onDocumentChanged: ((SceneDocument) -> Unit)? = null
    var onSelectionChanged: ((SceneLayer?) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gesture = Gesture.PINCH
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoom
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 4f)
                if (nextZoom != oldZoom) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val ratio = nextZoom / oldZoom
                    panX = detector.focusX - cx - (detector.focusX - cx - panX) * ratio
                    panY = detector.focusY - cy - (detector.focusY - cy - panY) * ratio
                    zoom = nextZoom
                    invalidate()
                }
                return true
            }
        })

    fun setScene(value: SceneDocument, selectedId: String? = selectedLayerId) {
        document = value
        selectedLayerId = selectedId?.takeIf { id -> value.layers.any { it.id == id } }
        onSelectionChanged?.invoke(selectedLayer())
        invalidate()
    }

    fun scene(): SceneDocument = document

    fun selectedLayer(): SceneLayer? =
        document.layers.firstOrNull { it.id == selectedLayerId }

    fun selectLayer(id: String?) {
        selectedLayerId = id?.takeIf { key -> document.layers.any { it.id == key } }
        onSelectionChanged?.invoke(selectedLayer())
        invalidate()
    }

    fun setZoom(value: Float) {
        zoom = value.coerceIn(0.5f, 4f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 20, 29))
        if (width <= 0 || height <= 0) return

        val scale = sceneScale()
        val left = canvasLeft(scale)
        val top = canvasTop(scale)
        val right = left + document.canvas.width * scale
        val bottom = top + document.canvas.height * scale
        val outputRect = RectF(left, top, right, bottom)

        canvas.drawRoundRect(outputRect, 8f * density, 8f * density, canvasPaint)
        canvas.save()
        canvas.clipRect(outputRect)
        drawGrid(canvas, outputRect)
        document.renderOrder().forEach { layer ->
            drawLayer(canvas, layer, left, top, scale, layer.id == selectedLayerId)
        }
        canvas.restore()

        canvas.drawRoundRect(outputRect, 8f * density, 8f * density, layerStroke)
        selectedLayer()?.let { drawHandles(canvas, it, left, top, scale) }
    }

    private fun drawGrid(canvas: Canvas, rect: RectF) {
        val step = max(24f * density, min(rect.width, rect.height) / 12f)
        var x = rect.left
        while (x <= rect.right) {
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            x += step
        }
        var y = rect.top
        while (y <= rect.bottom) {
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            y += step
        }
    }

    private fun drawLayer(
        canvas: Canvas,
        layer: SceneLayer,
        canvasLeft: Float,
        canvasTop: Float,
        scale: Float,
        selected: Boolean
    ) {
        val rect = layerRect(layer, canvasLeft, canvasTop, scale)
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.save()
        canvas.rotate(layer.transform.rotationDegrees, cx, cy)
        layerPaint.color = if (selected) Color.argb(220, 39, 116, 151)
            else Color.argb(175, 48, 82, 103)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, layerPaint)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, if (selected) selectedStroke else layerStroke)
        val label = layer.displayName.take(24)
        val maxTextWidth = max(0f, rect.width - 12f * density)
        val measured = labelPaint.measureText(label)
        val display = if (measured > maxTextWidth && maxTextWidth > 0f) {
            android.text.TextUtils.ellipsize(label, android.text.TextPaint(labelPaint), maxTextWidth,
                android.text.TextUtils.TruncateAt.END).toString()
        } else label
        if (rect.width > 28f * density && rect.height > 20f * density) {
            canvas.drawText(display, cx, cy + labelPaint.textSize * 0.35f, labelPaint)
        }
        canvas.restore()
    }

    private fun drawHandles(canvas: Canvas, layer: SceneLayer, left: Float, top: Float, scale: Float) {
        if (layer.locked) return
        val points = handlePoints(layer, left, top, scale)
        val corner = points.first
        val rotation = points.second
        val centerTop = points.third
        canvas.drawLine(centerTop.first, centerTop.second, rotation.first, rotation.second, connectorPaint)
        drawHandle(canvas, corner.first, corner.second, 7f * density)
        drawHandle(canvas, rotation.first, rotation.second, 7f * density)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, handlePaint)
        canvas.drawCircle(x, y, radius, handleStroke)
    }

    private fun layerRect(layer: SceneLayer, left: Float, top: Float, scale: Float): RectF {
        val t = layer.transform
        return RectF(
            left + t.x * document.canvas.width * scale,
            top + t.y * document.canvas.height * scale,
            left + (t.x + t.width) * document.canvas.width * scale,
            top + (t.y + t.height) * document.canvas.height * scale
        )
    }

    /** Returns resize handle, rotation handle, and unrotated top midpoint in view pixels. */
    private fun handlePoints(
        layer: SceneLayer,
        left: Float,
        top: Float,
        scale: Float
    ): Triple<Pair<Float, Float>, Pair<Float, Float>, Pair<Float, Float>> {
        val rect = layerRect(layer, left, top, scale)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val angle = Math.toRadians(layer.transform.rotationDegrees.toDouble())
        fun rotate(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx
            val dy = y - cy
            return Pair(
                cx + (dx * cos(angle) - dy * sin(angle)).toFloat(),
                cy + (dx * sin(angle) + dy * cos(angle)).toFloat()
            )
        }
        val bottomRight = rotate(rect.right, rect.bottom)
        val topMiddle = rotate(cx, rect.top)
        val rotation = rotate(cx, rect.top - 28f * density)
        return Triple(bottomRight, rotation, topMiddle)
    }

    private fun sceneScale(): Float {
        val fit = min(
            (width - 32f * density).coerceAtLeast(1f) / document.canvas.width,
            (height - 32f * density).coerceAtLeast(1f) / document.canvas.height
        )
        return fit * zoom
    }

    private fun canvasLeft(scale: Float): Float =
        width / 2f - document.canvas.width * scale / 2f + panX

    private fun canvasTop(scale: Float): Float =
        height / 2f - document.canvas.height * scale / 2f + panY

    private fun viewToSceneX(x: Float, scale: Float): Float =
        ((x - canvasLeft(scale)) / scale) / document.canvas.width

    private fun viewToSceneY(y: Float, scale: Float): Float =
        ((y - canvasTop(scale)) / scale) / document.canvas.height

    private fun hitTest(x: Float, y: Float, scale: Float): SceneLayer? =
        document.renderOrder().asReversed().firstOrNull { layer ->
            val rect = layerRect(layer, canvasLeft(scale), canvasTop(scale), scale)
            val cx = rect.centerX()
            val cy = rect.centerY()
            val angle = Math.toRadians(-layer.transform.rotationDegrees.toDouble())
            val dx = x - cx
            val dy = y - cy
            val localX = cx + (dx * cos(angle) - dy * sin(angle)).toFloat()
            val localY = cy + (dx * sin(angle) + dy * cos(angle)).toFloat()
            rect.contains(localX, localY)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val scale = sceneScale()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                val selected = selectedLayer()
                val points = selected?.let {
                    handlePoints(it, canvasLeft(scale), canvasTop(scale), scale)
                }
                val radius = 18f * density
                gesture = when {
                    event.pointerCount > 1 -> Gesture.PINCH
                    selected != null && !selected.locked &&
                        distance(event.x, event.y, points!!.first.first, points.first.second) <= radius ->
                        Gesture.RESIZE
                    selected != null && !selected.locked &&
                        distance(event.x, event.y, points!!.second.first, points.second.second) <= radius ->
                        Gesture.ROTATE
                    else -> {
                        val hit = hitTest(event.x, event.y, scale)
                        if (hit != null) {
                            selectLayer(hit.id)
                            if (hit.locked) Gesture.NONE else Gesture.DRAG
                        } else {
                            selectLayer(null)
                            Gesture.PAN
                        }
                    }
                }
                startTransform = selectedLayer()?.transform
                startPointerAngle = selectedLayer()?.let { pointerAngle(it, event.x, event.y, scale) } ?: 0f
                startRotation = selectedLayer()?.transform?.rotationDegrees ?: 0f
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture = Gesture.PINCH
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || gesture == Gesture.PINCH) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (distance(event.x, event.y, downX, downY) > 2f * density) moved = true
                when (gesture) {
                    Gesture.PAN -> {
                        panX += dx
                        panY += dy
                        invalidate()
                    }
                    Gesture.DRAG -> updateDraggedLayer(dx, dy, scale)
                    Gesture.RESIZE -> updateResizedLayer(dx, dy, scale)
                    Gesture.ROTATE -> updateRotatedLayer(event.x, event.y, scale)
                    else -> Unit
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                gesture = Gesture.NONE
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && gesture == Gesture.PAN && event.actionMasked == MotionEvent.ACTION_UP) {
                    // Empty-canvas taps simply clear selection; no implicit zoom or source creation.
                }
                gesture = Gesture.NONE
                startTransform = null
                performClick()
                return true
            }
        }
        return true
    }

    private fun updateDraggedLayer(dx: Float, dy: Float, scale: Float) {
        val layer = selectedLayer() ?: return
        val old = layer.transform
        val next = old.copy(
            x = (old.x + dx / (scale * document.canvas.width)).coerceIn(-1f, 2f),
            y = (old.y + dy / (scale * document.canvas.height)).coerceIn(-1f, 2f)
        )
        updateLayerTransform(layer.id, next)
    }

    private fun updateResizedLayer(dx: Float, dy: Float, scale: Float) {
        val layer = selectedLayer() ?: return
        val old = layer.transform
        val angle = Math.toRadians(-old.rotationDegrees.toDouble())
        val localDx = (dx * cos(angle) - dy * sin(angle)).toFloat()
        val localDy = (dx * sin(angle) + dy * cos(angle)).toFloat()
        val next = old.copy(
            width = (old.width + localDx / (scale * document.canvas.width)).coerceIn(0.03f, 2f),
            height = (old.height + localDy / (scale * document.canvas.height)).coerceIn(0.03f, 2f)
        )
        updateLayerTransform(layer.id, next)
    }

    private fun updateRotatedLayer(x: Float, y: Float, scale: Float) {
        val layer = selectedLayer() ?: return
        val currentAngle = pointerAngle(layer, x, y, scale)
        var rotation = startRotation + currentAngle - startPointerAngle
        while (rotation > 180f) rotation -= 360f
        while (rotation < -180f) rotation += 360f
        updateLayerTransform(layer.id, layer.transform.copy(rotationDegrees = rotation.coerceIn(-360f, 360f)))
    }

    private fun pointerAngle(layer: SceneLayer, x: Float, y: Float, scale: Float): Float {
        val rect = layerRect(layer, canvasLeft(scale), canvasTop(scale), scale)
        return Math.toDegrees(atan2((y - rect.centerY()).toDouble(), (x - rect.centerX()).toDouble())).toFloat()
    }

    private fun updateLayerTransform(id: String, transform: SceneTransform) {
        val updated = document.layers.map { layer ->
            if (layer.id == id) layer.copy(transform = transform) else layer
        }
        document = document.copy(layers = updated)
        onDocumentChanged?.invoke(document)
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

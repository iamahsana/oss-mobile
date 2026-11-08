package com.oss.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.SparseArray
import android.view.MotionEvent
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
    private enum class Gesture { NONE, DRAG, RESIZE, ROTATE }

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
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        strokeWidth = 1.2f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private var verticalGuide: Float? = null
    private var horizontalGuide: Float? = null
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageCache = SparseArray<Bitmap>()
    private val importedImageCache = linkedMapOf<String, Bitmap>()
    private val textBitmapCache = linkedMapOf<String, Bitmap>()
    private val processedImageCache = linkedMapOf<String, Bitmap>()

    private var document = SceneDocument(id = "editor", name = "Scene")
    private var selectedLayerId: String? = null
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTransform: SceneTransform? = null
    private var startPointerAngle = 0f
    private var startRotation = 0f
    private var moved = false

    // Viewport state is deliberately separate from SceneTransform: zoom/pan never
    // changes saved scene geometry or the coordinates consumed by the LIVE renderer.
    private var viewportZoom = 1f
    private var viewportPanX = 0f
    private var viewportPanY = 0f
    private var viewportGestureActive = false
    private var suppressSingleTouchUntilUp = false
    private var pinchStartDistance = 1f
    private var pinchStartMidX = 0f
    private var pinchStartMidY = 0f
    private var pinchStartZoom = 1f
    private var pinchStartPanX = 0f
    private var pinchStartPanY = 0f

    var onDocumentChanged: ((SceneDocument) -> Unit)? = null
    var onEditStarted: (() -> Unit)? = null
    var onSelectionChanged: ((SceneLayer?) -> Unit)? = null

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
        verticalGuide?.let { x -> canvas.drawLine(x, outputRect.top, x, outputRect.bottom, guidePaint) }
        horizontalGuide?.let { y -> canvas.drawLine(outputRect.left, y, outputRect.right, y, guidePaint) }
        canvas.restore()

        canvas.drawRoundRect(outputRect, 8f * density, 8f * density, layerStroke)
        selectedLayer()?.let { drawHandles(canvas, it, left, top, scale) }
    }

    private fun drawGrid(canvas: Canvas, rect: RectF) {
        val step = max(24f * density, min(rect.width(), rect.height()) / 12f)
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
        val image = imageBitmap(layer)
        val textBitmap = textBitmap(layer)
        val shapeBitmap = shapeBitmap(layer)
        val stickerBitmap = stickerBitmap(layer)
        if (image != null && layer.moduleId == "oss.source.image.static") {
            val displayImage = processedImageBitmap(layer, image)
            imagePaint.alpha = (layer.transform.opacity * 255f).toInt().coerceIn(0, 255)
            val flipX = layer.configuration[StaticImageLayerConfig.KEY_FLIP_HORIZONTAL]?.toBooleanStrictOrNull() == true
            val flipY = layer.configuration[StaticImageLayerConfig.KEY_FLIP_VERTICAL]?.toBooleanStrictOrNull() == true
            canvas.save()
            if (flipX || flipY) {
                canvas.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, cx, cy)
            }
            canvas.drawBitmap(displayImage, null, rect, imagePaint)
            canvas.restore()
            imagePaint.alpha = 255
        } else if (textBitmap != null) {
            imagePaint.alpha = (layer.transform.opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(textBitmap, null, rect, imagePaint)
            imagePaint.alpha = 255
        } else if (shapeBitmap != null) {
            imagePaint.alpha = (layer.transform.opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(shapeBitmap, null, rect, imagePaint)
            imagePaint.alpha = 255
        } else if (stickerBitmap != null) {
            imagePaint.alpha = (layer.transform.opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(stickerBitmap, null, rect, imagePaint)
            imagePaint.alpha = 255
        } else {
            layerPaint.color = if (selected) Color.argb(220, 39, 116, 151)
                else Color.argb(175, 48, 82, 103)
            canvas.drawRoundRect(rect, 6f * density, 6f * density, layerPaint)
        }
        canvas.drawRoundRect(rect, 6f * density, 6f * density, if (selected) selectedStroke else layerStroke)
        val label = layer.displayName.take(24)
        val maxTextWidth = max(0f, rect.width() - 12f * density)
        val measured = labelPaint.measureText(label)
        val display = if (measured > maxTextWidth && maxTextWidth > 0f) {
            android.text.TextUtils.ellipsize(label, android.text.TextPaint(labelPaint), maxTextWidth,
                android.text.TextUtils.TruncateAt.END).toString()
        } else label
        if (image == null && textBitmap == null && shapeBitmap == null && stickerBitmap == null &&
            rect.width() > 28f * density && rect.height() > 20f * density) {
            canvas.drawText(display, cx, cy + labelPaint.textSize * 0.35f, labelPaint)
        }
        canvas.restore()
    }

    private fun processedImageBitmap(layer: SceneLayer, source: Bitmap): Bitmap {
        val config = runCatching { StaticImageLayerConfig.from(layer.configuration) }.getOrNull()
            ?: return source
        val key = layer.id + ":" + layer.configuration.toSortedMap().toString()
        processedImageCache[key]?.let { if (!it.isRecycled) return it }
        val processed = ImageLayerBitmapProcessor.process(source, config.cropZoom, config.maskShape, config.cropOffsetX, config.cropOffsetY, config.borderWidth, config.borderColor)
        if (processed !== source) {
            if (processedImageCache.size >= 8) {
                val eldest = processedImageCache.entries.first()
                processedImageCache.remove(eldest.key)
                if (!eldest.value.isRecycled) eldest.value.recycle()
            }
            processedImageCache[key] = processed
        }
        return processed
    }

    private fun imageBitmap(resourceId: Int): Bitmap? {
        val cached = imageCache.get(resourceId)
        if (cached != null && !cached.isRecycled) return cached
        return try {
            val drawable = context.getDrawable(resourceId) ?: return null
            val bitmapWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
            val bitmapHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
            if (bitmapWidth.toLong() * bitmapHeight.toLong() > 4_194_304L) return null
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val target = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, bitmapWidth, bitmapHeight)
            drawable.draw(target)
            if (imageCache.size() >= 8) imageCache.removeAt(0)
            imageCache.put(resourceId, bitmap)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun imageBitmap(layer: SceneLayer): Bitmap? {
        if (layer.moduleId != "oss.source.image.static") return null
        val importedId = layer.configuration["image_file_id"]
        if (importedId != null) {
            importedImageCache[importedId]?.let { if (!it.isRecycled) return it }
            return try {
                val bitmap = SceneImageStorage.decode(context, importedId, 640) ?: return null
                if (importedImageCache.size >= 4) {
                    val eldest = importedImageCache.entries.first()
                    importedImageCache.remove(eldest.key)
                    if (!eldest.value.isRecycled) eldest.value.recycle()
                }
                importedImageCache[importedId] = bitmap
                bitmap
            } catch (_: Exception) {
                null
            }
        }
        return layer.configuration["resource_id"]?.toIntOrNull()?.let(::imageBitmap)
    }

    private fun textBitmap(layer: SceneLayer): Bitmap? {
        if (layer.moduleId != StaticTextLayerConfig.MODULE_ID) return null
        val key = layer.configuration.toSortedMap().toString()
        textBitmapCache[key]?.let { if (!it.isRecycled) return it }
        return try {
            val bitmap = TextLayerBitmapFactory.create(StaticTextLayerConfig.from(layer.configuration))
            if (textBitmapCache.size >= 4) {
                val eldest = textBitmapCache.entries.first()
                textBitmapCache.remove(eldest.key)
                if (!eldest.value.isRecycled) eldest.value.recycle()
            }
            textBitmapCache[key] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun shapeBitmap(layer: SceneLayer): Bitmap? {
        if (layer.moduleId != ShapeLayerConfig.MODULE_ID) return null
        val key = layer.configuration.toSortedMap().toString()
        textBitmapCache[key]?.let { if (!it.isRecycled) return it }
        return try {
            val bitmap = ShapeLayerBitmapFactory.create(ShapeLayerConfig.from(layer.configuration))
            if (textBitmapCache.size >= 4) {
                val eldest = textBitmapCache.entries.first()
                textBitmapCache.remove(eldest.key)
                if (!eldest.value.isRecycled) eldest.value.recycle()
            }
            textBitmapCache[key] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun stickerBitmap(layer: SceneLayer): Bitmap? {
        if (layer.moduleId != StickerLayerConfig.MODULE_ID) return null
        val key = "sticker:" + layer.configuration.toSortedMap().toString()
        textBitmapCache[key]?.let { if (!it.isRecycled) return it }
        return try {
            val bitmap = StickerLayerBitmapFactory.create(StickerLayerConfig.from(layer.configuration))
            if (textBitmapCache.size >= 8) {
                val eldest = textBitmapCache.entries.first()
                textBitmapCache.remove(eldest.key)
                if (!eldest.value.isRecycled) eldest.value.recycle()
            }
            textBitmapCache[key] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
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
        return fit * viewportZoom
    }

    private fun canvasLeft(scale: Float): Float =
        width / 2f + viewportPanX - document.canvas.width * scale / 2f

    private fun canvasTop(scale: Float): Float =
        height / 2f + viewportPanY - document.canvas.height * scale / 2f

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    }

    private fun pointerMidX(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.x else (event.getX(0) + event.getX(1)) / 2f

    private fun pointerMidY(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.y else (event.getY(0) + event.getY(1)) / 2f

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
        val scale = sceneScale()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                viewportGestureActive = false
                suppressSingleTouchUntilUp = false
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
                    event.pointerCount > 1 -> Gesture.NONE
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
                            Gesture.NONE
                        }
                    }
                }
                if (gesture != Gesture.NONE) onEditStarted?.invoke()
                startTransform = selectedLayer()?.transform
                startPointerAngle = selectedLayer()?.let { pointerAngle(it, event.x, event.y, scale) } ?: 0f
                startRotation = selectedLayer()?.transform?.rotationDegrees ?: 0f
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    viewportGestureActive = true
                    suppressSingleTouchUntilUp = true
                    gesture = Gesture.NONE
                    pinchStartDistance = pointerDistance(event)
                    pinchStartMidX = pointerMidX(event)
                    pinchStartMidY = pointerMidY(event)
                    pinchStartZoom = viewportZoom
                    pinchStartPanX = viewportPanX
                    pinchStartPanY = viewportPanY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && viewportGestureActive) {
                    val distanceRatio = pointerDistance(event) / pinchStartDistance
                    viewportZoom = (pinchStartZoom * distanceRatio).coerceIn(1f, 4f)
                    viewportPanX = pinchStartPanX + pointerMidX(event) - pinchStartMidX
                    viewportPanY = pinchStartPanY + pointerMidY(event) - pinchStartMidY
                    invalidate()
                    return true
                }
                if (suppressSingleTouchUntilUp || event.pointerCount > 1) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (distance(event.x, event.y, downX, downY) > 2f * density) moved = true
                when (gesture) {
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
                viewportGestureActive = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                viewportGestureActive = false
                suppressSingleTouchUntilUp = false
                verticalGuide = null
                horizontalGuide = null
                invalidate()
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
        var nextX = (old.x + dx / (scale * document.canvas.width)).coerceIn(-1f, 2f)
        var nextY = (old.y + dy / (scale * document.canvas.height)).coerceIn(-1f, 2f)
        val snapDistance = 9f * density / scale
        val candidatesX = mutableListOf(
            0f to 0f,
            (0.5f - nextX - old.width / 2f) to 0.5f,
            (1f - nextX - old.width) to 1f
        )
        val candidatesY = mutableListOf(
            0f to 0f,
            (0.5f - nextY - old.height / 2f) to 0.5f,
            (1f - nextY - old.height) to 1f
        )
        document.layers.filter { it.id != layer.id && it.visible }.forEach { other ->
            val t = other.transform
            listOf(t.x, t.x + t.width / 2f, t.x + t.width).forEach { target ->
                listOf(nextX, nextX + old.width / 2f, nextX + old.width).forEach { edge ->
                    candidatesX += (target - edge) to target
                }
            }
            listOf(t.y, t.y + t.height / 2f, t.y + t.height).forEach { target ->
                listOf(nextY, nextY + old.height / 2f, nextY + old.height).forEach { edge ->
                    candidatesY += (target - edge) to target
                }
            }
        }
        val snapX = candidatesX.minByOrNull { kotlin.math.abs(it.first) }
        if (snapX != null && kotlin.math.abs(snapX.first) * document.canvas.width <= snapDistance) {
            nextX = (nextX + snapX.first).coerceIn(-1f, 2f)
            verticalGuide = canvasLeft(scale) + snapX.second * document.canvas.width * scale
        } else verticalGuide = null
        val snapY = candidatesY.minByOrNull { kotlin.math.abs(it.first) }
        if (snapY != null && kotlin.math.abs(snapY.first) * document.canvas.height <= snapDistance) {
            nextY = (nextY + snapY.first).coerceIn(-1f, 2f)
            horizontalGuide = canvasTop(scale) + snapY.second * document.canvas.height * scale
        } else horizontalGuide = null
        updateLayerTransform(layer.id, old.copy(x = nextX, y = nextY))
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

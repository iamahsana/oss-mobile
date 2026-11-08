package com.oss.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Scene editor screen. The canvas is an editing surface, never a second video preview. */
class SceneEditorActivity : Activity() {
    companion object {
        const val EXTRA_SCENE_ID = "scene_editor_id"
        const val EXTRA_SCENE_NAME = "scene_editor_name"
        private const val TEST_IMAGE_MODULE = "oss.source.image.static"
        private const val REQUEST_PICK_IMAGE = 5101
    }

    private lateinit var repository: SceneDocumentRepository
    private lateinit var editor: SceneEditorCanvasView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var canvasSizeButton: Button
    private lateinit var document: SceneDocument
    private var streamService: ScreenStreamService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            streamService = (binder as? ScreenStreamService.LocalBinder)?.getService()
            streamService?.applySceneDocument(document)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sceneId = intent.getStringExtra(EXTRA_SCENE_ID) ?: "studio_scene_0"
        val sceneName = intent.getStringExtra(EXTRA_SCENE_NAME) ?: "Scene"
        repository = SceneDocumentRepository(this)
        document = runCatching { repository.load(sceneId) }.getOrElse {
            SceneDocument(id = sceneId, name = sceneName)
        }
        buildEditor()
        serviceBound = bindService(
            Intent(this, ScreenStreamService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun buildEditor() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(11, 18, 25))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = makeButton("‹", 42) { finish() }
        header.addView(back, LinearLayout.LayoutParams(dp(46), dp(44)))
        titleView = TextView(this).apply {
            text = document.name
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(8), 0)
            maxLines = 1
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(makeButton("Simpan", 14) { saveScene() })
        root.addView(header)

        statusView = TextView(this).apply {
            text = "Geser objek • tarik sudut untuk ubah ukuran • putar dari handle atas"
            textSize = 11f
            setTextColor(Color.rgb(151, 177, 194))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(statusView)

        canvasSizeButton = makeButton(canvasButtonLabel(), 12) { showCanvasSizes() }
        root.addView(canvasSizeButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(6)
            })

        editor = SceneEditorCanvasView(this).apply {
            setScene(document)
            onDocumentChanged = { updated ->
                document = updated
                // Publish transforms immediately. The GL renderer coalesces pointer updates.
                val applied = streamService?.applySceneDocument(updated) == true
                statusView.text = if (applied) {
                    "Perubahan sedang diterapkan ke LIVE"
                } else {
                    "Mode edit • simpan scene sebelum memulai LIVE"
                }
            }
            onSelectionChanged = { layer ->
                if (layer != null) {
                    statusView.text = if (layer.locked) {
                        "${layer.displayName} • terkunci"
                    } else {
                        "${layer.displayName} • siap diedit"
                    }
                }
            }
        }
        root.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        firstRow.addView(makeButton("＋ Teks", 12) { showAddTextDialog() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        firstRow.addView(makeButton("＋ Galeri", 12) { openImagePicker() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        firstRow.addView(makeButton("Layers", 12) { showLayers() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        root.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondRow.addView(makeButton("Tampilkan / sembunyikan", 11) { toggleVisibility() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        secondRow.addView(makeButton("Kunci / buka", 11) { toggleLock() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        secondRow.addView(makeButton("Hapus", 11) { deleteSelected() },
            LinearLayout.LayoutParams(0, dp(44), 0.7f).apply { marginStart = dp(5) })
        root.addView(secondRow)

        val layerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        layerActions.addView(makeButton("Duplikat", 11) { duplicateSelected() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        layerActions.addView(makeButton("Naik layer", 11) { moveSelectedLayer(1) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            })
        layerActions.addView(makeButton("Turun layer", 11) { moveSelectedLayer(-1) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        root.addView(layerActions)

        setContentView(root)
    }

    private fun canvasButtonLabel(): String {
        val canvas = document.canvas
        return "Ukuran canvas  •  ${canvas.width} × ${canvas.height}"
    }

    private fun showCanvasSizes() {
        val presets = listOf(
            "Vertikal 9:16  •  720 × 1280" to SceneCanvas(720, 1280),
            "Horizontal 16:9  •  1280 × 720" to SceneCanvas(1280, 720),
            "Persegi 1:1  •  1080 × 1080" to SceneCanvas(1080, 1080),
            "Potret 4:5  •  864 × 1080" to SceneCanvas(864, 1080)
        )
        val labels = (presets.map { it.first } + "Ukuran khusus…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih ukuran canvas")
            .setItems(labels) { _, index ->
                if (index < presets.size) applyCanvasSize(presets[index].second)
                else showCustomCanvasSize()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCustomCanvasSize() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val widthInput = EditText(this).apply {
            hint = "Lebar (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(document.canvas.width.toString())
        }
        val heightInput = EditText(this).apply {
            hint = "Tinggi (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(document.canvas.height.toString())
        }
        fields.addView(widthInput)
        fields.addView(heightInput)
        AlertDialog.Builder(this)
            .setTitle("Ukuran canvas khusus")
            .setView(fields)
            .setPositiveButton("Terapkan") { _, _ ->
                val width = widthInput.text.toString().toIntOrNull()
                val height = heightInput.text.toString().toIntOrNull()
                if (width == null || height == null || width !in 1..8192 || height !in 1..8192) {
                    Toast.makeText(this, "Ukuran harus 1–8192 px.", Toast.LENGTH_LONG).show()
                } else applyCanvasSize(SceneCanvas(width, height))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyCanvasSize(canvas: SceneCanvas) {
        document = document.copy(canvas = canvas)
        canvasSizeButton.text = canvasButtonLabel()
        editor.setScene(document, editor.selectedLayer()?.id)
        streamService?.applySceneDocument(document)
        try {
            repository.save(document)
            statusView.text = "Canvas ${canvas.width} × ${canvas.height} • tersimpan"
        } catch (error: Exception) {
            statusView.text = "Canvas diubah, tetapi gagal menyimpan: ${error.message}"
        }
    }
    private fun showAddTextDialog() {
        val input = EditText(this).apply {
            hint = "Tulis teks overlay"
            setText("Teks baru")
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Tambah layer teks")
            .setView(container)
            .setPositiveButton("Tambahkan") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank() || value.length > 500) {
                    Toast.makeText(this, "Teks harus berisi 1–500 karakter.", Toast.LENGTH_LONG).show()
                } else {
                    addTextLayer(value)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun addTextLayer(value: String) {
        val config = StaticTextLayerConfig(value)
        val bitmap = TextLayerBitmapFactory.create(config)
        val textWidth = 0.72f
        val textHeight = (textWidth * document.canvas.width / bitmap.width *
            bitmap.height / document.canvas.height).coerceIn(0.02f, 0.5f)
        bitmap.recycle()
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val layer = SceneLayer(
            id = "text_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            moduleId = StaticTextLayerConfig.MODULE_ID,
            displayName = value.replace("\\n", " ").take(22).ifBlank { "Teks" },
            zIndex = nextZ,
            transform = SceneTransform(x = 0.10f, y = 0.12f, width = textWidth, height = textHeight),
            configuration = config.toMap()
        )
        document = document.copy(layers = document.layers + layer)
        editor.setScene(document, layer.id)
        streamService?.applySceneDocument(document)
        statusView.text = "Layer teks ditambahkan. Geser, ubah ukuran, atau putar."
    }

    private fun openImagePicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_PICK_IMAGE)
        } catch (error: Exception) {
            Toast.makeText(this, "Galeri tidak tersedia: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val imported = SceneImageStorage.importImage(this, uri)
            val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
            val width = 0.46f
            val imageAspect = imported.width.toFloat() / imported.height.toFloat()
            val height = (width * document.canvas.width / document.canvas.height / imageAspect)
                .coerceIn(0.03f, 2f)
            val layer = SceneLayer(
                id = "image_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                moduleId = TEST_IMAGE_MODULE,
                displayName = "Image ${document.layers.size + 1}",
                zIndex = nextZ,
                transform = SceneTransform(
                    x = 0.10f,
                    y = 0.10f,
                    width = width,
                    height = height
                ),
                configuration = mapOf(
                    "image_file_id" to imported.id,
                    "x_percent" to "10",
                    "y_percent" to "10",
                    "width_percent" to "46",
                    "opacity" to "1"
                )
            )
            document = document.copy(layers = document.layers + layer)
            editor.setScene(document, layer.id)
            streamService?.applySceneDocument(document)
            statusView.text = "Gambar berhasil diimpor. Geser, ubah ukuran, atau putar."
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Gagal mengimpor gambar: ${error.message ?: "format tidak didukung"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun addTestImage() {
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val layer = SceneLayer(
            id = "image_${System.currentTimeMillis()}",
            moduleId = TEST_IMAGE_MODULE,
            displayName = "Image ${document.layers.size + 1}",
            zIndex = nextZ,
            transform = SceneTransform(
                x = 0.12f,
                y = 0.12f,
                width = 0.46f,
                height = 0.129f
            ),
            configuration = mapOf(
                "resource_id" to R.drawable.oss_test_overlay.toString()
            )
        )
        document = document.copy(layers = document.layers + layer)
        editor.setScene(document, layer.id)
        streamService?.applySceneDocument(document)
        statusView.text = "Gambar uji ditambahkan. Geser, ubah ukuran, atau putar."
    }

    private fun showLayers() {
        if (document.layers.isEmpty()) {
            Toast.makeText(this, "Belum ada layer.", Toast.LENGTH_SHORT).show()
            return
        }
        val layers = document.layers.sortedWith(
            compareByDescending<SceneLayer> { it.zIndex }.thenBy { it.id }
        )
        val labels = layers.map { layer ->
            buildString {
                append(if (layer.visible) "● " else "○ ")
                append(if (layer.locked) "🔒 " else "")
                append(layer.displayName)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih layer")
            .setItems(labels) { _, index ->
                editor.selectLayer(layers[index].id)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun toggleVisibility() {
        val selected = editor.selectedLayer() ?: return noSelection()
        document = document.copy(layers = document.layers.map {
            if (it.id == selected.id) it.copy(visible = !it.visible) else it
        })
        editor.setScene(document, selected.id)
        streamService?.applySceneDocument(document)
    }

    private fun toggleLock() {
        val selected = editor.selectedLayer() ?: return noSelection()
        document = document.copy(layers = document.layers.map {
            if (it.id == selected.id) it.copy(locked = !it.locked) else it
        })
        editor.setScene(document, selected.id)
        statusView.text = if (selected.locked) "Layer dibuka." else "Layer dikunci."
        streamService?.applySceneDocument(document)
    }

    private fun deleteSelected() {
        val selected = editor.selectedLayer() ?: return noSelection()
        document = document.copy(layers = document.layers.filterNot { it.id == selected.id })
        editor.setScene(document, null)
        streamService?.applySceneDocument(document)
        statusView.text = "Layer dihapus."
    }

    private fun duplicateSelected() {
        val selected = editor.selectedLayer() ?: return noSelection()
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val copy = selected.copy(
            id = "layer_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            displayName = selected.displayName + " copy",
            zIndex = nextZ,
            transform = selected.transform.copy(
                x = (selected.transform.x + 0.03f).coerceIn(-1f, 2f),
                y = (selected.transform.y + 0.03f).coerceIn(-1f, 2f)
            ),
            visible = true,
            locked = false,
            configuration = selected.configuration.toMap()
        )
        document = document.copy(layers = document.layers + copy)
        editor.setScene(document, copy.id)
        streamService?.applySceneDocument(document)
        statusView.text = copy.displayName + " dibuat."
    }

    /** direction=1 moves toward the front; direction=-1 moves toward the back. */
    private fun moveSelectedLayer(direction: Int) {
        val selected = editor.selectedLayer() ?: return noSelection()
        val ordered = document.layers.sortedWith(
            compareBy<SceneLayer> { it.zIndex }.thenBy { it.id }
        ).toMutableList()
        val index = ordered.indexOfFirst { it.id == selected.id }
        val target = index + direction
        if (index < 0 || target !in ordered.indices) {
            statusView.text = if (direction > 0) "Layer sudah paling depan."
                else "Layer sudah paling belakang."
            return
        }
        val moving = ordered[index]
        ordered[index] = ordered[target]
        ordered[target] = moving
        val normalized = ordered.mapIndexed { newIndex, layer ->
            layer.copy(zIndex = newIndex)
        }
        document = document.copy(layers = normalized)
        editor.setScene(document, selected.id)
        streamService?.applySceneDocument(document)
        statusView.text = if (direction > 0) "Layer dinaikkan." else "Layer diturunkan."
    }

    private fun noSelection() {
        Toast.makeText(this, "Pilih layer terlebih dahulu.", Toast.LENGTH_SHORT).show()
    }

    private fun saveScene() {
        try {
            repository.save(document)
            titleView.text = document.name
            statusView.text = "Scene tersimpan di perangkat."
            Toast.makeText(this, "Scene berhasil disimpan.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun makeButton(label: String, textSize: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            this.textSize = textSize.toFloat()
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(27, 47, 62))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(48, 76, 96))
            }
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        streamService = null
        super.onDestroy()
    }
}

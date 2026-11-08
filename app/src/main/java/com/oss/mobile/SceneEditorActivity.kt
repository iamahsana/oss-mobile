package com.oss.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
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
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
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
        private const val REQUEST_PICK_GIF = 5102
        private const val REQUEST_PICK_VIDEO = 5103
    }

    private lateinit var repository: SceneDocumentRepository
    private lateinit var editor: SceneEditorCanvasView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var canvasSizeButton: Button
    private lateinit var propertyPanel: LinearLayout
    private lateinit var document: SceneDocument
    private lateinit var history: SceneHistory
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
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
        history = SceneHistory(document)
        buildEditor()
        serviceBound = bindService(
            Intent(this, ScreenStreamService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private lateinit var dockContainer: LinearLayout
    private var dockShowingProperties = false

    private fun buildEditor() {
        // PixelLab/Streamingin workspace: full-bleed stage + one adaptive bottom dock.
        window.statusBarColor = Color.rgb(47, 115, 218)
        window.navigationBarColor = Color.rgb(248, 248, 250)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 248, 250))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(47, 115, 218))
            setPadding(dp(8), dp(3), dp(8), dp(5))
        }
        val primary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        primary.addView(makeButton("‹", 22) { finish() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        titleView = TextView(this).apply {
            text = document.name
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            setPadding(dp(6), 0, 0, 0)
        }
        primary.addView(titleView, LinearLayout.LayoutParams(0, dp(42), 1f))
        primary.addView(makeButton("＋", 22) { showAddSourceMenu() })
        primary.addView(makeButton("▣", 20) { saveScene() })
        primary.addView(makeButton("↶", 20) { undoScene() })
        primary.addView(makeButton("↷", 20) { redoScene() })
        primary.addView(makeButton("⋮", 22) { showWorkspaceMenu() })
        top.addView(primary)

        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        canvasSizeButton = makeButton("Canvas", 12) { showCanvasSizes() }
        canvasSizeButton.text = canvasButtonLabel()
        secondary.addView(canvasSizeButton)
        secondary.addView(makeButton("Layers", 12) { showLayers() })
        secondary.addView(makeButton("Template", 12) { showSceneLibrary(true) })
        secondary.addView(makeButton("Scene", 12) { showSceneLibrary(false) })
        secondary.addView(makeButton("Pas layar", 11) {
            editor.resetViewport()
            statusView.text = "Viewport diatur ulang; komposisi scene tidak berubah."
        })
        top.addView(secondary)
        root.addView(top)

        statusView = TextView(this).apply {
            text = "Pilih modul di bawah untuk menambahkan elemen."
            textSize = 10f
            setTextColor(Color.rgb(95, 101, 112))
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setBackgroundColor(Color.rgb(248, 248, 250))
        }
        root.addView(statusView)

        editor = SceneEditorCanvasView(this).apply {
            setScene(document)
            setBackgroundColor(Color.rgb(248, 248, 248))
            onEditStarted = { checkpointHistory() }
            onDocumentChanged = { updated ->
                document = updated
                updateHistoryButtons()
                streamService?.applySceneDocument(updated)
                statusView.text = "Perubahan diterapkan ke dokumen scene"
                if (dockShowingProperties) renderBottomDock()
            }
            onSelectionChanged = { layer ->
                dockShowingProperties = layer != null
                statusView.text = if (layer == null) "Pilih modul di bawah untuk menambahkan elemen."
                    else "${layer.displayName} • properti elemen"
                renderBottomDock()
            }
        }
        root.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(5))
            elevation = dp(5).toFloat()
        }
        // The properties workspace is the same bottom dock, never a floating dialog.
        propertyPanel = dockContainer
        root.addView(dockContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dockDefaultHeight()
        ))
        setContentView(root)
        renderBottomDock()
        updateHistoryButtons()
    }

    private fun renderBottomDock() {
        if (!::dockContainer.isInitialized) return
        dockContainer.removeAllViews()
        val heading = TextView(this).apply {
            text = if (dockShowingProperties && editor.selectedLayer() != null)
                "PROPERTI • ${editor.selectedLayer()?.displayName}" else "MODUL ELEMEN"
            textSize = 10f
            setTextColor(Color.rgb(105, 109, 118))
            setPadding(dp(3), 0, 0, dp(5))
        }
        dockContainer.addView(heading)
        val strip = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun item(label: String, action: () -> Unit) {
            val b = makeButton(label, 11, action)
            items.addView(b, LinearLayout.LayoutParams(dp(82), dockItemHeight()).apply {
                marginEnd = dp(5)
            })
        }
        if (dockShowingProperties && editor.selectedLayer() != null) {
            val selected = editor.selectedLayer()!!
            addSeekProperty(items, "Opacity", (selected.transform.opacity * 100).toInt(), 100,
                onStart = { checkpointHistory() },
                onChange = { value ->
                    updateSelectedLayer { it.copy(transform = it.transform.copy(opacity = value / 100f)) }
                },
                onFinish = { finishPropertyChange() }
            )
            when (selected.moduleId) {
                StaticTextLayerConfig.MODULE_ID -> {
                    val config = StaticTextLayerConfig.from(selected.configuration)
                    addSeekProperty(items, "Ukuran teks", (config.textSize.toInt() - 16).coerceIn(0, 240), 240,
                        onStart = { checkpointHistory() },
                        onChange = { value -> updateSelectedLayer { layer ->
                            layer.copy(configuration = StaticTextLayerConfig.from(layer.configuration)
                                .copy(textSize = (value + 16).toFloat()).toMap())
                        } },
                        onFinish = { fitSelectedTextHeight(); finishPropertyChange() }
                    )
                    addPropertyToggle(items, "Bold", config.bold) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration =
                            StaticTextLayerConfig.from(layer.configuration).copy(bold = checked).toMap()) }
                    }
                    addPropertyToggle(items, "Italic", config.italic) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration =
                            StaticTextLayerConfig.from(layer.configuration).copy(italic = checked).toMap()) }
                    }
                    addPropertyToggle(items, "Underline", config.underline) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration =
                            StaticTextLayerConfig.from(layer.configuration).copy(underline = checked).toMap()) }
                    }
                    item("Detail teks") { editSelectedText() }
                }
                "oss.source.image.static" -> {
                    val config = StaticImageLayerConfig.from(selected.configuration)
                    addSeekProperty(items, "Zoom crop", ((config.cropZoom - 1f) * 100).toInt().coerceIn(0, 200), 200,
                        onStart = { checkpointHistory() },
                        onChange = { value -> updateSelectedLayer { layer ->
                            layer.copy(configuration = layer.configuration + (StaticImageLayerConfig.KEY_CROP_ZOOM to (1f + value / 100f).toString()))
                        } },
                        onFinish = { finishPropertyChange() }
                    )
                    addPropertyToggle(items, "Flip H", config.flipHorizontal) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration = layer.configuration + (StaticImageLayerConfig.KEY_FLIP_HORIZONTAL to checked.toString())) }
                    }
                    addPropertyToggle(items, "Flip V", config.flipVertical) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration = layer.configuration + (StaticImageLayerConfig.KEY_FLIP_VERTICAL to checked.toString())) }
                    }
                    item("Detail gambar") { editSelectedImage() }
                }
                ShapeLayerConfig.MODULE_ID -> {
                    val config = ShapeLayerConfig.from(selected.configuration)
                    addSeekProperty(items, "Border", config.strokeWidth.toInt(), 48,
                        onStart = { checkpointHistory() },
                        onChange = { value -> updateSelectedLayer { layer ->
                            layer.copy(configuration = ShapeLayerConfig.from(layer.configuration)
                                .copy(strokeWidth = value.toFloat()).toMap())
                        } },
                        onFinish = { finishPropertyChange() }
                    )
                    addSeekProperty(items, "Radius", config.cornerRadius.toInt(), 100,
                        onStart = { checkpointHistory() },
                        onChange = { value -> updateSelectedLayer { layer ->
                            layer.copy(configuration = ShapeLayerConfig.from(layer.configuration)
                                .copy(cornerRadius = value.toFloat()).toMap())
                        } },
                        onFinish = { finishPropertyChange() }
                    )
                    addPropertyToggle(items, "Isi warna", config.fillEnabled) { checked ->
                        updateSelectedLayer { layer -> layer.copy(configuration =
                            ShapeLayerConfig.from(layer.configuration).copy(fillEnabled = checked).toMap()) }
                    }
                    item("Detail bentuk") { editSelectedShape() }
                }
                else -> item("Properti lanjutan") { editSelectedTransform() }
            }
            item("Posisi / ukuran") { editSelectedTransform() }
            item("Duplikat") { duplicateSelected() }
            item("Urutan") { showLayers() }
            item(if (selected.locked) "Buka kunci" else "Kunci") { toggleLock() }
            item("Grup") { chooseLayersForGrouping() }
            item("Hapus") { deleteSelected() }
            item("Selesai") {
                dockShowingProperties = false
                editor.setScene(document, null)
                renderBottomDock()
            }
        } else {
            item("Teks  ＋") { showAddTextDialog() }
            item("Gambar  ＋") { openImagePicker() }
            item("Stiker  ＋") { showStickerPicker() }
            item("Bentuk  ＋") { showAddShapeDialog() }
            item("GIF  ＋") { openGifPicker() }
            item("Video  ＋") { openVideoPicker() }
            item("Share Screen") { showScreenSourceInfo() }
            item("Layers") { showLayers() }
        }
        strip.addView(items)
        dockContainer.addView(strip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabs.addView(makeButton("Modul", 10) {
            dockShowingProperties = false
            editor.setScene(document, null)
            renderBottomDock()
        }, LinearLayout.LayoutParams(0, dockTabHeight(), 1f))
        tabs.addView(makeButton("Properti", 10) {
            if (editor.selectedLayer() != null) {
                dockShowingProperties = true
                renderBottomDock()
            } else Toast.makeText(this@SceneEditorActivity, "Pilih elemen terlebih dahulu.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dockTabHeight(), 1f))
        dockContainer.addView(tabs)
    }

    private fun addSeekProperty(
        parent: LinearLayout,
        label: String,
        initial: Int,
        maximum: Int,
        onStart: () -> Unit,
        onChange: (Int) -> Unit,
        onFinish: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 248, 251))
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.rgb(225, 228, 234))
            }
        }
        card.addView(TextView(this).apply {
            text = "$label: $initial"
            textSize = 10f
            setTextColor(Color.rgb(55, 64, 78))
        })
        card.addView(SeekBar(this).apply {
            max = maximum.coerceAtLeast(1)
            progress = initial.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        (card.getChildAt(0) as TextView).text = "$label: $progress"
                        onChange(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { onStart() }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onFinish() }
            })
        })
        parent.addView(card, LinearLayout.LayoutParams(dp(148), dockItemHeight()).apply {
            marginEnd = dp(5)
        })
    }

    private fun addPropertyToggle(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val button = makeButton("$label: ${if (checked) "ON" else "OFF"}", 10) {
            checkpointHistory()
            onChange(!checked)
            finishPropertyChange()
            renderBottomDock()
        }
        parent.addView(button, LinearLayout.LayoutParams(dp(92), dockItemHeight()).apply {
            marginEnd = dp(5)
        })
    }

    private fun updateSelectedLayer(update: (SceneLayer) -> SceneLayer) {
        val selected = editor.selectedLayer() ?: return
        val updated = update(selected)
        document = document.copy(layers = document.layers.map { if (it.id == selected.id) updated else it })
        editor.setScene(document, selected.id, notifySelection = false)
        streamService?.applySceneDocument(document)
    }

    private fun fitSelectedTextHeight() {
        val selected = editor.selectedLayer() ?: return
        if (selected.moduleId != StaticTextLayerConfig.MODULE_ID) return
        val bitmap = runCatching {
            TextLayerBitmapFactory.create(StaticTextLayerConfig.from(selected.configuration))
        }.getOrNull() ?: return
        val height = (selected.transform.width * document.canvas.width / bitmap.width *
            bitmap.height / document.canvas.height).coerceIn(0.02f, 2f)
        bitmap.recycle()
        updateSelectedLayer { layer -> layer.copy(transform = layer.transform.copy(height = height)) }
    }

    private fun finishPropertyChange() {
        runCatching { repository.save(document) }
            .onFailure { statusView.text = "Gagal menyimpan properti: ${it.message}" }
        updateHistoryButtons()
    }

    private fun showWorkspaceMenu() {
        val choices = arrayOf("Pengaturan canvas", "Template scene", "Scene tersimpan", "Daftar layer", "Simpan scene")
        AlertDialog.Builder(this).setItems(choices) { _, which ->
            when (which) {
                0 -> showCanvasSizes()
                1 -> showSceneLibrary(true)
                2 -> showSceneLibrary(false)
                3 -> showLayers()
                4 -> saveScene()
            }
        }.show()
    }

    private fun showSceneLibrary(templates: Boolean) {
        val saved = runCatching { repository.list() }.getOrDefault(emptyList())
        val templateEntries = SceneTemplateCatalog.entries
        val entries = if (templates) templateEntries.map { it.title } else saved.map { it.name }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Belum ada scene tersimpan.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (templates) "Template scene" else "Scene tersimpan")
            .setItems(entries.toTypedArray()) { _, index ->
                if (templates) {
                    checkpointHistory()
                    document = SceneTemplateCatalog.create(templateEntries[index], document.id)
                    history = SceneHistory(document)
                    editor.setScene(document, null)
                    titleView.text = document.name
                    canvasSizeButton.text = canvasButtonLabel()
                    updateHistoryButtons()
                    streamService?.applySceneDocument(document)
                    runCatching { repository.save(document) }
                        .onSuccess { statusView.text = "Template diterapkan dan disimpan." }
                        .onFailure { statusView.text = "Template diterapkan; gagal menyimpan: ${it.message}" }
                    dockShowingProperties = false
                    renderBottomDock()
                } else {
                    val selected = saved[index]
                    document = selected
                    history = SceneHistory(document)
                    editor.setScene(document, null)
                    titleView.text = document.name
                    canvasSizeButton.text = canvasButtonLabel()
                    updateHistoryButtons()
                    streamService?.applySceneDocument(document)
                    statusView.text = "Scene ${document.name} dibuka."
                    dockShowingProperties = false
                    renderBottomDock()
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun showAddSourceMenu() {
        AlertDialog.Builder(this)
            .setTitle("Tambah sumber")
            .setItems(arrayOf("Share Screen")) { _, _ -> showScreenSourceInfo() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showScreenSourceInfo() {
        if (document.layers.any { it.moduleId == SceneScreenLayerConfig.MODULE_ID }) {
            Toast.makeText(this, "Share Screen sudah ada di scene.", Toast.LENGTH_SHORT).show()
            return
        }
        checkpointHistory()
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val layer = SceneLayer(
            id = "screen_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            moduleId = SceneScreenLayerConfig.MODULE_ID,
            displayName = "Share Screen",
            zIndex = nextZ,
            transform = SceneTransform(x = 0f, y = 0f, width = 1f, height = 1f),
            configuration = SceneScreenLayerConfig().toMap()
        )
        document = document.copy(layers = document.layers + layer)
        publishSceneEdit(layer.id, "Share Screen ditambahkan sebagai elemen. Atur ukuran dan posisinya di canvas.")
    }

    private fun chooseLayersForGrouping() {
        val layers = document.layers
        if (layers.size < 2) {
            Toast.makeText(this, "Tambahkan minimal dua layer.", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIds = linkedSetOf<String>()
        selectedIds.addAll(editor.selectedLayerIds())
        AlertDialog.Builder(this)
            .setTitle("Pilih layer yang akan dikelompokkan")
            .setMultiChoiceItems(layers.map { it.displayName }.toTypedArray(),
                BooleanArray(layers.size) { layers[it].id in selectedIds }) { _, which, checked ->
                if (checked) selectedIds.add(layers[which].id) else selectedIds.remove(layers[which].id)
            }
            .setPositiveButton("Buat grup") { _, _ ->
                if (selectedIds.size < 2) {
                    Toast.makeText(this, "Pilih minimal dua layer.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                checkpointHistory()
                val groupId = "grp_" + java.util.UUID.randomUUID().toString().replace("-", "")
                document = SceneLayerOperations.group(document, selectedIds, groupId)
                publishSceneEdit(selectedIds.first(), "Grup dibuat.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun ungroupSelected() {
        val groupId = editor.selectedLayer()?.groupId
        if (groupId == null) {
            Toast.makeText(this, "Layer ini belum tergabung dalam grup.", Toast.LENGTH_SHORT).show()
            return
        }
        checkpointHistory()
        document = SceneLayerOperations.ungroup(document, groupId)
        publishSceneEdit(editor.selectedLayer()?.id, "Grup dilepas.")
    }

    private fun arrangeSelectedLayers(distribute: Boolean) {
        val layers = document.layers
        val selectedIds = linkedSetOf<String>()
        editor.selectedLayer()?.id?.let(selectedIds::add)
        AlertDialog.Builder(this)
            .setTitle(if (distribute) "Pilih layer untuk disebar" else "Pilih layer untuk diratakan")
            .setMultiChoiceItems(layers.map { it.displayName }.toTypedArray(),
                BooleanArray(layers.size) { layers[it].id in selectedIds }) { _, which, checked ->
                if (checked) selectedIds.add(layers[which].id) else selectedIds.remove(layers[which].id)
            }
            .setPositiveButton("Lanjut") { _, _ ->
                if (selectedIds.size < if (distribute) 3 else 2) {
                    Toast.makeText(this, if (distribute) "Pilih minimal tiga layer." else "Pilih minimal dua layer.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val choices = if (distribute) arrayOf("Horizontal", "Vertikal")
                    else arrayOf("Kiri", "Tengah horizontal", "Kanan", "Atas", "Tengah vertikal", "Bawah")
                AlertDialog.Builder(this)
                    .setTitle(if (distribute) "Arah penyebaran" else "Sisi perataan")
                    .setItems(choices) { _, choice ->
                        checkpointHistory()
                        document = if (distribute) {
                            SceneLayerOperations.distribute(document, selectedIds,
                                if (choice == 0) SceneLayerOperations.Axis.HORIZONTAL else SceneLayerOperations.Axis.VERTICAL)
                        } else {
                            SceneLayerOperations.align(document, selectedIds,
                                SceneLayerOperations.Edge.values()[choice])
                        }
                        publishSceneEdit(editor.selectedLayer()?.id,
                            if (distribute) "Layer disebar merata." else "Layer diratakan.")
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun publishSceneEdit(selectedId: String?, message: String) {
        editor.setScene(document, selectedId)
        streamService?.applySceneDocument(document)
        runCatching { repository.save(document) }
            .onSuccess { statusView.text = message }
            .onFailure { statusView.text = "$message Gagal menyimpan: ${it.message}" }
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
        checkpointHistory()
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
        checkpointHistory()
        document = document.copy(layers = document.layers + layer)
        editor.setScene(document, layer.id)
        streamService?.applySceneDocument(document)
        statusView.text = "Layer teks ditambahkan. Geser, ubah ukuran, atau putar."
    }

    private fun editSelectedText() {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId != StaticTextLayerConfig.MODULE_ID) {
            Toast.makeText(this, "Pilih layer teks terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val current = StaticTextLayerConfig.from(selected.configuration)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val textInput = EditText(this).apply {
            setText(current.text)
            hint = "Tulis teks"
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        panel.addView(textInput)
        val sizeLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Ukuran teks: ${current.textSize.toInt()}"
            setPadding(0, dp(12), 0, dp(4))
        }
        panel.addView(sizeLabel)
        val sizeSeek = SeekBar(this).apply {
            max = 240
            progress = (current.textSize.toInt() - 16).coerceIn(0, 240)
        }
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = "Ukuran teks: ${progress + 16}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(sizeSeek)
        val boldCheck = CheckBox(this).apply {
            text = "Tebal (bold)"
            setTextColor(Color.WHITE)
            isChecked = current.bold
        }
        panel.addView(boldCheck)
        val italicCheck = CheckBox(this).apply {
            text = "Miring (italic)"
            setTextColor(Color.WHITE)
            isChecked = current.italic
        }
        panel.addView(italicCheck)
        val underlineCheck = CheckBox(this).apply { text = "Garis bawah (underline)"; setTextColor(Color.WHITE); isChecked = current.underline }
        panel.addView(underlineCheck)
        val strikeCheck = CheckBox(this).apply { text = "Coret teks (strikethrough)"; setTextColor(Color.WHITE); isChecked = current.strikethrough }
        panel.addView(strikeCheck)
        val fontLabel = TextView(this).apply {
            text = "Jenis huruf"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(fontLabel)
        val fontGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val fonts = listOf("Sans" to StaticTextLayerConfig.FontFamily.SANS, "Serif" to StaticTextLayerConfig.FontFamily.SERIF, "Mono" to StaticTextLayerConfig.FontFamily.MONOSPACE)
        fonts.forEach { item ->
            val radio = RadioButton(this).apply { id = View.generateViewId(); text = item.first; setTextColor(Color.WHITE) }
            fontGroup.addView(radio)
            if (item.second == current.fontFamily) fontGroup.check(radio.id)
        }
        panel.addView(fontGroup)
        val outlineCheck = CheckBox(this).apply {
            text = "Outline hitam"
            setTextColor(Color.WHITE)
            isChecked = current.outlineWidth > 0f
        }
        panel.addView(outlineCheck)
        val outlineLabel = TextView(this).apply {
            text = "Ketebalan outline: ${current.outlineWidth.toInt()}"
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(2))
        }
        panel.addView(outlineLabel)
        val outlineSeek = SeekBar(this).apply {
            max = 12
            progress = current.outlineWidth.toInt().coerceIn(0, 12)
            isEnabled = outlineCheck.isChecked
        }
        outlineCheck.setOnCheckedChangeListener { _, checked ->
            outlineSeek.isEnabled = checked
        }
        outlineSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                outlineLabel.text = "Ketebalan outline: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(outlineSeek)
        val shadowCheck = CheckBox(this).apply {
            text = "Bayangan (shadow)"
            setTextColor(Color.WHITE)
            isChecked = current.shadow
        }
        panel.addView(shadowCheck)
        val backgroundCheck = CheckBox(this).apply {
            text = "Background teks"
            setTextColor(Color.WHITE)
            isChecked = current.backgroundEnabled
        }
        panel.addView(backgroundCheck)
        val gradientCheck = CheckBox(this).apply {
            text = "Gradasi (warna gelap → cyan)"
            setTextColor(Color.WHITE)
            isChecked = current.backgroundGradient
            isEnabled = current.backgroundEnabled
        }
        backgroundCheck.setOnCheckedChangeListener { _, checked ->
            gradientCheck.isEnabled = checked
        }
        panel.addView(gradientCheck)
        val backgroundColorLabel = TextView(this).apply { text = "Warna background"; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(2)) }
        panel.addView(backgroundColorLabel)
        val backgroundColors = listOf("Gelap" to Color.argb(220, 15, 25, 35), "Hitam" to Color.BLACK, "Biru" to Color.rgb(20, 70, 125), "Ungu" to Color.rgb(90, 45, 125), "Putih" to Color.WHITE)
        val backgroundColorGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        var selectedBackgroundColor = current.backgroundColor
        backgroundColors.forEach { item ->
            val radio = RadioButton(this).apply { id = View.generateViewId(); text = item.first; textSize = 11f; setTextColor(item.second) }
            backgroundColorGroup.addView(radio)
            if (item.second == current.backgroundColor) backgroundColorGroup.check(radio.id)
        }
        backgroundColorGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedBackgroundColor = backgroundColors.firstOrNull { item -> (0 until group.childCount).any { i -> val child = group.getChildAt(i) as? RadioButton; child?.id == checkedId && child.text.toString() == item.first } }?.second ?: selectedBackgroundColor
        }
        panel.addView(backgroundColorGroup)
        val radiusLabel = TextView(this).apply { text = "Sudut background: ${current.backgroundRadius.toInt()}"; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(2)) }
        panel.addView(radiusLabel)
        val radiusSeek = SeekBar(this).apply { max = 48; progress = current.backgroundRadius.toInt(); isEnabled = current.backgroundEnabled }
        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { radiusLabel.text = "Sudut background: $progress" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(radiusSeek)
        val opacityLabel = TextView(this).apply { text = "Transparansi background: ${current.backgroundOpacity}%"; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(2)) }
        panel.addView(opacityLabel)
        val opacitySeek = SeekBar(this).apply { max = 100; progress = current.backgroundOpacity; isEnabled = current.backgroundEnabled }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { opacityLabel.text = "Transparansi background: $progress%" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        backgroundCheck.setOnCheckedChangeListener { _, checked ->
            gradientCheck.isEnabled = checked
            backgroundColorGroup.isEnabled = checked
            for (i in 0 until backgroundColorGroup.childCount) backgroundColorGroup.getChildAt(i).isEnabled = checked
            radiusSeek.isEnabled = checked
            opacitySeek.isEnabled = checked
        }
        panel.addView(opacitySeek)
        val colorLabel = TextView(this).apply {
            text = "Warna"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(colorLabel)
        val colors = listOf(
            "Putih" to Color.WHITE,
            "Cyan" to Color.rgb(65, 213, 245),
            "Kuning" to Color.YELLOW,
            "Merah muda" to Color.rgb(255, 125, 190),
            "Hijau" to Color.rgb(110, 235, 145)
        )
        val colorGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        var selectedColor = current.color
        colors.forEachIndexed { index, item ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = item.first
                textSize = 11f
                setTextColor(item.second)
                if (item.second == current.color) selectedColor = item.second
            }
            colorGroup.addView(radio)
            if (item.second == current.color) colorGroup.check(radio.id)
        }
        colorGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedColor = colors.firstOrNull { (label, _) ->
                (0 until group.childCount).any { i ->
                    val child = group.getChildAt(i) as? RadioButton
                    child?.id == checkedId && child.text.toString() == label
                }
            }?.second ?: selectedColor
        }
        panel.addView(colorGroup)
        val alignLabel = TextView(this).apply {
            text = "Perataan"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(alignLabel)
        val alignmentGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val alignments = listOf("Kiri" to StaticTextLayerConfig.Alignment.LEFT,
            "Tengah" to StaticTextLayerConfig.Alignment.CENTER,
            "Kanan" to StaticTextLayerConfig.Alignment.RIGHT)
        alignments.forEach { item ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = item.first
                setTextColor(Color.WHITE)
            }
            alignmentGroup.addView(radio)
            if (item.second == current.alignment) alignmentGroup.check(radio.id)
        }
        panel.addView(alignmentGroup)
        AlertDialog.Builder(this)
            .setTitle("Pengaturan teks")
            .setView(panel)
            .setPositiveButton("Terapkan") { _, _ ->
                val value = textInput.text.toString().trim()
                if (value.isBlank() || value.length > 500) {
                    Toast.makeText(this, "Teks harus berisi 1–500 karakter.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val alignment = alignments.firstOrNull { (label, _) ->
                    (0 until alignmentGroup.childCount).any { i ->
                        val child = alignmentGroup.getChildAt(i) as? RadioButton
                        child?.id == alignmentGroup.checkedRadioButtonId &&
                            child.text.toString() == label
                    }
                }?.second ?: current.alignment
                val config = StaticTextLayerConfig(
                    text = value,
                    color = selectedColor,
                    textSize = (sizeSeek.progress + 16).toFloat(),
                    bold = boldCheck.isChecked,
                    alignment = alignment,
                    outlineWidth = if (outlineCheck.isChecked) outlineSeek.progress.toFloat().coerceAtLeast(1f) else 0f,
                    shadow = shadowCheck.isChecked,
                    backgroundEnabled = backgroundCheck.isChecked,
                    backgroundColor = selectedBackgroundColor,
                    backgroundGradient = backgroundCheck.isChecked && gradientCheck.isChecked,
                    backgroundRadius = radiusSeek.progress.toFloat(),
                    backgroundOpacity = opacitySeek.progress,
                    italic = italicCheck.isChecked,
                    underline = underlineCheck.isChecked,
                    strikethrough = strikeCheck.isChecked,
                    fontFamily = fonts.firstOrNull { (label, _) -> (0 until fontGroup.childCount).any { i -> val child = fontGroup.getChildAt(i) as? RadioButton; child?.id == fontGroup.checkedRadioButtonId && child.text.toString() == label } }?.second ?: current.fontFamily
                )
                val bitmap = TextLayerBitmapFactory.create(config)
                val width = selected.transform.width
                val height = (width * document.canvas.width / bitmap.width *
                    bitmap.height / document.canvas.height).coerceIn(0.02f, 2f)
                bitmap.recycle()
                val updated = selected.copy(
                    displayName = value.replace("\\n", " ").take(22).ifBlank { "Teks" },
                    transform = selected.transform.copy(height = height),
                    configuration = config.toMap()
                )
                checkpointHistory()
                document = document.copy(layers = document.layers.map {
                    if (it.id == selected.id) updated else it
                })
                editor.setScene(document, selected.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Pengaturan teks diterapkan."
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddShapeDialog() {
        val labels = arrayOf("Persegi panjang", "Lingkaran", "Garis")
        val shapes = ShapeLayerConfig.Shape.values()
        AlertDialog.Builder(this)
            .setTitle("Tambah shape")
            .setItems(labels) { _, index -> showShapeSettings(null, shapes[index]) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editSelectedShape() {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId != ShapeLayerConfig.MODULE_ID) {
            Toast.makeText(this, "Pilih layer shape terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        showShapeSettings(selected, ShapeLayerConfig.from(selected.configuration).shape)
    }

    private fun showShapeSettings(existing: SceneLayer?, initialShape: ShapeLayerConfig.Shape) {
        val current = existing?.let { ShapeLayerConfig.from(it.configuration) } ?: ShapeLayerConfig(shape = initialShape)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val shapeLabel = TextView(this).apply { text = "Bentuk"; setTextColor(Color.WHITE) }
        panel.addView(shapeLabel)
        val shapeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val shapeOptions = listOf(
            "Kotak" to ShapeLayerConfig.Shape.RECTANGLE,
            "Lingkaran" to ShapeLayerConfig.Shape.CIRCLE,
            "Garis" to ShapeLayerConfig.Shape.LINE
        )
        shapeOptions.forEach { (label, shape) ->
            val radio = RadioButton(this).apply { id = View.generateViewId(); text = label; setTextColor(Color.WHITE) }
            shapeGroup.addView(radio)
            if (shape == current.shape) shapeGroup.check(radio.id)
        }
        panel.addView(shapeGroup)

        val fillCheck = CheckBox(this).apply {
            text = "Isi warna"
            setTextColor(Color.WHITE)
            isChecked = current.fillEnabled
            isEnabled = current.shape != ShapeLayerConfig.Shape.LINE
        }
        panel.addView(fillCheck)

        val colorLabel = TextView(this).apply { text = "Warna"; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(2)) }
        panel.addView(colorLabel)
        val colors = listOf(
            "Cyan" to Color.rgb(65, 190, 225),
            "Putih" to Color.WHITE,
            "Kuning" to Color.rgb(255, 210, 70),
            "Pink" to Color.rgb(255, 120, 185),
            "Hijau" to Color.rgb(90, 220, 135),
            "Ungu" to Color.rgb(160, 110, 235)
        )
        val colorGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        var selectedColor = current.fillColor
        colors.forEach { (label, color) ->
            val radio = RadioButton(this).apply { id = View.generateViewId(); text = label; textSize = 11f; setTextColor(color) }
            colorGroup.addView(radio)
            if (color == current.fillColor) colorGroup.check(radio.id)
        }
        colorGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedColor = colors.firstOrNull { (label, _) ->
                (0 until group.childCount).any { i ->
                    val child = group.getChildAt(i) as? RadioButton
                    child?.id == checkedId && child.text.toString() == label
                }
            }?.second ?: selectedColor
        }
        panel.addView(colorGroup)

        val borderLabel = TextView(this).apply {
            text = "Ketebalan border: ${current.strokeWidth.toInt()}"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(borderLabel)
        val borderSeek = SeekBar(this).apply { max = 48; progress = current.strokeWidth.toInt() }
        borderSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                borderLabel.text = "Ketebalan border: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView(borderSeek)

        val radiusLabel = TextView(this).apply {
            text = "Radius sudut: ${current.cornerRadius.toInt()}"
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(radiusLabel)
        val radiusSeek = SeekBar(this).apply {
            max = 100
            progress = current.cornerRadius.toInt()
            isEnabled = current.shape == ShapeLayerConfig.Shape.RECTANGLE
        }
        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radiusLabel.text = "Radius sudut: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        shapeGroup.setOnCheckedChangeListener { group, checkedId ->
            val selectedShape = shapeOptions.firstOrNull { (label, _) ->
                (0 until group.childCount).any { i ->
                    val child = group.getChildAt(i) as? RadioButton
                    child?.id == checkedId && child.text.toString() == label
                }
            }?.second ?: current.shape
            fillCheck.isEnabled = selectedShape != ShapeLayerConfig.Shape.LINE
            radiusSeek.isEnabled = selectedShape == ShapeLayerConfig.Shape.RECTANGLE
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Pengaturan shape baru" else "Edit shape")
            .setView(panel)
            .setPositiveButton("Terapkan") { _, _ ->
                val shape = shapeOptions.firstOrNull { (label, _) ->
                    (0 until shapeGroup.childCount).any { i ->
                        val child = shapeGroup.getChildAt(i) as? RadioButton
                        child?.id == shapeGroup.checkedRadioButtonId && child.text.toString() == label
                    }
                }?.second ?: current.shape
                val config = current.copy(
                    shape = shape,
                    fillEnabled = fillCheck.isChecked && shape != ShapeLayerConfig.Shape.LINE,
                    fillColor = selectedColor,
                    strokeColor = selectedColor,
                    strokeWidth = borderSeek.progress.toFloat(),
                    cornerRadius = radiusSeek.progress.toFloat()
                )
                if (existing == null) {
                    val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
                    val isLine = shape == ShapeLayerConfig.Shape.LINE
                    val isCircle = shape == ShapeLayerConfig.Shape.CIRCLE
                    val layer = SceneLayer(
                        id = "shape_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                        moduleId = ShapeLayerConfig.MODULE_ID,
                        displayName = when (shape) {
                            ShapeLayerConfig.Shape.RECTANGLE -> "Persegi"
                            ShapeLayerConfig.Shape.CIRCLE -> "Lingkaran"
                            ShapeLayerConfig.Shape.LINE -> "Garis"
                        },
                        zIndex = nextZ,
                        transform = SceneTransform(
                            x = if (isCircle) 0.34f else 0.12f,
                            y = 0.28f,
                            width = if (isLine) 0.68f else if (isCircle) 0.30f else 0.68f,
                            height = if (isLine) 0.06f else if (isCircle) 0.30f else 0.30f
                        ),
                        configuration = config.toMap()
                    )
                    checkpointHistory()
                    document = document.copy(layers = document.layers + layer)
                    editor.setScene(document, layer.id)
                } else {
                    checkpointHistory()
                    document = document.copy(layers = document.layers.map {
                        if (it.id == existing.id) it.copy(configuration = config.toMap()) else it
                    })
                    editor.setScene(document, existing.id)
                }
                streamService?.applySceneDocument(document)
                statusView.text = "Shape diterapkan. Geser, ubah ukuran, atau putar di canvas."
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showStickerPicker() {
        val stickers = StickerLayerConfig.ALLOWED_STICKERS
        AlertDialog.Builder(this)
            .setTitle("Pilih stiker")
            .setItems(stickers.toTypedArray()) { _, index ->
                val emoji = stickers[index]
                val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
                val layer = SceneLayer(
                    id = "sticker_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    moduleId = StickerLayerConfig.MODULE_ID,
                    displayName = "Stiker $emoji",
                    zIndex = nextZ,
                    transform = SceneTransform(x = 0.34f, y = 0.28f, width = 0.30f, height = 0.17f),
                    configuration = StickerLayerConfig(emoji).toMap()
                )
                checkpointHistory()
                document = document.copy(layers = document.layers + layer)
                editor.setScene(document, layer.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Stiker ditambahkan. Geser, ubah ukuran, atau putar."
            }
            .setNegativeButton("Batal", null)
            .show()
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

    private fun openGifPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/gif"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_PICK_GIF)
        } catch (error: Exception) {
            Toast.makeText(this, "Pemilih GIF tidak tersedia: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openVideoPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_PICK_VIDEO)
        } catch (error: Exception) {
            Toast.makeText(this, "Pemilih video tidak tersedia: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQUEST_PICK_VIDEO) {
            try {
                val imported = SceneMediaAssetStorage.import(this, uri)
                require(imported.asset.kind == SceneMediaAsset.Kind.VIDEO) { "Pilih file video MP4 atau WebM." }
                require(imported.asset.sizeBytes <= SceneVideoLayerConfig.MAX_FILE_BYTES) { "Ukuran video maksimal 50 MB." }
                val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
                val layer = SceneLayer(
                    id = "video_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    moduleId = SceneVideoLayerConfig.MODULE_ID,
                    displayName = "Video ${document.layers.size + 1}",
                    zIndex = nextZ,
                    transform = SceneTransform(x = 0.25f, y = 0.25f, width = 0.50f, height = 0.50f),
                    configuration = SceneVideoLayerConfig(imported.asset.id).toMap()
                )
                checkpointHistory()
                document = document.copy(layers = document.layers + layer)
                editor.setScene(document, layer.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Video ditambahkan. LIVE memutar video berulang tanpa audio."
            } catch (error: Exception) {
                Toast.makeText(this, "Gagal mengimpor video: ${error.message ?: "format tidak didukung"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == REQUEST_PICK_GIF) {
            try {
                val imported = SceneMediaAssetStorage.import(this, uri)
                require(imported.asset.kind == SceneMediaAsset.Kind.ANIMATED_IMAGE) {
                    "Pilih file GIF animasi."
                }
                require(imported.asset.sizeBytes <= SceneGifLayerConfig.MAX_GIF_BYTES) {
                    "Ukuran GIF maksimal 10 MB."
                }
                val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
                val layer = SceneLayer(
                    id = "gif_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    moduleId = SceneGifLayerConfig.MODULE_ID,
                    displayName = "GIF ${document.layers.size + 1}",
                    zIndex = nextZ,
                    transform = SceneTransform(x = 0.34f, y = 0.32f, width = 0.32f, height = 0.32f),
                    configuration = SceneGifLayerConfig(imported.asset.id).toMap()
                )
                checkpointHistory()
                document = document.copy(layers = document.layers + layer)
                editor.setScene(document, layer.id)
                streamService?.applySceneDocument(document)
                statusView.text = "GIF ditambahkan. Animasi akan berjalan saat scene dirender di LIVE."
            } catch (error: Exception) {
                Toast.makeText(this, "Gagal mengimpor GIF: ${error.message ?: "format tidak didukung"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != REQUEST_PICK_IMAGE) return
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
                transform = SceneTransform(x = 0.10f, y = 0.10f, width = width, height = height),
                configuration = mapOf(
                    "image_file_id" to imported.id,
                    "x_percent" to "10",
                    "y_percent" to "10",
                    "width_percent" to "46",
                    "opacity" to "1"
                )
            )
            checkpointHistory()
            document = document.copy(layers = document.layers + layer)
            editor.setScene(document, layer.id)
            streamService?.applySceneDocument(document)
            statusView.text = "Gambar berhasil diimpor. Geser, ubah ukuran, atau putar."
        } catch (error: Exception) {
            Toast.makeText(this, "Gagal mengimpor gambar: ${error.message ?: "format tidak didukung"}", Toast.LENGTH_LONG).show()
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
        checkpointHistory()
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
                append("   •   ")
                append(layer.moduleId.substringAfterLast('.'))
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Layer • pilih untuk mengelola")
            .setItems(labels) { _, index ->
                editor.selectLayer(layers[index].id)
                showLayerActions(layers[index].id)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showLayerActions(layerId: String) {
        val layer = document.layers.firstOrNull { it.id == layerId } ?: return
        editor.selectLayer(layerId)
        val actions = mutableListOf<String>()
        actions += if (layer.visible) "Sembunyikan" else "Tampilkan"
        actions += if (layer.locked) "Buka kunci" else "Kunci posisi"
        actions += "Naikkan satu tingkat"
        actions += "Turunkan satu tingkat"
        if (layer.moduleId != SceneScreenLayerConfig.MODULE_ID) {
            actions += "Duplikat"
            actions += "Ganti nama"
            actions += "Hapus"
        }
        if (layer.groupId != null) actions += "Lepas dari grup"
        AlertDialog.Builder(this)
            .setTitle(layer.displayName)
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "Sembunyikan", "Tampilkan" -> toggleVisibility()
                    "Kunci posisi", "Buka kunci" -> toggleLock()
                    "Naikkan satu tingkat" -> moveSelectedLayer(1)
                    "Turunkan satu tingkat" -> moveSelectedLayer(-1)
                    "Duplikat" -> duplicateSelected()
                    "Ganti nama" -> renameSelectedLayer()
                    "Hapus" -> deleteSelected()
                    "Lepas dari grup" -> ungroupSelected()
                }
            }
            .setNegativeButton("Selesai", null)
            .show()
    }

    private fun renameSelectedLayer() {
        val selected = editor.selectedLayer() ?: return noSelection()
        val input = EditText(this).apply {
            setText(selected.displayName)
            selectAll()
            hint = "Nama layer"
            filters = arrayOf(android.text.InputFilter.LengthFilter(64))
        }
        AlertDialog.Builder(this)
            .setTitle("Ganti nama layer")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Nama layer tidak boleh kosong.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                checkpointHistory()
                document = document.copy(layers = document.layers.map {
                    if (it.id == selected.id) it.copy(displayName = name) else it
                })
                publishSceneEdit(selected.id, "Nama layer diperbarui.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renameSelectedLayer() {
        val selected = editor.selectedLayer() ?: return noSelection()
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(selected.displayName)
            setSelection(text.length)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Nama layer")
            .setView(field)
            .setPositiveButton("Simpan") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Nama layer tidak boleh kosong.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                checkpointHistory()
                document = document.copy(layers = document.layers.map {
                    if (it.id == selected.id) it.copy(displayName = name.take(48)) else it
                })
                editor.setScene(document, selected.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Nama layer diperbarui."
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editSelectedImage() {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId != TEST_IMAGE_MODULE) {
            Toast.makeText(this, "Pilih layer gambar terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentConfig = runCatching {
            StaticImageLayerConfig.from(selected.configuration)
        }.getOrNull()
        if (currentConfig == null) {
            Toast.makeText(this, "Konfigurasi gambar tidak valid.", Toast.LENGTH_SHORT).show()
            return
        }
        val flipHorizontal = CheckBox(this).apply {
            text = "Balik horizontal"
            isChecked = currentConfig.flipHorizontal
        }
        val flipVertical = CheckBox(this).apply {
            text = "Balik vertikal"
            isChecked = currentConfig.flipVertical
        }
        val opacityLabel = TextView(this).apply {
            text = "Opacity gambar: " + (selected.transform.opacity * 100).toInt() + "%"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val opacity = SeekBar(this).apply {
            max = 100
            progress = (selected.transform.opacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    opacityLabel.text = "Opacity gambar: " + progress + "%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val cropLabel = TextView(this).apply {
            text = "Zoom / crop: " + (currentConfig.cropZoom * 100).toInt() + "%"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val cropZoom = SeekBar(this).apply {
            max = 200
            progress = ((currentConfig.cropZoom - 1f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    cropLabel.text = "Zoom / crop: " + (100 + progress) + "%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val cropXLabel = TextView(this).apply { text = "Posisi crop horizontal: ${(currentConfig.cropOffsetX * 100).toInt()}%"; setTextColor(Color.WHITE); textSize = 14f }
        val cropX = SeekBar(this).apply { max = 200; progress = ((currentConfig.cropOffsetX + 1f) * 100).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { cropXLabel.text = "Posisi crop horizontal: ${(progress - 100)}%" }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit }) }
        val cropYLabel = TextView(this).apply { text = "Posisi crop vertikal: ${(currentConfig.cropOffsetY * 100).toInt()}%"; setTextColor(Color.WHITE); textSize = 14f }
        val cropY = SeekBar(this).apply { max = 200; progress = ((currentConfig.cropOffsetY + 1f) * 100).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { cropYLabel.text = "Posisi crop vertikal: ${(progress - 100)}%" }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit }) }
        val borderLabel = TextView(this).apply { text = "Ketebalan border: ${(currentConfig.borderWidth * 100).toInt()}%"; setTextColor(Color.WHITE); textSize = 14f }
        val border = SeekBar(this).apply { max = 8; progress = (currentConfig.borderWidth * 100).toInt(); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { borderLabel.text = "Ketebalan border: $progress%" }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit }) }
        val borderColors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val colorOptions = listOf("Putih" to "#FFFFFF", "Hitam" to "#000000", "Hijau" to "#00E676", "Ungu" to "#B388FF")
        var selectedBorderColor = currentConfig.borderColor
        colorOptions.forEach { (label, color) -> val radio = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = color; setTextColor(Color.WHITE); isChecked = color.equals(currentConfig.borderColor, true) }; if (radio.isChecked) borderColors.check(radio.id); borderColors.addView(radio) }
        borderColors.setOnCheckedChangeListener { group, checkedId -> selectedBorderColor = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: "#FFFFFF" }
        val maskLabel = TextView(this).apply {
            text = "Bentuk mask"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        val masks = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val maskOptions = listOf(
            "Persegi" to StaticImageLayerConfig.MASK_RECTANGLE,
            "Lingkaran / oval" to StaticImageLayerConfig.MASK_CIRCLE,
            "Sudut membulat" to StaticImageLayerConfig.MASK_ROUNDED
        )
        var selectedMask = currentConfig.maskShape
        maskOptions.forEachIndexed { index, option ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = option.first
                isChecked = option.second == currentConfig.maskShape
                setTextColor(Color.WHITE)
            }
            if (radio.isChecked) masks.check(radio.id)
            masks.addView(radio)
            radio.tag = option.second
        }
        masks.setOnCheckedChangeListener { group, checkedId ->
            selectedMask = group.findViewById<RadioButton>(checkedId)?.tag as? String
                ?: StaticImageLayerConfig.MASK_RECTANGLE
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(flipHorizontal)
            addView(flipVertical)
            addView(opacityLabel)
            addView(opacity)
            addView(cropLabel)
            addView(cropZoom)
            addView(cropXLabel)
            addView(cropX)
            addView(cropYLabel)
            addView(cropY)
            addView(maskLabel)
            addView(masks)
            addView(borderLabel)
            addView(border)
            addView(borderColors)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit gambar")
            .setView(panel)
            .setPositiveButton("Terapkan") { _, _ ->
                checkpointHistory()
                val config = selected.configuration.toMutableMap().apply {
                    put(StaticImageLayerConfig.KEY_FLIP_HORIZONTAL, flipHorizontal.isChecked.toString())
                    put(StaticImageLayerConfig.KEY_FLIP_VERTICAL, flipVertical.isChecked.toString())
                    put(StaticImageLayerConfig.KEY_CROP_ZOOM, (1f + cropZoom.progress / 100f).toString())
                    put(StaticImageLayerConfig.KEY_MASK_SHAPE, selectedMask)
                    put(StaticImageLayerConfig.KEY_CROP_OFFSET_X, ((cropX.progress - 100) / 100f).toString())
                    put(StaticImageLayerConfig.KEY_CROP_OFFSET_Y, ((cropY.progress - 100) / 100f).toString())
                    put(StaticImageLayerConfig.KEY_BORDER_WIDTH, (border.progress / 100f).toString())
                    put(StaticImageLayerConfig.KEY_BORDER_COLOR, selectedBorderColor)
                }
                document = document.copy(layers = document.layers.map {
                    if (it.id == selected.id) it.copy(
                        configuration = config,
                        transform = it.transform.copy(opacity = opacity.progress / 100f)
                    ) else it
                })
                editor.setScene(document, selected.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Crop, mask, dan border gambar diterapkan."
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toggleVisibility() {
        val selected = editor.selectedLayer() ?: return noSelection()
        checkpointHistory()
        document = document.copy(layers = document.layers.map {
            if (it.id == selected.id) it.copy(visible = !it.visible) else it
        })
        publishSceneEdit(selected.id, if (selected.visible) "Layer disembunyikan." else "Layer ditampilkan.")
    }

    private fun toggleLock() {
        val selected = editor.selectedLayer() ?: return noSelection()
        checkpointHistory()
        document = document.copy(layers = document.layers.map {
            if (it.id == selected.id) it.copy(locked = !it.locked) else it
        })
        publishSceneEdit(selected.id, if (selected.locked) "Layer dibuka." else "Layer dikunci.")
    }

    private fun deleteSelected() {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId == SceneScreenLayerConfig.MODULE_ID) {
            Toast.makeText(this, "Share Screen adalah sumber utama scene. Sembunyikan layer jika tidak ingin menampilkannya.", Toast.LENGTH_LONG).show()
            return
        }
        checkpointHistory()
        document = document.copy(layers = document.layers.filterNot { it.id == selected.id })
        publishSceneEdit(null, "Layer dihapus.")
        dockShowingProperties = false
        renderBottomDock()
    }

    private fun duplicateSelected() {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId == SceneScreenLayerConfig.MODULE_ID) {
            Toast.makeText(this, "Sumber Share Screen tidak bisa diduplikasi.", Toast.LENGTH_SHORT).show()
            return
        }
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
        checkpointHistory()
        document = document.copy(layers = document.layers + copy)
        editor.setScene(document, copy.id)
        streamService?.applySceneDocument(document)
        statusView.text = copy.displayName + " dibuat."
    }

    /** direction=1 moves toward the front; direction=-1 moves toward the back. */
    private fun moveSelectedLayer(direction: Int) {
        val selected = editor.selectedLayer() ?: return noSelection()
        if (selected.moduleId == SceneScreenLayerConfig.MODULE_ID) {
            Toast.makeText(this, "Share Screen tetap menjadi layer paling bawah.", Toast.LENGTH_SHORT).show()
            return
        }
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
        checkpointHistory()
        document = document.copy(layers = normalized)
        publishSceneEdit(selected.id, if (direction > 0) "Layer dinaikkan." else "Layer diturunkan.")
    }


    private fun editSelectedTransform() {
        val selected = editor.selectedLayer() ?: return noSelection()
        val t = selected.transform
        propertyPanel.removeAllViews()
        propertyPanel.visibility = View.VISIBLE
        propertyPanel.layoutParams = (propertyPanel.layoutParams as LinearLayout.LayoutParams).apply {
            height = dockPropertyHeight()
        }
        propertyPanel.addView(TextView(this).apply {
            text = "POSISI & UKURAN  •  ${selected.displayName}"
            textSize = 12f
            setTextColor(Color.rgb(43, 54, 72))
        })

        fun row(vararg entries: Pair<String, Float>): List<EditText> {
            val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val inputs = entries.map { (label, value) ->
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }
                column.addView(TextView(this).apply {
                    text = label
                    textSize = 10f
                    setTextColor(Color.rgb(91, 101, 118))
                })
                val input = EditText(this).apply {
                    setSingleLine(true)
                    textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(String.format(java.util.Locale.US, "%.3f", value))
                    setPadding(dp(7), 0, dp(7), 0)
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(246, 248, 251))
                        cornerRadius = dp(7).toFloat()
                        setStroke(dp(1), Color.rgb(220, 224, 232))
                    }
                }
                column.addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                ))
                container.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                input
            }
            propertyPanel.addView(container)
            return inputs
        }

        val transformInputs = if (isLandscape()) {
            row(
                "X" to t.x,
                "Y" to t.y,
                "Lebar" to t.width,
                "Tinggi" to t.height,
                "Rotasi" to t.rotationDegrees,
                "Opacity" to t.opacity
            )
        } else {
            row("X" to t.x, "Y" to t.y) +
                row("Lebar" to t.width, "Tinggi" to t.height) +
                row("Rotasi" to t.rotationDegrees, "Opacity" to t.opacity)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(makeButton("Kembali", 11) {
            propertyPanel.layoutParams = (propertyPanel.layoutParams as LinearLayout.LayoutParams).apply { height = dockDefaultHeight() }
            renderBottomDock()
        })
        actions.addView(makeButton("Terapkan", 11) {
            val values = transformInputs.map { it.text.toString().trim().toFloatOrNull() }
            if (values.any { it == null }) {
                Toast.makeText(this, "Semua nilai harus berupa angka.", Toast.LENGTH_LONG).show()
                return@makeButton
            }
            val v = values.filterNotNull()
            val next = runCatching {
                SceneTransform(
                    x = v[0], y = v[1], width = v[2], height = v[3],
                    rotationDegrees = v[4], opacity = v[5]
                )
            }.getOrElse {
                Toast.makeText(this, "Nilai di luar batas yang diizinkan.", Toast.LENGTH_LONG).show()
                return@makeButton
            }
            checkpointHistory()
            document = document.copy(layers = document.layers.map { layer ->
                if (layer.id == selected.id) layer.copy(transform = next) else layer
            })
            editor.setScene(document, selected.id)
            streamService?.applySceneDocument(document)
            runCatching { repository.save(document) }
            statusView.text = "Transform layer diterapkan."
            propertyPanel.layoutParams = (propertyPanel.layoutParams as LinearLayout.LayoutParams).apply { height = dockDefaultHeight() }
            renderBottomDock()
        })
        actions.getChildAt(1).let { it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(6) } }
        propertyPanel.addView(actions)
    }

    private fun checkpointHistory() {
        history.record(document)
        // The caller immediately applies an edit, so expose Undo before the
        // resulting document callback (canvas gestures) or setScene call.
        if (::undoButton.isInitialized) {
            undoButton.isEnabled = true
            undoButton.alpha = 1f
            redoButton.isEnabled = false
            redoButton.alpha = 0.45f
        }
    }

    private fun updateHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        undoButton.isEnabled = history.canUndo(document)
        redoButton.isEnabled = history.canRedo()
        undoButton.alpha = if (undoButton.isEnabled) 1f else 0.45f
        redoButton.alpha = if (redoButton.isEnabled) 1f else 0.45f
    }

    private fun undoScene() {
        val previous = history.undo(document)
        if (previous == null) {
            updateHistoryButtons()
            Toast.makeText(this, "Tidak ada perubahan untuk dibatalkan.", Toast.LENGTH_SHORT).show()
            return
        }
        document = previous
        val selectedId = editor.selectedLayer()?.id?.takeIf { id -> document.layers.any { it.id == id } }
        editor.setScene(document, selectedId)
        streamService?.applySceneDocument(document)
        statusView.text = "Perubahan terakhir dibatalkan."
        updateHistoryButtons()
    }

    private fun redoScene() {
        val next = history.redo()
        if (next == null) {
            Toast.makeText(this, "Tidak ada perubahan untuk diulangi.", Toast.LENGTH_SHORT).show()
            return
        }
        document = next
        val selectedId = editor.selectedLayer()?.id?.takeIf { id -> document.layers.any { it.id == id } }
        editor.setScene(document, selectedId)
        streamService?.applySceneDocument(document)
        statusView.text = "Perubahan diulangi."
        updateHistoryButtons()
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
            setTextColor(Color.rgb(43, 54, 72))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 248, 251))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 224, 232))
            }
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dockDefaultHeight(): Int = dp(if (isLandscape()) 112 else 142)
    private fun dockPropertyHeight(): Int = dp(if (isLandscape()) 154 else 214)
    private fun dockItemHeight(): Int = dp(if (isLandscape()) 54 else 76)
    private fun dockTabHeight(): Int = dp(if (isLandscape()) 27 else 35)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::editor.isInitialized) {
            editor.requestLayout()
            editor.invalidate()
        }
        if (::dockContainer.isInitialized && dockContainer.height > 0) renderBottomDock()
    }

    override fun onPause() {
        // Persist the latest in-memory scene when the app is backgrounded or interrupted.
        if (::repository.isInitialized && ::document.isInitialized) {
            runCatching { repository.save(document) }
                .onFailure { if (::statusView.isInitialized) statusView.text = "Autosave gagal: ${it.message}" }
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        streamService = null
        super.onDestroy()
    }
}

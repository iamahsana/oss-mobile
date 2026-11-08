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
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
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

    private fun buildEditor() {
        // PixelLab-inspired workspace: fixed canvas stage + separate bottom tool dock.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(241, 243, 247))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeButton("‹", 22) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = TextView(this).apply {
            text = document.name
            textSize = 17f
            setTextColor(Color.rgb(28, 34, 45))
            setPadding(dp(8), 0, dp(4), 0)
            maxLines = 1
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(makeButton("↶", 18) { undoScene() })
        header.addView(makeButton("↷", 18) { redoScene() })
        header.addView(makeButton("Simpan", 12) { saveScene() })
        root.addView(header)

        val canvasToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        canvasSizeButton = makeButton(canvasButtonLabel(), 10) { showCanvasSizes() }
        canvasToolbar.addView(canvasSizeButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        canvasToolbar.addView(makeButton("Pas layar", 10) {
            editor.resetViewport()
            statusView.text = "Tampilan editor diatur ulang; ukuran scene tidak berubah."
        })
        root.addView(canvasToolbar)

        statusView = TextView(this).apply {
            text = "Pilih elemen di canvas untuk mengeditnya. Canvas ini adalah komposisi LIVE."
            textSize = 11f
            setTextColor(Color.rgb(91, 101, 118))
            setPadding(dp(4), dp(3), dp(4), dp(5))
        }
        root.addView(statusView)

        // The stage receives all remaining space. Dock controls never cover the canvas.
        editor = SceneEditorCanvasView(this).apply {
            setScene(document)
            onEditStarted = { checkpointHistory() }
            onDocumentChanged = { updated ->
                document = updated
                updateHistoryButtons()
                val applied = streamService?.applySceneDocument(updated) == true
                statusView.text = if (applied) "Perubahan diterapkan ke scene LIVE"
                    else "Mode edit • perubahan tersimpan pada dokumen scene"
            }
            onSelectionChanged = { layer ->
                statusView.text = if (layer == null) "Canvas siap • pilih atau tambahkan elemen"
                    else if (layer.locked) "${layer.displayName} • terkunci"
                    else "${layer.displayName} • dipilih"
            }
        }
        root.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(8)
        })

        // Persistent bottom dock: a separate surface below the canvas, not an overlay.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 16)
            setPadding(dp(9), dp(8), dp(9), dp(8))
        }
        val dockTitle = TextView(this).apply {
            text = "TAMBAH ELEMEN"
            textSize = 10f
            setTextColor(Color.rgb(104, 112, 127))
            setPadding(dp(3), 0, 0, dp(5))
        }
        dock.addView(dockTitle)

        fun toolRow(vararg items: Pair<String, () -> Unit>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            items.forEachIndexed { index, item ->
                row.addView(makeButton(item.first, 10, item.second),
                    LinearLayout.LayoutParams(0, dp(39), 1f).apply {
                        if (index > 0) marginStart = dp(3)
                        if (index < items.lastIndex) marginEnd = dp(3)
                    })
            }
            dock.addView(row)
        }

        toolRow(
            "Teks" to { showAddTextDialog() },
            "Gambar" to { openImagePicker() },
            "Stiker" to { showStickerPicker() },
            "Bentuk" to { showAddShapeDialog() }
        )
        toolRow(
            "GIF" to { openGifPicker() },
            "Video" to { openVideoPicker() },
            "Layers" to { showLayers() },
            "Tambah lain" to { showAddSourceMenu() }
        )

        val selectedToolsTitle = TextView(this).apply {
            text = "PENGATURAN OBJEK"
            textSize = 10f
            setTextColor(Color.rgb(104, 112, 127))
            setPadding(dp(3), dp(8), 0, dp(5))
        }
        dock.addView(selectedToolsTitle)
        toolRow(
            "Posisi / ukuran" to { editSelectedTransform() },
            "Edit teks" to { editSelectedText() },
            "Edit gambar" to { editSelectedImage() },
            "Edit bentuk" to { editSelectedShape() }
        )
        toolRow(
            "Duplikat" to { duplicateSelected() },
            "Urutan layer" to { showLayers() },
            "Kunci" to { toggleLock() },
            "Hapus" to { deleteSelected() }
        )
        toolRow(
            "Grup" to { chooseLayersForGrouping() },
            "Lepas grup" to { ungroupSelected() },
            "Ratakan" to { arrangeSelectedLayers(false) },
            "Sebar" to { arrangeSelectedLayers(true) }
        )
        root.addView(dock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        updateHistoryButtons()
    }

    private fun showAddSourceMenu() {
        val choices = arrayOf("Share Screen", "Kamera", "Audio / sumber eksternal")
        AlertDialog.Builder(this)
            .setTitle("Tambah sumber")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showScreenSourceInfo()
                    1 -> Toast.makeText(this, "Sumber kamera disiapkan sebagai modul berikutnya.", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this, "Sumber audio dikelola melalui Audio Mixer.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showScreenSourceInfo() {
        AlertDialog.Builder(this)
            .setTitle("Share Screen sebagai elemen")
            .setMessage("Share Screen akan menjadi sumber visual tersendiri di dalam scene: dapat diposisikan, diubah ukuran, di-crop, dan diatur urutan layer-nya. Izin MediaProjection Android tetap diminta saat memulai LIVE. Integrasi renderer sumber layar per-layer masih perlu diselesaikan sebelum tombol ini menambahkan layer aktif.")
            .setPositiveButton("Mengerti", null)
            .show()
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
        editor.setScene(document, selected.id)
        streamService?.applySceneDocument(document)
    }

    private fun toggleLock() {
        val selected = editor.selectedLayer() ?: return noSelection()
        checkpointHistory()
        document = document.copy(layers = document.layers.map {
            if (it.id == selected.id) it.copy(locked = !it.locked) else it
        })
        editor.setScene(document, selected.id)
        statusView.text = if (selected.locked) "Layer dibuka." else "Layer dikunci."
        streamService?.applySceneDocument(document)
    }

    private fun deleteSelected() {
        val selected = editor.selectedLayer() ?: return noSelection()
        checkpointHistory()
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
        checkpointHistory()
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
        checkpointHistory()
        document = document.copy(layers = normalized)
        editor.setScene(document, selected.id)
        streamService?.applySceneDocument(document)
        statusView.text = if (direction > 0) "Layer dinaikkan." else "Layer diturunkan."
    }


    private fun editSelectedTransform() {
        val selected = editor.selectedLayer() ?: return noSelection()
        val t = selected.transform
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: String, value: Float): EditText {
            val input = EditText(this).apply {
                hint = label
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(java.util.Locale.US, "%.3f", value))
            }
            panel.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setPadding(0, dp(5), 0, 0)
            })
            panel.addView(input)
            return input
        }
        val x = field("X (0–1 = area canvas)", t.x)
        val y = field("Y (0–1 = area canvas)", t.y)
        val width = field("Lebar (0.001–2)", t.width)
        val height = field("Tinggi (0.001–2)", t.height)
        val rotation = field("Rotasi (derajat)", t.rotationDegrees)
        val opacity = field("Opacity (0–1)", t.opacity)
        AlertDialog.Builder(this)
            .setTitle("Posisi & ukuran presisi")
            .setView(panel)
            .setPositiveButton("Terapkan") { _, _ ->
                val values = listOf(x, y, width, height, rotation, opacity)
                    .map { it.text.toString().trim().toFloatOrNull() }
                if (values.any { it == null }) {
                    Toast.makeText(this, "Semua nilai harus berupa angka.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val v = values.filterNotNull()
                val next = runCatching {
                    SceneTransform(
                        x = v[0], y = v[1], width = v[2], height = v[3],
                        rotationDegrees = v[4], opacity = v[5]
                    )
                }.getOrElse {
                    Toast.makeText(this, "Nilai di luar batas yang diizinkan.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                checkpointHistory()
                document = document.copy(layers = document.layers.map {
                    if (it.id == selected.id) it.copy(transform = next) else it
                })
                editor.setScene(document, selected.id)
                streamService?.applySceneDocument(document)
                statusView.text = "Transform layer diterapkan."
            }
            .setNegativeButton("Batal", null)
            .show()
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

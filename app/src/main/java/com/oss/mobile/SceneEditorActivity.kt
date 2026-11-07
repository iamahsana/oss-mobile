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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Scene editor screen. The canvas is an editing surface, never a second video preview. */
class SceneEditorActivity : Activity() {
    companion object {
        const val EXTRA_SCENE_ID = "scene_editor_id"
        const val EXTRA_SCENE_NAME = "scene_editor_name"
        private const val TEST_IMAGE_MODULE = "oss.source.image.static"
    }

    private lateinit var repository: SceneDocumentRepository
    private lateinit var editor: SceneEditorCanvasView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
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
        header.addView(makeButton("‹", 42) { finish() }, LinearLayout.LayoutParams(dp(46), dp(44)))
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

        editor = SceneEditorCanvasView(this).apply {
            setScene(document)
            onDocumentChanged = { updated ->
                document = updated
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

        val addRow = actionRow()
        addRow.addView(makeButton("＋ Gambar uji", 12) { addTestImage() },
            weightedParams(end = dp(5)))
        addRow.addView(makeButton("Layers", 12) { showLayers() },
            weightedParams(start = dp(5)))
        root.addView(addRow)

        val editRow = actionRow()
        editRow.addView(makeButton("Tampilkan / sembunyikan", 10) { toggleVisibility() },
            weightedParams(end = dp(5)))
        editRow.addView(makeButton("Kunci / buka", 10) { toggleLock() },
            weightedParams(start = dp(5), end = dp(5)))
        editRow.addView(makeButton("Hapus", 10) { deleteSelected() },
            weightedParams(start = dp(5), weight = 0.7f))
        root.addView(editRow)

        val orderRow = actionRow()
        orderRow.addView(makeButton("Duplikat", 11) { duplicateSelected() }, weightedParams(end = dp(5)))
        orderRow.addView(makeButton("Ke depan", 11) { moveSelectedToFront() },
            weightedParams(start = dp(5), end = dp(5)))
        orderRow.addView(makeButton("Ke belakang", 11) { moveSelectedToBack() },
            weightedParams(start = dp(5)))
        root.addView(orderRow)

        setContentView(root)
    }

    private fun actionRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun weightedParams(
        start: Int = 0,
        end: Int = 0,
        weight: Float = 1f
    ) = LinearLayout.LayoutParams(0, dp(44), weight).apply {
        marginStart = start
        marginEnd = end
    }

    private fun addTestImage() {
        val nextZ = (document.layers.maxOfOrNull { it.zIndex } ?: -1) + 1
        val layer = SceneLayer(
            id = "image_${System.currentTimeMillis()}",
            moduleId = TEST_IMAGE_MODULE,
            displayName = "Image ${document.layers.size + 1}",
            zIndex = nextZ,
            transform = SceneTransform(x = 0.12f, y = 0.12f, width = 0.46f, height = 0.129f),
            configuration = mapOf("resource_id" to R.drawable.oss_test_overlay.toString())
        )
        updateDocument(document.copy(layers = document.layers + layer), layer.id)
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
            .setItems(labels) { _, index -> editor.selectLayer(layers[index].id) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun toggleVisibility() {
        val selected = requireSelection() ?: return
        updateDocument(SceneLayerOperations.toggleVisibility(document, selected.id), selected.id)
    }

    private fun toggleLock() {
        val selected = requireSelection() ?: return
        updateDocument(SceneLayerOperations.toggleLock(document, selected.id), selected.id)
        statusView.text = if (selected.locked) "Layer dibuka." else "Layer dikunci."
    }

    private fun duplicateSelected() {
        val selected = requireSelection() ?: return
        val updated = SceneLayerOperations.duplicate(document, selected.id)
        val copy = updated.layers.firstOrNull { it.id !in document.layers.map(SceneLayer::id) }
        if (copy != null) {
            updateDocument(updated, copy.id)
            statusView.text = "Layer berhasil diduplikasi."
        }
    }

    private fun moveSelectedToFront() {
        val selected = requireSelection() ?: return
        updateDocument(SceneLayerOperations.moveToFront(document, selected.id), selected.id)
        statusView.text = "Layer dipindahkan ke depan."
    }

    private fun moveSelectedToBack() {
        val selected = requireSelection() ?: return
        updateDocument(SceneLayerOperations.moveToBack(document, selected.id), selected.id)
        statusView.text = "Layer dipindahkan ke belakang."
    }

    private fun deleteSelected() {
        val selected = requireSelection() ?: return
        updateDocument(SceneLayerOperations.remove(document, selected.id), null)
        statusView.text = "Layer dihapus."
    }

    private fun requireSelection(): SceneLayer? {
        val selected = editor.selectedLayer()
        if (selected == null) Toast.makeText(this, "Pilih layer terlebih dahulu.", Toast.LENGTH_SHORT).show()
        return selected
    }

    private fun updateDocument(updated: SceneDocument, selectedId: String?) {
        document = updated
        editor.setScene(document, selectedId)
        streamService?.applySceneDocument(document)
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

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
        firstRow.addView(makeButton("＋ Gambar uji", 12) { addTestImage() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        firstRow.addView(makeButton("Layers", 12) { showLayers() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
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

        setContentView(root)
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

package com.oss.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val scenes = mutableListOf("Scene 1")
    private var selectedScene = 0
    private lateinit var sceneList: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var streamStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderStudio()
    }

    private fun renderStudio() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 20)
            setBackgroundColor(0xFF101820.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "OSS"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Open Stream Software  •  Studio"
            textSize = 14f
            setTextColor(0xFFB8C7D1.toInt())
        }

        previewLabel = TextView(this).apply {
            text = "PREVIEW\n\nBelum ada sumber\nTambahkan sumber melalui Extensions"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF2F8.toInt())
            setBackgroundColor(0xFF263746.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = 24; bottomMargin = 20 }
        }

        val sceneHeading = TextView(this).apply {
            text = "SCENES"
            textSize = 13f
            setTextColor(0xFF8FD3FF.toInt())
        }
        sceneList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addScene = Button(this).apply {
            text = "＋  Add scene"
            setOnClickListener {
                scenes.add("Scene ${scenes.size + 1}")
                selectedScene = scenes.lastIndex
                refreshScenes()
            }
        }

        streamStatus = TextView(this).apply {
            text = "Streaming engine belum tersedia — tahap integrasi berikutnya."
            textSize = 12f
            setTextColor(0xFFFFD180.toInt())
            setPadding(0, 16, 0, 8)
        }

        val startStream = Button(this).apply {
            text = "Start streaming"
            isEnabled = false
            contentDescription = "RTMP streaming will be enabled after the streaming engine is implemented"
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(previewLabel)
        root.addView(sceneHeading)
        root.addView(sceneList)
        root.addView(addScene)
        root.addView(streamStatus)
        root.addView(startStream)
        setContentView(root)
        refreshScenes()
    }

    private fun refreshScenes() {
        sceneList.removeAllViews()
        scenes.forEachIndexed { index, scene ->
            val button = Button(this).apply {
                text = if (index == selectedScene) "✓  $scene" else scene
                setOnClickListener {
                    selectedScene = index
                    previewLabel.text = "PREVIEW\n\n$scene\nBelum ada sumber"
                    refreshScenes()
                }
            }
            sceneList.addView(button)
        }
        previewLabel.text = "PREVIEW\n\n${scenes[selectedScene]}\nBelum ada sumber"
    }
}

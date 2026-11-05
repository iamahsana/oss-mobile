package com.oss.mobile

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 48, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "OSS"
            textSize = 32f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Open Stream Software"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Studio foundation is ready to grow."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "Start setup"
            isEnabled = false
            contentDescription = "Streaming setup will be added in a later milestone"
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(startButton)
        setContentView(root)
    }
}

package com.example.myapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = "Hello from GitHub Codespaces!"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        setContentView(textView)
    }
}

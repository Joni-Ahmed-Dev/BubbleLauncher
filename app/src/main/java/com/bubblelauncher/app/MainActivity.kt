package com.bubblelauncher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bubblelauncher.app.ui.AppRoot
import com.bubblelauncher.app.ui.BubbleTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_PICKER = "open_picker"
    }

    private val openPicker = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consume(intent)
        setContent { BubbleTheme { AppRoot(openPicker) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_OPEN_PICKER, false) == true) {
            openPicker.value = true
            i.removeExtra(EXTRA_OPEN_PICKER)
        }
    }
}

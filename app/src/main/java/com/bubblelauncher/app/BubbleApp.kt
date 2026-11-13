package com.bubblelauncher.app

import android.app.Application
import com.bubblelauncher.app.data.BubbleStore

class BubbleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BubbleStore.init(this)
    }
}

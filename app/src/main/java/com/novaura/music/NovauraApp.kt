package com.novaura.music

import android.app.Application
import com.novaura.music.util.CrashLogger

class NovauraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
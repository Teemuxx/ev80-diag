package io.github.ev80diag

import android.app.Application
import io.github.ev80diag.calc.SocTracker

class EV80App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        CrashLogger.install(this)
        SocTracker.init(this)
    }
}

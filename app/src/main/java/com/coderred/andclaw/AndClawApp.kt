package com.coderred.andclaw

import android.app.Application
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proot.ProcessManager
import com.coderred.andclaw.proot.ProotManager
import com.coderred.andclaw.proot.SetupManager

class AndClawApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var prootManager: ProotManager
        private set
    lateinit var setupManager: SetupManager
        private set
    lateinit var processManager: ProcessManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        prootManager = ProotManager(this)
        setupManager = SetupManager(this, prootManager)
        processManager = ProcessManager(prootManager)
    }
}

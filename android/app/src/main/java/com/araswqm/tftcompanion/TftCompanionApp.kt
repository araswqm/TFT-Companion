package com.araswqm.tftcompanion

import android.app.Application
import com.araswqm.tftcompanion.data.SettingsRepository

class TftCompanionApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }
}

package com.example

import android.app.Application
import com.example.data.local.NekoDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.NekoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NekoXrayApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { NekoDatabase.getInstance(this) }
    val settingsStore by lazy { SettingsDataStore(this) }
    val repository by lazy { NekoRepository(database, settingsStore, applicationContext) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            com.example.core.xray.XrayAssetHelper.ensureInitialized(this@NekoXrayApp)
            repository.ensureDefaults()
            com.example.core.widget.NekoWidgetManager.refreshAll(this@NekoXrayApp)
        }
    }
}

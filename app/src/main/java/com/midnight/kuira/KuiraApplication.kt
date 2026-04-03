package com.midnight.kuira

import android.app.Application
import com.midnight.kuira.service.ConnectorService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KuiraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ConnectorService.start(this)
    }
}

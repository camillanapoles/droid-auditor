package com.camillanapoles.droidauditor

import android.app.Application
import com.camillanapoles.droidauditor.di.AppContainer

class AuditApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

package com.trading.app

import android.app.Application
import com.trading.app.di.AppContainer

class TradingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

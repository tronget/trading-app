package com.trading.app.di

import android.content.Context
import com.trading.app.BuildConfig
import com.trading.app.data.ApiClient
import com.trading.app.data.AuthRepository
import com.trading.app.data.AuthRepositoryImpl
import com.trading.app.data.MarketRepository
import com.trading.app.data.MarketRepositoryImpl
import com.trading.app.data.TokenStore
import com.trading.app.data.TradingRepository
import com.trading.app.data.TradingRepositoryImpl

/** Ручной DI-контейнер: один экземпляр на процесс (хранится в Application). */
class AppContainer(context: Context) {
    val tokenStore = TokenStore(context.applicationContext)
    private val api = ApiClient(BuildConfig.GATEWAY_URL, tokenStore)

    val authRepository: AuthRepository = AuthRepositoryImpl(api, tokenStore)
    val marketRepository: MarketRepository = MarketRepositoryImpl(api, tokenStore)
    val tradingRepository: TradingRepository = TradingRepositoryImpl(api)
}

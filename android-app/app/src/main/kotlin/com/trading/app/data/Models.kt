package com.trading.app.data

import kotlinx.serialization.Serializable

@Serializable
data class CredentialsRequest(val login: String, val password: String)

@Serializable
data class RegisterResponse(val userId: Long, val accessToken: String, val refreshToken: String)

@Serializable
data class LoginResponse(val accessToken: String, val refreshToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AccessTokenResponse(val accessToken: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class QuoteItem(
    val symbol: String,
    val name: String,
    val last: Double? = null,
    val changePct: Double? = null,
    val volume: Long? = null,
)

@Serializable
data class Tick(
    val symbol: String,
    val price: Double,
    val ts: Long,
    val volume: Int = 0,
    val seq: Long = 0,
)

@Serializable
data class Candle(
    val t: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

@Serializable
data class SubscribeCommand(val action: String, val symbols: List<String>)

// Money-поля приходят строками (NUMERIC без потери точности) — конвертируем на UI.
@Serializable
data class Position(
    val symbol: String,
    val qty: String,
    val avgPrice: String,
    val last: String? = null,
    val pnl: String? = null,
)

@Serializable
data class Portfolio(
    val userId: Long,
    val currency: String,
    val cash: String,
    val positions: List<Position>,
    val totalValue: String,
)

@Serializable
data class Order(
    val id: Long,
    val userId: Long,
    val symbol: String,
    val side: String,
    val type: String,
    val qty: String,
    val price: String? = null,
    val filledQty: String,
    val status: String,
    val clientOrderId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Trade(
    val id: Long,
    val orderId: Long,
    val userId: Long,
    val symbol: String,
    val side: String,
    val qty: String,
    val price: String,
    val fee: String,
    val executedAt: String,
)

@Serializable
data class PlaceOrderRequest(
    val symbol: String,
    val side: String,
    val type: String,
    val qty: String,
    val price: String? = null,
    val clientOrderId: String? = null,
)

package com.trading.app.ui

/** Человекочитаемые подписи для серверных кодов ордеров/сделок. */

fun sideLabel(side: String): String = when (side.uppercase()) {
    "BUY" -> "Покупка"
    "SELL" -> "Продажа"
    else -> side
}

fun orderTypeLabel(type: String): String = when (type.uppercase()) {
    "MARKET" -> "Рыночный"
    "LIMIT" -> "Лимитный"
    else -> type
}

fun orderStatusLabel(status: String): String = when (status.uppercase()) {
    "NEW" -> "Активен"
    "PARTIALLY_FILLED" -> "Частично исполнен"
    "FILLED" -> "Исполнен"
    "REJECTED" -> "Отклонён"
    "CANCELLED" -> "Отменён"
    else -> status
}

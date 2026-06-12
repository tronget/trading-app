package trading.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

/**
 * Денежные значения сериализуются строкой, чтобы не терять точность
 * (JSON-числа двоичные, NUMERIC(18,6) в них не помещается без округления).
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) =
        encoder.encodeString(value.stripTrailingZeros().toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal(decoder.decodeString())
}

typealias Money = @Serializable(with = BigDecimalSerializer::class) BigDecimal

enum class Side { BUY, SELL }
enum class OrderType { MARKET, LIMIT }
enum class OrderStatus { NEW, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED }

@Serializable
data class CreateUserRequest(val login: String, val passwordHash: String)

@Serializable
data class UserDto(val id: Long, val login: String, val createdAt: String)

/** Отдаётся только Gateway для проверки пароля при логине. */
@Serializable
data class UserWithHashDto(val id: Long, val login: String, val passwordHash: String)

@Serializable
data class InstrumentDto(val id: Long, val symbol: String, val name: String, val devIdx: Int)

@Serializable
data class PlaceOrderRequest(
    val userId: Long,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val qty: Money,
    val price: Money? = null,
    val clientOrderId: String? = null,
)

@Serializable
data class OrderDto(
    val id: Long,
    val userId: Long,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val qty: Money,
    val price: Money?,
    val filledQty: Money,
    val status: OrderStatus,
    val clientOrderId: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TradeDto(
    val id: Long,
    val orderId: Long,
    val userId: Long,
    val symbol: String,
    val side: Side,
    val qty: Money,
    val price: Money,
    val fee: Money,
    val executedAt: String,
)

@Serializable
data class PositionDto(
    val symbol: String,
    val qty: Money,
    val avgPrice: Money,
    val last: Money? = null,
    val pnl: Money? = null,
)

@Serializable
data class PortfolioDto(
    val userId: Long,
    val currency: String,
    val cash: Money,
    val positions: List<PositionDto>,
    val totalValue: Money,
)

@Serializable
data class ErrorResponse(val error: String)

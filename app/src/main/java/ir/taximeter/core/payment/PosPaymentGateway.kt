package ir.taximeter.core.payment

/**
 * Vendor-neutral boundary for requesting a card payment from a Bluetooth POS.
 *
 * IMPORTANT: This is an integration contract, not a generic POS protocol. Iranian
 * payment terminals commonly require a vendor/acquirer SDK or a documented protocol.
 * Implement this interface only with the selected terminal's official SDK/protocol.
 * Never report payment success merely because a Bluetooth socket was opened or data
 * was written; return Approved only after receiving and validating the terminal's
 * authoritative approval response.
 */
data class PosPaymentRequest(
    /** Unique idempotency/reference value for this payment attempt. */
    val requestId: String,
    /** Passenger trip identifier for audit and reconciliation. */
    val tripId: String?,
    /** The app's business currency is toman. Adapter must convert units only as required by vendor docs. */
    val amountToman: Long
)

sealed class PosPaymentResult {
    data class Approved(val terminalReference: String? = null) : PosPaymentResult()
    data class Declined(val message: String) : PosPaymentResult()
    data class Failed(val message: String) : PosPaymentResult()
    data class NotConfigured(val message: String) : PosPaymentResult()
}

interface PosPaymentGateway {
    /** Initiate one charge. This method must not return Approved without terminal confirmation. */
    suspend fun charge(request: PosPaymentRequest): PosPaymentResult
}

/** Safe default until the exact POS model and its official integration contract are known. */
class UnconfiguredBluetoothPosGateway : PosPaymentGateway {
    override suspend fun charge(request: PosPaymentRequest): PosPaymentResult =
        PosPaymentResult.NotConfigured(
            "درگاه پوز هنوز به SDK یا پروتکل رسمی دستگاه متصل نشده است؛ هیچ مبلغی ارسال نشده و پرداختی انجام نشده است."
        )
}


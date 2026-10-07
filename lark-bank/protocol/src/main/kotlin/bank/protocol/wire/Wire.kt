package bank.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The bank's wire and journal shapes (bank spec 0005): the source bank.proto is generated from. Each mirrors a domain
 * type case for case and name for name, with ids as the String they hold, and kimney derives the
 * mapping both ways. Field numbers are declaration order, so a field is added at the end and never moved: the pinned
 * bank.proto shows any change. A @SerialName only renames a message in bank.proto, where two would share a name.
 */

/** An amount, as a plain decimal at its currency's places, and its currency (bank specs 0011 and 0016). */
@Serializable
data class Money(val amount: String, val currency: Currency)

/** A currency as the ledger holds it: its code and its places, so an amount never depends on today's registry. */
@Serializable
data class Currency(val code: String, val exponent: Int)

sealed interface AccountCommand {
    @Serializable
    data class Open(val owner: String, val initial: Money, val reference: String) : AccountCommand

    @Serializable
    data class Deposit(val amount: Money, val reference: String) : AccountCommand

    @Serializable
    data class Withdraw(val amount: Money, val reference: String) : AccountCommand

    @Serializable
    data class Debit(val transfer: String, val amount: Money) : AccountCommand

    @Serializable
    data class Credit(val transfer: String, val amount: Money) : AccountCommand

    @Serializable
    data class Refund(val transfer: String, val amount: Money) : AccountCommand

    @Serializable
    @SerialName("CloseLegs")
    data class Close(val transfer: String) : AccountCommand
}

/** A balance enquiry: an account request that changes nothing. */
@Serializable
data object GetBalance

sealed interface AccountEvent {
    @Serializable
    data class Opened(val owner: String, val initial: Money, val reference: String, val atMillis: Long) : AccountEvent

    @Serializable
    data class Deposited(val amount: Money, val reference: String, val atMillis: Long) : AccountEvent

    @Serializable
    data class Withdrawn(val amount: Money, val reference: String, val atMillis: Long) : AccountEvent

    @Serializable
    data class Debited(val transfer: String, val amount: Money, val atMillis: Long) : AccountEvent

    @Serializable
    data class Credited(val transfer: String, val amount: Money, val atMillis: Long) : AccountEvent

    @Serializable
    data class Refunded(val transfer: String, val amount: Money, val atMillis: Long) : AccountEvent

    @Serializable
    data class LegsClosed(val transfer: String, val atMillis: Long) : AccountEvent
}

sealed interface Account {
    @Serializable
    @SerialName("UnopenedAccount")
    data object Unopened : Account

    @Serializable
    @SerialName("OpenAccount")
    data class Open(val owner: String, val balance: Money, val recent: List<String>, val legs: Set<String>) : Account
}

@Serializable
data class Balance(val id: String, val owner: String, val balance: Money)

sealed interface AccountError {
    @Serializable
    data class NoSuchAccount(val id: String) : AccountError

    @Serializable
    data class AlreadyOpen(val id: String) : AccountError

    @Serializable
    data class InsufficientFunds(val id: String, val balance: Money, val requested: Money) : AccountError

    @Serializable
    data class InvalidAmount(val amount: Money) : AccountError

    @Serializable
    data class CurrencyMismatch(val id: String, val account: String, val given: String) : AccountError

    @Serializable
    @SerialName("AccountUnavailable")
    data class Unavailable(val message: String) : AccountError
}

@Serializable
data class BulkCredit(val amount: Money, val reference: String)

sealed interface TransferRequest {
    @Serializable
    @SerialName("StartTransfer")
    data class Start(val from: String, val to: String, val amount: Money) : TransferRequest

    @Serializable
    @SerialName("GetTransfer")
    data object Status : TransferRequest
}

sealed interface TransferEvent {
    @Serializable
    data class Requested(val from: String, val to: String, val amount: Money, val atMillis: Long) : TransferEvent

    @Serializable
    data class SourceDebited(val atMillis: Long) : TransferEvent

    @Serializable
    @SerialName("TransferRejected")
    data class Rejected(val reason: String, val atMillis: Long) : TransferEvent

    @Serializable
    data class DestinationCredited(val atMillis: Long) : TransferEvent

    @Serializable
    data class CreditRefused(val reason: String, val atMillis: Long) : TransferEvent

    @Serializable
    data class SourceRefunded(val atMillis: Long) : TransferEvent

    @Serializable
    data class Screened(
        val outcome: ScreeningOutcome,
        val rule: String?,
        val version: Int?,
        val evidence: String,
        val answered: Boolean,
        val atMillis: Long,
    ) : TransferEvent
}

/** Named apart from any message of the same word, as TransferStatus's values are. */
@Serializable
enum class ScreeningOutcome {
    @SerialName("SCREENING_APPROVED")
    Approved,

    @SerialName("SCREENING_DECLINED")
    Declined,
}

/** Its values are named apart from the messages of the same word, since a proto enum's values share its package. */
@Serializable
enum class TransferStatus {
    @SerialName("STATUS_PENDING")
    Pending,

    @SerialName("STATUS_DEBITED")
    Debited,

    @SerialName("STATUS_COMPLETED")
    Completed,

    @SerialName("STATUS_REJECTED")
    Rejected,

    @SerialName("STATUS_REFUNDING")
    Refunding,

    @SerialName("STATUS_REFUNDED")
    Refunded,
}

@Serializable
data class TransferView(
    val id: String,
    val from: String,
    val to: String,
    val amount: Money,
    val status: TransferStatus,
    val reason: String?,
)

sealed interface TransferError {
    @Serializable
    data class SameAccount(val id: String) : TransferError

    @Serializable
    data class InvalidTransferAmount(val amount: Money) : TransferError

    @Serializable
    data class NoSuchTransfer(val id: String) : TransferError

    @Serializable
    data class TransferIdReused(val id: String) : TransferError

    @Serializable
    @SerialName("TransferUnavailable")
    data class Unavailable(val message: String) : TransferError
}


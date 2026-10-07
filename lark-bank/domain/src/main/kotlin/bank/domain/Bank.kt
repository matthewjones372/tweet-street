package bank.domain

import arrow.core.Either

/** The bank as the HTTP layer sees it. The app answers it with sharded actors; a test with a map. */
interface Bank {
    fun open(id: AccountId, owner: String, initial: Money, reference: String): Either<AccountError, Balance>
    fun deposit(id: AccountId, amount: Money, reference: String): Either<AccountError, Balance>
    fun withdraw(id: AccountId, amount: Money, reference: String): Either<AccountError, Balance>
    fun balance(id: AccountId): Either<AccountError, Balance>

    /** Starts the saga and waits up to the bank's own limit for it to settle; a slow one answers still pending. */
    fun transfer(id: TransferId, from: AccountId, to: AccountId, amount: Money): Either<TransferError, TransferView>
    fun transferStatus(id: TransferId): Either<TransferError, TransferView>
}

data class TransferView(
    val id: String,
    val from: String,
    val to: String,
    val amount: Money,
    val status: TransferStatus,
    val reason: String?,
)

fun Transfer.Moving.view(id: TransferId) = TransferView(id.value, from.value, to.value, amount, status, reason)

package bank.protocol

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Currencies
import bank.domain.Money
import bank.domain.ScreeningOutcome
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.events.v1.AccountEvent as AccountRecord
import bank.events.v1.AccountEvent.EventCase as AccountCase
import bank.events.v1.Credited
import bank.events.v1.CreditRefused
import bank.events.v1.Debited
import bank.events.v1.Deposited
import bank.events.v1.DestinationCredited
import bank.events.v1.LegsClosed
import bank.events.v1.Money as MoneyRecord
import bank.events.v1.Opened
import bank.events.v1.Refunded
import bank.events.v1.Rejected
import bank.events.v1.Requested
import bank.events.v1.Screened
import bank.events.v1.ScreeningOutcome as ScreeningOutcomeRecord
import bank.events.v1.SourceDebited
import bank.events.v1.SourceRefunded
import bank.events.v1.TransferEvent as TransferRecord
import bank.events.v1.TransferEvent.EventCase as TransferCase
import bank.events.v1.Withdrawn

/*
 * The bank's events as the contract other services read (bank spec 0015, lark-bank-events): each domain event to its
 * Protobuf message, and back. The journal's own encoding (Codecs.kt) changes on its own; this one only ever grows.
 */

/** An account's event as published: [sequence] is its place in account [account], from 1. */
fun AccountEvent.toContract(account: AccountId, sequence: Long): AccountRecord {
    val record = AccountRecord.newBuilder().setAccountId(account.value).setSequence(sequence).setAtMillis(atMillis)
    when (this) {
        is AccountEvent.Opened ->
            record.setOpened(Opened.newBuilder().setOwner(owner).setInitial(initial.toContract()).setReference(reference))
        is AccountEvent.Deposited -> record.setDeposited(Deposited.newBuilder().setAmount(amount.toContract()).setReference(reference))
        is AccountEvent.Withdrawn -> record.setWithdrawn(Withdrawn.newBuilder().setAmount(amount.toContract()).setReference(reference))
        is AccountEvent.Debited -> record.setDebited(Debited.newBuilder().setTransferId(transfer.value).setAmount(amount.toContract()))
        is AccountEvent.Credited -> record.setCredited(Credited.newBuilder().setTransferId(transfer.value).setAmount(amount.toContract()))
        is AccountEvent.Refunded -> record.setRefunded(Refunded.newBuilder().setTransferId(transfer.value).setAmount(amount.toContract()))
        is AccountEvent.LegsClosed -> record.setLegsClosed(LegsClosed.newBuilder().setTransferId(transfer.value))
    }
    return record.build()
}

/**
 * A transfer's event as published, with both its accounts on every event: [sequence] is its place in transfer
 * [transfer], from 1, and [from] and [to] are what its `Requested` named.
 */
fun TransferEvent.toContract(transfer: TransferId, sequence: Long, from: AccountId, to: AccountId): TransferRecord {
    val record = TransferRecord.newBuilder().setTransferId(transfer.value).setSequence(sequence).setAtMillis(atMillis)
        .setFromAccount(from.value).setToAccount(to.value)
    when (this) {
        is TransferEvent.Requested -> record.setRequested(Requested.newBuilder().setAmount(amount.toContract()))
        is TransferEvent.SourceDebited -> record.setSourceDebited(SourceDebited.getDefaultInstance())
        is TransferEvent.Rejected -> record.setRejected(Rejected.newBuilder().setReason(reason))
        is TransferEvent.DestinationCredited -> record.setDestinationCredited(DestinationCredited.getDefaultInstance())
        is TransferEvent.CreditRefused -> record.setCreditRefused(CreditRefused.newBuilder().setReason(reason))
        is TransferEvent.SourceRefunded -> record.setSourceRefunded(SourceRefunded.getDefaultInstance())
        is TransferEvent.Screened -> record.setScreened(toContract())
    }
    return record.build()
}

private fun TransferEvent.Screened.toContract(): Screened {
    val screened = Screened.newBuilder().setEvidence(evidence).setAnswered(answered).setOutcome(
        when (outcome) {
            ScreeningOutcome.Approved -> ScreeningOutcomeRecord.SCREENING_OUTCOME_APPROVED
            ScreeningOutcome.Declined -> ScreeningOutcomeRecord.SCREENING_OUTCOME_DECLINED
        },
    )
    rule?.let(screened::setRule)
    version?.let(screened::setVersion)
    return screened.build()
}

/** Plain, at the currency's scale, which a [Money] always holds: "10.50", never "1.05E+1". */
fun Money.toContract(): MoneyRecord = MoneyRecord.newBuilder().setCurrency(currency.code).setAmount(amount.toPlainString()).build()

/** A published event read back: its id, its place in that id, and the event. */
data class FromContract<out E>(val id: String, val sequence: Long, val event: E)

/** Why a published event could not be read back as the bank's own. */
sealed interface ContractError {
    val message: String

    /** A case added after this build: a consumer skips it (lark-bank-events' README). */
    data class Unknown(val id: String, val sequence: Long) : ContractError {
        override val message: String get() = "event $sequence of $id is of a kind this build does not know"
    }

    data class Unreadable(override val message: String) : ContractError
}

fun AccountRecord.fromContract(currencies: Currencies): Either<ContractError, FromContract<AccountEvent>> = either {
    val event = when (eventCase) {
        AccountCase.OPENED -> AccountEvent.Opened(opened.owner, money(opened.initial, currencies), opened.reference, atMillis)
        AccountCase.DEPOSITED -> AccountEvent.Deposited(money(deposited.amount, currencies), deposited.reference, atMillis)
        AccountCase.WITHDRAWN -> AccountEvent.Withdrawn(money(withdrawn.amount, currencies), withdrawn.reference, atMillis)
        AccountCase.DEBITED -> AccountEvent.Debited(TransferId(debited.transferId), money(debited.amount, currencies), atMillis)
        AccountCase.CREDITED -> AccountEvent.Credited(TransferId(credited.transferId), money(credited.amount, currencies), atMillis)
        AccountCase.REFUNDED -> AccountEvent.Refunded(TransferId(refunded.transferId), money(refunded.amount, currencies), atMillis)
        AccountCase.LEGS_CLOSED -> AccountEvent.LegsClosed(TransferId(legsClosed.transferId), atMillis)
        AccountCase.EVENT_NOT_SET, null -> null
    }
    FromContract(accountId, sequence, ensureNotNull(event) { ContractError.Unknown(accountId, sequence) })
}

fun TransferRecord.fromContract(currencies: Currencies): Either<ContractError, FromContract<TransferEvent>> = either {
    val event = when (eventCase) {
        TransferCase.REQUESTED ->
            TransferEvent.Requested(AccountId(fromAccount), AccountId(toAccount), money(requested.amount, currencies), atMillis)
        TransferCase.SOURCE_DEBITED -> TransferEvent.SourceDebited(atMillis)
        TransferCase.REJECTED -> TransferEvent.Rejected(rejected.reason, atMillis)
        TransferCase.DESTINATION_CREDITED -> TransferEvent.DestinationCredited(atMillis)
        TransferCase.CREDIT_REFUSED -> TransferEvent.CreditRefused(creditRefused.reason, atMillis)
        TransferCase.SOURCE_REFUNDED -> TransferEvent.SourceRefunded(atMillis)
        TransferCase.SCREENED -> screened(screened, atMillis)
        TransferCase.EVENT_NOT_SET, null -> null
    }
    FromContract(transferId, sequence, ensureNotNull(event) { ContractError.Unknown(transferId, sequence) })
}

private fun arrow.core.raise.Raise<ContractError>.screened(record: Screened, atMillis: Long): TransferEvent.Screened {
    val outcome = when (record.outcome) {
        ScreeningOutcomeRecord.SCREENING_OUTCOME_APPROVED -> ScreeningOutcome.Approved
        ScreeningOutcomeRecord.SCREENING_OUTCOME_DECLINED -> ScreeningOutcome.Declined
        ScreeningOutcomeRecord.SCREENING_OUTCOME_UNSPECIFIED, ScreeningOutcomeRecord.UNRECOGNIZED, null ->
            raise(ContractError.Unreadable("a screening decision with no outcome it can read"))
    }
    return TransferEvent.Screened(
        outcome,
        if (record.hasRule()) record.rule else null,
        if (record.hasVersion()) record.version else null,
        record.evidence,
        record.answered,
        atMillis,
    )
}

private fun arrow.core.raise.Raise<ContractError>.money(record: MoneyRecord, currencies: Currencies): Money {
    val currency = currencies.of(record.currency).mapLeft { ContractError.Unreadable(it.message) }.bind()
    return Money.parse(record.amount, currency).mapLeft { ContractError.Unreadable(it.message) }.bind()
}

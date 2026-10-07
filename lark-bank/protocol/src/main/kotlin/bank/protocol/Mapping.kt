package bank.protocol

import bank.domain.Account
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountEvent
import bank.domain.Balance
import bank.domain.Currency
import bank.domain.Money
import bank.domain.TransferError
import bank.domain.TransferEvent
import bank.domain.TransferView
import io.github.matthewjones372.kimney.Transformer
import io.github.matthewjones372.kimney.into
import java.math.BigDecimal
import bank.protocol.wire.Account as WireAccount
import bank.protocol.wire.AccountCommand as WireAccountCommand
import bank.protocol.wire.AccountError as WireAccountError
import bank.protocol.wire.AccountEvent as WireAccountEvent
import bank.protocol.wire.Balance as WireBalance
import bank.protocol.wire.BulkCredit as WireBulkCredit
import bank.protocol.wire.Currency as WireCurrency
import bank.protocol.wire.Money as WireMoney
import bank.protocol.wire.TransferError as WireTransferError
import bank.protocol.wire.TransferEvent as WireTransferEvent
import bank.protocol.wire.TransferRequest as WireTransferRequest
import bank.protocol.wire.TransferView as WireTransferView

// Every crossing between the domain and its wire shape, derived by kimney at compile time (bank spec 0005). A case or
// a field added on one side and not the other stops the build here. Money is the one pair written by hand: a
// BigDecimal crosses as its plain decimal string (bank spec 0016).

private val moneyToWire = Transformer<Money, WireMoney> { money ->
    WireMoney(money.amount.toPlainString(), WireCurrency(money.currency.code, money.currency.exponent))
}

private val moneyFromWire = Transformer<WireMoney, Money> { wire ->
    Money(BigDecimal(wire.amount), Currency(wire.currency.code, wire.currency.exponent))
}

internal fun AccountCommand.toWire(): WireAccountCommand =
    into<_, WireAccountCommand>().withTransformer(moneyToWire).transform()

internal fun WireAccountCommand.toDomain(): AccountCommand =
    into<_, AccountCommand>().withTransformer(moneyFromWire).transform()

internal fun AccountEvent.toWire(): WireAccountEvent =
    into<_, WireAccountEvent>().withTransformer(moneyToWire).transform()

internal fun WireAccountEvent.toDomain(): AccountEvent =
    into<_, AccountEvent>().withTransformer(moneyFromWire).transform()

internal fun Account.toWire(): WireAccount = into<_, WireAccount>().withTransformer(moneyToWire).transform()

internal fun WireAccount.toDomain(): Account = into<_, Account>().withTransformer(moneyFromWire).transform()

internal fun Balance.toWire(): WireBalance = into<_, WireBalance>().withTransformer(moneyToWire).transform()

internal fun WireBalance.toDomain(): Balance = into<_, Balance>().withTransformer(moneyFromWire).transform()

internal fun AccountError.toWire(): WireAccountError =
    into<_, WireAccountError>().withTransformer(moneyToWire).transform()

internal fun WireAccountError.toDomain(): AccountError =
    into<_, AccountError>().withTransformer(moneyFromWire).transform()

internal fun BulkCredit.toWire(): WireBulkCredit = into<_, WireBulkCredit>().withTransformer(moneyToWire).transform()

internal fun WireBulkCredit.toDomain(): BulkCredit = into<_, BulkCredit>().withTransformer(moneyFromWire).transform()

internal fun TransferRequest.toWire(): WireTransferRequest =
    into<_, WireTransferRequest>().withTransformer(moneyToWire).transform()

internal fun WireTransferRequest.toDomain(): TransferRequest =
    into<_, TransferRequest>().withTransformer(moneyFromWire).transform()

internal fun TransferEvent.toWire(): WireTransferEvent =
    into<_, WireTransferEvent>().withTransformer(moneyToWire).transform()

internal fun WireTransferEvent.toDomain(): TransferEvent =
    into<_, TransferEvent>().withTransformer(moneyFromWire).transform()

internal fun TransferView.toWire(): WireTransferView =
    into<_, WireTransferView>().withTransformer(moneyToWire).transform()

internal fun WireTransferView.toDomain(): TransferView =
    into<_, TransferView>().withTransformer(moneyFromWire).transform()

internal fun TransferError.toWire(): WireTransferError =
    into<_, WireTransferError>().withTransformer(moneyToWire).transform()

internal fun WireTransferError.toDomain(): TransferError =
    into<_, TransferError>().withTransformer(moneyFromWire).transform()

package bank.app

import arrow.core.Either
import bank.domain.AccountId
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.Unavailable
import bank.protocol.AccountMessage
import bank.protocol.TransferAsk
import bank.protocol.TransferMessage
import bank.protocol.TransferRequest
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** A saga whose start was lost on the way: it never answers a start, and has not heard of the transfer. */
private object LostStart : ActorRef<TransferMessage> {
    override val address = Address("here", "/lost", 0)

    override fun tell(message: TransferMessage) {
        if (message is TransferAsk && message.request == TransferRequest.Status) {
            message.reply(Either.Left(TransferError.NoSuchTransfer("t-lost")))
        }
    }
}

class ShardedBankSpec {

    @Test
    fun `a start that timed out and never landed is unavailable, to send again, and not a missing transfer`() {
        val bank = ShardedBank(
            accounts = { error("no account is asked") },
            transfers = { LostStart },
            askTimeout = 200.milliseconds,
            transferWait = 100.milliseconds,
        )
        bank.transfer(TransferId("t-lost"), AccountId("a"), AccountId("b"), gbp(5))
            .shouldBeLeft()
            .shouldBeInstanceOf<Unavailable>()
    }
}

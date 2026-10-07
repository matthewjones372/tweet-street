package bank.app

import bank.api.AccountView
import bank.api.Caller
import bank.api.Books
import bank.api.Ledger
import bank.api.ReadModels
import bank.api.StatementLine
import bank.api.TransferCounts
import bank.api.toAmount
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Money
import bank.domain.Transfer
import bank.domain.TransferEvent
import bank.domain.TransferStatus
import bank.domain.evolve
import java.math.BigDecimal
import java.sql.Connection
import javax.sql.DataSource

/** One event a projection writes: whose, its sequence there, and the event. */
data class Sequenced<out E>(val id: String, val sequence: Long, val event: E)

/**
 * The read models, in Postgres. Every writer takes a whole batch in one transaction, and skips an event whose
 * sequence its row has already reached, so a batch handed over twice — after a crash, or a singleton moving —
 * changes nothing the second time.
 */
class JdbcReadModels(private val data: DataSource) : ReadModels {

    /**
     * Statements, balances and the ledger's totals, from a batch of account events. Answers how many were new.
     *
     * Partitions write at once (spec 0014), and after a range of slices moves between databases (lark spec 0105) two
     * of them can hold the same account's events. So what counts as new is decided by the statement line's key, not
     * by the balance read first: the totals add only the lines this batch inserted, a balance is only ever moved
     * forward, and the totals' rows are written in one order, so two batches never wait on each other in a circle.
     */
    fun project(batch: List<Sequenced<AccountEvent>>): Int = transaction { connection ->
        val ids = batch.map { it.id }.distinct()
        val known = connection.balances(ids)
        // Only events that move money reach the statements and the ledger: a transfer's legs closing moves none.
        val fresh = batch.filter { it.sequence > (known[it.id]?.lastSeq ?: 0) && it.event.amount() != null }
        if (fresh.isEmpty()) return@transaction 0

        val reached = known.toMutableMap()
        val inserted = connection.prepareStatement(
            "insert into statement_line (account_id, seq_nr, kind, currency, exponent, amount, balance, reference, at_millis) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
        ).use { insert ->
            fresh.forEach { (id, sequence, event) ->
                val amount = checkNotNull(event.amount())
                val before = reached[id]
                val balance = (before?.balance ?: Money.zero(amount.currency)) + event.delta()
                reached[id] = Row(before?.owner ?: (event as? AccountEvent.Opened)?.owner.orEmpty(), balance, sequence)
                insert.setString(1, id)
                insert.setLong(2, sequence)
                insert.setString(3, event.kind())
                insert.setCurrency(4, amount.currency)
                insert.setBigDecimal(6, amount.amount)
                insert.setBigDecimal(7, balance.amount)
                insert.setString(8, event.reference)
                insert.setLong(9, event.atMillis)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        // A line another writer inserted first is its to count: 0 rows here.
        val totals = sortedMapOf<String, Pair<Currency, Totals>>()
        fresh.filterIndexed { at, _ -> inserted[at] > 0 }.forEach { (_, _, event) ->
            val currency = checkNotNull(event.amount()).currency
            val (_, sum) = totals[currency.code] ?: (currency to Totals())
            totals[currency.code] = currency to sum + event.totals()
        }
        connection.prepareStatement(
            "insert into account_balance (account_id, owner, currency, exponent, balance, last_seq) values (?, ?, ?, ?, ?, ?) " +
                "on conflict (account_id) do update set balance = excluded.balance, last_seq = excluded.last_seq " +
                "where account_balance.last_seq < excluded.last_seq",
        ).use { upsert ->
            fresh.map { it.id }.distinct().sorted().forEach { id ->
                val row = reached.getValue(id)
                upsert.setString(1, id)
                upsert.setString(2, row.owner)
                upsert.setCurrency(3, row.balance.currency)
                upsert.setBigDecimal(5, row.balance.amount)
                upsert.setLong(6, row.lastSeq)
                upsert.addBatch()
            }
            upsert.executeBatch()
        }
        connection.prepareStatement(
            "insert into ledger_totals (currency, exponent, paid_in, paid_out, in_flight, events) values (?, ?, ?, ?, ?, ?) " +
                "on conflict (currency) do update set paid_in = ledger_totals.paid_in + excluded.paid_in, " +
                "paid_out = ledger_totals.paid_out + excluded.paid_out, in_flight = ledger_totals.in_flight + excluded.in_flight, " +
                "events = ledger_totals.events + excluded.events",
        ).use { upsert ->
            totals.values.forEach { (currency, moved) ->
                upsert.setCurrency(1, currency)
                upsert.setBigDecimal(3, moved.paidIn.setScale(currency.exponent))
                upsert.setBigDecimal(4, moved.paidOut.setScale(currency.exponent))
                upsert.setBigDecimal(5, moved.inFlight.setScale(currency.exponent))
                upsert.setLong(6, moved.events)
                upsert.addBatch()
            }
            if (totals.isNotEmpty()) upsert.executeBatch()
        }
        inserted.count { it > 0 }
    }

    /** Each transfer's state, so the sweeper can find the ones that stopped moving. */
    fun projectTransfers(batch: List<Sequenced<TransferEvent>>): Int = transaction { connection ->
        val ids = batch.map { it.id }.distinct()
        val known = connection.transfers(ids)
        val fresh = batch.filter { it.sequence > (known[it.id]?.second ?: 0) }
        if (fresh.isEmpty()) return@transaction 0
        val reached = known.mapValues { (_, row) -> row.first }.toMutableMap()
        val last = mutableMapOf<String, Pair<Long, Long>>()
        fresh.forEach { (id, sequence, event) ->
            reached[id] = (reached[id] ?: Transfer.Unrequested).evolve(event)
            last[id] = sequence to event.atMillis
        }
        connection.prepareStatement(
            "insert into transfer_status (transfer_id, from_account, to_account, currency, exponent, amount, status, settled, " +
                "last_seq, updated_millis) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (transfer_id) do update set status = excluded.status, " +
                "settled = excluded.settled, last_seq = excluded.last_seq, updated_millis = excluded.updated_millis " +
                "where transfer_status.last_seq < excluded.last_seq",
        ).use { upsert ->
            last.toSortedMap().forEach { (id, at) ->
                val moving = reached.getValue(id) as Transfer.Moving
                upsert.setString(1, id)
                upsert.setString(2, moving.from.value)
                upsert.setString(3, moving.to.value)
                upsert.setCurrency(4, moving.amount.currency)
                upsert.setBigDecimal(6, moving.amount.amount)
                upsert.setString(7, moving.status.name)
                upsert.setBoolean(8, moving.settled)
                upsert.setLong(9, at.first)
                upsert.setLong(10, at.second)
                upsert.addBatch()
            }
            upsert.executeBatch()
        }
        fresh.size
    }

    /** Transfers that have not settled and have not moved since [before]: what the sweeper nudges. */
    fun stuck(before: Long, limit: Int): List<String> = data.connection.use { connection ->
        connection.prepareStatement(
            "select transfer_id from transfer_status where settled = false and updated_millis < ? order by updated_millis limit ?",
        ).use { query ->
            query.setLong(1, before)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }

    override fun accountsOf(who: Caller): List<AccountView> = seenBy(who) { connection ->
        connection.prepareStatement(
            "select account_id, owner, currency, exponent, balance from account_balance where owner = ? order by account_id",
        ).use { query ->
            query.setString(1, who.actingAs)
            query.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) null
                    else AccountView(
                        rows.getString(1), rows.getString(2),
                        Money(rows.getBigDecimal(5), Currency(rows.getString(3), rows.getInt(4))).toAmount(),
                    )
                }.toList()
            }
        }
    }

    override fun statement(who: Caller, account: String, limit: Int, before: Long?): List<StatementLine> = seenBy(who) { connection ->
        connection.prepareStatement(
            "select seq_nr, kind, currency, exponent, amount, balance, reference, at_millis from statement_line where account_id = ? " +
                "and seq_nr < ? order by seq_nr desc limit ?",
        ).use { query ->
            query.setString(1, account)
            // No cursor is the newest page: every sequence number is below Long.MAX_VALUE.
            query.setLong(2, before ?: Long.MAX_VALUE)
            query.setInt(3, limit)
            query.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) null
                    else {
                        val currency = Currency(rows.getString(3), rows.getInt(4))
                        StatementLine(
                            rows.getLong(1), rows.getString(2), Money(rows.getBigDecimal(5), currency).toAmount(),
                            Money(rows.getBigDecimal(6), currency).toAmount(), rows.getString(7), rows.getLong(8),
                        )
                    }
                }.toList()
            }
        }
    }

    /** Fails unless [currencies] lists every currency an account holds, at the places it holds it in. */
    fun heldIn(currencies: Currencies) {
        val held = data.connection.use { connection ->
            connection.createStatement().use { query ->
                query.executeQuery("select distinct currency, exponent from account_balance").use { rows ->
                    generateSequence { if (rows.next()) Currency(rows.getString(1), rows.getInt(2)) else null }.toList()
                }
            }
        }
        val unlisted = held.filter { currencies.listed(it) == null }
        check(unlisted.isEmpty()) {
            "accounts hold ${unlisted.joinToString { "${it.code} at ${it.exponent} places" }}, which bank.currencies does not list so"
        }
    }

    /** Each currency's books: what the ledger shows and the gauges watch. */
    fun books(): List<CurrencyBooks> = data.connection.use { connection ->
        val held = connection.createStatement().use { query ->
            query.executeQuery("select currency, count(*), coalesce(sum(balance), 0) from account_balance group by currency").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to (rows.getLong(2) to rows.getBigDecimal(3)) else null }.toMap()
            }
        }
        connection.createStatement().use { query ->
            query.executeQuery("select currency, exponent, paid_in, paid_out, in_flight, events from ledger_totals order by currency").use { rows ->
                generateSequence {
                    if (!rows.next()) null
                    else {
                        val (accounts, balances) = held[rows.getString(1)] ?: (0L to BigDecimal.ZERO)
                        CurrencyBooks(
                            Currency(rows.getString(1), rows.getInt(2)), accounts, balances,
                            inFlight = rows.getBigDecimal(5), paidIn = rows.getBigDecimal(3), paidOut = rows.getBigDecimal(4),
                            events = rows.getLong(6),
                        )
                    }
                }.toList()
            }
        }
    }

    override fun ledger(): Ledger {
        val books = books()
        return Ledger(
            accounts = books.sumOf { it.accounts },
            eventsProjected = books.sumOf { it.events },
            conserved = books.all { it.conserved },
            currencies = books.map { it.shown() },
        )
    }

    override fun transfers(): TransferCounts = data.connection.use { connection ->
        connection.createStatement().use { query ->
            query.executeQuery("select status, count(*) from transfer_status group by status").use { rows ->
                val counts = generateSequence { if (rows.next()) rows.getString(1) to rows.getLong(2) else null }.toMap()
                fun count(vararg of: TransferStatus) = of.sumOf { counts[it.name] ?: 0 }
                TransferCounts(
                    pending = count(TransferStatus.Pending, TransferStatus.Debited, TransferStatus.Refunding),
                    completed = count(TransferStatus.Completed),
                    rejected = count(TransferStatus.Rejected),
                    refunded = count(TransferStatus.Refunded),
                )
            }
        }
    }

    /** The sequence number and time of the event that opened [account], once the statements have it; read as the owner. */
    fun opened(account: String): Pair<Long, Long>? = data.connection.use { connection ->
        connection.prepareStatement("select seq_nr, at_millis from statement_line where account_id = ? and kind = 'opened'").use { query ->
            query.setString(1, account)
            query.executeQuery().use { row -> if (row.next()) row.getLong(1) to row.getLong(2) else null }
        }
    }

    /**
     * [work] as `bank_reader`, with `bank.caller` and `bank.auditor` set for this transaction alone (bank spec 0021):
     * the read models' row-level policies then return only what [who] may see. The bank's own reads (projections, the
     * sweeper, the ledger's totals) read as the tables' owner, which no policy restricts.
     */
    private fun <A> seenBy(who: Caller, work: (Connection) -> A): A = transaction { connection ->
        connection.prepareStatement(
            "select set_config('role', 'bank_reader', true), set_config('bank.caller', ?, true), set_config('bank.auditor', ?, true)",
        ).use { set ->
            set.setString(1, who.actingAs)
            set.setString(2, if ("auditor" in who.groups && !who.isImpersonating) "on" else "off")
            set.execute()
        }
        work(connection)
    }

    /**
     * Refuses to start a node whose login cannot read as `bank_reader`: every customer's read would fail. At home the
     * operator makes the login a member (deploy/k8s/postgres.yaml); elsewhere the migration does.
     */
    fun canReadAsCaller() = seenBy(Caller("", "", emptySet())) { connection ->
        connection.createStatement().use { it.executeQuery("select count(*) from account_balance").close() }
    }

    private fun <A> transaction(work: (Connection) -> A): A = data.connection.use { connection ->
        connection.autoCommit = false
        try {
            work(connection).also { connection.commit() }
        } catch (failed: java.sql.SQLException) {
            connection.rollback()
            throw failed
        }
    }
}

/**
 * One currency's books. [conserved] is the claim the design exists for: every balance plus the money in flight is
 * exactly what was paid in less what was paid out, in this currency alone.
 */
class CurrencyBooks(
    val currency: Currency,
    val accounts: Long,
    balances: BigDecimal,
    inFlight: BigDecimal,
    paidIn: BigDecimal,
    paidOut: BigDecimal,
    val events: Long,
) {
    // At the currency's places, whatever scale a sum or an empty total came back with.
    val balances: BigDecimal = balances.setScale(currency.exponent)
    val inFlight: BigDecimal = inFlight.setScale(currency.exponent)
    val paidIn: BigDecimal = paidIn.setScale(currency.exponent)
    val paidOut: BigDecimal = paidOut.setScale(currency.exponent)

    /** Paid in less paid out, less balances and money in flight: 0 when the books balance. */
    val gap: BigDecimal get() = paidIn - paidOut - balances - inFlight
    val conserved: Boolean get() = gap.signum() == 0

    fun shown() = Books(
        currency.code, accounts, Money(balances, currency).toAmount().value, Money(inFlight, currency).toAmount().value,
        Money(paidIn, currency).toAmount().value, Money(paidOut, currency).toAmount().value, conserved,
    )

    override fun toString(): String =
        "CurrencyBooks($currency, accounts=$accounts, balances=$balances, inFlight=$inFlight, paidIn=$paidIn, " +
            "paidOut=$paidOut, events=$events)"
}

private data class Row(val owner: String, val balance: Money, val lastSeq: Long)

/** A currency's code and places, at [index] and the parameter after it. */
private fun java.sql.PreparedStatement.setCurrency(index: Int, currency: Currency) {
    setString(index, currency.code)
    setInt(index + 1, currency.exponent)
}

private fun Connection.balances(ids: List<String>): Map<String, Row> =
    prepareStatement("select account_id, owner, currency, exponent, balance, last_seq from account_balance where account_id = any(?)")
        .use { query ->
            query.setArray(1, createArrayOf("text", ids.toTypedArray()))
            query.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) null
                    else rows.getString(1) to Row(rows.getString(2), Money(rows.getBigDecimal(5), Currency(rows.getString(3), rows.getInt(4))), rows.getLong(6))
                }.toMap()
            }
        }

/** Each known transfer's state, rebuilt from its row, and the sequence the row reached. */
private fun Connection.transfers(ids: List<String>): Map<String, Pair<Transfer, Long>> =
    prepareStatement(
        "select transfer_id, from_account, to_account, currency, exponent, amount, status, last_seq, updated_millis from transfer_status " +
            "where transfer_id = any(?)",
    ).use { query ->
        query.setArray(1, createArrayOf("text", ids.toTypedArray()))
        query.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) null
                else {
                    val status = TransferStatus.valueOf(rows.getString(7))
                    val moving = Transfer.Moving(
                        AccountId(rows.getString(2)), AccountId(rows.getString(3)),
                        Money(rows.getBigDecimal(6), Currency(rows.getString(4), rows.getInt(5))), status, null, rows.getLong(9), null,
                    )
                    rows.getString(1) to (moving as Transfer to rows.getLong(8))
                }
            }.toMap()
        }
    }

/** Money from outside, money leaving, and money between two accounts, as one event moves them. */
private data class Totals(
    val paidIn: BigDecimal = BigDecimal.ZERO,
    val paidOut: BigDecimal = BigDecimal.ZERO,
    val inFlight: BigDecimal = BigDecimal.ZERO,
    val events: Long = 0,
) {
    operator fun plus(other: Totals) =
        Totals(paidIn + other.paidIn, paidOut + other.paidOut, inFlight + other.inFlight, events + other.events)
}

private fun AccountEvent.totals(): Totals = when (this) {
    is AccountEvent.Opened -> Totals(paidIn = initial.amount, events = 1)
    is AccountEvent.Deposited -> Totals(paidIn = amount.amount, events = 1)
    is AccountEvent.Withdrawn -> Totals(paidOut = amount.amount, events = 1)
    is AccountEvent.Debited -> Totals(inFlight = amount.amount, events = 1)
    is AccountEvent.Credited -> Totals(inFlight = amount.amount.negate(), events = 1)
    is AccountEvent.Refunded -> Totals(inFlight = amount.amount.negate(), events = 1)
    is AccountEvent.LegsClosed -> Totals()
}

private fun AccountEvent.delta(): Money = when (this) {
    is AccountEvent.Opened -> initial
    is AccountEvent.Deposited -> amount
    is AccountEvent.Credited -> amount
    is AccountEvent.Refunded -> amount
    is AccountEvent.Withdrawn -> -amount
    is AccountEvent.Debited -> -amount
    is AccountEvent.LegsClosed -> error("a transfer's legs closing moves no money")
}

/** What the event moved, or null for one that moves no money. */
fun AccountEvent.amount(): Money? = when (this) {
    is AccountEvent.Opened -> initial
    is AccountEvent.Deposited -> amount
    is AccountEvent.Credited -> amount
    is AccountEvent.Refunded -> amount
    is AccountEvent.Withdrawn -> amount
    is AccountEvent.Debited -> amount
    is AccountEvent.LegsClosed -> null
}

fun AccountEvent.kind(): String = when (this) {
    is AccountEvent.Opened -> "opened"
    is AccountEvent.Deposited -> "deposited"
    is AccountEvent.Credited -> "credited"
    is AccountEvent.Refunded -> "refunded"
    is AccountEvent.Withdrawn -> "withdrawn"
    is AccountEvent.Debited -> "debited"
    is AccountEvent.LegsClosed -> "closed"
}

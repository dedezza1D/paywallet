package br.com.paywallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

class LedgerIntegrationTest extends IntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired TransactionTemplate tx;

    @Test
    void postingsCannotBeUpdatedOrDeleted() {
        var user = newUserWithBalance("Immutable", "10.00");
        var accountId = ledger.walletOf(user.id()).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE postings SET amount = 999999 WHERE account_id = ?", accountId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM postings WHERE account_id = ?", accountId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("immutable");
    }

    /** Bypasses the application entirely: the database itself rejects a single-leg transaction. */
    @Test
    void databaseRejectsUnbalancedTransactionOnCommit() {
        var accountId = ledger.walletOf(newUser(UserType.COMMON, "Hacker").id()).getId();
        var txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO ledger_transactions (id, type, idempotency_key, created_at) VALUES (?, 'CASH_IN', ?, now())",
                    txId, "manual-" + txId);
            jdbc.update("""
                    INSERT INTO postings (id, transaction_id, account_id, direction, amount, balance_after, created_at)
                    VALUES (?, ?, ?, 'CREDIT', 100000, 100000, now())
                    """, UUID.randomUUID(), txId, accountId);
        })).hasStackTraceContaining("unbalanced");

        assertThat(ledger.findByIdempotencyKey("manual-" + txId)).isEmpty();
    }

    @Test
    void applicationRejectsUnbalancedLegs() {
        var accountId = ledger.walletOf(newUserWithBalance("Legs", "5.00").id()).getId();

        assertThatThrownBy(() -> ledger.post(new PostCommand(LedgerTransactionType.CASH_IN, newKey(), null,
                List.of(Leg.debit(AccountType.CASH_IN_ACCOUNT_ID, 100), Leg.credit(accountId, 99)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userWalletCannotGoNegative() {
        var poor = newUserWithBalance("Poor", "1.00");
        var a = ledger.walletOf(poor.id()).getId();
        var b = ledger.walletOf(newUser(UserType.COMMON, "Rich").id()).getId();

        assertThatThrownBy(() -> ledger.post(new PostCommand(LedgerTransactionType.P2P_TRANSFER, newKey(), null,
                List.of(Leg.debit(a, 101), Leg.credit(b, 101)))))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(ledger.walletOf(poor.id()).getBalance()).isEqualTo(100);
    }

    @Test
    void snapshotsAlwaysMatchPostingsAndSystemSumsToZero() {
        var a = newUserWithBalance("Recon A", "100.00");
        var b = newUserWithBalance("Recon B", "30.00");
        walletService.transfer(a.id(), new TransferRequest(new BigDecimal("12.34"), b.id(), null, null), newKey());

        var report = ledger.reconcile();

        assertThat(report.mismatches()).isEmpty();
        assertThat(report.systemTotal()).isZero();
        assertThat(report.consistent()).isTrue();
    }
}

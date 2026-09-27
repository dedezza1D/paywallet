package br.com.paywallet.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.credit.CreditAnalysisService;
import br.com.paywallet.exception.ExternalServiceException;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

class ObservabilityIntegrationTest extends IntegrationTest {

    @Autowired MeterRegistry meters;
    @Autowired CreditAnalysisService analyses;

    @Test
    void committedMovementsAndRiskDecisionsAreCounted() {
        double transfers = count("paywallet.ledger.transactions", "type", "P2P_TRANSFER");
        double amount = count("paywallet.ledger.amount", "type", "P2P_TRANSFER");
        double approvals = count("paywallet.fraud.assessments", "decision", "APPROVE");
        var payer = newUserWithBalance("Measured Payer", "100.00");
        var payee = newUser(UserType.COMMON, "Measured Payee");

        walletService.transfer(payer.id(), new TransferRequest(new BigDecimal("12.34"), payee.id(), null, null),
                newKey());

        assertThat(count("paywallet.ledger.transactions", "type", "P2P_TRANSFER") - transfers).isEqualTo(1);
        assertThat(count("paywallet.ledger.amount", "type", "P2P_TRANSFER") - amount).isEqualTo(12.34, within(0.0001));
        assertThat(count("paywallet.fraud.assessments", "decision", "APPROVE") - approvals).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("paywallet.outbox.published", "topic", "paywallet.transfers.completed") > 0);
    }

    @Test
    void partnerCallsAreTimedWithTheirOutcome() {
        var borrower = newUserWithBalance("Timed Borrower", "10.00");
        doThrow(new ExternalServiceException("Bureau down", null)).when(creditBureau).report(borrower.document());

        assertThatThrownBy(() -> analyses.current(borrower.id())).isInstanceOf(ExternalServiceException.class);

        assertThat(meters.find("paywallet.partner.requests").tag("partner", "credit-bureau").timers())
                .anySatisfy(t -> assertThat(t.getId().getTag("error")).isEqualTo("ExternalServiceException"));
    }

    @Test
    void pendingQueuesAreExposedAsGauges() {
        assertThat(meters.find("paywallet.queue.pending").gauges()).hasSize(8);
        assertThat(meters.find("paywallet.queue.pending").tag("queue", "outbox").gauge().value()).isNotNaN();
        assertThat(meters.find("paywallet.queue.oldest.age").tag("queue", "pix").gauge().value())
                .isGreaterThanOrEqualTo(0);
    }

    private double count(String name, String tag, String value) {
        return meters.find(name).tag(tag, value).counters().stream().mapToDouble(Counter::count).sum();
    }
}

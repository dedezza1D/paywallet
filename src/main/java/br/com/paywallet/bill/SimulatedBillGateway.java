package br.com.paywallet.bill;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the banking partner in local environments. Any valid code is known and payable until 30 days
 * after its due date, at face value; open-amount bills accept R$ 1.00 to R$ 10,000.00. Payments whose amount
 * ends in 99 cents are refused, to exercise the reversal path.
 */
@Component
@ConditionalOnProperty(name = "app.bill.gateway", havingValue = "simulated", matchIfMissing = true)
class SimulatedBillGateway implements BillGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedBillGateway.class);

    private final Clock clock;

    SimulatedBillGateway(Clock clock) {
        this.clock = clock;
        log.warn("Using the simulated bill gateway: no bill is really paid");
    }

    @Override
    public Optional<BillQuote> lookup(String barcode) {
        LocalDate today = LocalDate.now(clock);
        var code = BoletoCode.parse(barcode, today);
        LocalDate due = code.dueDate();
        LocalDate deadline = (due != null ? due : today).plusDays(30);
        if (code.amountCents() == null) {
            return Optional.of(new BillQuote("Simulated Beneficiary", "12345678000199", due, null, 10_000, 100,
                    1_000_000, deadline, false));
        }
        long amount = code.amountCents();
        return Optional.of(new BillQuote("Simulated Beneficiary", "12345678000199", due, amount, amount, amount,
                amount, deadline, false));
    }

    @Override
    public PaymentResult pay(BillOrder order) {
        if (order.amountCents() % 100 == 99) {
            return PaymentResult.rejected("Bill already paid at another institution");
        }
        return PaymentResult.ok(UUID.randomUUID().toString().replace("-", "").toUpperCase());
    }
}

package br.com.paywallet.bill;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Access to the boleto clearing system: the CIP/NPC registry for lookups and a settling bank for payments.
 * Both require a banking partner; implementations wrap that partner's API.
 */
public interface BillGateway {

    /** @return empty when the registry does not know the bill */
    Optional<BillQuote> lookup(String barcode);

    /** @throws br.com.paywallet.exception.ExternalServiceException when the partner cannot be reached */
    PaymentResult pay(BillOrder order);

    /**
     * Registry data for a bill. {@code amountDueCents} already includes interest, fines and discounts for today;
     * payments must stay within {@code minAmountCents} and {@code maxAmountCents}.
     */
    record BillQuote(String beneficiaryName, String beneficiaryDocument, LocalDate dueDate, Long nominalAmountCents,
                     long amountDueCents, long minAmountCents, long maxAmountCents, LocalDate paymentDeadline,
                     boolean alreadyPaid) {
    }

    record BillOrder(String paymentId, String barcode, long amountCents, String payerName, String payerDocument) {
    }

    record PaymentResult(boolean accepted, String authenticationCode, String rejectionReason) {

        public static PaymentResult ok(String authenticationCode) {
            return new PaymentResult(true, authenticationCode, null);
        }

        public static PaymentResult rejected(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}

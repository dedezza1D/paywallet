package br.com.paywallet.pix;

import java.util.Optional;

/**
 * Connection to the Pix network (DICT lookups and SPI settlement). Talking to the central bank directly
 * requires being a Pix participant or going through a PSP; implementations wrap that provider.
 */
public interface PixGateway {

    Optional<ExternalAccount> lookup(String key);

    /** @throws br.com.paywallet.exception.ExternalServiceException when the network cannot be reached */
    SubmitResult submit(PixOrder order);

    /**
     * Sends back part or all of a Pix received from another institution.
     *
     * @throws br.com.paywallet.exception.ExternalServiceException when the network cannot be reached
     */
    SubmitResult submitReturn(ReturnOrder order);

    record ExternalAccount(String holderName, String holderDocument, String ispb, String institutionName) {
    }

    record PixOrder(String endToEndId, String key, long amountCents, String payerName, String payerDocument,
                    String description) {
    }

    record ReturnOrder(String returnId, String originalEndToEndId, long amountCents, String reason) {
    }

    record SubmitResult(boolean accepted, String rejectionReason) {

        public static SubmitResult ok() {
            return new SubmitResult(true, null);
        }

        public static SubmitResult rejected(String reason) {
            return new SubmitResult(false, reason);
        }
    }
}

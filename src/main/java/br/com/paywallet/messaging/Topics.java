package br.com.paywallet.messaging;

public final class Topics {

    public static final String TRANSFERS_COMPLETED = "paywallet.transfers.completed";
    public static final String PIX_RECEIVED = "paywallet.pix.received";
    public static final String CHARGES_PAID = "paywallet.charges.paid";

    private Topics() {
    }
}

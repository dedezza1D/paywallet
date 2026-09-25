package br.com.paywallet.external;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import br.com.paywallet.ledger.Money;
import br.com.paywallet.wallet.TransferCompletedEvent;

@Component
public class TransferNotificationListener {

    private final NotificationClient notificationClient;

    public TransferNotificationListener(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    @Async
    @EventListener
    public void onTransferCompleted(TransferCompletedEvent event) {
        notificationClient.send(event.payeeEmail(), "You received R$ %s from %s"
                .formatted(Money.fromCents(event.amountCents()).toPlainString(), event.payerName()));
    }
}

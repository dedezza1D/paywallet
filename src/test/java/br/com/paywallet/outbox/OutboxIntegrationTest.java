package br.com.paywallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.TransferNotAuthorizedException;
import br.com.paywallet.feed.FeedService;
import br.com.paywallet.messaging.EventHeaders;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.TransferCompletedEvent;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

class OutboxIntegrationTest extends IntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);

    @Autowired FeedService feed;
    @Autowired KafkaTemplate<String, String> kafka;

    @Test
    void transferWritesItsEventAndTheRelayPublishesIt() {
        var payer = newUserWithBalance("Outbox Payer", "50.00");
        var payee = newUser(UserType.COMMON, "Outbox Payee");

        var result = walletService.transfer(payer.id(), new TransferRequest(BigDecimal.TEN, payee.id(), null, null), newKey());
        String transactionId = result.response().transactionId().toString();

        var row = jdbc.queryForMap("SELECT event_type, message_key, payload::text AS payload FROM outbox_events "
                + "WHERE payload->>'transactionId' = ?", transactionId);
        assertThat(row.get("event_type")).isEqualTo(TransferCompletedEvent.TYPE);
        assertThat(row.get("message_key")).isEqualTo(payer.id().toString());
        assertThat((String) row.get("payload")).contains("\"amountCents\": 1000");

        await().atMost(ASYNC).until(() -> publishedAt(transactionId) != null);
        await().atMost(ASYNC).untilAsserted(() ->
                assertThat(feed.userFeed(payee.id(), PageRequest.of(0, 10)).getContent()).hasSize(1));
    }

    /** Rejected transfers, including ones failing inside the ledger transaction, leave no event behind. */
    @Test
    void onlyCommittedTransfersProduceEvents() throws Exception {
        var payer = newUserWithBalance("Atomic", "30.00");
        var payee = newUser(UserType.COMMON, "Atomic Payee");
        var request = new TransferRequest(BigDecimal.TEN, payee.id(), null, null);

        when(authorizationClient.isAuthorized()).thenReturn(false);
        assertThatThrownBy(() -> walletService.transfer(payer.id(), request, newKey()))
                .isInstanceOf(TransferNotAuthorizedException.class);
        when(authorizationClient.isAuthorized()).thenReturn(true);

        Callable<Boolean> task = () -> {
            try {
                walletService.transfer(payer.id(), request, newKey());
                return true;
            } catch (BusinessException e) {
                return false;
            }
        };
        long succeeded;
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = pool.invokeAll(IntStream.range(0, 10).mapToObj(i -> task).toList());
            succeeded = 0;
            for (var f : futures) {
                if (f.get()) succeeded++;
            }
        }

        assertThat(succeeded).isEqualTo(3);
        assertThat(eventsFor(payer)).isEqualTo(3);
    }

    /** The relay may send an event twice (e.g. broker ack received but commit lost); consumers must not care. */
    @Test
    void redeliveredEventIsProcessedOnlyOnce() {
        var payer = newUserWithBalance("Redelivery", "50.00");
        var payee = newUser(UserType.COMMON, "Redelivery Payee");
        var result = walletService.transfer(payer.id(), new TransferRequest(BigDecimal.ONE, payee.id(), null, null), newKey());
        String transactionId = result.response().transactionId().toString();
        await().atMost(ASYNC).until(() -> publishedAt(transactionId) != null);
        await().atMost(ASYNC).untilAsserted(() -> verify(notificationClient).send(eq(payee.email()), anyString()));

        jdbc.update("UPDATE outbox_events SET published_at = NULL WHERE payload->>'transactionId' = ?", transactionId);
        await().atMost(ASYNC).until(() -> publishedAt(transactionId) != null);

        await().pollDelay(Duration.ofSeconds(3)).atMost(ASYNC).untilAsserted(() -> {
            assertThat(feed.userFeed(payee.id(), PageRequest.of(0, 10)).getContent()).hasSize(1);
            verify(notificationClient, times(1)).send(eq(payee.email()), anyString());
        });
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE payload->>'transactionId' = ?",
                Integer.class, transactionId)).isEqualTo(2);
    }

    @Test
    void poisonMessageIsParkedInTheDeadLetterTopic() throws Exception {
        String poison = "not-json-" + UUID.randomUUID();
        var record = new ProducerRecord<String, String>(Topics.TRANSFERS_COMPLETED, "poison", poison);
        record.headers().add(EventHeaders.EVENT_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(Topics.TRANSFERS_COMPLETED + "-dlt"));
            List<String> parked = new ArrayList<>();
            await().atMost(ASYNC).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> parked.add(r.value()));
                assertThat(parked).contains(poison);
            });
        }
    }

    private Object publishedAt(String transactionId) {
        return jdbc.queryForObject("SELECT published_at FROM outbox_events WHERE payload->>'transactionId' = ?",
                Object.class, transactionId);
    }

    private long eventsFor(UserResponse payer) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?",
                Long.class, payer.id().toString());
        return count == null ? 0 : count;
    }
}

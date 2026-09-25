package br.com.paywallet.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.core.JsonProcessingException;

@Configuration
public class KafkaConfig {

    /** Partitions allow parallel consumers; messages are keyed by payer, so each user's events stay ordered. */
    private static final int PARTITIONS = 3;

    @Bean
    NewTopic transfersCompletedTopic() {
        return TopicBuilder.name(Topics.TRANSFERS_COMPLETED).partitions(PARTITIONS).build();
    }

    /** The recoverer writes to the same partition number, so the DLT needs at least as many partitions. */
    @Bean
    NewTopic transfersCompletedDlt() {
        return TopicBuilder.name(Topics.TRANSFERS_COMPLETED + "-dlt").partitions(PARTITIONS).build();
    }

    @Bean
    NewTopic pixReceivedTopic() {
        return TopicBuilder.name(Topics.PIX_RECEIVED).partitions(PARTITIONS).build();
    }

    @Bean
    NewTopic pixReceivedDlt() {
        return TopicBuilder.name(Topics.PIX_RECEIVED + "-dlt").partitions(PARTITIONS).build();
    }

    /**
     * Retries a failing record a few times, then parks it in "{topic}-dlt" so one poison message cannot
     * block its partition. Malformed payloads go straight to the DLT since retrying cannot fix them.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1_000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}

package br.com.paywallet.messaging;

import java.util.List;
import java.util.stream.Stream;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class KafkaConfig {

    /** Partitions allow parallel consumers; messages are keyed by user, so each user's events stay ordered. */
    private static final int PARTITIONS = 3;

    private static final List<String> TOPICS =
            List.of(Topics.TRANSFERS_COMPLETED, Topics.PIX_RECEIVED, Topics.CHARGES_PAID, Topics.LOAN_INSTALLMENT_OVERDUE,
                    Topics.MARKETPLACE_ORDERS_COMPLETED);

    /**
     * Each topic gets a "-dlt" companion. The recoverer writes to the same partition number, so the DLT needs at
     * least as many partitions as its topic.
     */
    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(TOPICS.stream()
                .flatMap(topic -> Stream.of(topic, topic + "-dlt"))
                .map(name -> TopicBuilder.name(name).partitions(PARTITIONS).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * Retries a failing record a few times, then parks it in "{topic}-dlt" so one poison message cannot
     * block its partition. Malformed payloads go straight to the DLT since retrying cannot fix them.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, MeterRegistry meters) {
        var deadLetters = new DeadLetterPublishingRecoverer(template);
        ConsumerRecordRecoverer countingDeadLetters = (record, e) -> {
            meters.counter("paywallet.kafka.dead_letters", "topic", record.topic()).increment();
            deadLetters.accept(record, e);
        };
        var handler = new DefaultErrorHandler(countingDeadLetters, new FixedBackOff(1_000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}

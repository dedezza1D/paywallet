package br.com.paywallet.card;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param webhookSecret           HMAC secret shared with the card processor; empty disables its webhooks
 * @param webhookTolerance        maximum clock skew accepted on webhook timestamps
 * @param revolvingMonthlyPercent interest per month on a statement balance carried to the next statement
 * @param installmentMonthlyPercent interest per month when a statement is split into installments
 * @param minimumPaymentPercent   share of the statement shown as the minimum payment
 * @param dueDaysAfterClosing     days between a statement closing and its due date
 * @param statementJobEnabled     runs the daily statement closing job (disabled in tests)
 * @param zone                    time zone of closing and due dates
 */
@ConfigurationProperties(prefix = "app.cards")
public record CardProperties(
        String webhookSecret,
        Duration webhookTolerance,
        BigDecimal revolvingMonthlyPercent,
        BigDecimal installmentMonthlyPercent,
        BigDecimal minimumPaymentPercent,
        int dueDaysAfterClosing,
        boolean statementJobEnabled,
        ZoneId zone) {
}

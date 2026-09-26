package br.com.paywallet.fraud;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds of the risk rules. Amounts are in reais. A night window with equal start and end hours disables the
 * nighttime rule.
 */
@ConfigurationProperties(prefix = "app.fraud")
public record FraudProperties(
        int reviewScore,
        int declineScore,
        Duration velocityWindow,
        int velocityMaxCount,
        int amountAnomalyMultiplier,
        int amountAnomalyMinHistory,
        BigDecimal amountAnomalyFloor,
        BigDecimal newCounterpartyAmount,
        int nightStartHour,
        int nightEndHour,
        BigDecimal nightAmount,
        int newAccountDays,
        BigDecimal newAccountAmount,
        Duration cardDeclinesWindow,
        int cardDeclinesMax,
        ZoneId zone) {
}

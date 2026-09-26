package br.com.paywallet.credit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param collectionEnabled          runs the daily installment collection job (disabled in tests)
 * @param analysisValidity           how long a credit decision is reused before a new bureau query
 * @param minAmount                  smallest loan, in BRL
 * @param minInstallments            fewest installments
 * @param maxInstallments            most installments
 * @param iofFlatPercent             IOF charged once on the amount (individuals: 0.38)
 * @param iofDailyPercent            IOF per day on each installment's principal, up to 365 days (individuals: 0.0082)
 * @param lateFinePercent            fine on an overdue installment (consumer law caps it at 2)
 * @param lateMonthlyInterestPercent default interest per month, charged pro rata per day
 * @param zone                       time zone of due dates
 */
@ConfigurationProperties(prefix = "app.credit")
public record CreditProperties(
        boolean collectionEnabled,
        Duration analysisValidity,
        BigDecimal minAmount,
        int minInstallments,
        int maxInstallments,
        BigDecimal iofFlatPercent,
        BigDecimal iofDailyPercent,
        BigDecimal lateFinePercent,
        BigDecimal lateMonthlyInterestPercent,
        ZoneId zone) {
}

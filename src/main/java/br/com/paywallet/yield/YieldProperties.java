package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled        runs the daily job; disabled where balances must not change on their own (tests)
 * @param cdiPercentage  share of the CDI paid to customers, e.g. 100 or 102
 * @param cdiSource      "bcb" for the central bank series, "fixed" for {@code fixedDailyRate} on weekdays
 * @param fixedDailyRate daily CDI in percent, used by the fixed source
 * @param bcbSeriesUrl   BCB SGS endpoint of series 12 (daily CDI)
 * @param maxCatchUpDays oldest missed day the job still processes after downtime
 * @param zone           time zone that defines the end of each day
 */
@ConfigurationProperties(prefix = "app.yield")
public record YieldProperties(
        boolean enabled,
        BigDecimal cdiPercentage,
        String cdiSource,
        BigDecimal fixedDailyRate,
        String bcbSeriesUrl,
        int maxCatchUpDays,
        ZoneId zone) {
}

package br.com.paywallet.hotdata;

import java.math.BigDecimal;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dailyTransfer maximum amount (BRL) a user may send per day
 * @param zone          time zone that defines when the day rolls over
 */
@ConfigurationProperties(prefix = "app.limits")
public record LimitsProperties(BigDecimal dailyTransfer, ZoneId zone) {
}

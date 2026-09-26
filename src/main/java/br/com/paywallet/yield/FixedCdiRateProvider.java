package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Offline stand-in: a constant rate on weekdays. Ignores holidays. */
@Component
@ConditionalOnProperty(name = "app.yield.cdi-source", havingValue = "fixed")
class FixedCdiRateProvider implements CdiRateProvider {

    private final BigDecimal rate;

    FixedCdiRateProvider(YieldProperties props) {
        this.rate = props.fixedDailyRate();
    }

    @Override
    public SortedMap<LocalDate, BigDecimal> dailyRates(LocalDate from, LocalDate to) {
        var rates = new TreeMap<LocalDate, BigDecimal>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                rates.put(day, rate);
            }
        }
        return rates;
    }
}

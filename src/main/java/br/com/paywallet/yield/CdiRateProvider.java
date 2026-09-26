package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;

/** Daily CDI rates. Only business days are returned, so the keys double as the business calendar. */
public interface CdiRateProvider {

    /** @return daily rate in percent per day (e.g. 0.055131), by date, for business days in the range */
    SortedMap<LocalDate, BigDecimal> dailyRates(LocalDate from, LocalDate to);
}

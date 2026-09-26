package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class YieldDtos {

    private YieldDtos() {
    }

    public record DailyYield(LocalDate date, BigDecimal endOfDayBalance, BigDecimal credited) {
    }

    /**
     * @param cdiDailyRate    latest daily CDI used, in percent per day
     * @param annualRate      that rate at the customer's share of the CDI, annualized over 252 business days
     */
    public record YieldSummary(boolean eligible, BigDecimal cdiPercentage, BigDecimal cdiDailyRate,
                               BigDecimal annualRate, BigDecimal totalCredited, BigDecimal last30Days,
                               List<DailyYield> history) {
    }

    public record RunResult(LocalDate date, BigDecimal cdiDailyRate, BigDecimal cdiPercentage, int accounts,
                            BigDecimal totalCredited) {
    }
}

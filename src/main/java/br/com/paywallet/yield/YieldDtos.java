package br.com.paywallet.yield;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class YieldDtos {

    private YieldDtos() {
    }

    /** {@code credited} is what reached the wallet: gross minus income tax and IOF. */
    public record DailyYield(LocalDate date, BigDecimal endOfDayBalance, BigDecimal gross, BigDecimal incomeTax,
                             BigDecimal iof, BigDecimal credited) {
    }

    /**
     * @param cdiDailyRate    latest daily CDI used, in percent per day
     * @param annualRate      that rate at the customer's share of the CDI, annualized over 252 business days,
     *                        before taxes
     * @param totalCredited   net yield received so far
     * @param totalWithheld   income tax and IOF withheld so far
     * @param last30Days      net yield of the last 30 days
     */
    public record YieldSummary(boolean eligible, BigDecimal cdiPercentage, BigDecimal cdiDailyRate,
                               BigDecimal annualRate, BigDecimal totalCredited, BigDecimal totalWithheld,
                               BigDecimal last30Days, List<DailyYield> history) {
    }

    public record RunResult(LocalDate date, BigDecimal cdiDailyRate, BigDecimal cdiPercentage, int accounts,
                            BigDecimal totalCredited, BigDecimal totalWithheld) {
    }
}

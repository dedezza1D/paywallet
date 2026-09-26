package br.com.paywallet.merchant;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargeDtos.DailyTotals;
import br.com.paywallet.merchant.ChargeDtos.Dashboard;
import br.com.paywallet.merchant.ChargeDtos.Totals;

@Service
public class MerchantDashboardService {

    private static final int MAX_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final MerchantProperties props;

    public MerchantDashboardService(JdbcTemplate jdbc, MerchantProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    private record Row(LocalDate day, PaymentMethod method, long count, long gross, long fees, long net) {
    }

    /** Paid charges between {@code from} and {@code to}, inclusive, grouped by day in the merchant time zone. */
    public Dashboard dashboard(Long merchantId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new BusinessException("Invalid period (maximum %d days)".formatted(MAX_DAYS));
        }
        var start = Timestamp.from(from.atStartOfDay(props.zone()).toInstant());
        var end = Timestamp.from(to.plusDays(1).atStartOfDay(props.zone()).toInstant());
        List<Row> rows = jdbc.query("""
                SELECT (paid_at AT TIME ZONE ?)::date AS day, payment_method, count(*) AS count,
                       sum(amount) AS gross, sum(fee_amount) AS fees, sum(net_amount) AS net
                  FROM charges
                 WHERE merchant_id = ? AND status = 'PAID' AND paid_at >= ? AND paid_at < ?
                 GROUP BY day, payment_method
                """,
                (rs, i) -> new Row(rs.getObject("day", LocalDate.class),
                        PaymentMethod.valueOf(rs.getString("payment_method")), rs.getLong("count"),
                        rs.getLong("gross"), rs.getLong("fees"), rs.getLong("net")),
                props.zone().getId(), merchantId, start, end);

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        long[] total = new long[4];
        long[] wallet = new long[4];
        long[] pix = new long[4];
        for (Row row : rows) {
            add(total, row);
            add(row.method() == PaymentMethod.WALLET ? wallet : pix, row);
            add(byDay.computeIfAbsent(row.day(), d -> new long[4]), row);
        }
        List<DailyTotals> daily = new ArrayList<>();
        byDay.forEach((day, t) -> daily.add(new DailyTotals(day, t[0], Money.fromCents(t[1]),
                Money.fromCents(t[2]), Money.fromCents(t[3]))));
        return new Dashboard(from, to, totals(total), totals(wallet), totals(pix), daily);
    }

    private static void add(long[] acc, Row row) {
        acc[0] += row.count();
        acc[1] += row.gross();
        acc[2] += row.fees();
        acc[3] += row.net();
    }

    private static Totals totals(long[] t) {
        return new Totals(t[0], Money.fromCents(t[1]), Money.fromCents(t[2]), Money.fromCents(t[3]));
    }
}

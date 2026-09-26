package br.com.paywallet.card;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.card.CardDtos.ClosingResult;
import br.com.paywallet.card.CardDtos.StatementPaymentResult;
import br.com.paywallet.card.CardDtos.StatementResponse;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;

/**
 * Monthly credit card statements. On its closing day a card gathers every charge billed up to that date; if the
 * previous statement is past due and not fully paid, its remainder is carried over with revolving interest.
 */
@Service
public class CardStatementService {

    private static final Logger log = LoggerFactory.getLogger(CardStatementService.class);

    private record Outcome(int closed, int carried) {
    }

    private final CardRepository cards;
    private final CardStatementRepository statements;
    private final CardCharges charges;
    private final CardService cardService;
    private final LedgerService ledger;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final CardProperties props;
    private final Clock clock;

    public CardStatementService(CardRepository cards, CardStatementRepository statements, CardCharges charges,
                                CardService cardService, LedgerService ledger, BalanceCache balanceCache,
                                TransactionTemplate transactions, CardProperties props, Clock clock) {
        this.cards = cards;
        this.statements = statements;
        this.charges = charges;
        this.cardService = cardService;
        this.ledger = ledger;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Sao_Paulo")
    public void scheduledClosing() {
        if (props.statementJobEnabled()) {
            close(LocalDate.now(clock.withZone(props.zone())));
        }
    }

    /** Closes the statements of every credit card whose closing day is {@code date}. Safe to run again. */
    public ClosingResult close(LocalDate date) {
        int closed = 0;
        int carried = 0;
        for (Card card : cards.creditCardsClosingOn(date.getDayOfMonth())) {
            var result = transactions.execute(status -> closeCard(card.getId(), date));
            closed += result.closed();
            carried += result.carried();
        }
        log.info("Card statements closed for {}: {} closed, {} carried over", date, closed, carried);
        return new ClosingResult(date, closed, carried);
    }

    private Outcome closeCard(UUID cardId, LocalDate date) {
        var card = cards.lockById(cardId).orElseThrow();
        if (statements.existsByCardIdAndClosingDate(card.getId(), date)) {
            return new Outcome(0, 0);
        }
        var now = clock.instant();
        int carried = 0;
        for (CardStatement previous : statements.findByCardIdAndStatusOrderByClosingDate(card.getId(),
                CardStatement.Status.OPEN)) {
            if (!previous.getDueDate().isBefore(date)) {
                continue;
            }
            long remainder = previous.remaining();
            charges.add(card.getId(), null, "Previous balance", remainder, 1, 1, date, now);
            long interest = BigDecimal.valueOf(remainder).multiply(props.revolvingMonthlyPercent())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
            if (interest > 0) {
                ledger.post(new PostCommand(LedgerTransactionType.CARD_REVOLVING_INTEREST,
                        "card-interest:" + previous.getId(), "Revolving interest",
                        List.of(Leg.debit(AccountType.CARD_RECEIVABLES_ACCOUNT_ID, interest),
                                Leg.credit(AccountType.INTEREST_INCOME_ACCOUNT_ID, interest))));
                charges.add(card.getId(), null, "Revolving interest", interest, 1, 1, date, now);
            }
            previous.carry();
            carried++;
        }
        long total = charges.unbilledUpTo(card.getId(), date);
        if (total == 0) {
            return new Outcome(0, carried);
        }
        long minimum = BigDecimal.valueOf(total).multiply(props.minimumPaymentPercent())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        var statement = statements.saveAndFlush(new CardStatement(UUID.randomUUID(), card.getId(), date,
                date.plusDays(props.dueDaysAfterClosing()), total, minimum, now));
        charges.assignUpTo(card.getId(), date, statement.getId());
        return new Outcome(1, carried);
    }

    @Transactional(readOnly = true)
    public List<StatementResponse> list(Long userId, UUID cardId) {
        cardService.owned(userId, cardId);
        return statements.findByCardIdOrderByClosingDateDesc(cardId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public StatementResponse get(Long userId, UUID cardId, UUID statementId) {
        cardService.owned(userId, cardId);
        return response(statement(cardId, statementId));
    }

    /** Pays part or all of an open statement from the wallet. */
    public StatementPaymentResult pay(Long userId, UUID cardId, UUID statementId, BigDecimal value,
                                      String idempotencyKey) {
        String key = "card-payment:%d:%s".formatted(userId, idempotencyKey);
        String description = "Card statement payment " + statementId;
        var previous = ledger.findByIdempotencyKey(key);
        if (previous.isPresent()) {
            return replay(userId, cardId, statementId, previous.get().getDescription(), description);
        }
        StatementResponse paid;
        try {
            paid = transactions.execute(status -> {
                var card = cards.lockById(cardId).filter(c -> c.getUserId().equals(userId))
                        .orElseThrow(() -> new NotFoundException("Card not found"));
                var statement = statement(card.getId(), statementId);
                if (statement.getStatus() != CardStatement.Status.OPEN) {
                    throw new BusinessException("Statement is %s".formatted(statement.getStatus()));
                }
                long amount = value == null ? statement.remaining() : Money.toCents(value);
                if (amount > statement.remaining()) {
                    throw new BusinessException("Payment exceeds the amount owed of R$ "
                            + Money.fromCents(statement.remaining()));
                }
                ledger.post(new PostCommand(LedgerTransactionType.CARD_STATEMENT_PAYMENT, key, description,
                        List.of(Leg.debit(ledger.walletOf(userId).getId(), amount),
                                Leg.credit(AccountType.CARD_RECEIVABLES_ACCOUNT_ID, amount))));
                statement.pay(amount);
                return response(statement);
            });
        } catch (DataIntegrityViolationException e) {
            var existing = ledger.findByIdempotencyKey(key).orElseThrow(() -> e);
            return replay(userId, cardId, statementId, existing.getDescription(), description);
        }
        balanceCache.evict(userId);
        return new StatementPaymentResult(paid, false);
    }

    private StatementPaymentResult replay(Long userId, UUID cardId, UUID statementId, String recorded,
                                          String expected) {
        if (!recorded.equals(expected)) {
            throw new BusinessException("Idempotency-Key already used for a different payment");
        }
        return new StatementPaymentResult(get(userId, cardId, statementId), true);
    }

    private CardStatement statement(UUID cardId, UUID statementId) {
        return statements.findByIdAndCardId(statementId, cardId)
                .orElseThrow(() -> new NotFoundException("Statement not found"));
    }

    private StatementResponse response(CardStatement s) {
        return new StatementResponse(s.getId(), s.getCardId(), s.getClosingDate(), s.getDueDate(),
                Money.fromCents(s.getTotal()), Money.fromCents(s.getMinimumPayment()), Money.fromCents(s.getPaid()),
                Money.fromCents(s.remaining()), s.getStatus(), charges.ofStatement(s.getId()));
    }
}

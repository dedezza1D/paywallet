package br.com.paywallet.card;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.card.CardDtos.AuthorizationDecision;
import br.com.paywallet.card.CardDtos.AuthorizationRequest;
import br.com.paywallet.card.CardDtos.AuthorizationResponse;
import br.com.paywallet.card.CardDtos.ClearingRequest;
import br.com.paywallet.card.CardDtos.ReversalRequest;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.fraud.Channel;
import br.com.paywallet.fraud.FraudCheck;
import br.com.paywallet.fraud.FraudService;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;

/**
 * Card purchases as reported by the processor. A debit purchase reserves the money in CARD_HOLDS at authorization
 * and moves it to CARD_SETTLEMENT at clearing, returning any difference. A credit purchase only consumes the limit
 * until clearing, when the amount becomes a receivable billed in monthly installments.
 * Every step locks the card, so concurrent purchases cannot overspend the balance or the limit.
 */
@Service
public class CardAuthorizationService {

    static final String APPROVED = "00";
    static final String INVALID_CARD = "14";
    static final String INSUFFICIENT_FUNDS = "51";
    static final String EXPIRED_CARD = "54";
    static final String NOT_PERMITTED = "57";
    static final String SUSPECTED_FRAUD = "59";

    private record Decline(String code, String reason) {
    }

    private final CardRepository cards;
    private final CardAuthorizationRepository authorizations;
    private final CardCharges charges;
    private final LedgerService ledger;
    private final BalanceCache balanceCache;
    private final FraudService fraud;
    private final TransactionTemplate transactions;
    private final CardProperties props;
    private final Clock clock;

    public CardAuthorizationService(CardRepository cards, CardAuthorizationRepository authorizations,
                                    CardCharges charges, LedgerService ledger, BalanceCache balanceCache,
                                    FraudService fraud, TransactionTemplate transactions, CardProperties props,
                                    Clock clock) {
        this.cards = cards;
        this.authorizations = authorizations;
        this.charges = charges;
        this.ledger = ledger;
        this.balanceCache = balanceCache;
        this.fraud = fraud;
        this.transactions = transactions;
        this.props = props;
        this.clock = clock;
    }

    /** Answers the same way when the processor repeats an authorization id. */
    public AuthorizationDecision authorize(AuthorizationRequest req) {
        var previous = authorizations.findById(req.authorizationId());
        if (previous.isPresent()) {
            return decision(previous.get());
        }
        int installments = req.installments() == null ? 1 : req.installments();
        long amount = Money.toCents(req.amount());
        try {
            return transactions.execute(status -> {
                var card = cards.lockByProcessorToken(req.cardToken()).orElse(null);
                if (card == null) {
                    return new AuthorizationDecision(req.authorizationId(), false, INVALID_CARD, "Unknown card");
                }
                var repeated = authorizations.findById(req.authorizationId());
                if (repeated.isPresent()) {
                    return decision(repeated.get());
                }
                Decline decline = declineReason(card, installments);
                if (decline == null && card.getType() == Card.Type.CREDIT
                        && amount > card.getCreditLimit() - charges.used(card.getId())) {
                    decline = new Decline(INSUFFICIENT_FUNDS, "Insufficient credit limit");
                }
                if (decline == null && fraud.assess(new FraudCheck(card.getUserId(), Channel.CARD, amount,
                        FraudCheck.merchant(req.merchantName()))).declined()) {
                    decline = new Decline(SUSPECTED_FRAUD, "Suspected fraud");
                }
                Instant now = clock.instant();
                if (decline != null) {
                    return decision(authorizations.saveAndFlush(CardAuthorization.declined(req.authorizationId(),
                            card.getId(), amount, req.merchantName(), req.mcc(), installments, decline.code(),
                            decline.reason(), now)));
                }
                var hold = card.getType() == Card.Type.DEBIT
                        ? ledger.post(new PostCommand(LedgerTransactionType.CARD_HOLD, "card-hold:" + req.authorizationId(),
                                "Card purchase at " + req.merchantName(),
                                List.of(Leg.debit(ledger.walletOf(card.getUserId()).getId(), amount),
                                        Leg.credit(AccountType.CARD_HOLDS_ACCOUNT_ID, amount))))
                        : null;
                var approved = authorizations.saveAndFlush(CardAuthorization.approved(req.authorizationId(),
                        card.getId(), amount, req.merchantName(), req.mcc(), installments,
                        hold == null ? null : hold.getId(), now));
                if (hold != null) {
                    evictAfterCommit(card.getUserId());
                }
                return decision(approved);
            });
        } catch (InsufficientFundsException e) {
            // The hold rolled back with its transaction; the decline is recorded in a new one.
            return transactions.execute(status -> {
                var card = cards.lockByProcessorToken(req.cardToken()).orElseThrow();
                return authorizations.findById(req.authorizationId()).map(CardAuthorizationService::decision)
                        .orElseGet(() -> decision(authorizations.saveAndFlush(CardAuthorization.declined(
                                req.authorizationId(), card.getId(), amount, req.merchantName(), req.mcc(),
                                installments, INSUFFICIENT_FUNDS, "Insufficient balance", clock.instant()))));
            });
        } catch (DataIntegrityViolationException e) {
            return authorizations.findById(req.authorizationId()).map(CardAuthorizationService::decision)
                    .orElseThrow(() -> e);
        }
    }

    public AuthorizationResponse clear(ClearingRequest req) {
        long amount = Money.toCents(req.amount());
        return transactions.execute(status -> {
            var auth = lockAuthorization(req.authorizationId());
            if (auth.getStatus() == CardAuthorization.Status.CLEARED) {
                if (auth.getClearedAmount() != amount) {
                    throw new ConflictException("Authorization already cleared with a different amount");
                }
                return CardService.response(auth);
            }
            if (auth.getStatus() != CardAuthorization.Status.APPROVED) {
                throw new ConflictException("Authorization is %s".formatted(auth.getStatus()));
            }
            if (amount > auth.getAmount()) {
                throw new BusinessException("Cleared amount exceeds the authorized amount");
            }
            var card = cards.lockById(auth.getCardId()).orElseThrow();
            Instant now = clock.instant();
            String key = "card-clearing:" + auth.getId();
            String description = "Card purchase at " + auth.getMerchantName();
            if (card.getType() == Card.Type.DEBIT) {
                var legs = new ArrayList<Leg>();
                legs.add(Leg.debit(AccountType.CARD_HOLDS_ACCOUNT_ID, auth.getAmount()));
                legs.add(Leg.credit(AccountType.CARD_SETTLEMENT_ACCOUNT_ID, amount));
                if (auth.getAmount() > amount) {
                    legs.add(Leg.credit(ledger.walletOf(card.getUserId()).getId(), auth.getAmount() - amount));
                    evictAfterCommit(card.getUserId());
                }
                ledger.post(new PostCommand(LedgerTransactionType.CARD_DEBIT_CLEARING, key, description, legs));
            } else {
                ledger.post(new PostCommand(LedgerTransactionType.CARD_CREDIT_CLEARING, key, description,
                        List.of(Leg.debit(AccountType.CARD_RECEIVABLES_ACCOUNT_ID, amount),
                                Leg.credit(AccountType.CARD_SETTLEMENT_ACCOUNT_ID, amount))));
                charges.addInstallments(card.getId(), auth.getId(), auth.getMerchantName(), amount,
                        auth.getInstallments(), today(), now);
            }
            auth.clear(amount, now);
            return CardService.response(auth);
        });
    }

    public AuthorizationResponse reverse(ReversalRequest req) {
        return transactions.execute(status -> {
            var auth = lockAuthorization(req.authorizationId());
            if (auth.getStatus() == CardAuthorization.Status.REVERSED) {
                return CardService.response(auth);
            }
            if (auth.getStatus() != CardAuthorization.Status.APPROVED) {
                throw new ConflictException("Authorization is %s".formatted(auth.getStatus()));
            }
            var card = cards.lockById(auth.getCardId()).orElseThrow();
            if (card.getType() == Card.Type.DEBIT) {
                ledger.post(new PostCommand(LedgerTransactionType.CARD_HOLD_RELEASE, "card-release:" + auth.getId(),
                        "Card purchase reversed at " + auth.getMerchantName(),
                        List.of(Leg.debit(AccountType.CARD_HOLDS_ACCOUNT_ID, auth.getAmount()),
                                Leg.credit(ledger.walletOf(card.getUserId()).getId(), auth.getAmount()))));
                evictAfterCommit(card.getUserId());
            }
            auth.reverse(clock.instant());
            return CardService.response(auth);
        });
    }

    private Decline declineReason(Card card, int installments) {
        return switch (card.getStatus()) {
            case CANCELLED -> new Decline(INVALID_CARD, "Card cancelled");
            case BLOCKED -> new Decline(NOT_PERMITTED, "Card blocked");
            case ACTIVE -> {
                if (card.isExpired(today())) {
                    yield new Decline(EXPIRED_CARD, "Card expired");
                }
                if (card.getType() == Card.Type.DEBIT && installments > 1) {
                    yield new Decline(NOT_PERMITTED, "Installments are not available on debit");
                }
                yield null;
            }
        };
    }

    private CardAuthorization lockAuthorization(String id) {
        return authorizations.lockById(id).orElseThrow(() -> new NotFoundException("Authorization not found"));
    }

    private void evictAfterCommit(Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                balanceCache.evict(userId);
            }
        });
    }

    private static AuthorizationDecision decision(CardAuthorization a) {
        return new AuthorizationDecision(a.getId(), a.getStatus() != CardAuthorization.Status.DECLINED,
                a.getResponseCode(), a.getDeclineReason());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(props.zone()));
    }
}

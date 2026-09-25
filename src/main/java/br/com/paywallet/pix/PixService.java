package br.com.paywallet.pix;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.exception.TransferNotAuthorizedException;
import br.com.paywallet.external.AuthorizationClient;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.hotdata.DailyLimitService;
import br.com.paywallet.hotdata.IdempotencyGuard;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.outbox.OutboxWriter;
import br.com.paywallet.pix.PixDtos.IncomingPix;
import br.com.paywallet.pix.PixDtos.PixPaymentResponse;
import br.com.paywallet.pix.PixDtos.SendPixRequest;
import br.com.paywallet.pix.PixDtos.SendResult;
import br.com.paywallet.pix.PixKeyService.Destination;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import jakarta.persistence.EntityManager;

/**
 * Pix to a key of this institution settles immediately in the ledger. Pix to another institution debits the
 * payer against the settlement account and stays PENDING until {@link PixSettlementWorker} submits it.
 */
@Service
public class PixService {

    private static final Logger log = LoggerFactory.getLogger(PixService.class);

    private final PixKeyService keys;
    private final PixPaymentRepository payments;
    private final UserService users;
    private final LedgerService ledger;
    private final AuthorizationClient authorizer;
    private final IdempotencyGuard idempotency;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final EntityManager em;
    private final PixProperties props;
    private final Clock clock;

    public PixService(PixKeyService keys, PixPaymentRepository payments, UserService users, LedgerService ledger,
                      AuthorizationClient authorizer, IdempotencyGuard idempotency, DailyLimitService limits,
                      BalanceCache balanceCache, OutboxWriter outbox, TransactionTemplate transactions,
                      EntityManager em, PixProperties props, Clock clock) {
        this.keys = keys;
        this.payments = payments;
        this.users = users;
        this.ledger = ledger;
        this.authorizer = authorizer;
        this.idempotency = idempotency;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.outbox = outbox;
        this.transactions = transactions;
        this.em = em;
        this.props = props;
        this.clock = clock;
    }

    private record Order(String key, long amount, String description) {
    }

    public SendResult send(Long payerId, SendPixRequest req, String idempotencyKey) {
        Order order = toOrder(req);
        String scopedKey = "pix:%d:%s".formatted(payerId, idempotencyKey);

        var previous = payments.findByIdempotencyKey(scopedKey);
        if (previous.isPresent()) {
            return replay(previous.get(), order);
        }
        String token = idempotency.tryAcquire(scopedKey).orElseThrow(() ->
                new ConflictException("A Pix with this Idempotency-Key is already in progress"));
        try {
            previous = payments.findByIdempotencyKey(scopedKey);
            if (previous.isPresent()) {
                return replay(previous.get(), order);
            }
            return execute(payerId, order, scopedKey);
        } finally {
            idempotency.release(scopedKey, token);
        }
    }

    private SendResult execute(Long payerId, Order order, String scopedKey) {
        User payer = users.get(payerId);
        if (!payer.getType().canSendMoney()) {
            throw new BusinessException("Merchants cannot send Pix");
        }
        Destination destination = keys.resolve(order.key());
        if (payerId.equals(destination.localUserId())) {
            throw new BusinessException("Cannot send a Pix to your own key");
        }
        var payerWallet = ledger.walletOf(payerId);
        if (payerWallet.getBalance() < order.amount()) {
            throw new InsufficientFundsException();
        }

        limits.reserve(payerId, order.amount());
        PixPayment payment;
        try {
            if (!authorizer.isAuthorized()) {
                throw new TransferNotAuthorizedException();
            }
            payment = transactions.execute(status -> destination.isLocal()
                    ? settleInternally(payer, destination, order, scopedKey)
                    : startOutgoing(payer, destination, order, scopedKey));
        } catch (DataIntegrityViolationException e) {
            releaseLimit(payerId, order.amount());
            return payments.findByIdempotencyKey(scopedKey).map(p -> replay(p, order)).orElseThrow(() -> e);
        } catch (RuntimeException e) {
            releaseLimit(payerId, order.amount());
            throw e;
        }

        balanceCache.evict(payerId);
        if (destination.isLocal()) {
            balanceCache.evict(destination.localUserId());
        }
        return new SendResult(PixPaymentResponse.from(payment), false);
    }

    private PixPayment settleInternally(User payer, Destination destination, Order order, String scopedKey) {
        User payee = users.get(destination.localUserId());
        var now = clock.instant();
        String endToEndId = EndToEndIds.generate(props.ispb(), now);
        var tx = ledger.post(new PostCommand(LedgerTransactionType.PIX_INTERNAL, scopedKey, "Pix " + endToEndId,
                List.of(Leg.debit(ledger.walletOf(payer.getId()).getId(), order.amount()),
                        Leg.credit(ledger.walletOf(payee.getId()).getId(), order.amount()))));
        var payment = PixPayment.internal(endToEndId, payer.getId(), payee.getId(), destination.key(),
                payee.getFullName(), Documents.mask(payee.getDocument()), props.ispb(), order.amount(),
                order.description(), scopedKey, tx.getId(), tx.getCreatedAt());
        em.persist(payment);
        outbox.append(Topics.PIX_RECEIVED, payee.getId().toString(), PixReceivedEvent.TYPE,
                new PixReceivedEvent(endToEndId, payee.getId(), payee.getEmail(), payer.getFullName(),
                        order.amount(), tx.getCreatedAt()));
        return payment;
    }

    private PixPayment startOutgoing(User payer, Destination destination, Order order, String scopedKey) {
        var now = clock.instant();
        String endToEndId = EndToEndIds.generate(props.ispb(), now);
        var account = destination.external();
        var tx = ledger.post(new PostCommand(LedgerTransactionType.PIX_OUT, scopedKey, "Pix " + endToEndId,
                List.of(Leg.debit(ledger.walletOf(payer.getId()).getId(), order.amount()),
                        Leg.credit(AccountType.PIX_SETTLEMENT_ACCOUNT_ID, order.amount()))));
        var payment = PixPayment.outgoing(endToEndId, payer.getId(), destination.key(), account.holderName(),
                Documents.mask(account.holderDocument()), account.ispb(), order.amount(), order.description(),
                scopedKey, tx.getId(), tx.getCreatedAt());
        em.persist(payment);
        return payment;
    }

    /** Idempotent by end-to-end id: the PSP may deliver the same notification more than once. */
    public PixPaymentResponse receive(IncomingPix incoming) {
        var existing = payments.findByEndToEndId(incoming.endToEndId());
        if (existing.isPresent()) {
            return PixPaymentResponse.from(existing.get());
        }
        var localKey = keys.findLocal(PixKeyType.detect(incoming.key()).normalize(incoming.key()))
                .orElseThrow(() -> new NotFoundException("Pix key not found"));
        long amount = Money.toCents(incoming.value());
        User payee = users.get(localKey.getUserId());

        PixPayment payment;
        try {
            payment = transactions.execute(status -> {
                var tx = ledger.post(new PostCommand(LedgerTransactionType.PIX_IN, "pix-in:" + incoming.endToEndId(),
                        "Pix " + incoming.endToEndId(),
                        List.of(Leg.debit(AccountType.PIX_SETTLEMENT_ACCOUNT_ID, amount),
                                Leg.credit(ledger.walletOf(payee.getId()).getId(), amount))));
                var received = PixPayment.incoming(incoming.endToEndId(), payee.getId(), localKey.getValue(),
                        incoming.payerName(), Documents.mask(incoming.payerDocument()), incoming.payerIspb(), amount,
                        incoming.description(), tx.getId(), tx.getCreatedAt());
                em.persist(received);
                outbox.append(Topics.PIX_RECEIVED, payee.getId().toString(), PixReceivedEvent.TYPE,
                        new PixReceivedEvent(incoming.endToEndId(), payee.getId(), payee.getEmail(),
                                incoming.payerName(), amount, tx.getCreatedAt()));
                return received;
            });
        } catch (DataIntegrityViolationException e) {
            return payments.findByEndToEndId(incoming.endToEndId()).map(PixPaymentResponse::from).orElseThrow(() -> e);
        }
        balanceCache.evict(payee.getId());
        return PixPaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PixPaymentResponse get(Long userId, String endToEndId) {
        return payments.findByEndToEndId(endToEndId)
                .filter(p -> p.involves(userId))
                .map(PixPaymentResponse::from)
                .orElseThrow(() -> new NotFoundException("Pix payment not found"));
    }

    private Order toOrder(SendPixRequest req) {
        boolean hasKey = req.key() != null && !req.key().isBlank();
        boolean hasCode = req.brCode() != null && !req.brCode().isBlank();
        if (hasKey == hasCode) {
            throw new BusinessException("Provide either a Pix key or a QR code");
        }
        if (hasKey) {
            if (req.value() == null) {
                throw new BusinessException("Amount is required");
            }
            return new Order(req.key(), Money.toCents(req.value()), req.description());
        }
        BrCode code = BrCode.parse(req.brCode());
        BigDecimal amount = code.amount() != null ? code.amount() : req.value();
        if (amount == null) {
            throw new BusinessException("Amount is required");
        }
        if (code.amount() != null && req.value() != null && code.amount().compareTo(req.value()) != 0) {
            throw new BusinessException("Amount differs from the QR code");
        }
        String description = req.description() != null ? req.description() : code.description();
        return new Order(code.key(), Money.toCents(amount), description);
    }

    private SendResult replay(PixPayment payment, Order order) {
        String key = PixKeyType.detect(order.key()).normalize(order.key());
        if (payment.getAmount() != order.amount() || !key.equals(payment.getKey())) {
            throw new BusinessException("Idempotency-Key already used for a different Pix");
        }
        return new SendResult(PixPaymentResponse.from(payment), true);
    }

    private void releaseLimit(Long userId, long amount) {
        try {
            limits.release(userId, amount);
        } catch (DataAccessException e) {
            log.error("Failed to release limit reservation for user {}: {}", userId, e.getMessage());
        }
    }
}

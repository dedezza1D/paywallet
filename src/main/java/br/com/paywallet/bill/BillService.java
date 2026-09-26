package br.com.paywallet.bill;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.bill.BillDtos.BillPaymentResponse;
import br.com.paywallet.bill.BillDtos.BillQuoteResponse;
import br.com.paywallet.bill.BillDtos.PayBillRequest;
import br.com.paywallet.bill.BillDtos.PayResult;
import br.com.paywallet.bill.BillGateway.BillQuote;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.InsufficientFundsException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.exception.TransferNotAuthorizedException;
import br.com.paywallet.external.AuthorizationClient;
import br.com.paywallet.fraud.Channel;
import br.com.paywallet.fraud.FraudCheck;
import br.com.paywallet.fraud.FraudService;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.hotdata.DailyLimitService;
import br.com.paywallet.hotdata.IdempotencyGuard;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.user.Documents;
import br.com.paywallet.user.UserService;
import jakarta.persistence.EntityManager;

/**
 * Pays boletos with wallet balance. The payer is debited against the bill settlement account right away and
 * the payment stays PENDING until {@link BillSettlementWorker} settles it with the banking partner.
 */
@Service
public class BillService {

    private static final Logger log = LoggerFactory.getLogger(BillService.class);

    private final BillGateway gateway;
    private final BillPaymentRepository payments;
    private final UserService users;
    private final LedgerService ledger;
    private final AuthorizationClient authorizer;
    private final FraudService fraud;
    private final IdempotencyGuard idempotency;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final EntityManager em;
    private final Clock clock;

    public BillService(BillGateway gateway, BillPaymentRepository payments, UserService users, LedgerService ledger,
                       AuthorizationClient authorizer, FraudService fraud, IdempotencyGuard idempotency,
                       DailyLimitService limits, BalanceCache balanceCache, TransactionTemplate transactions,
                       EntityManager em, Clock clock) {
        this.gateway = gateway;
        this.payments = payments;
        this.users = users;
        this.ledger = ledger;
        this.authorizer = authorizer;
        this.fraud = fraud;
        this.idempotency = idempotency;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.em = em;
        this.clock = clock;
    }

    private record Resolved(BoletoCode code, BillQuote quote, String notPayableReason) {
    }

    public BillQuoteResponse lookup(String rawCode) {
        Resolved bill = resolve(rawCode);
        var quote = bill.quote();
        return new BillQuoteResponse(bill.code().kind(), bill.code().barcode(), bill.code().digitableLine(),
                bill.code().bankCode(), quote.beneficiaryName(), Documents.mask(quote.beneficiaryDocument()),
                quote.dueDate() != null ? quote.dueDate() : bill.code().dueDate(),
                quote.nominalAmountCents() == null ? null : Money.fromCents(quote.nominalAmountCents()),
                Money.fromCents(quote.amountDueCents()), Money.fromCents(quote.minAmountCents()),
                Money.fromCents(quote.maxAmountCents()), quote.paymentDeadline(), bill.notPayableReason() == null,
                bill.notPayableReason());
    }

    public PayResult pay(Long payerId, PayBillRequest req, String idempotencyKey) {
        String scopedKey = "bill:%d:%s".formatted(payerId, idempotencyKey);
        var previous = payments.findByIdempotencyKey(scopedKey);
        if (previous.isPresent()) {
            return replay(previous.get(), req);
        }
        String token = idempotency.tryAcquire(scopedKey).orElseThrow(() ->
                new ConflictException("A bill payment with this Idempotency-Key is already in progress"));
        try {
            previous = payments.findByIdempotencyKey(scopedKey);
            if (previous.isPresent()) {
                return replay(previous.get(), req);
            }
            return execute(payerId, req, scopedKey);
        } finally {
            idempotency.release(scopedKey, token);
        }
    }

    private PayResult execute(Long payerId, PayBillRequest req, String scopedKey) {
        Resolved bill = resolve(req.code());
        if (bill.notPayableReason() != null) {
            throw new BusinessException(bill.notPayableReason());
        }
        long amount = amountToPay(bill.quote(), req);
        if (payments.isPaidOrInFlight(bill.code().barcode())) {
            throw new ConflictException("This bill has already been paid");
        }
        if (ledger.walletOf(payerId).getBalance() < amount) {
            throw new InsufficientFundsException();
        }

        fraud.screen(new FraudCheck(payerId, Channel.BILL, amount,
                FraudCheck.document(bill.quote().beneficiaryDocument())));
        limits.reserve(payerId, amount);
        BillPayment payment;
        try {
            if (!authorizer.isAuthorized()) {
                throw new TransferNotAuthorizedException();
            }
            payment = transactions.execute(status -> {
                var tx = ledger.post(new PostCommand(LedgerTransactionType.BILL_PAYMENT, scopedKey,
                        "Bill " + bill.code().barcode(),
                        List.of(Leg.debit(ledger.walletOf(payerId).getId(), amount),
                                Leg.credit(AccountType.BILL_SETTLEMENT_ACCOUNT_ID, amount))));
                var created = new BillPayment(payerId, bill.code(), bill.quote(),
                        Documents.mask(bill.quote().beneficiaryDocument()), amount, scopedKey, tx.getId(),
                        clock.instant());
                em.persist(created);
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            releaseLimit(payerId, amount);
            // Either this request raced itself (same key) or another payer settled the same barcode first.
            return payments.findByIdempotencyKey(scopedKey).map(p -> replay(p, req))
                    .orElseThrow(() -> new ConflictException("This bill has already been paid"));
        } catch (RuntimeException e) {
            releaseLimit(payerId, amount);
            throw e;
        }
        balanceCache.evict(payerId);
        return new PayResult(BillPaymentResponse.from(payment), false);
    }

    @Transactional(readOnly = true)
    public BillPaymentResponse get(Long payerId, UUID id) {
        return payments.findByIdAndPayerUserId(id, payerId).map(BillPaymentResponse::from)
                .orElseThrow(() -> new NotFoundException("Bill payment not found"));
    }

    @Transactional(readOnly = true)
    public Page<BillPaymentResponse> list(Long payerId, Pageable pageable) {
        return payments.findByPayerUserId(payerId, pageable).map(BillPaymentResponse::from);
    }

    private Resolved resolve(String rawCode) {
        LocalDate today = LocalDate.now(clock);
        var code = BoletoCode.parse(rawCode, today);
        var quote = gateway.lookup(code.barcode())
                .orElseThrow(() -> new NotFoundException("Bill not found in the registry"));
        String reason = null;
        if (quote.alreadyPaid()) {
            reason = "This bill has already been paid";
        } else if (quote.paymentDeadline() != null && today.isAfter(quote.paymentDeadline())) {
            reason = "Payment deadline has passed";
        }
        return new Resolved(code, quote, reason);
    }

    private static long amountToPay(BillQuote quote, PayBillRequest req) {
        if (req.value() == null) {
            return quote.amountDueCents();
        }
        long requested = Money.toCents(req.value());
        if (requested < quote.minAmountCents() || requested > quote.maxAmountCents()) {
            throw new BusinessException("Amount must be between R$ %s and R$ %s".formatted(
                    Money.fromCents(quote.minAmountCents()), Money.fromCents(quote.maxAmountCents())));
        }
        return requested;
    }

    private PayResult replay(BillPayment payment, PayBillRequest req) {
        String barcode = BoletoCode.parse(req.code(), LocalDate.now(clock)).barcode();
        if (!barcode.equals(payment.getBarcode())) {
            throw new BusinessException("Idempotency-Key already used for a different bill");
        }
        return new PayResult(BillPaymentResponse.from(payment), true);
    }

    private void releaseLimit(Long userId, long amount) {
        try {
            limits.release(userId, amount);
        } catch (DataAccessException e) {
            log.error("Failed to release limit reservation for user {}: {}", userId, e.getMessage());
        }
    }
}

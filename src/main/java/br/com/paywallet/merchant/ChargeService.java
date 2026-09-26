package br.com.paywallet.merchant;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.merchant.ChargeDtos.ChargeResponse;
import br.com.paywallet.merchant.ChargeDtos.CreateChargeRequest;
import br.com.paywallet.merchant.ChargeDtos.CreateResult;
import br.com.paywallet.merchant.ChargeDtos.PayResult;
import br.com.paywallet.merchant.ChargeDtos.PaymentReceipt;
import br.com.paywallet.merchant.ChargeDtos.PublicChargeResponse;
import br.com.paywallet.messaging.Topics;
import br.com.paywallet.outbox.OutboxWriter;
import br.com.paywallet.pix.BrCode;
import br.com.paywallet.pix.PixKey;
import br.com.paywallet.pix.PixKeyService;
import br.com.paywallet.pix.PixKeyType;
import br.com.paywallet.pix.PixProperties;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;
import jakarta.persistence.EntityManager;

/**
 * Merchant charges, payable once, before expiry, either with wallet balance ({@link #payWithWallet}) or with
 * a Pix carrying the charge's txid (through {@link #claimForPix}). Every payment splits the amount between the
 * merchant (net) and {@code SYSTEM_FEES} (MDR) in a single ledger movement.
 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TXID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ChargeRepository charges;
    private final UserService users;
    private final LedgerService ledger;
    private final PixKeyService pixKeys;
    private final AuthorizationClient authorizer;
    private final FraudService fraud;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final EntityManager em;
    private final MerchantProperties props;
    private final PixProperties pixProps;
    private final Clock clock;

    public ChargeService(ChargeRepository charges, UserService users, LedgerService ledger, PixKeyService pixKeys,
                         AuthorizationClient authorizer, FraudService fraud, DailyLimitService limits,
                         BalanceCache balanceCache, OutboxWriter outbox, TransactionTemplate transactions,
                         EntityManager em, MerchantProperties props, PixProperties pixProps, Clock clock) {
        this.charges = charges;
        this.users = users;
        this.ledger = ledger;
        this.pixKeys = pixKeys;
        this.authorizer = authorizer;
        this.fraud = fraud;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.outbox = outbox;
        this.transactions = transactions;
        this.em = em;
        this.props = props;
        this.pixProps = pixProps;
        this.clock = clock;
    }

    public CreateResult create(Long merchantId, CreateChargeRequest req) {
        User merchant = users.get(merchantId);
        if (merchant.getType() != UserType.MERCHANT) {
            throw new BusinessException("Only merchants can create charges");
        }
        if (req.reference() != null) {
            var existing = charges.findByMerchantIdAndReference(merchantId, req.reference());
            if (existing.isPresent()) {
                return new CreateResult(toResponse(existing.get(), merchant), true);
            }
        }
        Duration ttl = req.expiresInMinutes() == null ? props.defaultChargeTtl()
                : Duration.ofMinutes(req.expiresInMinutes());
        if (ttl.compareTo(props.maxChargeTtl()) > 0) {
            throw new BusinessException("Charge validity exceeds the maximum of %d minutes"
                    .formatted(props.maxChargeTtl().toMinutes()));
        }
        Instant now = clock.instant();
        var charge = new Charge(merchantId, randomToken(), randomTxid(), Money.toCents(req.value()),
                req.description(), req.reference(), pickPixKey(merchantId, req.pixKey()), now, now.plus(ttl));
        try {
            transactions.executeWithoutResult(status -> em.persist(charge));
        } catch (DataIntegrityViolationException e) {
            return charges.findByMerchantIdAndReference(merchantId, req.reference())
                    .map(c -> new CreateResult(toResponse(c, merchant), true))
                    .orElseThrow(() -> e);
        }
        return new CreateResult(toResponse(charge, merchant), false);
    }

    @Transactional(readOnly = true)
    public ChargeResponse get(Long merchantId, UUID chargeId) {
        return toResponse(owned(merchantId, chargeId), users.get(merchantId));
    }

    @Transactional(readOnly = true)
    public Page<ChargeResponse> list(Long merchantId, Charge.Status status, Pageable pageable) {
        User merchant = users.get(merchantId);
        var page = status == null ? charges.findByMerchantId(merchantId, pageable)
                : charges.findByMerchantIdAndStatus(merchantId, status, pageable);
        return page.map(c -> toResponse(c, merchant));
    }

    @Transactional
    public ChargeResponse cancel(Long merchantId, UUID chargeId) {
        owned(merchantId, chargeId);
        var charge = charges.lockById(chargeId).orElseThrow();
        if (charge.effectiveStatus(clock.instant()) != Charge.Status.PENDING) {
            throw new BusinessException("Only pending charges can be cancelled");
        }
        charge.cancel();
        return toResponse(charge, users.get(merchantId));
    }

    @Transactional(readOnly = true)
    public PublicChargeResponse publicView(String token) {
        var charge = charges.findByPublicToken(token).orElseThrow(() -> new NotFoundException("Charge not found"));
        User merchant = users.get(charge.getMerchantId());
        Charge.Status status = charge.effectiveStatus(clock.instant());
        return new PublicChargeResponse(merchant.getFullName(), Money.fromCents(charge.getAmount()),
                charge.getDescription(), status, charge.getExpiresAt(),
                status == Charge.Status.PENDING ? brCode(charge, merchant) : null);
    }

    /**
     * Pays with wallet balance. The charge itself is the idempotency key: a retry by the same payer returns
     * the original receipt, while anyone else gets 409.
     */
    public PayResult payWithWallet(Long payerId, String token) {
        var charge = charges.findByPublicToken(token).orElseThrow(() -> new NotFoundException("Charge not found"));
        if (charge.getStatus() == Charge.Status.PAID) {
            return replayOrConflict(charge, payerId);
        }
        User payer = users.get(payerId);
        if (!payer.getType().canSendMoney()) {
            throw new BusinessException("Merchants cannot pay charges");
        }
        ensurePayable(charge, charge.getAmount());
        if (ledger.walletOf(payerId).getBalance() < charge.getAmount()) {
            throw new InsufficientFundsException();
        }

        fraud.screen(new FraudCheck(payerId, Channel.CHARGE, charge.getAmount(),
                FraudCheck.user(charge.getMerchantId())));
        limits.reserve(payerId, charge.getAmount());
        Charge paid;
        try {
            if (!authorizer.isAuthorized()) {
                throw new TransferNotAuthorizedException();
            }
            paid = transactions.execute(status -> {
                var locked = charges.lockById(charge.getId()).orElseThrow();
                if (locked.getStatus() == Charge.Status.PAID) {
                    throw new ConflictException("Charge already paid");
                }
                ensurePayable(locked, locked.getAmount());
                Fee fee = Fee.of(locked.getAmount(), props.walletFeeBps());
                var tx = ledger.post(new PostCommand(LedgerTransactionType.CHARGE_PAYMENT,
                        "charge:" + locked.getId(), "Charge " + locked.getId(),
                        splitLegs(ledger.walletOf(payerId).getId(), locked.getMerchantId(), locked.getAmount(), fee)));
                markPaid(locked, PaymentMethod.WALLET, payerId, null, fee, tx.getId());
                return locked;
            });
        } catch (ConflictException | DataIntegrityViolationException e) {
            releaseLimit(payerId, charge.getAmount());
            return replayOrConflict(charges.findById(charge.getId()).orElseThrow(), payerId);
        } catch (RuntimeException e) {
            releaseLimit(payerId, charge.getAmount());
            throw e;
        }
        balanceCache.evict(payerId);
        balanceCache.evict(paid.getMerchantId());
        return new PayResult(receipt(paid), false);
    }

    /**
     * Called inside the Pix settlement transaction when a Pix carries a txid. Returns the locked charge when the
     * txid belongs to the receiving merchant. With {@code strict}, an unpayable charge rejects the Pix (payers of
     * this institution can be refused); without it (Pix already sent by another bank) the Pix is credited as a
     * plain transfer instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Charge> claimForPix(String txid, Long payeeUserId, long amount, boolean strict) {
        if (txid == null || txid.isBlank() || "***".equals(txid)) {
            return Optional.empty();
        }
        var charge = charges.lockByTxid(txid).filter(c -> c.getMerchantId().equals(payeeUserId));
        if (charge.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (charge.get().getStatus() == Charge.Status.PAID) {
                throw new BusinessException("Charge already paid");
            }
            ensurePayable(charge.get(), amount);
            return charge;
        } catch (BusinessException e) {
            if (strict) {
                throw e;
            }
            log.warn("Pix for charge {} credited without settling it: {}", charge.get().getId(), e.getMessage());
            return Optional.empty();
        }
    }

    public Fee pixFee(long amount) {
        return Fee.of(amount, props.pixFeeBps());
    }

    /** Debits the payer's full amount and credits the merchant's net plus the platform fee. */
    public List<Leg> splitLegs(UUID payerAccountId, Long merchantId, long amount, Fee fee) {
        var legs = new ArrayList<Leg>();
        legs.add(Leg.debit(payerAccountId, amount));
        legs.add(Leg.credit(ledger.walletOf(merchantId).getId(), fee.netCents()));
        if (fee.feeCents() > 0) {
            legs.add(Leg.credit(AccountType.FEES_ACCOUNT_ID, fee.feeCents()));
        }
        return legs;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPaid(Charge charge, PaymentMethod method, Long payerUserId, String endToEndId, Fee fee,
                         UUID ledgerTransactionId) {
        Instant now = clock.instant();
        charge.markPaid(method, payerUserId, endToEndId, fee, ledgerTransactionId, now);
        User merchant = users.get(charge.getMerchantId());
        outbox.append(Topics.CHARGES_PAID, merchant.getId().toString(), ChargePaidEvent.TYPE,
                new ChargePaidEvent(charge.getId(), merchant.getId(), merchant.getEmail(), charge.getReference(),
                        charge.getAmount(), fee.netCents(), method, now));
    }

    /** Keeps the refunded total of a charge paid by Pix in step with returns of that Pix. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPixReturn(String endToEndId, long delta) {
        charges.lockByEndToEndId(endToEndId).ifPresent(c -> c.addRefund(delta));
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOverdue() {
        int expired = charges.expireOverdue(clock.instant());
        if (expired > 0) {
            log.info("Expired {} charges", expired);
        }
    }

    private void ensurePayable(Charge charge, long amount) {
        switch (charge.effectiveStatus(clock.instant())) {
            case PENDING -> {
                if (amount != charge.getAmount()) {
                    throw new BusinessException("Amount differs from the charge");
                }
            }
            case PAID -> throw new ConflictException("Charge already paid");
            case EXPIRED -> throw new BusinessException("Charge expired");
            case CANCELLED -> throw new BusinessException("Charge cancelled");
        }
    }

    private PayResult replayOrConflict(Charge charge, Long payerId) {
        if (charge.getStatus() == Charge.Status.PAID && payerId.equals(charge.getPayerUserId())
                && charge.getPaymentMethod() == PaymentMethod.WALLET) {
            return new PayResult(receipt(charge), true);
        }
        throw new ConflictException("Charge already paid");
    }

    private PaymentReceipt receipt(Charge charge) {
        return new PaymentReceipt(charge.getId(), charge.getStatus(), Money.fromCents(charge.getAmount()),
                users.get(charge.getMerchantId()).getFullName(), charge.getLedgerTransactionId(), charge.getPaidAt());
    }

    private Charge owned(Long merchantId, UUID chargeId) {
        return charges.findByIdAndMerchantId(chargeId, merchantId)
                .orElseThrow(() -> new NotFoundException("Charge not found"));
    }

    private String pickPixKey(Long merchantId, String requested) {
        List<PixKey> keys = pixKeys.list(merchantId);
        if (requested != null) {
            String normalized = PixKeyType.detect(requested).normalize(requested);
            return keys.stream().map(PixKey::getValue).filter(normalized::equals).findFirst()
                    .orElseThrow(() -> new BusinessException("Charges can only use your own Pix keys"));
        }
        return keys.stream()
                .min(Comparator.comparing((PixKey k) -> k.getType() != PixKeyType.EVP).thenComparing(PixKey::getCreatedAt))
                .map(PixKey::getValue)
                .orElse(null);
    }

    private ChargeResponse toResponse(Charge c, User merchant) {
        Charge.Status status = c.effectiveStatus(clock.instant());
        return new ChargeResponse(c.getId(), status, Money.fromCents(c.getAmount()), c.getDescription(),
                c.getReference(), c.getTxid(), props.paymentLinkBaseUrl() + c.getPublicToken(),
                status == Charge.Status.PENDING ? brCode(c, merchant) : null, c.getCreatedAt(), c.getExpiresAt(),
                c.getPaidAt(), c.getPaymentMethod(),
                c.getFeeAmount() == null ? null : Money.fromCents(c.getFeeAmount()),
                c.getNetAmount() == null ? null : Money.fromCents(c.getNetAmount()),
                Money.fromCents(c.getRefundedAmount()));
    }

    private String brCode(Charge charge, User merchant) {
        if (charge.getPixKey() == null) {
            return null;
        }
        return new BrCode(charge.getPixKey(), Money.fromCents(charge.getAmount()), charge.getDescription(),
                merchant.getFullName(), pixProps.merchantCity(), charge.getTxid()).encode();
    }

    private void releaseLimit(Long userId, long amount) {
        try {
            limits.release(userId, amount);
        } catch (DataAccessException e) {
            log.error("Failed to release limit reservation for user {}: {}", userId, e.getMessage());
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomTxid() {
        var txid = new StringBuilder("PW");
        for (int i = 0; i < 23; i++) {
            txid.append(TXID_ALPHABET.charAt(RANDOM.nextInt(TXID_ALPHABET.length())));
        }
        return txid.toString();
    }
}

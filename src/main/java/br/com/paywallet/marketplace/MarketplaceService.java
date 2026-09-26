package br.com.paywallet.marketplace;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
import br.com.paywallet.marketplace.MarketplaceDtos.CashbackSummary;
import br.com.paywallet.marketplace.MarketplaceDtos.OrderResponse;
import br.com.paywallet.marketplace.MarketplaceDtos.ProductResponse;
import br.com.paywallet.marketplace.MarketplaceDtos.PurchaseRequest;
import br.com.paywallet.marketplace.MarketplaceDtos.PurchaseResult;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;
import jakarta.persistence.EntityManager;

/**
 * Sells digital products with wallet balance. The buyer is debited into the marketplace settlement account right
 * away and the order stays PENDING until {@link FulfillmentWorker} gets the product from the provider.
 */
@Service
public class MarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);

    private final ProductRepository products;
    private final MarketplaceOrderRepository orders;
    private final UserService users;
    private final LedgerService ledger;
    private final AuthorizationClient authorizer;
    private final IdempotencyGuard idempotency;
    private final DailyLimitService limits;
    private final BalanceCache balanceCache;
    private final VoucherCipher cipher;
    private final TransactionTemplate transactions;
    private final EntityManager em;
    private final MarketplaceProperties props;
    private final Clock clock;

    public MarketplaceService(ProductRepository products, MarketplaceOrderRepository orders, UserService users,
                              LedgerService ledger, AuthorizationClient authorizer, IdempotencyGuard idempotency,
                              DailyLimitService limits, BalanceCache balanceCache, VoucherCipher cipher,
                              TransactionTemplate transactions, EntityManager em, MarketplaceProperties props,
                              Clock clock) {
        this.products = products;
        this.orders = orders;
        this.users = users;
        this.ledger = ledger;
        this.authorizer = authorizer;
        this.idempotency = idempotency;
        this.limits = limits;
        this.balanceCache = balanceCache;
        this.cipher = cipher;
        this.transactions = transactions;
        this.em = em;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> catalog(Product.Category category) {
        var list = category == null ? products.findByActiveTrueOrderByCategoryAscBrandAsc()
                : products.findByActiveTrueAndCategoryOrderByBrand(category);
        return list.stream().map(p -> new ProductResponse(p.getId(), p.getCategory(), p.getBrand(), p.getName(),
                p.denominationList().stream().map(Money::fromCents).toList(),
                BigDecimal.valueOf(p.getCashbackBps(), 2))).toList();
    }

    public PurchaseResult purchase(Long userId, PurchaseRequest req, String idempotencyKey) {
        String scopedKey = "marketplace:%d:%s".formatted(userId, idempotencyKey);
        var previous = orders.findByIdempotencyKey(scopedKey);
        if (previous.isPresent()) {
            return replay(previous.get(), req);
        }
        String token = idempotency.tryAcquire(scopedKey).orElseThrow(() ->
                new ConflictException("A purchase with this Idempotency-Key is already in progress"));
        try {
            previous = orders.findByIdempotencyKey(scopedKey);
            if (previous.isPresent()) {
                return replay(previous.get(), req);
            }
            return execute(userId, req, scopedKey);
        } finally {
            idempotency.release(scopedKey, token);
        }
    }

    private PurchaseResult execute(Long userId, PurchaseRequest req, String scopedKey) {
        if (users.get(userId).getType() != UserType.COMMON) {
            throw new BusinessException("Marketplace purchases are available to individuals only");
        }
        var product = products.findById(req.productId()).filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        long amount = Money.toCents(req.value());
        if (!product.denominationList().contains(amount)) {
            throw new BusinessException("Value not available for this product");
        }
        boolean recharge = product.getCategory() == Product.Category.MOBILE_RECHARGE;
        if (recharge && req.phoneNumber() == null) {
            throw new BusinessException("Mobile recharges require a phone number");
        }
        if (!recharge && req.phoneNumber() != null) {
            throw new BusinessException("Only mobile recharges take a phone number");
        }
        if (ledger.walletOf(userId).getBalance() < amount) {
            throw new InsufficientFundsException();
        }

        limits.reserve(userId, amount);
        MarketplaceOrder order;
        try {
            if (!authorizer.isAuthorized()) {
                throw new TransferNotAuthorizedException();
            }
            order = transactions.execute(status -> {
                var tx = ledger.post(new PostCommand(LedgerTransactionType.MARKETPLACE_PURCHASE, scopedKey,
                        product.getName(),
                        List.of(Leg.debit(ledger.walletOf(userId).getId(), amount),
                                Leg.credit(AccountType.MARKETPLACE_SETTLEMENT_ACCOUNT_ID, amount))));
                var created = new MarketplaceOrder(userId, product, amount, req.phoneNumber(), scopedKey, tx.getId(),
                        clock.instant());
                em.persist(created);
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            releaseLimit(userId, amount);
            return orders.findByIdempotencyKey(scopedKey).map(o -> replay(o, req)).orElseThrow(() -> e);
        } catch (RuntimeException e) {
            releaseLimit(userId, amount);
            throw e;
        }
        balanceCache.evict(userId);
        return new PurchaseResult(response(order, product, false), false);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, UUID orderId) {
        var order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> new NotFoundException("Order not found"));
        return response(order, products.getReferenceById(order.getProductId()), true);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> list(Long userId, Pageable pageable) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(o -> response(o, products.getReferenceById(o.getProductId()), false));
    }

    @Transactional(readOnly = true)
    public CashbackSummary cashback(Long userId) {
        Instant monthStart = LocalDate.now(clock.withZone(props.zone())).withDayOfMonth(1)
                .atStartOfDay(props.zone()).toInstant();
        return new CashbackSummary(Money.fromCents(orders.cashbackSince(userId, Instant.EPOCH)),
                Money.fromCents(orders.cashbackSince(userId, monthStart)));
    }

    private PurchaseResult replay(MarketplaceOrder order, PurchaseRequest req) {
        if (!order.getProductId().equals(req.productId()) || order.getAmount() != Money.toCents(req.value())
                || !Objects.equals(order.getPhoneNumber(), req.phoneNumber())) {
            throw new BusinessException("Idempotency-Key already used for a different purchase");
        }
        return new PurchaseResult(response(order, products.findById(order.getProductId()).orElseThrow(), false), true);
    }

    private OrderResponse response(MarketplaceOrder o, Product product, boolean withVoucher) {
        String voucher = withVoucher && o.getVoucherCode() != null ? cipher.decrypt(o.getVoucherCode()) : null;
        return new OrderResponse(o.getId(), o.getProductId(), product.getName(), product.getCategory(),
                Money.fromCents(o.getAmount()), Money.fromCents(o.getCashback()), o.getPhoneNumber(), o.getStatus(),
                voucher, o.getFailureReason(), o.getCreatedAt(), o.getCompletedAt());
    }

    private void releaseLimit(Long userId, long amount) {
        try {
            limits.release(userId, amount);
        } catch (DataAccessException e) {
            log.error("Failed to release limit reservation for user {}: {}", userId, e.getMessage());
        }
    }
}

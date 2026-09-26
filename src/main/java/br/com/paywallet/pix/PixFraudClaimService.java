package br.com.paywallet.pix;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.fraud.FraudAdminService;
import br.com.paywallet.hotdata.BalanceCache;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.ledger.LedgerService.Leg;
import br.com.paywallet.ledger.LedgerService.PostCommand;
import br.com.paywallet.ledger.LedgerTransactionType;
import br.com.paywallet.ledger.Money;
import br.com.paywallet.pix.PixReturnDtos.FraudClaimResponse;
import br.com.paywallet.pix.PixReturnDtos.FraudClaimStatus;
import br.com.paywallet.pix.PixReturnDtos.ResolveClaimRequest;

/**
 * Special Return Mechanism (MED) for Pix between customers of this institution. A payer reports a Pix as fraud;
 * whatever is still in the receiver's wallet, up to the amount not yet returned, is frozen in SYSTEM_PIX_MED_HOLDS.
 * An analyst then either returns the frozen money to the payer, optionally freezing the receiver's outflows, or
 * releases it back to the receiver.
 */
@Service
public class PixFraudClaimService {

    static final Duration CLAIM_WINDOW = Duration.ofDays(80);

    private static final String COLUMNS = "id, end_to_end_id, claimant_user_id, receiver_user_id, description, "
            + "blocked_amount, returned_amount, status, created_at, resolved_at";

    private final PixPaymentRepository payments;
    private final PixReturnRepository returns;
    private final PixReturnService returnService;
    private final FraudAdminService fraudAdmin;
    private final LedgerService ledger;
    private final BalanceCache balanceCache;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PixFraudClaimService(PixPaymentRepository payments, PixReturnRepository returns,
                                PixReturnService returnService, FraudAdminService fraudAdmin, LedgerService ledger,
                                BalanceCache balanceCache, TransactionTemplate transactions, JdbcTemplate jdbc,
                                Clock clock) {
        this.payments = payments;
        this.returns = returns;
        this.returnService = returnService;
        this.fraudAdmin = fraudAdmin;
        this.ledger = ledger;
        this.balanceCache = balanceCache;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public FraudClaimResponse file(Long claimantId, String endToEndId, String description) {
        UUID claimId;
        try {
            claimId = transactions.execute(status -> {
                var original = payments.lockByEndToEndId(endToEndId)
                        .filter(p -> claimantId.equals(p.getPayerUserId()))
                        .orElseThrow(() -> new NotFoundException("Pix not found"));
                if (original.getScope() != PixPayment.Scope.INTERNAL) {
                    throw new BusinessException("Claims on Pix sent to other institutions are handled by the "
                            + "receiving institution through the central bank");
                }
                if (original.getStatus() != PixPayment.Status.COMPLETED) {
                    throw new BusinessException("Only completed Pix can be claimed");
                }
                Instant now = clock.instant();
                if (original.getCreatedAt().plus(CLAIM_WINDOW).isBefore(now)) {
                    throw new BusinessException("Fraud claims must be filed within 80 days");
                }
                Long receiver = original.getPayeeUserId();
                long claimable = original.getAmount() - returns.returnedAmount(endToEndId);
                if (claimable <= 0) {
                    throw new BusinessException("This Pix has already been returned");
                }
                long blocked = Math.min(claimable, ledger.walletOf(receiver).getBalance());
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO pix_fraud_claims (id, end_to_end_id, claimant_user_id, receiver_user_id,
                                                      description, blocked_amount, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                        """, id, endToEndId, claimantId, receiver, description, blocked, Timestamp.from(now));
                if (blocked > 0) {
                    ledger.post(new PostCommand(LedgerTransactionType.PIX_MED_BLOCK, "pix-med-block:" + id,
                            "Pix fraud claim " + endToEndId,
                            List.of(Leg.debit(ledger.walletOf(receiver).getId(), blocked),
                                    Leg.credit(AccountType.PIX_MED_HOLDS_ACCOUNT_ID, blocked))));
                }
                return id;
            });
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A fraud claim for this Pix already exists");
        }
        var claim = get(claimId);
        balanceCache.evict(claim.receiverUserId());
        return claim;
    }

    public List<FraudClaimResponse> mine(Long claimantId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM pix_fraud_claims WHERE claimant_user_id = ? ORDER BY created_at DESC",
                PixFraudClaimService::claim, claimantId);
    }

    public List<FraudClaimResponse> list(FraudClaimStatus status) {
        String name = status == null ? null : status.name();
        return jdbc.query("SELECT " + COLUMNS
                + " FROM pix_fraud_claims WHERE (CAST(? AS VARCHAR) IS NULL OR status = ?)"
                + " ORDER BY created_at DESC LIMIT 200", PixFraudClaimService::claim, name, name);
    }

    public FraudClaimResponse resolve(UUID claimId, ResolveClaimRequest req, Long analystId) {
        var resolved = transactions.execute(status -> {
            var claim = jdbc.query("SELECT " + COLUMNS + " FROM pix_fraud_claims WHERE id = ? FOR UPDATE",
                    PixFraudClaimService::claim, claimId).stream().findFirst()
                    .orElseThrow(() -> new NotFoundException("Fraud claim not found"));
            if (claim.status() != FraudClaimStatus.OPEN) {
                throw new BusinessException("Fraud claim already resolved as " + claim.status());
            }
            var original = payments.lockByEndToEndId(claim.endToEndId()).orElseThrow();
            long blocked = Money.toCents(claim.blocked());
            Instant now = clock.instant();
            if (req.accepted()) {
                if (blocked > 0) {
                    var tx = ledger.post(new PostCommand(LedgerTransactionType.PIX_MED_RETURN,
                            "pix-med-return:" + claimId, "Pix fraud claim return " + claim.endToEndId(),
                            List.of(Leg.debit(AccountType.PIX_MED_HOLDS_ACCOUNT_ID, blocked),
                                    Leg.credit(ledger.walletOf(claim.claimantUserId()).getId(), blocked))));
                    returnService.recordFraudReturn(original, analystId, blocked, tx.getId(), "pix-med:" + claimId);
                }
                if (!Boolean.FALSE.equals(req.blockReceiver())) {
                    fraudAdmin.block(claim.receiverUserId(), "Pix fraud claim " + claimId + " accepted", analystId);
                }
            } else if (blocked > 0) {
                ledger.post(new PostCommand(LedgerTransactionType.PIX_MED_RELEASE, "pix-med-release:" + claimId,
                        "Pix fraud claim released " + claim.endToEndId(),
                        List.of(Leg.debit(AccountType.PIX_MED_HOLDS_ACCOUNT_ID, blocked),
                                Leg.credit(ledger.walletOf(claim.receiverUserId()).getId(), blocked))));
            }
            jdbc.update("""
                    UPDATE pix_fraud_claims SET status = ?, returned_amount = ?, resolved_by = ?, resolved_at = ?
                     WHERE id = ?
                    """, req.accepted() ? "ACCEPTED" : "REJECTED", req.accepted() ? blocked : 0, analystId,
                    Timestamp.from(now), claimId);
            return claim;
        });
        balanceCache.evict(resolved.claimantUserId());
        balanceCache.evict(resolved.receiverUserId());
        return get(claimId);
    }

    private FraudClaimResponse get(UUID claimId) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM pix_fraud_claims WHERE id = ?",
                PixFraudClaimService::claim, claimId);
    }

    private static FraudClaimResponse claim(ResultSet rs, int row) throws SQLException {
        Long returned = (Long) rs.getObject("returned_amount");
        return new FraudClaimResponse(rs.getObject("id", UUID.class), rs.getString("end_to_end_id"),
                rs.getLong("claimant_user_id"), rs.getLong("receiver_user_id"), rs.getString("description"),
                Money.fromCents(rs.getLong("blocked_amount")), returned == null ? null : Money.fromCents(returned),
                FraudClaimStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }
}

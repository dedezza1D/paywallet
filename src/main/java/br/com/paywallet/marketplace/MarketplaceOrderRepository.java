package br.com.paywallet.marketplace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

interface MarketplaceOrderRepository extends JpaRepository<MarketplaceOrder, UUID> {

    Optional<MarketplaceOrder> findByIdempotencyKey(String idempotencyKey);

    Optional<MarketplaceOrder> findByIdAndUserId(UUID id, Long userId);

    Page<MarketplaceOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** Lock timeout -2 is Hibernate's SKIP LOCKED: concurrent workers never pick the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from MarketplaceOrder o where o.status = 'PENDING' order by o.createdAt")
    List<MarketplaceOrder> lockPending(Pageable pageable);

    @Query("""
            select coalesce(sum(o.cashback), 0) from MarketplaceOrder o
             where o.userId = :userId and o.status = 'COMPLETED' and o.completedAt >= :since
            """)
    long cashbackSince(Long userId, Instant since);
}

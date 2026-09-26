package br.com.paywallet.pix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

interface PixReturnRepository extends JpaRepository<PixReturn, UUID> {

    Optional<PixReturn> findByIdempotencyKey(String idempotencyKey);

    List<PixReturn> findByOriginalEndToEndIdOrderByCreatedAt(String originalEndToEndId);

    @Query("""
            select coalesce(sum(r.amount), 0) from PixReturn r
             where r.originalEndToEndId = :endToEndId and r.status <> 'FAILED'
            """)
    long returnedAmount(String endToEndId);

    /** Lock timeout -2 is Hibernate's SKIP LOCKED: concurrent workers never pick the same return. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from PixReturn r where r.status = 'PENDING' order by r.createdAt")
    List<PixReturn> lockPending(Pageable pageable);
}

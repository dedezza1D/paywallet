package br.com.paywallet.bill;

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

interface BillPaymentRepository extends JpaRepository<BillPayment, UUID> {

    Optional<BillPayment> findByIdempotencyKey(String idempotencyKey);

    Optional<BillPayment> findByIdAndPayerUserId(UUID id, Long payerUserId);

    Page<BillPayment> findByPayerUserId(Long payerUserId, Pageable pageable);

    @Query("select count(b) > 0 from BillPayment b where b.barcode = :barcode and b.status <> 'FAILED'")
    boolean isPaidOrInFlight(String barcode);

    /** Lock timeout -2 is Hibernate's SKIP LOCKED: concurrent workers never pick the same payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select b from BillPayment b where b.status = 'PENDING' order by b.createdAt")
    List<BillPayment> lockPending(Pageable pageable);
}

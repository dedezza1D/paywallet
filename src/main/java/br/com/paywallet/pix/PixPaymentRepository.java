package br.com.paywallet.pix;

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

interface PixPaymentRepository extends JpaRepository<PixPayment, UUID> {

    Optional<PixPayment> findByEndToEndId(String endToEndId);

    Page<PixPayment> findByPayerUserIdOrPayeeUserId(Long payerUserId, Long payeeUserId, Pageable pageable);

    Optional<PixPayment> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PixPayment p where p.endToEndId = :endToEndId")
    Optional<PixPayment> lockByEndToEndId(String endToEndId);

    /** Lock timeout -2 is Hibernate's SKIP LOCKED: concurrent workers never pick the same payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select p from PixPayment p where p.status = 'PENDING' order by p.createdAt")
    List<PixPayment> lockPending(Pageable pageable);
}

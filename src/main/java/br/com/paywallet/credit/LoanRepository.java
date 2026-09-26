package br.com.paywallet.credit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface LoanRepository extends JpaRepository<Loan, UUID> {

    Optional<Loan> findByIdempotencyKey(String idempotencyKey);

    Optional<Loan> findByIdAndUserId(UUID id, Long userId);

    List<Loan> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Serializes payments of one loan, whether by the customer or by the collection job. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> lockById(UUID id);
}

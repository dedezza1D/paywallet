package br.com.paywallet.merchant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface ChargeRepository extends JpaRepository<Charge, UUID> {

    Optional<Charge> findByPublicToken(String publicToken);

    Optional<Charge> findByIdAndMerchantId(UUID id, Long merchantId);

    Optional<Charge> findByMerchantIdAndReference(Long merchantId, String reference);

    Page<Charge> findByMerchantId(Long merchantId, Pageable pageable);

    Page<Charge> findByMerchantIdAndStatus(Long merchantId, Charge.Status status, Pageable pageable);

    /** FOR UPDATE: two payers racing for the same charge are serialized here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.id = :id")
    Optional<Charge> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.txid = :txid")
    Optional<Charge> lockByTxid(@Param("txid") String txid);

    @Modifying
    @Query("update Charge c set c.status = 'EXPIRED' where c.status = 'PENDING' and c.expiresAt <= :now")
    int expireOverdue(@Param("now") Instant now);
}

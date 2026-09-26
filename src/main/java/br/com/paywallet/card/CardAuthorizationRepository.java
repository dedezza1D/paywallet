package br.com.paywallet.card;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface CardAuthorizationRepository extends JpaRepository<CardAuthorization, String> {

    Page<CardAuthorization> findByCardIdOrderByCreatedAtDesc(UUID cardId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CardAuthorization a where a.id = :id")
    Optional<CardAuthorization> lockById(String id);
}

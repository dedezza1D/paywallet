package br.com.paywallet.card;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface CardRepository extends JpaRepository<Card, UUID> {

    Optional<Card> findByIdAndUserId(UUID id, Long userId);

    List<Card> findByUserIdOrderByCreatedAt(Long userId);

    /** FOR UPDATE: authorizations of one card are decided one at a time, so the limit cannot be overspent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.processorToken = :token")
    Optional<Card> lockByProcessorToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> lockById(UUID id);

    @Query("select c from Card c where c.type = 'CREDIT' and c.status <> 'CANCELLED' and c.closingDay = :day")
    List<Card> creditCardsClosingOn(int day);
}

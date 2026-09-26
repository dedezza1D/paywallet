package br.com.paywallet.card;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CardStatementRepository extends JpaRepository<CardStatement, UUID> {

    List<CardStatement> findByCardIdOrderByClosingDateDesc(UUID cardId);

    Optional<CardStatement> findByIdAndCardId(UUID id, UUID cardId);

    boolean existsByCardIdAndClosingDate(UUID cardId, LocalDate closingDate);

    List<CardStatement> findByCardIdAndStatusOrderByClosingDate(UUID cardId, CardStatement.Status status);
}

package br.com.paywallet.ledger;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface PostingRepository extends JpaRepository<Posting, UUID> {

    @EntityGraph(attributePaths = "transaction")
    Page<Posting> findByAccountId(UUID accountId, Pageable pageable);

    @EntityGraph(attributePaths = "account")
    List<Posting> findByTransactionId(UUID transactionId);
}

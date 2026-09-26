package br.com.paywallet.credit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CreditAnalysisRepository extends JpaRepository<CreditAnalysis, UUID> {

    Optional<CreditAnalysis> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}

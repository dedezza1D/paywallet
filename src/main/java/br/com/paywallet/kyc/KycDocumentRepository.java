package br.com.paywallet.kyc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    List<KycDocument> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<KycDocument> findByIdAndUserId(UUID id, Long userId);
}

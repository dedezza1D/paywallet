package br.com.paywallet.pix;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PixKeyRepository extends JpaRepository<PixKey, UUID> {

    Optional<PixKey> findByValue(String value);

    List<PixKey> findByUserIdOrderByCreatedAt(Long userId);

    long countByUserId(Long userId);

    boolean existsByUserIdAndType(Long userId, PixKeyType type);
}

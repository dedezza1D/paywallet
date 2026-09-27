package br.com.paywallet.pix;

import java.time.Instant;
import java.util.UUID;

import br.com.paywallet.crypto.EncryptedString;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pix_keys")
public class PixKey {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, updatable = false, length = 5)
    private PixKeyType type;

    @Convert(converter = EncryptedString.class)
    @Column(name = "key_value", nullable = false, updatable = false)
    private String value;

    @Column(name = "value_index", nullable = false, updatable = false, unique = true, length = 64)
    private String valueIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PixKey() {
    }

    PixKey(Long userId, PixKeyType type, String value, String valueIndex, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.value = value;
        this.valueIndex = valueIndex;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public PixKeyType getType() { return type; }
    public String getValue() { return value; }
    public Instant getCreatedAt() { return createdAt; }
}

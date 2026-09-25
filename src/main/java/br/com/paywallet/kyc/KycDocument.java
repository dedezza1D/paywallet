package br.com.paywallet.kyc;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** KYC document metadata; the file itself lives in object storage under {@code objectKey}. */
@Entity
@Table(name = "kyc_documents")
public class KycDocument {

    public enum Type { ID_FRONT, ID_BACK, DRIVER_LICENSE, SELFIE, PROOF_OF_ADDRESS }

    public enum Status { PENDING_REVIEW, APPROVED, REJECTED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Type type;

    @Column(name = "object_key", nullable = false, updatable = false, unique = true, length = 500)
    private String objectKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KycDocument() {
    }

    KycDocument(UUID id, Long userId, Type type, String objectKey, String contentType, long sizeBytes, String sha256) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.status = Status.PENDING_REVIEW;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public Type getType() { return type; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

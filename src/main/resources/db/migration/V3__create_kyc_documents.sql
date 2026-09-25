-- KYC document metadata. The file itself lives in object storage under object_key.
CREATE TABLE kyc_documents (
    id            UUID                     PRIMARY KEY,
    user_id       BIGINT                   NOT NULL REFERENCES users (id),
    type          VARCHAR(20)              NOT NULL,
    object_key    VARCHAR(500)             NOT NULL UNIQUE,
    content_type  VARCHAR(100)             NOT NULL,
    size_bytes    BIGINT                   NOT NULL,
    sha256        VARCHAR(64)              NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_kyc_type CHECK (type IN ('ID_FRONT', 'ID_BACK', 'DRIVER_LICENSE', 'SELFIE', 'PROOF_OF_ADDRESS')),
    CONSTRAINT ck_kyc_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_kyc_documents_user ON kyc_documents (user_id, created_at DESC);

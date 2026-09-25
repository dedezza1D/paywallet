package br.com.paywallet.kyc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.storage.ObjectStorage;
import br.com.paywallet.storage.StorageProperties;
import br.com.paywallet.user.UserService;
import jakarta.persistence.EntityManager;

@Service
public class KycService {

    /**
     * Accepted types, identified by the file's leading bytes (magic number) rather than by the
     * Content-Type or extension declared by the client.
     */
    private enum AllowedFile {
        JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
        PNG("image/png", "png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
        PDF("application/pdf", "pdf", new byte[] {'%', 'P', 'D', 'F', '-'});

        final String contentType;
        final String extension;
        final byte[] magic;

        AllowedFile(String contentType, String extension, byte[] magic) {
            this.contentType = contentType;
            this.extension = extension;
            this.magic = magic;
        }

        static AllowedFile detect(byte[] content) {
            for (AllowedFile f : values()) {
                if (content.length >= f.magic.length
                        && Arrays.equals(Arrays.copyOf(content, f.magic.length), f.magic)) {
                    return f;
                }
            }
            throw new BusinessException("Unsupported format: upload a JPEG, PNG or PDF");
        }
    }

    public record KycDocumentResponse(UUID id, KycDocument.Type type, String contentType, long sizeBytes,
                                      KycDocument.Status status, Instant createdAt) {

        static KycDocumentResponse from(KycDocument d) {
            return new KycDocumentResponse(d.getId(), d.getType(), d.getContentType(), d.getSizeBytes(),
                    d.getStatus(), d.getCreatedAt());
        }
    }

    public record DownloadUrl(URL url, Instant expiresAt) {
    }

    private final KycDocumentRepository repository;
    private final ObjectStorage storage;
    private final StorageProperties props;
    private final UserService users;
    private final EntityManager em;
    private final TransactionTemplate tx;

    public KycService(KycDocumentRepository repository, ObjectStorage storage, StorageProperties props,
                      UserService users, EntityManager em, TransactionTemplate tx) {
        this.repository = repository;
        this.storage = storage;
        this.props = props;
        this.users = users;
        this.em = em;
        this.tx = tx;
    }

    /**
     * Uploads the file before writing metadata. If the INSERT fails, an orphan object remains in the
     * bucket (harmless; a lifecycle rule can clean it up), but never a record pointing to a missing file.
     */
    public KycDocumentResponse upload(Long userId, KycDocument.Type type, MultipartFile file) {
        users.get(userId);
        if (file.isEmpty()) {
            throw new BusinessException("Empty file");
        }
        if (file.getSize() > props.maxUploadBytes()) {
            throw new BusinessException("File exceeds the %d MB limit".formatted(props.maxUploadBytes() / 1_048_576));
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var kind = AllowedFile.detect(content);
        var id = UUID.randomUUID();
        // The object key contains nothing supplied by the client (e.g. no original file name).
        var key = "kyc/%d/%s.%s".formatted(userId, id, kind.extension);
        var sha256 = sha256(content);

        storage.put(key, content, kind.contentType, sha256);

        var doc = new KycDocument(id, userId, type, key, kind.contentType, content.length, sha256);
        tx.executeWithoutResult(s -> em.persist(doc));
        return KycDocumentResponse.from(doc);
    }

    public List<KycDocumentResponse> list(Long userId) {
        users.get(userId);
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(KycDocumentResponse::from).toList();
    }

    public DownloadUrl downloadUrl(Long userId, UUID documentId) {
        var doc = repository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        return new DownloadUrl(storage.presignedGet(doc.getObjectKey()), Instant.now().plus(props.presignTtl()));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

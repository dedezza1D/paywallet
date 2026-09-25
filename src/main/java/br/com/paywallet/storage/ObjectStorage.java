package br.com.paywallet.storage;

import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import br.com.paywallet.exception.ExternalServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Private bucket access. Nothing here is public: clients read files only through presigned URLs
 * that expire within minutes.
 */
@Component
public class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;

    public ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    public void put(String key, byte[] content, String contentType, String sha256Hex) {
        var request = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .metadata(Map.of("sha256", sha256Hex));
        if (StringUtils.hasText(props.serverSideEncryption())) {
            request.serverSideEncryption(props.serverSideEncryption());
            if (StringUtils.hasText(props.kmsKeyId())) {
                request.ssekmsKeyId(props.kmsKeyId());
            }
        }
        try {
            s3.putObject(request.build(), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new ExternalServiceException("File storage unavailable", e);
        }
    }

    public URL presignedGet(String key) {
        var presign = GetObjectPresignRequest.builder()
                .signatureDuration(props.presignTtl())
                .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())
                .build();
        return presigner.presignGetObject(presign).url();
    }

    /** Local environments only; in production the bucket is provisioned by infrastructure as code. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        if (!props.createBucket()) {
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Bucket {} created", props.bucket());
        } catch (SdkException e) {
            log.warn("Could not check bucket {}: {}", props.bucket(), e.getMessage());
        }
    }
}

package br.com.paywallet.pix;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.NotFoundException;
import br.com.paywallet.exception.TooManyRequestsException;
import br.com.paywallet.pix.PixGateway.ExternalAccount;
import br.com.paywallet.user.User;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;

/** Local key directory (the institution's share of the DICT) plus lookups of keys held elsewhere. */
@Service
public class PixKeyService {

    private final PixKeyRepository keys;
    private final UserService users;
    private final PixGateway gateway;
    private final PixProperties props;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public PixKeyService(PixKeyRepository keys, UserService users, PixGateway gateway, PixProperties props,
                         StringRedisTemplate redis, Clock clock) {
        this.keys = keys;
        this.users = users;
        this.gateway = gateway;
        this.props = props;
        this.redis = redis;
        this.clock = clock;
    }

    /** Where a key points: a user of this institution or an account elsewhere. */
    public record Destination(PixKeyType keyType, String key, Long localUserId, ExternalAccount external) {

        boolean isLocal() {
            return localUserId != null;
        }
    }

    public record KeyOwner(PixKeyType keyType, String name, String document, String institution) {
    }

    /**
     * Ownership of CPF/CNPJ and email keys is proven by matching the registered account. Phone keys
     * would need an SMS code in production; that verification is not implemented yet.
     */
    @Transactional
    public PixKey register(Long userId, PixKeyType type, String rawValue) {
        User user = users.get(userId);
        String value = switch (type) {
            case EVP -> UUID.randomUUID().toString();
            case CPF, CNPJ -> {
                String normalized = type.normalize(rawValue);
                boolean matchesUser = normalized.equals(user.getDocument())
                        && (type == PixKeyType.CPF) == (user.getType() == UserType.COMMON);
                if (!matchesUser) {
                    throw new BusinessException("Document keys must be the account holder's own " + type);
                }
                yield normalized;
            }
            case EMAIL -> {
                String normalized = type.normalize(rawValue);
                if (!normalized.equals(user.getEmail())) {
                    throw new BusinessException("Email keys must match the account email");
                }
                yield normalized;
            }
            case PHONE -> type.normalize(rawValue);
        };

        int max = user.getType() == UserType.MERCHANT ? props.maxKeysMerchant() : props.maxKeysIndividual();
        if (keys.countByUserId(userId) >= max) {
            throw new BusinessException("Pix key limit reached (%d)".formatted(max));
        }
        if (keys.findByValue(value).isPresent()) {
            throw new BusinessException("This Pix key is already registered");
        }
        return keys.saveAndFlush(new PixKey(userId, type, value, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<PixKey> list(Long userId) {
        return keys.findByUserIdOrderByCreatedAt(userId);
    }

    @Transactional
    public void delete(Long userId, UUID keyId) {
        var key = keys.findById(keyId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Pix key not found"));
        keys.delete(key);
    }

    @Transactional(readOnly = true)
    public Optional<PixKey> findLocal(String normalizedKey) {
        return keys.findByValue(normalizedKey);
    }

    @Transactional(readOnly = true)
    public Destination resolve(String rawKey) {
        PixKeyType type = PixKeyType.detect(rawKey);
        String key = type.normalize(rawKey);
        return keys.findByValue(key)
                .map(k -> new Destination(type, key, k.getUserId(), null))
                .or(() -> gateway.lookup(key).map(account -> new Destination(type, key, null, account)))
                .orElseThrow(() -> new NotFoundException("Pix key not found"));
    }

    /** Confirmation screen before paying. Rate limited per user to prevent harvesting the directory. */
    public KeyOwner owner(Long requesterId, String rawKey) {
        throttleLookups(requesterId);
        Destination destination = resolve(rawKey);
        if (destination.isLocal()) {
            User owner = users.get(destination.localUserId());
            return new KeyOwner(destination.keyType(), owner.getFullName(), Documents.mask(owner.getDocument()),
                    props.institutionName());
        }
        var account = destination.external();
        return new KeyOwner(destination.keyType(), account.holderName(), Documents.mask(account.holderDocument()),
                account.institutionName());
    }

    private void throttleLookups(Long userId) {
        String key = "pix:lookups:%d:%d".formatted(userId, clock.instant().getEpochSecond() / 60);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofMinutes(2));
        }
        if (count != null && count > props.lookupsPerMinute()) {
            throw new TooManyRequestsException("Too many Pix key lookups. Please wait a minute.", Duration.ofMinutes(1));
        }
    }
}

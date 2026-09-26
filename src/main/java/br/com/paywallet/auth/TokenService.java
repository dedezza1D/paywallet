package br.com.paywallet.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.RSAKey;

import br.com.paywallet.user.User;

/** Issues short-lived access tokens: {@code sub} is the user id, {@code roles} the granted roles. */
@Service
public class TokenService {

    public static final String ROLES_CLAIM = "roles";
    public static final String USER_TYPE_CLAIM = "user_type";

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final String keyId;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, SecurityProperties props, RSAKey jwtSigningKey, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.keyId = jwtSigningKey.getKeyID();
        this.clock = clock;
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    public AccessToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.jwt().accessTokenTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .audience(List.of(props.jwt().audience()))
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .claim(USER_TYPE_CLAIM, user.getType().name())
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }
}

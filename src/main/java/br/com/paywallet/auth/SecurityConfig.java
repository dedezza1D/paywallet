package br.com.paywallet.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless API: every request carries a bearer JWT, with no session or cookies and therefore no CSRF.
 * Users can only see and move their own resources.
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ROLE_ADMIN";
    private static final String MERCHANT = "TYPE_MERCHANT";

    private static CorsConfigurationSource corsSource(List<String> origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Transaction-Pin",
                "X-Device-Id"));
        config.setExposedHeaders(List.of("Retry-After", "X-Trace-Id", "Idempotent-Replayed"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties props,
                                            ObjectMapper objectMapper, StringRedisTemplate redis) throws Exception {
        if (props.corsAllowedOrigins() != null && !props.corsAllowedOrigins().isEmpty()) {
            http.cors(cors -> cors.configurationSource(corsSource(props.corsAllowedOrigins())));
        }
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/email/verify", "/auth/email/verification-code",
                                "/auth/password/forgot", "/auth/password/reset", "/auth/login/mfa").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json", "/feed", "/pay/*").permitAll()
                        // Actuator lives on the management port, which is not exposed publicly.
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Authenticated by an HMAC signature from the PSP or card processor instead of a user token.
                        .requestMatchers(HttpMethod.POST, "/pix/webhooks/**", "/cards/webhooks/**").permitAll()
                        .requestMatchers("/ledger/**", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/merchant/**").hasAuthority(MERCHANT)
                        .requestMatchers(HttpMethod.GET, "/users").hasRole("ADMIN")
                        // In production money comes in through Pix/boleto; deposits here are admin-only (self in dev).
                        .requestMatchers(HttpMethod.POST, "/users/{id}/deposit")
                        .access(props.allowSelfDeposit() ? selfOrAdmin() : adminOnly())
                        .requestMatchers("/users/{id}", "/users/{id}/**").access(selfOrAdmin())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemEntryPoint(objectMapper))
                        .accessDeniedHandler(problemAccessDenied(objectMapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problemEntryPoint(objectMapper))
                        .accessDeniedHandler(problemAccessDenied(objectMapper)))
                .addFilterAfter(new ClosedAccountFilter(redis), BearerTokenAuthenticationFilter.class)
                .headers(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Maps the "roles" claim to ROLE_* authorities and "user_type" to TYPE_*; the principal name is the "sub"
     * claim (user id).
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(roles.convert(jwt));
            String userType = jwt.getClaimAsString(TokenService.USER_TYPE_CLAIM);
            if (userType != null) {
                authorities.add(new SimpleGrantedAuthority("TYPE_" + userType));
            }
            return authorities;
        });
        return converter;
    }

    private static AuthorizationManager<RequestAuthorizationContext> selfOrAdmin() {
        return (authentication, context) -> {
            var auth = authenticated(authentication);
            if (auth == null) {
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(isAdmin(auth) || auth.getName().equals(context.getVariables().get("id")));
        };
    }

    private static AuthorizationManager<RequestAuthorizationContext> adminOnly() {
        return (authentication, context) -> {
            var auth = authenticated(authentication);
            return new AuthorizationDecision(auth != null && isAdmin(auth));
        };
    }

    private static Authentication authenticated(Supplier<Authentication> supplier) {
        var auth = supplier.get();
        return auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken ? null : auth;
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> ADMIN.equals(a.getAuthority()));
    }

    /** Keeps the standard WWW-Authenticate header while returning the same problem+json body as the rest of the API. */
    private static AuthenticationEntryPoint problemEntryPoint(ObjectMapper mapper) {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            bearer.commence(request, response, ex);
            writeProblem(mapper, response, HttpStatus.UNAUTHORIZED, "Authentication required or invalid token");
        };
    }

    private static AccessDeniedHandler problemAccessDenied(ObjectMapper mapper) {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, ex) -> {
            bearer.handle(request, response, ex);
            writeProblem(mapper, response, HttpStatus.FORBIDDEN, "Access to this resource is denied");
        };
    }

    private static void writeProblem(ObjectMapper mapper, HttpServletResponse response, HttpStatus status,
                                     String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}

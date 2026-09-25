package br.com.paywallet;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import br.com.paywallet.auth.TokenService;
import br.com.paywallet.external.AuthorizationClient;
import br.com.paywallet.external.NotificationClient;
import br.com.paywallet.user.UserDtos.CreateUserRequest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletService;

/** Runs against the same data stores used in production, started once and shared by all test classes. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static final LocalStackContainer S3 = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4"))
            .withServices(LocalStackContainer.Service.S3)
            .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        Startables.deepStart(POSTGRES, MONGO, REDIS, S3, KAFKA).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint", () -> S3.getEndpoint().toString());
        registry.add("app.storage.region", S3::getRegion);
        registry.add("app.storage.access-key", S3::getAccessKey);
        registry.add("app.storage.secret-key", S3::getSecretKey);
        registry.add("app.outbox.poll-interval", () -> "200ms");
    }

    @MockitoBean protected AuthorizationClient authorizationClient;
    @MockitoBean protected NotificationClient notificationClient;

    @Autowired protected MockMvc mvc;
    @Autowired protected UserService userService;
    @Autowired protected WalletService walletService;
    @Autowired protected TokenService tokenService;
    @Autowired protected JdbcTemplate jdbc;

    protected static final String PASSWORD = "strong-password-123";

    @BeforeEach
    void externalServicesSucceedByDefault() {
        when(authorizationClient.isAuthorized()).thenReturn(true);
        when(notificationClient.send(anyString(), anyString())).thenReturn(true);
    }

    /** Unique document and email per call: ledger rows are immutable, so the database is never cleaned. */
    protected UserResponse newUser(UserType type, String name) {
        var rnd = ThreadLocalRandom.current();
        String document = type == UserType.COMMON
                ? "%011d".formatted(rnd.nextLong(1_000_000_000L, 99_999_999_999L))
                : "%014d".formatted(rnd.nextLong(10_000_000_000_000L, 99_999_999_999_999L));
        return userService.create(new CreateUserRequest(name, document,
                name.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID() + "@mail.com", PASSWORD, type));
    }

    protected String tokenFor(UserResponse user) {
        return tokenService.issue(userService.get(user.id())).value();
    }

    protected RequestPostProcessor as(UserResponse user) {
        String token = tokenFor(user);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    /** Tokens issued after this call carry the ADMIN role. */
    protected void makeAdmin(UserResponse user) {
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.id());
    }

    protected UserResponse newUserWithBalance(String name, String amount) {
        var user = newUser(UserType.COMMON, name);
        walletService.deposit(user.id(), new BigDecimal(amount), UUID.randomUUID().toString());
        return user;
    }

    protected BigDecimal balanceOf(UserResponse user) {
        return walletService.balance(user.id()).balance();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }
}

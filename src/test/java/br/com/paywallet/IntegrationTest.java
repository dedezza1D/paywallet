package br.com.paywallet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import br.com.paywallet.bill.BillGateway;
import br.com.paywallet.card.CardProcessor;
import br.com.paywallet.credit.CreditBureau;
import br.com.paywallet.external.AuthorizationClient;
import br.com.paywallet.external.EmailSender;
import br.com.paywallet.external.NotificationClient;
import br.com.paywallet.marketplace.MarketplaceProvider;
import br.com.paywallet.pix.PixGateway;
import br.com.paywallet.user.UserDtos.CreateUserRequest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserService;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletService;
import br.com.paywallet.yield.CdiRateProvider;

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
            .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.KMS)
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
        registry.add("app.crypto.kms-endpoint", () -> S3.getEndpoint().toString());
        registry.add("app.crypto.region", S3::getRegion);
        registry.add("app.crypto.access-key", S3::getAccessKey);
        registry.add("app.crypto.secret-key", S3::getSecretKey);
        registry.add("app.outbox.poll-interval", () -> "200ms");
        registry.add("app.pix.settlement-interval", () -> "200ms");
        registry.add("app.bill.settlement-interval", () -> "200ms");
        registry.add("app.marketplace.fulfillment-interval", () -> "200ms");
        // The daily job must not credit yield in the middle of tests that assert exact balances.
        registry.add("app.yield.enabled", () -> "false");
        registry.add("app.credit.collection-enabled", () -> "false");
        registry.add("app.pix.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("app.cards.webhook-secret", () -> CARD_WEBHOOK_SECRET);
        registry.add("app.cards.statement-job-enabled", () -> "false");
        // Every MockMvc request comes from the same address; the per-IP limit has its own test.
        registry.add("app.security.ip-rate-limit", () -> "100000");
        // Same start and end hour: the nighttime rule would make results depend on when tests run.
        registry.add("app.fraud.night-start-hour", () -> "0");
        registry.add("app.fraud.night-end-hour", () -> "0");
        // Set for the whole suite: a test with its own properties starts a second context, whose Kafka consumers
        // join the same groups and take messages the first context is waiting for.
        registry.add("app.security.cors-allowed-origins", () -> "http://localhost:8082");
    }

    protected static final String WEBHOOK_SECRET = "test-webhook-secret";
    protected static final String CARD_WEBHOOK_SECRET = "test-card-webhook-secret";
    protected static final String GIFT_CODE = "TEST-GIFT-CODE-0001";
    /** Every day is a business day in tests, at 0.05% per day. */
    protected static final BigDecimal TEST_CDI_DAILY_RATE = new BigDecimal("0.05");

    @MockitoBean protected AuthorizationClient authorizationClient;
    @MockitoBean protected NotificationClient notificationClient;
    @MockitoBean protected PixGateway pixGateway;
    @MockitoBean protected BillGateway billGateway;
    @MockitoBean protected CdiRateProvider cdiRates;
    @MockitoBean protected CreditBureau creditBureau;
    @MockitoBean protected CardProcessor cardProcessor;
    @MockitoBean protected MarketplaceProvider marketplaceProvider;
    @MockitoBean protected EmailSender emailSender;

    @Autowired protected MockMvc mvc;
    @Autowired protected UserService userService;
    @Autowired protected WalletService walletService;
    @Autowired protected TokenService tokenService;
    @Autowired protected JdbcTemplate jdbc;

    protected static final String PASSWORD = "strong-password-123";
    protected static final String TEST_PIN = "482915";
    private static final String TEST_PIN_HASH = new BCryptPasswordEncoder().encode(TEST_PIN);

    @BeforeEach
    void externalServicesSucceedByDefault() {
        when(authorizationClient.isAuthorized()).thenReturn(true);
        when(notificationClient.send(anyString(), anyString())).thenReturn(true);
        when(pixGateway.submit(any())).thenReturn(PixGateway.SubmitResult.ok());
        when(pixGateway.submitReturn(any())).thenReturn(PixGateway.SubmitResult.ok());
        when(billGateway.pay(any())).thenReturn(BillGateway.PaymentResult.ok("AUTH-TEST"));
        when(marketplaceProvider.fulfill(any())).thenAnswer(invocation -> MarketplaceProvider.FulfillmentResult.ok(
                invocation.<MarketplaceProvider.FulfillmentOrder>getArgument(0).phoneNumber() == null ? GIFT_CODE : null,
                "REF-TEST"));
        when(creditBureau.report(anyString())).thenReturn(new CreditBureau.BureauReport(800, false));
        when(cardProcessor.issue(any(), anyString(), any())).thenAnswer(invocation -> new CardProcessor.IssuedCard(
                "tok_" + UUID.randomUUID().toString().replace("-", ""), "4242", "MASTERCARD", 12,
                LocalDate.now().getYear() + 5));
        when(cdiRates.dailyRates(any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            var rates = new TreeMap<LocalDate, BigDecimal>();
            from.datesUntil(to.plusDays(1)).forEach(day -> rates.put(day, TEST_CDI_DAILY_RATE));
            return rates;
        });
    }

    /**
     * Unique document and email per call: ledger rows are immutable, so the database is never cleaned. The email is
     * marked as verified, as if the customer had entered the code, and the transaction PIN is {@link #TEST_PIN}.
     */
    protected UserResponse newUser(UserType type, String name) {
        var rnd = ThreadLocalRandom.current();
        String document = type == UserType.COMMON
                ? "%011d".formatted(rnd.nextLong(1_000_000_000L, 99_999_999_999L))
                : "%014d".formatted(rnd.nextLong(10_000_000_000_000L, 99_999_999_999_999L));
        var user = userService.create(new CreateUserRequest(name, document,
                name.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID() + "@mail.com", PASSWORD, type, true));
        jdbc.update("UPDATE users SET email_verified_at = now(), transaction_pin_hash = ? WHERE id = ?", TEST_PIN_HASH,
                user.id());
        return userService.findById(user.id());
    }

    protected String tokenFor(UserResponse user) {
        return tokenService.issue(userService.get(user.id())).value();
    }

    protected RequestPostProcessor as(UserResponse user) {
        String token = tokenFor(user);
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            request.addHeader("X-Transaction-Pin", TEST_PIN);
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

package br.com.paywallet.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.ExternalServiceException;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.pix.PixGateway.ExternalAccount;
import br.com.paywallet.pix.PixGateway.SubmitResult;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class PixIntegrationTest extends IntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final ExternalAccount OTHER_BANK =
            new ExternalAccount("Carol Jones", "11122233344", "60701190", "Other Bank");

    @Autowired LedgerService ledger;

    // Keys

    @Test
    void registersEveryKeyTypeForTheOwner() throws Exception {
        var user = newUser(UserType.COMMON, "Key Owner");

        registerKey(user, "CPF", user.document()).andExpect(status().isCreated());
        registerKey(user, "EMAIL", user.email().toUpperCase()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value(user.email()));
        registerKey(user, "PHONE", randomPhone()).andExpect(status().isCreated());
        registerKey(user, "EVP", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));

        mvc.perform(get("/pix/keys").with(as(user))).andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void rejectsKeysTheUserDoesNotOwn() throws Exception {
        var alice = newUser(UserType.COMMON, "Alice Keys");
        var bob = newUser(UserType.COMMON, "Bob Keys");
        String phone = randomPhone();

        registerKey(alice, "CPF", bob.document()).andExpect(status().isUnprocessableEntity());
        registerKey(alice, "EMAIL", bob.email()).andExpect(status().isUnprocessableEntity());
        registerKey(alice, "PHONE", phone).andExpect(status().isCreated());
        registerKey(bob, "PHONE", phone).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enforcesTheKeyLimitForIndividuals() throws Exception {
        var user = newUser(UserType.COMMON, "Many Keys");
        for (int i = 0; i < 5; i++) {
            registerKey(user, "EVP", null).andExpect(status().isCreated());
        }
        registerKey(user, "EVP", null).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void onlyTheOwnerCanDeleteAKey() throws Exception {
        var owner = newUser(UserType.COMMON, "Deleter");
        var other = newUser(UserType.COMMON, "Intruder");
        String keyId = JsonPath.read(registerKey(owner, "EVP", null).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(delete("/pix/keys/{id}", keyId).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/pix/keys/{id}", keyId).with(as(owner))).andExpect(status().isNoContent());
    }

    @Test
    void lookupShowsMaskedOwnerAndIsRateLimited() throws Exception {
        var owner = newUser(UserType.COMMON, "Looked Up");
        var curious = newUser(UserType.COMMON, "Curious");
        registerKey(owner, "CPF", owner.document()).andExpect(status().isCreated());
        String masked = "***." + owner.document().substring(3, 6) + "." + owner.document().substring(6, 9) + "-**";

        mvc.perform(get("/pix/keys/lookup").param("key", owner.document()).with(as(curious)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Looked Up"))
                .andExpect(jsonPath("$.document").value(masked))
                .andExpect(jsonPath("$.institution").value("PayWallet"));

        // Two windows' worth of calls, so the limit is hit even if the minute rolls over mid-loop.
        boolean throttled = false;
        for (int i = 0; i < 61 && !throttled; i++) {
            throttled = mvc.perform(get("/pix/keys/lookup").param("key", owner.document()).with(as(curious)))
                    .andReturn().getResponse().getStatus() == 429;
        }
        assertThat(throttled).isTrue();
    }

    // Payments

    @Test
    void pixToAKeyOfThisInstitutionSettlesImmediately() throws Exception {
        var payer = newUserWithBalance("Internal Payer", "100.00");
        var payee = newUser(UserType.COMMON, "Internal Payee");
        var stranger = newUser(UserType.COMMON, "Stranger");
        registerKey(payee, "EMAIL", payee.email());

        String body = sendPix(payer, newKey(), "{\"key\": \"%s\", \"value\": 40}".formatted(payee.email()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.scope").value("INTERNAL"))
                .andExpect(jsonPath("$.counterpartyName").value("Internal Payee"))
                .andReturn().getResponse().getContentAsString();
        String endToEndId = JsonPath.read(body, "$.endToEndId");

        assertThat(endToEndId).hasSize(32).startsWith("E");
        assertThat(balanceOf(payer)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("40.00");
        mvc.perform(get("/pix/payments/{id}", endToEndId).with(as(payee))).andExpect(status().isOk());
        mvc.perform(get("/pix/payments/{id}", endToEndId).with(as(stranger))).andExpect(status().isNotFound());
        verify(notificationClient, timeout(ASYNC.toMillis())).send(eq(payee.email()), contains("Pix of R$ 40.00"));
    }

    @Test
    void repeatingTheIdempotencyKeyReplaysThePix() throws Exception {
        var payer = newUserWithBalance("Replay Payer", "100.00");
        var payee = newUser(UserType.COMMON, "Replay Payee");
        registerKey(payee, "EMAIL", payee.email());
        String key = newKey();
        String body = "{\"key\": \"%s\", \"value\": 10}".formatted(payee.email());

        sendPix(payer, key, body).andExpect(status().isCreated());
        sendPix(payer, key, body).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(balanceOf(payer)).isEqualByComparingTo("90.00");
    }

    @Test
    void pixToAnotherInstitutionCompletesAfterSettlement() throws Exception {
        var payer = newUserWithBalance("External Payer", "100.00");
        when(pixGateway.lookup("carol@otherbank.com")).thenReturn(Optional.of(OTHER_BANK));

        String body = sendPix(payer, newKey(), "{\"key\": \"carol@otherbank.com\", \"value\": 30}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.counterpartyDocument").value("***.222.333-**"))
                .andReturn().getResponse().getContentAsString();
        String endToEndId = JsonPath.read(body, "$.endToEndId");
        assertThat(balanceOf(payer)).isEqualByComparingTo("70.00");

        awaitStatus(payer, endToEndId, "COMPLETED");
        verify(pixGateway, atLeastOnce()).submit(argThat(order ->
                order.endToEndId().equals(endToEndId) && order.amountCents() == 3000));
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void rejectedExternalPixIsReversedAndReleasesTheLimit() throws Exception {
        var payer = newUserWithBalance("Rejected Payer", "100.00");
        when(pixGateway.lookup("closed@otherbank.com")).thenReturn(Optional.of(OTHER_BANK));
        when(pixGateway.submit(forKey("closed@otherbank.com"))).thenReturn(SubmitResult.rejected("Receiving account closed"));

        String body = sendPix(payer, newKey(), "{\"key\": \"closed@otherbank.com\", \"value\": 30}")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        awaitStatus(payer, JsonPath.read(body, "$.endToEndId"), "FAILED");
        mvc.perform(get("/pix/payments/{id}", (String) JsonPath.read(body, "$.endToEndId")).with(as(payer)))
                .andExpect(jsonPath("$.failureReason").value("Receiving account closed"));
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
        mvc.perform(get("/users/{id}/limits", payer.id()).with(as(payer)))
                .andExpect(jsonPath("$.usedToday").value(0.0));
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void networkOutageKeepsThePixPendingUntilItCanBeSubmitted() throws Exception {
        var payer = newUserWithBalance("Outage Payer", "100.00");
        when(pixGateway.lookup("later@otherbank.com")).thenReturn(Optional.of(OTHER_BANK));
        when(pixGateway.submit(forKey("later@otherbank.com")))
                .thenThrow(new ExternalServiceException("SPI unavailable", null))
                .thenThrow(new ExternalServiceException("SPI unavailable", null))
                .thenReturn(SubmitResult.ok());

        String body = sendPix(payer, newKey(), "{\"key\": \"later@otherbank.com\", \"value\": 5}")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String endToEndId = JsonPath.read(body, "$.endToEndId");

        awaitStatus(payer, endToEndId, "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM pix_payments WHERE end_to_end_id = ?",
                Integer.class, endToEndId)).isEqualTo(3);
        assertThat(balanceOf(payer)).isEqualByComparingTo("95.00");
    }

    @Test
    void paysAStaticQrCodeWithTheAmountItCarries() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Corner Bakery");
        var payer = newUserWithBalance("Qr Payer", "50.00");
        String evp = JsonPath.read(registerKey(merchant, "EVP", null).andReturn().getResponse().getContentAsString(),
                "$.value");

        String brCode = JsonPath.read(mvc.perform(post("/pix/qr-codes").with(as(merchant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"%s\", \"value\": 12.34, \"txid\": \"ORDER42\"}".formatted(evp)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.brCode");

        sendPix(payer, newKey(), "{\"brCode\": \"%s\", \"value\": 99}".formatted(brCode))
                .andExpect(status().isUnprocessableEntity());
        sendPix(payer, newKey(), "{\"brCode\": \"%s\"}".formatted(brCode))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value(12.34))
                .andExpect(jsonPath("$.counterpartyName").value("Corner Bakery"));
        assertThat(balanceOf(merchant)).isEqualByComparingTo("12.34");
    }

    @Test
    void qrCodesAreOnlyGeneratedForOwnKeys() throws Exception {
        var owner = newUser(UserType.COMMON, "Qr Owner");
        var other = newUser(UserType.COMMON, "Qr Other");
        registerKey(owner, "EMAIL", owner.email());

        mvc.perform(post("/pix/qr-codes").with(as(other)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"%s\"}".formatted(owner.email())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rejectsUnknownKeysAndPaymentsToYourself() throws Exception {
        var user = newUserWithBalance("Self Payer", "10.00");
        registerKey(user, "EMAIL", user.email());

        sendPix(user, newKey(), "{\"key\": \"unknown-%s@nowhere.com\", \"value\": 1}".formatted(newKey()))
                .andExpect(status().isNotFound());
        sendPix(user, newKey(), "{\"key\": \"%s\", \"value\": 1}".formatted(user.email()))
                .andExpect(status().isUnprocessableEntity());
    }

    // Incoming webhook

    @Test
    void incomingPixCreditsTheWalletExactlyOnce() throws Exception {
        var payee = newUser(UserType.COMMON, "Incoming Payee");
        registerKey(payee, "EMAIL", payee.email());
        String endToEndId = EndToEndIds.generate("60701190", Instant.now());
        String body = """
                {"endToEndId": "%s", "key": "%s", "value": 75.10, "payerName": "Dan Brown",
                 "payerDocument": "55566677788", "payerIspb": "60701190", "description": "rent"}
                """.formatted(endToEndId, payee.email());

        webhook(body, signedNow(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("IN"))
                .andExpect(jsonPath("$.counterpartyDocument").value("***.666.777-**"));
        webhook(body, signedNow(body)).andExpect(status().isOk());

        assertThat(balanceOf(payee)).isEqualByComparingTo("75.10");
        verify(notificationClient, timeout(ASYNC.toMillis())).send(eq(payee.email()), contains("from Dan Brown"));
    }

    @Test
    void webhookRejectsBadOrStaleSignatures() throws Exception {
        var payee = newUser(UserType.COMMON, "Webhook Payee");
        registerKey(payee, "EMAIL", payee.email());
        String body = """
                {"endToEndId": "%s", "key": "%s", "value": 1, "payerName": "Eve", "payerIspb": "60701190"}
                """.formatted(EndToEndIds.generate("60701190", Instant.now()), payee.email());
        String now = String.valueOf(Instant.now().getEpochSecond());
        String stale = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());

        webhook(body, new String[] {now, "deadbeef"}).andExpect(status().isUnauthorized());
        webhook(body, new String[] {stale, WebhookSignatureVerifier.sign(WEBHOOK_SECRET, stale + "." + body)})
                .andExpect(status().isUnauthorized());
        webhook(body.replace("\"value\": 1", "\"value\": 1000"), signedNow(body)).andExpect(status().isUnauthorized());
        assertThat(balanceOf(payee)).isEqualByComparingTo("0.00");
    }

    // Helpers

    /**
     * Matches only this test's payment. Earlier tests may leave Pix pending that the worker settles in the
     * background; a catch-all stub would let them consume this test's scripted responses.
     */
    private static PixGateway.PixOrder forKey(String key) {
        return argThat(order -> order != null && key.equals(order.key()));
    }

    private ResultActions registerKey(UserResponse user, String type, String value) throws Exception {
        String json = value == null ? "{\"type\": \"%s\"}".formatted(type)
                : "{\"type\": \"%s\", \"value\": \"%s\"}".formatted(type, value);
        return mvc.perform(post("/pix/keys").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions sendPix(UserResponse payer, String idempotencyKey, String json) throws Exception {
        return mvc.perform(post("/pix/payments").with(as(payer))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions webhook(String body, String[] timestampAndSignature) throws Exception {
        return mvc.perform(post("/pix/webhooks/incoming")
                .header("X-Pix-Timestamp", timestampAndSignature[0])
                .header("X-Pix-Signature", timestampAndSignature[1])
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String[] signedNow(String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return new String[] {timestamp, WebhookSignatureVerifier.sign(WEBHOOK_SECRET, timestamp + "." + body)};
    }

    private void awaitStatus(UserResponse payer, String endToEndId, String expected) {
        await().atMost(ASYNC).untilAsserted(() ->
                mvc.perform(get("/pix/payments/{id}", endToEndId).with(as(payer)))
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    private static String randomPhone() {
        return "+55119" + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    }
}

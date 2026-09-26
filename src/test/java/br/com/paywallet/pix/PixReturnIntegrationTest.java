package br.com.paywallet.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class PixReturnIntegrationTest extends IntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(20);

    @Test
    void receiverReturnsAnInternalPixInParts() throws Exception {
        var payer = newUserWithBalance("Return Payer", "500.00");
        var receiver = newUser(UserType.COMMON, "Return Receiver");
        String e2e = sendInternal(payer, receiver, "100.00");

        String key = newKey();
        returnPix(receiver, e2e, "{\"value\":30.00}", key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reason").value("MD06"))
                .andExpect(jsonPath("$.returnId").value(startsWith("D")));
        returnPix(receiver, e2e, "{\"value\":30.00}", key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        returnPix(receiver, e2e, "{\"value\":70.01}", newKey()).andExpect(status().isUnprocessableEntity());
        returnPix(payer, e2e, "{\"value\":1.00}", newKey()).andExpect(status().isNotFound());
        returnPix(receiver, e2e, "", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.value").value(70.00));

        assertThat(balanceOf(payer)).isEqualByComparingTo("500.00");
        assertThat(balanceOf(receiver)).isEqualByComparingTo("0.00");
        mvc.perform(get("/pix/payments/{id}/returns", e2e).with(as(payer)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsToOtherInstitutionsSettleAsynchronouslyAndAreReversedWhenRefused() throws Exception {
        var receiver = newUser(UserType.COMMON, "External Receiver");
        registerKey(receiver, receiver.email());
        String accepted = receiveExternal(receiver, "80.00");
        String refused = receiveExternal(receiver, "20.00");
        doReturn(PixGateway.SubmitResult.rejected("Original account closed"))
                .when(pixGateway).submitReturn(argThat(o -> o != null && refused.equals(o.originalEndToEndId())));

        String ok = JsonPath.read(returnPix(receiver, accepted, "{\"value\":50.00}", newKey())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.returnId");
        returnPix(receiver, refused, "", newKey()).andExpect(status().isAccepted());
        assertThat(balanceOf(receiver)).isEqualByComparingTo("30.00");

        awaitReturnStatus(receiver, accepted, "COMPLETED");
        awaitReturnStatus(receiver, refused, "FAILED");
        assertThat(ok).startsWith("D");
        assertThat(balanceOf(receiver)).isEqualByComparingTo("50.00");
        mvc.perform(get("/pix/payments/{id}/returns", refused).with(as(receiver)))
                .andExpect(jsonPath("$[0].failureReason").value("Original account closed"));
    }

    @Test
    void acceptedFraudClaimReturnsWhatWasLeftAndFreezesTheReceiver() throws Exception {
        var victim = newUserWithBalance("Scam Victim", "500.00");
        var scammer = newUser(UserType.COMMON, "Scammer");
        var accomplice = newUser(UserType.COMMON, "Accomplice");
        String e2e = sendInternal(victim, scammer, "200.00");
        transfer(scammer, accomplice, "50.00").andExpect(status().isCreated());

        String claimId = JsonPath.read(mvc.perform(post("/pix/payments/{id}/fraud-claims", e2e).with(as(victim))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Fake seller\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.blocked").value(150.00))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(balanceOf(scammer)).isEqualByComparingTo("0.00");
        mvc.perform(post("/pix/payments/{id}/fraud-claims", e2e).with(as(victim))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Again\"}"))
                .andExpect(status().isConflict());
        returnPix(scammer, e2e, "", newKey()).andExpect(status().isUnprocessableEntity());

        var analyst = admin();
        mvc.perform(post("/admin/pix/fraud-claims/{id}/resolution", claimId).with(as(analyst))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.returned").value(150.00));

        assertThat(balanceOf(victim)).isEqualByComparingTo("450.00");
        mvc.perform(get("/pix/payments/{id}/returns", e2e).with(as(victim)))
                .andExpect(jsonPath("$[0].reason").value("FR01"))
                .andExpect(jsonPath("$[0].value").value(150.00));
        transfer(accomplice, scammer, "10.00").andExpect(status().isCreated());
        transfer(scammer, accomplice, "1.00").andExpect(status().isForbidden());
        mvc.perform(get("/pix/fraud-claims").with(as(victim))).andExpect(jsonPath("$[0].id").value(claimId));
    }

    @Test
    void rejectedFraudClaimReleasesTheFrozenMoney() throws Exception {
        var payer = newUserWithBalance("Regretful Payer", "100.00");
        var receiver = newUser(UserType.COMMON, "Honest Receiver");
        String e2e = sendInternal(payer, receiver, "60.00");

        String claimId = JsonPath.read(mvc.perform(post("/pix/payments/{id}/fraud-claims", e2e).with(as(payer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Changed my mind\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(balanceOf(receiver)).isEqualByComparingTo("0.00");

        mvc.perform(post("/admin/pix/fraud-claims/{id}/resolution", claimId).with(as(admin()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":false}"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(balanceOf(receiver)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(payer)).isEqualByComparingTo("40.00");
        returnPix(receiver, e2e, "{\"value\":10.00}", newKey()).andExpect(status().isCreated());
    }

    @Test
    void claimsOnPixToOtherInstitutionsAreRefused() throws Exception {
        var payer = newUserWithBalance("Outbound Payer", "50.00");
        String key = "claim-" + UUID.randomUUID() + "@otherbank.com";
        doReturn(Optional.of(new PixGateway.ExternalAccount("Carol Jones", "11122233344", "60701190", "Other Bank")))
                .when(pixGateway).lookup(key);
        String e2e = JsonPath.read(mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"%s\",\"value\":10.00}".formatted(key)))
                .andReturn().getResponse().getContentAsString(), "$.endToEndId");

        mvc.perform(post("/pix/payments/{id}/fraud-claims", e2e).with(as(payer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("Claims on Pix sent to other institutions")));
    }

    private String sendInternal(UserResponse payer, UserResponse receiver, String value) throws Exception {
        registerKey(receiver, receiver.email());
        return JsonPath.read(mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"%s\",\"value\":%s}".formatted(receiver.email(), value)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.endToEndId");
    }

    private String receiveExternal(UserResponse receiver, String value) throws Exception {
        String e2e = EndToEndIds.generate("60701190", Instant.now());
        String body = """
                {"endToEndId": "%s", "key": "%s", "value": %s, "payerName": "Dan Brown",
                 "payerDocument": "55566677788", "payerIspb": "60701190"}
                """.formatted(e2e, receiver.email(), value);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/pix/webhooks/incoming").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Pix-Timestamp", timestamp)
                        .header("X-Pix-Signature",
                                WebhookSignatureVerifier.sign(WEBHOOK_SECRET, timestamp + "." + body)))
                .andExpect(status().isOk());
        return e2e;
    }

    private void registerKey(UserResponse user, String email) throws Exception {
        mvc.perform(post("/pix/keys").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"value\":\"%s\"}".formatted(email)));
    }

    private ResultActions returnPix(UserResponse user, String e2e, String body, String key) throws Exception {
        var request = post("/pix/payments/{id}/returns", e2e).with(as(user)).header("Idempotency-Key", key);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private ResultActions transfer(UserResponse payer, UserResponse payee, String value) throws Exception {
        return mvc.perform(post("/transfer").with(as(payer)).header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":%s,\"payee\":%d}".formatted(value, payee.id())));
    }

    private void awaitReturnStatus(UserResponse user, String e2e, String expected) {
        await().atMost(ASYNC).untilAsserted(() -> mvc.perform(get("/pix/payments/{id}/returns", e2e).with(as(user)))
                .andExpect(jsonPath("$[0].status").value(expected)));
    }

    private UserResponse admin() {
        var admin = newUser(UserType.COMMON, "Claims Analyst");
        makeAdmin(admin);
        return admin;
    }
}

package br.com.paywallet.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.pix.BrCode;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class MerchantIntegrationTest extends IntegrationTest {

    @Autowired ChargeService chargeService;
    @Autowired LedgerService ledger;

    @Test
    void createsAChargeWithPaymentLinkAndQrCode() throws Exception {
        var merchant = merchantWithPixKey("Corner Cafe");

        String body = createCharge(merchant, "{\"value\": 42.90, \"description\": \"Order 7\", \"reference\": \"order-7\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentLink").value(startsWith("http://localhost:8080/pay/")))
                .andReturn().getResponse().getContentAsString();

        var code = BrCode.parse(JsonPath.read(body, "$.brCode"));
        assertThat(code.amount()).isEqualByComparingTo("42.90");
        assertThat(code.txid()).isEqualTo(JsonPath.read(body, "$.txid")).startsWith("PW").hasSize(25);

        // The link is public: no token needed to see what is being charged.
        mvc.perform(get("/pay/{token}", token(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").value("Corner Cafe"))
                .andExpect(jsonPath("$.value").value(42.90))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void theSameReferenceReturnsTheExistingCharge() throws Exception {
        var merchant = merchantWithPixKey("Idempotent Shop");
        String json = "{\"value\": 10, \"reference\": \"order-1\"}";

        String first = createCharge(merchant, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = createCharge(merchant, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void merchantWithoutPixKeyGetsAnAppOnlyCharge() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "No Key Store");
        createCharge(merchant, "{\"value\": 5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.brCode").doesNotExist());
    }

    @Test
    void onlyMerchantsReachTheMerchantArea() throws Exception {
        var individual = newUser(UserType.COMMON, "Not A Merchant");
        createCharge(individual, "{\"value\": 5}").andExpect(status().isForbidden());
        mvc.perform(get("/merchant/dashboard").param("from", "2026-01-01").param("to", "2026-01-31").with(as(individual)))
                .andExpect(status().isForbidden());
    }

    @Test
    void walletPaymentAppliesTheMdrAndFeedsTheDashboard() throws Exception {
        var merchant = merchantWithPixKey("Book Store");
        var payer = newUserWithBalance("Book Buyer", "150.00");
        long feesBefore = feesBalance();
        String charge = createCharge(merchant, "{\"value\": 100, \"reference\": \"book-1\"}")
                .andReturn().getResponse().getContentAsString();

        pay(payer, token(charge))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.merchantName").value("Book Store"));

        assertThat(balanceOf(payer)).isEqualByComparingTo("50.00");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("98.01");
        assertThat(feesBalance() - feesBefore).isEqualTo(199);
        assertThat(ledger.reconcile().consistent()).isTrue();

        mvc.perform(get("/merchant/charges/{id}", (String) JsonPath.read(charge, "$.id")).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("WALLET"))
                .andExpect(jsonPath("$.fee").value(1.99))
                .andExpect(jsonPath("$.net").value(98.01));

        String today = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();
        mvc.perform(get("/merchant/dashboard").param("from", today).param("to", today).with(as(merchant)))
                .andExpect(jsonPath("$.total.count").value(1))
                .andExpect(jsonPath("$.total.gross").value(100.00))
                .andExpect(jsonPath("$.total.fees").value(1.99))
                .andExpect(jsonPath("$.total.net").value(98.01))
                .andExpect(jsonPath("$.wallet.count").value(1))
                .andExpect(jsonPath("$.pix.count").value(0))
                .andExpect(jsonPath("$.daily[0].date").value(today));

        verify(notificationClient, timeout(20_000)).send(eq(merchant.email()), contains("Charge book-1 paid"));
    }

    @Test
    void aChargeIsPaidOnlyOnce() throws Exception {
        var merchant = merchantWithPixKey("Once Store");
        var first = newUserWithBalance("First Payer", "50.00");
        var second = newUserWithBalance("Second Payer", "50.00");
        String token = token(createCharge(merchant, "{\"value\": 20}").andReturn().getResponse().getContentAsString());

        String receipt = pay(first, token).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = pay(first, token).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        pay(second, token).andExpect(status().isConflict());

        assertThat(replay).isEqualTo(receipt);

        assertThat(balanceOf(first)).isEqualByComparingTo("30.00");
        assertThat(balanceOf(second)).isEqualByComparingTo("50.00");
    }

    @Test
    void concurrentPayersCannotPayTheSameChargeTwice() throws Exception {
        var merchant = merchantWithPixKey("Race Store");
        String token = token(createCharge(merchant, "{\"value\": 10}").andReturn().getResponse().getContentAsString());
        List<UserResponse> payers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            payers.add(newUserWithBalance("Racer " + i, "10.00"));
        }

        List<Callable<Boolean>> tasks = payers.stream().<Callable<Boolean>>map(p -> () -> {
            try {
                chargeService.payWithWallet(p.id(), token);
                return true;
            } catch (ConflictException e) {
                return false;
            }
        }).toList();
        long paid;
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            paid = pool.invokeAll(tasks).stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
        }

        assertThat(paid).isEqualTo(1);
        assertThat(balanceOf(merchant)).isEqualByComparingTo("9.80");
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void expiredAndCancelledChargesCannotBePaid() throws Exception {
        var merchant = merchantWithPixKey("Strict Store");
        var payer = newUserWithBalance("Late Payer", "50.00");
        String expired = createCharge(merchant, "{\"value\": 5}").andReturn().getResponse().getContentAsString();
        String cancelled = createCharge(merchant, "{\"value\": 5}").andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE charges SET expires_at = ? WHERE id = ?::uuid",
                Timestamp.from(Instant.now().minusSeconds(60)), JsonPath.read(expired, "$.id"));

        mvc.perform(post("/merchant/charges/{id}/cancel", (String) JsonPath.read(cancelled, "$.id")).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        pay(payer, token(expired)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Charge expired"));
        pay(payer, token(cancelled)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Charge cancelled"));
        mvc.perform(get("/pay/{token}", token(expired))).andExpect(jsonPath("$.brCode").doesNotExist());

        chargeService.expireOverdue();
        assertThat(jdbc.queryForObject("SELECT status FROM charges WHERE id = ?::uuid", String.class,
                (String) JsonPath.read(expired, "$.id"))).isEqualTo("EXPIRED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("50.00");
    }

    @Test
    void merchantsOnlySeeTheirOwnCharges() throws Exception {
        var owner = merchantWithPixKey("Owner Store");
        var other = merchantWithPixKey("Other Store");
        String id = JsonPath.read(createCharge(owner, "{\"value\": 5}").andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/merchant/charges/{id}", id).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(post("/merchant/charges/{id}/cancel", id).with(as(other))).andExpect(status().isNotFound());
    }

    @Test
    void merchantsCannotPayCharges() throws Exception {
        var seller = merchantWithPixKey("Seller");
        var buyer = merchantWithPixKey("Buying Merchant");
        String token = token(createCharge(seller, "{\"value\": 5}").andReturn().getResponse().getContentAsString());

        pay(buyer, token).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pixWithTheChargeQrCodeSettlesItWithThePixRate() throws Exception {
        var merchant = merchantWithPixKey("Pix Store");
        var payer = newUserWithBalance("Pix Buyer", "200.00");
        String charge = createCharge(merchant, "{\"value\": 100}").andReturn().getResponse().getContentAsString();
        String brCode = JsonPath.read(charge, "$.brCode");

        mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"brCode\": \"%s\"}".formatted(brCode)))
                .andExpect(status().isCreated());
        // A second Pix with the same QR code is refused: the charge is settled.
        mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"brCode\": \"%s\"}".formatted(brCode)))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/merchant/charges/{id}", (String) JsonPath.read(charge, "$.id")).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("PIX"))
                .andExpect(jsonPath("$.fee").value(0.99));
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("99.01");
    }

    @Test
    void pixFromAnotherBankCarryingTheTxidSettlesTheCharge() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Online Shop");
        String evp = JsonPath.read(mvc.perform(post("/pix/keys").with(as(merchant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"EVP\"}")).andReturn().getResponse().getContentAsString(), "$.value");
        String charge = createCharge(merchant, "{\"value\": 50}").andReturn().getResponse().getContentAsString();
        String txid = JsonPath.read(charge, "$.txid");

        String body = """
                {"endToEndId": "E60701190%s00000000001", "key": "%s", "value": 50, "payerName": "Remote Buyer",
                 "payerIspb": "60701190", "txid": "%s"}
                """.formatted(DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                        .withZone(ZoneOffset.UTC).format(Instant.now()), evp, txid);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/pix/webhooks/incoming")
                        .header("X-Pix-Timestamp", timestamp)
                        .header("X-Pix-Signature", sign(timestamp + "." + body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(get("/merchant/charges/{id}", (String) JsonPath.read(charge, "$.id")).with(as(merchant)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("PIX"));
        assertThat(balanceOf(merchant)).isEqualByComparingTo("49.50");
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    private UserResponse merchantWithPixKey(String name) throws Exception {
        var merchant = newUser(UserType.MERCHANT, name);
        mvc.perform(post("/pix/keys").with(as(merchant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"EVP\"}")).andExpect(status().isCreated());
        return merchant;
    }

    private ResultActions createCharge(UserResponse merchant, String json) throws Exception {
        return mvc.perform(post("/merchant/charges").with(as(merchant)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions pay(UserResponse payer, String token) throws Exception {
        return mvc.perform(post("/pay/{token}", token).with(as(payer)));
    }

    private static String token(String chargeJson) {
        String link = JsonPath.read(chargeJson, "$.paymentLink");
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private long feesBalance() {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, AccountType.FEES_ACCOUNT_ID);
    }

    private static String sign(String payload) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}

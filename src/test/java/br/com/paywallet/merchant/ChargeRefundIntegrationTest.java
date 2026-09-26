package br.com.paywallet.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

/** Wallet payments carry a 1.99% fee and Pix payments 0.99%; refunds come fully out of the merchant's wallet. */
class ChargeRefundIntegrationTest extends IntegrationTest {

    @Test
    void walletPaymentsAreRefundedToThePayerWithoutGivingBackTheFee() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Refunding Store");
        walletService.deposit(merchant.id(), new BigDecimal("10.00"), UUID.randomUUID().toString());
        var payer = newUserWithBalance("Refunded Buyer", "150.00");
        String charge = createCharge(merchant, "{\"value\": 100}");
        String chargeId = JsonPath.read(charge, "$.id");
        String link = JsonPath.read(charge, "$.paymentLink");
        mvc.perform(post("/pay/{token}", link.substring(link.lastIndexOf('/') + 1)).with(as(payer)))
                .andExpect(status().isCreated());
        assertThat(balanceOf(merchant)).isEqualByComparingTo("108.01");

        String key = newKey();
        refund(merchant, chargeId, "{\"value\": 40.00}", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(40.00));
        refund(merchant, chargeId, "{\"value\": 40.00}", key)
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.refunded").value(40.00));
        refund(merchant, chargeId, "{\"value\": 60.01}", newKey()).andExpect(status().isUnprocessableEntity());
        refund(merchant, chargeId, "", newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(100.00))
                .andExpect(jsonPath("$.fee").value(1.99));

        assertThat(balanceOf(payer)).isEqualByComparingTo("150.00");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("8.01");
        refund(merchant, chargeId, "", newKey()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refundsNeedTheMoneyInTheMerchantWallet() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Broke Store");
        var payer = newUserWithBalance("Hopeful Buyer", "50.00");
        String charge = createCharge(merchant, "{\"value\": 20}");
        String link = JsonPath.read(charge, "$.paymentLink");
        mvc.perform(post("/pay/{token}", link.substring(link.lastIndexOf('/') + 1)).with(as(payer)));

        refund(merchant, JsonPath.read(charge, "$.id"), "", newKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Insufficient funds"));
    }

    @Test
    void pixPaymentsAreRefundedWithAPixReturn() throws Exception {
        var merchant = newUser(UserType.MERCHANT, "Pix Refund Store");
        walletService.deposit(merchant.id(), new BigDecimal("5.00"), UUID.randomUUID().toString());
        mvc.perform(post("/pix/keys").with(as(merchant)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"EVP\"}")).andExpect(status().isCreated());
        var payer = newUserWithBalance("Pix Refund Buyer", "80.00");
        String charge = createCharge(merchant, "{\"value\": 50}");
        String chargeId = JsonPath.read(charge, "$.id");
        String e2e = JsonPath.read(mvc.perform(post("/pix/payments").with(as(payer)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brCode\": \"%s\"}".formatted((String) JsonPath.read(charge, "$.brCode"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.endToEndId");

        refund(merchant, chargeId, "{\"value\": 50.00}", newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(50.00));
        mvc.perform(get("/pix/payments/{id}/returns", e2e).with(as(payer)))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].value").value(50.00));
        assertThat(balanceOf(payer)).isEqualByComparingTo("80.00");
        assertThat(balanceOf(merchant)).isEqualByComparingTo("4.50");

        mvc.perform(post("/pix/payments/{id}/returns", e2e).with(as(merchant)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\": 1.00}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String createCharge(UserResponse merchant, String json) throws Exception {
        return mvc.perform(post("/merchant/charges").with(as(merchant)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions refund(UserResponse merchant, String chargeId, String body, String key) throws Exception {
        var request = post("/merchant/charges/{id}/refunds", chargeId).with(as(merchant))
                .header("Idempotency-Key", key);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}

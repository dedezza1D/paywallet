package br.com.paywallet.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.ExternalServiceException;
import br.com.paywallet.ledger.AccountType;
import br.com.paywallet.marketplace.MarketplaceProvider.FulfillmentOrder;
import br.com.paywallet.marketplace.MarketplaceProvider.FulfillmentResult;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class MarketplaceIntegrationTest extends IntegrationTest {

    @Test
    void catalogListsActiveProductsWithValuesAndCashback() throws Exception {
        var buyer = newUser(UserType.COMMON, "Browser");
        mvc.perform(get("/marketplace/products").with(as(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[?(@.id == 'netflix')].cashbackPercent", hasItem(2.00)));
        mvc.perform(get("/marketplace/products").param("category", "MOBILE_RECHARGE").with(as(buyer)))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].values[0]").value(15.00));
    }

    @Test
    void giftCardIsDeliveredWithEncryptedCodeCommissionAndCashback() throws Exception {
        var buyer = newUserWithBalance("Gift Buyer", "200.00");
        long fees = accountBalance(AccountType.FEES_ACCOUNT_ID);
        long cashbackExpense = accountBalance(AccountType.CASHBACK_ACCOUNT_ID);
        long settlement = accountBalance(AccountType.MARKETPLACE_SETTLEMENT_ACCOUNT_ID);

        String key = newKey();
        String body = "{\"productId\":\"netflix\",\"value\":50.00}";
        String orderId = JsonPath.read(purchase(buyer, body, key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.cashback").value(1.00))
                .andExpect(jsonPath("$.voucherCode").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(balanceOf(buyer)).isEqualByComparingTo("150.00");
        awaitStatus(buyer, orderId, "COMPLETED");

        mvc.perform(get("/marketplace/orders/" + orderId).with(as(buyer)))
                .andExpect(jsonPath("$.voucherCode").value(GIFT_CODE))
                .andExpect(jsonPath("$.productName").value("Netflix gift card"));
        mvc.perform(get("/marketplace/orders").with(as(buyer)))
                .andExpect(jsonPath("$.content[0].id").value(orderId))
                .andExpect(jsonPath("$.content[0].voucherCode").doesNotExist());
        String stored = jdbc.queryForObject("SELECT voucher_code FROM marketplace_orders WHERE id = ?", String.class,
                UUID.fromString(orderId));
        assertThat(stored).isNotBlank().doesNotContain(GIFT_CODE);

        assertThat(balanceOf(buyer)).isEqualByComparingTo("151.00");
        assertThat(accountBalance(AccountType.FEES_ACCOUNT_ID) - fees).isEqualTo(250);
        assertThat(accountBalance(AccountType.CASHBACK_ACCOUNT_ID) - cashbackExpense).isEqualTo(-100);
        assertThat(accountBalance(AccountType.MARKETPLACE_SETTLEMENT_ACCOUNT_ID) - settlement).isEqualTo(4_750);
        mvc.perform(get("/cashback").with(as(buyer)))
                .andExpect(jsonPath("$.total").value(1.00))
                .andExpect(jsonPath("$.thisMonth").value(1.00));
        verify(notificationClient, timeout(10_000)).send(eq(buyer.email()),
                contains("Your Netflix gift card of R$ 50.00 is ready. R$ 1.00 cashback"));

        purchase(buyer, body, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId));
        purchase(buyer, "{\"productId\":\"netflix\",\"value\":35.00}", key).andExpect(status().isUnprocessableEntity());
        assertThat(balanceOf(buyer)).isEqualByComparingTo("151.00");
    }

    @Test
    void refusedRechargeIsRefundedWithoutCashback() throws Exception {
        var buyer = newUserWithBalance("Recharge Buyer", "100.00");
        String phone = newPhone();
        doReturn(FulfillmentResult.rejected("Phone number not served by the carrier"))
                .when(marketplaceProvider).fulfill(argThat(forPhone(phone)));

        String orderId = JsonPath.read(purchase(buyer,
                        "{\"productId\":\"vivo\",\"value\":30.00,\"phoneNumber\":\"%s\"}".formatted(phone), newKey())
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(balanceOf(buyer)).isEqualByComparingTo("70.00");

        awaitStatus(buyer, orderId, "FAILED")
                .andExpect(jsonPath("$.failureReason").value("Phone number not served by the carrier"));
        assertThat(balanceOf(buyer)).isEqualByComparingTo("100.00");
        mvc.perform(get("/cashback").with(as(buyer))).andExpect(jsonPath("$.total").value(0.0));
    }

    @Test
    void ordersStayPendingWhileTheProviderIsDown() throws Exception {
        var buyer = newUserWithBalance("Patient Buyer", "100.00");
        String phone = newPhone();
        doThrow(new ExternalServiceException("Provider unavailable", null))
                .when(marketplaceProvider).fulfill(argThat(forPhone(phone)));

        String orderId = JsonPath.read(purchase(buyer,
                        "{\"productId\":\"tim\",\"value\":20.00,\"phoneNumber\":\"%s\"}".formatted(phone), newKey())
                .andReturn().getResponse().getContentAsString(), "$.id");
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT attempts FROM marketplace_orders WHERE id = ?", Integer.class, UUID.fromString(orderId)) >= 2);
        mvc.perform(get("/marketplace/orders/" + orderId).with(as(buyer))).andExpect(jsonPath("$.status").value("PENDING"));

        doReturn(FulfillmentResult.ok(null, "REF-LATER")).when(marketplaceProvider).fulfill(argThat(forPhone(phone)));
        awaitStatus(buyer, orderId, "COMPLETED").andExpect(jsonPath("$.voucherCode").doesNotExist());
        assertThat(balanceOf(buyer)).isEqualByComparingTo("80.20");
    }

    @Test
    void purchasesAreValidated() throws Exception {
        var buyer = newUserWithBalance("Careful Buyer", "40.00");
        var merchant = newUser(UserType.MERCHANT, "Buying Shop");

        purchase(buyer, "{\"productId\":\"netflix\",\"value\":42.00}", newKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Value not available for this product"));
        purchase(buyer, "{\"productId\":\"claro\",\"value\":15.00}", newKey())
                .andExpect(jsonPath("$.detail").value("Mobile recharges require a phone number"));
        purchase(buyer, "{\"productId\":\"uber\",\"value\":20.00,\"phoneNumber\":\"11987654321\"}", newKey())
                .andExpect(jsonPath("$.detail").value("Only mobile recharges take a phone number"));
        purchase(buyer, "{\"productId\":\"claro\",\"value\":15.00,\"phoneNumber\":\"1133334444\"}", newKey())
                .andExpect(status().isBadRequest());
        purchase(buyer, "{\"productId\":\"netflix\",\"value\":50.00}", newKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Insufficient funds"));
        purchase(buyer, "{\"productId\":\"steam\",\"value\":50.00}", newKey()).andExpect(status().isNotFound());
        purchase(merchant, "{\"productId\":\"uber\",\"value\":20.00}", newKey())
                .andExpect(jsonPath("$.detail").value("Marketplace purchases are available to individuals only"));
        assertThat(balanceOf(buyer)).isEqualByComparingTo("40.00");
    }

    private ResultActions purchase(UserResponse buyer, String body, String key) throws Exception {
        return mvc.perform(post("/marketplace/orders").with(as(buyer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions awaitStatus(UserResponse buyer, String orderId, String expected) throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> expected.equals(jdbc.queryForObject(
                "SELECT status FROM marketplace_orders WHERE id = ?", String.class, UUID.fromString(orderId))));
        return mvc.perform(get("/marketplace/orders/" + orderId).with(as(buyer)))
                .andExpect(jsonPath("$.status").value(expected));
    }

    private long accountBalance(UUID accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private static ArgumentMatcher<FulfillmentOrder> forPhone(String phone) {
        return order -> order != null && phone.equals(order.phoneNumber());
    }

    private static String newPhone() {
        return "119" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
    }
}

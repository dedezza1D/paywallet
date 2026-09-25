package br.com.paywallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.exception.BusinessException;
import br.com.paywallet.exception.ConflictException;
import br.com.paywallet.feed.FeedService;
import br.com.paywallet.feed.Visibility;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;
import br.com.paywallet.wallet.WalletDtos.TransferRequest;

class WalletIntegrationTest extends IntegrationTest {

    @Autowired FeedService feed;
    @Autowired LedgerService ledger;

    @Test
    void transferMovesMoneyWritesStatementFeedAndNotifies() throws Exception {
        var mary = newUserWithBalance("Mary", "50.00");
        var shop = newUser(UserType.MERCHANT, "Pizza Place");

        transfer(mary, newKey(), """
                {"value": 20.50, "payee": %d, "message": "pizza 🍕", "visibility": "PUBLIC"}
                """.formatted(shop.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payer").value(mary.id()))
                .andExpect(jsonPath("$.value").value(20.50));

        assertThat(balanceOf(mary)).isEqualByComparingTo("29.50");
        assertThat(balanceOf(shop)).isEqualByComparingTo("20.50");

        // The statement comes from the ledger, with the balance after each posting.
        mvc.perform(get("/users/{id}/statement", mary.id()).with(as(mary)))
                .andExpect(jsonPath("$.content[0].type").value("P2P_TRANSFER"))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(29.50))
                .andExpect(jsonPath("$.content[1].type").value("CASH_IN"));

        // Feed and notification are asynchronous.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(feed.userFeed(shop.id(), PageRequest.of(0, 10)).getContent())
                        .singleElement()
                        .satisfies(item -> {
                            assertThat(item.message()).isEqualTo("pizza 🍕");
                            assertThat(item.value()).isEqualByComparingTo("20.50");
                        }));
        verify(notificationClient, timeout(20_000)).send(eq(shop.email()), contains("20.50"));
    }

    @Test
    void publicFeedDoesNotExposeValues() throws Exception {
        var a = newUserWithBalance("Public", "10.00");
        var b = newUser(UserType.COMMON, "Friend");
        walletService.transfer(a.id(), new TransferRequest(BigDecimal.ONE, b.id(), "thanks!", Visibility.PUBLIC), newKey());

        // The public feed requires no login.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get("/feed").param("size", "100"))
                        .andExpect(jsonPath("$.content[?(@.message == 'thanks!' && @.payerName == 'Public')]").exists())
                        .andExpect(jsonPath("$.content[0].value").doesNotExist()));
    }

    @Test
    void sameIdempotencyKeyReplaysInsteadOfPayingTwice() throws Exception {
        var john = newUserWithBalance("John", "100.00");
        var anna = newUser(UserType.COMMON, "Anna");
        var key = newKey();
        var body = """
                {"value": 30, "payee": %d}
                """.formatted(anna.id());

        var first = transfer(john, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var second = transfer(john, key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(john)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(anna)).isEqualByComparingTo("30.00");
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() throws Exception {
        var john = newUserWithBalance("John2", "100.00");
        var anna = newUser(UserType.COMMON, "Anna2");
        var key = newKey();

        transfer(john, key, """
                {"value": 30, "payee": %d}
                """.formatted(anna.id())).andExpect(status().isCreated());
        transfer(john, key, """
                {"value": 31, "payee": %d}
                """.formatted(anna.id())).andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(john)).isEqualByComparingTo("70.00");
    }

    /** Extreme double click: 10 concurrent requests with the same key move money exactly once. */
    @Test
    void concurrentRequestsWithSameKeyMoveMoneyOnce() throws Exception {
        var payer = newUserWithBalance("Clicker", "100.00");
        var payee = newUser(UserType.COMMON, "Receiver");
        var key = newKey();
        var request = new TransferRequest(BigDecimal.TEN, payee.id(), null, null);

        Callable<String> task = () -> {
            try {
                return walletService.transfer(payer.id(), request, key).replayed() ? "replay" : "created";
            } catch (ConflictException e) {
                return "conflict";
            }
        };
        var outcomes = runConcurrently(10, task);

        assertThat(outcomes).containsOnlyOnce("created");
        assertThat(balanceOf(payer)).isEqualByComparingTo("90.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("10.00");
    }

    /** 20 concurrent transfers of 10 (distinct keys) from a balance of 50: exactly 5 succeed. */
    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        var payer = newUserWithBalance("Concurrent", "50.00");
        var payee = newUser(UserType.COMMON, "Target");
        var request = new TransferRequest(BigDecimal.TEN, payee.id(), null, null);

        Callable<String> task = () -> {
            try {
                walletService.transfer(payer.id(), request, newKey());
                return "ok";
            } catch (BusinessException e) {
                return "rejected";
            }
        };
        var outcomes = runConcurrently(20, task);

        assertThat(outcomes.stream().filter("ok"::equals)).hasSize(5);
        assertThat(balanceOf(payer)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("50.00");
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void dailyLimitIsEnforced() throws Exception {
        var rich = newUserWithBalance("Rich", "8000.00");
        var other = newUser(UserType.COMMON, "Other");

        transfer(rich, newKey(), """
                {"value": 4000, "payee": %d}
                """.formatted(other.id())).andExpect(status().isCreated());
        transfer(rich, newKey(), """
                {"value": 1000.01, "payee": %d}
                """.formatted(other.id()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Daily transfer limit exceeded"));

        mvc.perform(get("/users/{id}/limits", rich.id()).with(as(rich)))
                .andExpect(jsonPath("$.usedToday").value(4000.00))
                .andExpect(jsonPath("$.remainingToday").value(1000.00));
    }

    @Test
    void deniedAuthorizationReturns403AndReleasesLimit() throws Exception {
        var user = newUserWithBalance("Denied", "50.00");
        var other = newUser(UserType.COMMON, "Anyone");
        when(authorizationClient.isAuthorized()).thenReturn(false);

        transfer(user, newKey(), """
                {"value": 10, "payee": %d}
                """.formatted(other.id())).andExpect(status().isForbidden());

        assertThat(balanceOf(user)).isEqualByComparingTo("50.00");
        mvc.perform(get("/users/{id}/limits", user.id()).with(as(user)))
                .andExpect(jsonPath("$.usedToday").value(0.0));
    }

    @Test
    void merchantCannotSendMoney() throws Exception {
        var shop = newUser(UserType.MERCHANT, "Shop");
        var client = newUser(UserType.COMMON, "Customer");

        transfer(shop, newKey(), """
                {"value": 1, "payee": %d}
                """.formatted(client.id()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Merchants cannot send transfers"));
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        var user = newUser(UserType.COMMON, "NoKey");
        mvc.perform(post("/transfer").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 1, \"payee\": 2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidPayloadReturnsFieldErrors() throws Exception {
        var user = newUser(UserType.COMMON, "Invalid");
        transfer(user, newKey(), "{\"value\": -5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.value").exists())
                .andExpect(jsonPath("$.errors.payee").exists());
    }

    @Test
    void cachedBalanceIsInvalidatedAfterTransfer() throws Exception {
        var a = newUserWithBalance("Cache A", "40.00");
        var b = newUser(UserType.COMMON, "Cache B");
        assertThat(balanceOf(a)).isEqualByComparingTo("40.00"); // populates the cache

        walletService.transfer(a.id(), new TransferRequest(new BigDecimal("15.00"), b.id(), null, null), newKey());

        assertThat(balanceOf(a)).isEqualByComparingTo("25.00");
    }

    private ResultActions transfer(UserResponse payer, String key, String body) throws Exception {
        return mvc.perform(post("/transfer")
                .with(as(payer))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static List<String> runConcurrently(int times, Callable<String> task) throws Exception {
        try (var pool = Executors.newFixedThreadPool(times)) {
            var futures = pool.invokeAll(IntStream.range(0, times).mapToObj(i -> task).toList());
            var results = new ArrayList<String>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}

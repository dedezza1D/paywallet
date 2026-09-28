package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class AccountClosureIntegrationTest extends IntegrationTest {

    @Test
    void anAccountWithNothingToSettleCanBeClosed() throws Exception {
        var user = newUser(UserType.COMMON, "Leaving Customer");
        var token = as(user);
        mvc.perform(post("/pix/keys").with(token).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EVP\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/cards").with(token).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DEBIT\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/auth/account/closure").with(token))
                .andExpect(jsonPath("$.closable").value(true))
                .andExpect(jsonPath("$.blockers").isEmpty());
        close(user, "wrong-password-123").andExpect(status().isUnprocessableEntity());
        close(user, PASSWORD).andExpect(status().isNoContent());

        mvc.perform(get("/users/{id}/balance", user.id()).with(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("This account is closed"));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.email(), PASSWORD)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pix_keys WHERE user_id = ?", Long.class, user.id()))
                .isZero();
        assertThat(jdbc.queryForList("SELECT status FROM cards WHERE user_id = ?", String.class, user.id()))
                .containsOnly("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, user.id())).isZero();

        var sender = newUserWithBalance("Late Sender", "10.00");
        mvc.perform(post("/transfer").with(as(sender)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": 5, \"payee\": %d}".formatted(user.id())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("The recipient's account is closed"));
    }

    @Test
    void moneyLeftInTheAccountBlocksTheClosure() throws Exception {
        var user = newUserWithBalance("Customer With Balance", "3.00");

        mvc.perform(get("/auth/account/closure").with(as(user)))
                .andExpect(jsonPath("$.closable").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("Transfer out your balance"));
        close(user, PASSWORD).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT closed_at IS NULL FROM users WHERE id = ?", Boolean.class, user.id()))
                .isTrue();
    }

    private ResultActions close(UserResponse user, String password) throws Exception {
        return mvc.perform(post("/auth/account/closure").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(password)));
    }
}

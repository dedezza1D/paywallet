package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;

class TransactionPinIntegrationTest extends IntegrationTest {

    @Test
    void outflowsNeedTheRightPin() throws Exception {
        var payer = newUserWithBalance("Pin Payer", "100.00");
        var payee = newUser(UserType.COMMON, "Pin Payee");

        transfer(payer, payee, null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.detail").value("Transaction PIN required in the X-Transaction-Pin header"));
        transfer(payer, payee, "111222").andExpect(status().isForbidden());
        transfer(payer, payee, TEST_PIN).andExpect(status().isCreated());
        assertThat(balanceOf(payer)).isEqualByComparingTo("99.00");
    }

    @Test
    void repeatedWrongPinsLockOutflows() throws Exception {
        var payer = newUserWithBalance("Guessing Payer", "100.00");
        var payee = newUser(UserType.COMMON, "Guessing Payee");

        for (int i = 0; i < 5; i++) {
            transfer(payer, payee, "9" + "%05d".formatted(i)).andExpect(status().isForbidden());
        }
        transfer(payer, payee, TEST_PIN).andExpect(status().isTooManyRequests());
        assertThat(balanceOf(payer)).isEqualByComparingTo("100.00");
    }

    @Test
    void thePinIsSetWithTheAccountPassword() throws Exception {
        var payer = newUserWithBalance("New Pin Payer", "100.00");
        var payee = newUser(UserType.COMMON, "New Pin Payee");
        jdbc.update("UPDATE users SET transaction_pin_hash = NULL WHERE id = ?", payer.id());

        transfer(payer, payee, TEST_PIN)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.detail").value("Set a transaction PIN before moving money"));
        setPin(payer, "wrong-password-123", "730514").andExpect(status().isUnprocessableEntity());
        setPin(payer, PASSWORD, "123456")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Choose a PIN that is not a repeated digit or a sequence"));
        setPin(payer, PASSWORD, "12345").andExpect(status().isBadRequest());
        setPin(payer, PASSWORD, "730514").andExpect(status().isNoContent());

        transfer(payer, payee, "730514").andExpect(status().isCreated());
    }

    @Test
    void trivialPinsAreRecognized() {
        assertThat(TransactionPinService.isTrivial("000000")).isTrue();
        assertThat(TransactionPinService.isTrivial("123456")).isTrue();
        assertThat(TransactionPinService.isTrivial("987654")).isTrue();
        assertThat(TransactionPinService.isTrivial("12a456")).isTrue();
        assertThat(TransactionPinService.isTrivial("730514")).isFalse();
        assertThat(TransactionPinService.isTrivial("112233")).isFalse();
    }

    private ResultActions transfer(UserResponse payer, UserResponse payee, String pin) throws Exception {
        String token = tokenFor(payer);
        var request = post("/transfer").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\": 1.00, \"payee\": %d}".formatted(payee.id()));
        if (pin != null) {
            request.header("X-Transaction-Pin", pin);
        }
        return mvc.perform(request);
    }

    private ResultActions setPin(UserResponse user, String password, String pin) throws Exception {
        return mvc.perform(post("/auth/pin").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\",\"pin\":\"%s\"}".formatted(password, pin)));
    }
}

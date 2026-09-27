package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.user.UserDtos.UserResponse;
import br.com.paywallet.user.UserType;
import jakarta.servlet.ServletException;

class MfaIntegrationTest extends IntegrationTest {

    @Autowired StringRedisTemplate redis;

    @Test
    void enabledTotpTurnsLoginIntoTwoSteps() throws Exception {
        var user = newUser(UserType.COMMON, "Two Factor");
        String secret = JsonPath.read(mvc.perform(post("/auth/mfa/setup").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(startsWith("otpauth://totp/PayWallet:")))
                .andReturn().getResponse().getContentAsString(), "$.secret");
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;

        code(user, "/auth/mfa/enable", "000000").andExpect(status().isUnprocessableEntity());
        List<String> recovery = JsonPath.read(code(user, "/auth/mfa/enable", Totp.code(secret, step))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.recoveryCodes");
        assertThat(recovery).hasSize(8).allMatch(c -> c.matches("[A-Z2-7]{4}-[A-Z2-7]{4}"));

        String challenge = JsonPath.read(login(user.email(), PASSWORD, "phone-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.mfaToken");
        mfaLogin(challenge, "000000").andExpect(status().isUnauthorized());
        String nextCode = Totp.code(secret, step + 1);
        mfaLogin(challenge, nextCode).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString());

        String replay = JsonPath.read(login(user.email(), PASSWORD, "phone-1").andReturn().getResponse()
                .getContentAsString(), "$.mfaToken");
        mfaLogin(replay, nextCode).andExpect(status().isUnauthorized());
        mfaLogin(replay, recovery.getFirst()).andExpect(status().isOk());

        String again = JsonPath.read(login(user.email(), PASSWORD, "phone-1").andReturn().getResponse()
                .getContentAsString(), "$.mfaToken");
        mfaLogin(again, recovery.getFirst()).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingRequiresThePasswordAndACode() throws Exception {
        var user = newUser(UserType.COMMON, "Undo Two Factor");
        String secret = JsonPath.read(mvc.perform(post("/auth/mfa/setup").with(as(user))).andReturn().getResponse()
                .getContentAsString(), "$.secret");
        long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS;
        List<String> recovery = JsonPath.read(code(user, "/auth/mfa/enable", Totp.code(secret, step))
                .andReturn().getResponse().getContentAsString(), "$.recoveryCodes");

        disable(user, "not-the-password-at-all", recovery.get(1)).andExpect(status().isUnprocessableEntity());
        disable(user, PASSWORD, recovery.get(1)).andExpect(status().isNoContent());

        login(user.email(), PASSWORD, "phone-1").andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void signingInFromANewDeviceSendsAnAlert() throws Exception {
        var user = newUser(UserType.COMMON, "Traveller");

        login(user.email(), PASSWORD, "phone-1").andExpect(status().isOk());
        login(user.email(), PASSWORD, "phone-1").andExpect(status().isOk());
        verify(emailSender, never()).send(eq(user.email()), eq("New sign-in to your PayWallet account"), anyString());

        login(user.email(), PASSWORD, "laptop-2").andExpect(status().isOk());
        verify(emailSender, times(1)).send(eq(user.email()), eq("New sign-in to your PayWallet account"),
                contains("If this was not you"));
    }

    @Test
    void publicAuthEndpointsAreRateLimitedPerAddress() throws ServletException, IOException {
        var filter = new IpRateLimitFilter(redis, 3);
        String ip = "10.9." + (UUID.randomUUID().hashCode() & 0xff) + "." + (UUID.randomUUID().hashCode() & 0xff);
        int[] statuses = new int[4];
        for (int i = 0; i < 4; i++) {
            var request = new MockHttpServletRequest("POST", "/auth/login");
            request.setRemoteAddr(ip);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            statuses[i] = response.getStatus();
        }
        assertThat(statuses).containsExactly(200, 200, 200, 429);

        var other = new MockHttpServletRequest("GET", "/users/1");
        other.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(other, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private ResultActions login(String email, String password, String deviceId) throws Exception {
        return mvc.perform(post("/auth/login").header("X-Device-Id", deviceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions mfaLogin(String mfaToken, String code) throws Exception {
        return mvc.perform(post("/auth/login/mfa").header("X-Device-Id", "phone-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"%s\",\"code\":\"%s\"}".formatted(mfaToken, code)));
    }

    private ResultActions code(UserResponse user, String path, String code) throws Exception {
        return mvc.perform(post(path).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)));
    }

    private ResultActions disable(UserResponse user, String password, String code) throws Exception {
        return mvc.perform(post("/auth/mfa/disable").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\",\"code\":\"%s\"}".formatted(password, code)));
    }
}

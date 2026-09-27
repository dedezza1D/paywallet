package br.com.paywallet.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    @RestController
    static class FailingController {

        @GetMapping("/fail/{kind}")
        void fail(@PathVariable String kind) {
            throw switch (kind) {
                case "timeout" -> new QueryTimeoutException("Redis command timed out");
                default -> new RedisConnectionFailureException("Unable to connect to Redis");
            };
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "refused"})
    void unavailableDataStoreAnswers503(String kind) throws Exception {
        MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/fail/" + kind))
                .andExpect(status().isServiceUnavailable());
    }
}

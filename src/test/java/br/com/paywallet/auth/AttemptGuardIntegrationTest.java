package br.com.paywallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.paywallet.IntegrationTest;

class AttemptGuardIntegrationTest extends IntegrationTest {

    @Autowired AttemptGuard guard;

    @Test
    void parallelWrongGuessesStopAtTheLimit() throws Exception {
        String key = "test:guard:" + UUID.randomUUID();

        List<String> outcomes = inParallel(20, () -> {
            var slot = guard.acquire(key, 5, Duration.ofMinutes(1));
            if (slot == null) {
                return "locked";
            }
            Thread.sleep(50);
            slot.failed();
            return "wrong";
        });

        assertThat(outcomes).filteredOn("wrong"::equals).hasSize(5);
        assertThat(outcomes).filteredOn("locked"::equals).hasSize(15);
    }

    @Test
    void parallelRightGuessesWaitForASlotInsteadOfFailing() throws Exception {
        String key = "test:guard:" + UUID.randomUUID();

        List<String> outcomes = inParallel(20, () -> {
            var slot = guard.acquire(key, 5, Duration.ofMinutes(1));
            Thread.sleep(50);
            slot.succeeded();
            return "ok";
        });

        assertThat(outcomes).containsOnly("ok").hasSize(20);
    }

    static List<String> inParallel(int n, Callable<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            var futures = pool.invokeAll(IntStream.range(0, n).mapToObj(i -> task).toList());
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}

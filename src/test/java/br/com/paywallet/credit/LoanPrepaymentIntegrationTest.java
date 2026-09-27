package br.com.paywallet.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.paywallet.IntegrationTest;
import br.com.paywallet.ledger.LedgerService;
import br.com.paywallet.user.UserDtos.UserResponse;

class LoanPrepaymentIntegrationTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    @Autowired LedgerService ledger;

    @Test
    void payingOffEarlyRemovesTheInterestNotYetDue() throws Exception {
        var borrower = newUserWithBalance("Early Payer", "5000.00");
        String loanId = contract(borrower, "1000", 6);
        BigDecimal before = balanceOf(borrower);

        String quote = mvc.perform(get("/loans/{id}/prepayment", loanId).with(as(borrower)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments.length()").value(6))
                .andExpect(jsonPath("$.lateCharges").value(0.0))
                .andReturn().getResponse().getContentAsString();
        BigDecimal nominal = new BigDecimal(JsonPath.read(quote, "$.nominal").toString());
        BigDecimal discount = new BigDecimal(JsonPath.read(quote, "$.discount").toString());
        BigDecimal total = new BigDecimal(JsonPath.read(quote, "$.total").toString());
        BigDecimal financed = new BigDecimal(JsonPath.read(loan(borrower, loanId), "$.financed").toString());
        assertThat(discount).isPositive();
        assertThat(total).isEqualByComparingTo(nominal.subtract(discount));
        assertThat(total).isGreaterThanOrEqualTo(financed).isLessThan(nominal);

        String key = newKey();
        prepay(borrower, loanId, "", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID_OFF"))
                .andExpect(jsonPath("$.outstandingPrincipal").value(0.0))
                .andExpect(jsonPath("$.schedule[5].status").value("PAID"))
                .andExpect(jsonPath("$.schedule[5].discount").isNumber());
        prepay(borrower, loanId, "", key).andExpect(header().string("Idempotent-Replayed", "true"));
        prepay(borrower, loanId, "", newKey()).andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(borrower)).isEqualByComparingTo(before.subtract(total));
        assertThat(ledger.reconcile().consistent()).isTrue();
    }

    @Test
    void theNextInstallmentsCanBePaidAheadOfTime() throws Exception {
        var borrower = newUserWithBalance("Ahead Payer", "5000.00");
        String loanId = contract(borrower, "1200", 6);

        prepay(borrower, loanId, "{\"installments\": 2}", newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.schedule[0].status").value("PAID"))
                .andExpect(jsonPath("$.schedule[1].status").value("PAID"))
                .andExpect(jsonPath("$.schedule[2].status").value("PENDING"));
        mvc.perform(get("/loans/{id}/prepayment", loanId).with(as(borrower)))
                .andExpect(jsonPath("$.installments[0]").value(3))
                .andExpect(jsonPath("$.installments.length()").value(4));
        prepay(borrower, loanId, "{\"installments\": 5}", newKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Choose between 1 and 4 installments"));
    }

    @Test
    void overdueInstallmentsArePaidWithLateChargesAndNoDiscount() throws Exception {
        var borrower = newUserWithBalance("Late Early Payer", "5000.00");
        String loanId = contract(borrower, "600", 3);
        jdbc.update("UPDATE loan_installments SET due_date = ?, status = 'OVERDUE' WHERE loan_id = ? AND number = 1",
                Date.valueOf(TODAY.minusDays(15)), UUID.fromString(loanId));

        mvc.perform(get("/loans/{id}/prepayment", loanId).param("installments", "1").with(as(borrower)))
                .andExpect(jsonPath("$.discount").value(0.0))
                .andExpect(jsonPath("$.lateCharges").isNumber())
                .andExpect(jsonPath("$.total").value(greaterThan(
                        JsonPath.<Double>read(loan(borrower, loanId), "$.schedule[0].amount"))));
    }

    private String contract(UserResponse borrower, String value, int installments) throws Exception {
        return JsonPath.read(mvc.perform(post("/loans").with(as(borrower)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": %s, \"installments\": %d}".formatted(value, installments)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String loan(UserResponse borrower, String loanId) throws Exception {
        return mvc.perform(get("/loans/{id}", loanId).with(as(borrower))).andReturn().getResponse()
                .getContentAsString();
    }

    private ResultActions prepay(UserResponse borrower, String loanId, String body, String key) throws Exception {
        var request = post("/loans/{id}/prepayment", loanId).with(as(borrower)).header("Idempotency-Key", key);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}

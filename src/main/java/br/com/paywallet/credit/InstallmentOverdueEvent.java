package br.com.paywallet.credit;

import java.time.LocalDate;
import java.util.UUID;

public record InstallmentOverdueEvent(UUID loanId, Long userId, String email, int number, long amountCents,
                                      LocalDate dueDate) {

    public static final String TYPE = "InstallmentOverdue";
}

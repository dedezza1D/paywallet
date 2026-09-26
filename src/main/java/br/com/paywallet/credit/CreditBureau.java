package br.com.paywallet.credit;

/** Credit bureau (Serasa, SPC, Boa Vista). Queries require a contract with the bureau. */
public interface CreditBureau {

    /** @throws br.com.paywallet.exception.ExternalServiceException when the bureau cannot be reached */
    BureauReport report(String document);

    /** @param score 0 to 1000; {@code restricted} means overdue debts are registered against the document */
    record BureauReport(int score, boolean restricted) {
    }
}

package br.com.paywallet.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.ledger.LedgerService.ReconciliationReport;

@RestController
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/ledger/reconciliation")
    public ReconciliationReport reconciliation() {
        return ledger.reconcile();
    }
}

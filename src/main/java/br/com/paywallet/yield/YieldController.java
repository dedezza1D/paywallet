package br.com.paywallet.yield;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.paywallet.yield.YieldDtos.RunResult;
import br.com.paywallet.yield.YieldDtos.YieldSummary;

@RestController
public class YieldController {

    private final YieldService yield;

    public YieldController(YieldService yield) {
        this.yield = yield;
    }

    @GetMapping("/users/{id}/yield")
    public YieldSummary summary(@PathVariable Long id) {
        return yield.summary(id);
    }

    /** Operations: (re)process one business day, e.g. after the CDI source was down. Safe to repeat. */
    @PostMapping("/admin/yield/runs")
    public RunResult run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return yield.runFor(date);
    }
}

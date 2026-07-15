package com.eventledger.account;

import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Component tests for the Account Service against its real H2 database.
 * Covers idempotency, balance math, out-of-order tolerance, and validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private String tx(String eventId, String type, String amount, String ts) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, ts);
    }

    private void apply(String accountId, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void creditThenDebit_computesNetBalance() throws Exception {
        apply("acct-1", tx("e1", "CREDIT", "150.00", "2026-05-15T14:00:00Z"), 201);
        apply("acct-1", tx("e2", "DEBIT", "50.00", "2026-05-15T15:00:00Z"), 201);

        mvc.perform(get("/accounts/{id}/balance", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(is(100.0)));
    }

    @Test
    void duplicateEventId_isIdempotent_balanceUnchanged() throws Exception {
        apply("acct-2", tx("dup", "CREDIT", "100.00", "2026-05-15T14:00:00Z"), 201);

        // Resubmit the same eventId with a different amount → must be a no-op (200, duplicate).
        mvc.perform(post("/accounts/{id}/transactions", "acct-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("dup", "CREDIT", "999.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(get("/accounts/{id}/balance", "acct-2"))
                .andExpect(jsonPath("$.balance").value(is(100.0)));
    }

    @Test
    void outOfOrderArrival_balanceIsCorrect() throws Exception {
        // Apply a later-timestamp event first, then an earlier one. Net must be commutative.
        apply("acct-3", tx("late", "CREDIT", "30.00", "2026-06-01T16:00:00Z"), 201);
        apply("acct-3", tx("early", "CREDIT", "70.00", "2026-06-01T10:00:00Z"), 201);

        mvc.perform(get("/accounts/{id}/balance", "acct-3"))
                .andExpect(jsonPath("$.balance").value(is(100.0)));
    }

    @Test
    void bigDecimalPrecision_noFloatDrift() throws Exception {
        // 0.10 + 0.20 == 0.30 exactly with BigDecimal (double would give 0.30000000000000004).
        apply("acct-prec", tx("p1", "CREDIT", "0.10", "2026-05-15T14:00:00Z"), 201);
        apply("acct-prec", tx("p2", "CREDIT", "0.20", "2026-05-15T15:00:00Z"), 201);

        mvc.perform(get("/accounts/{id}/balance", "acct-prec"))
                .andExpect(jsonPath("$.balance").value(is(0.3)));
    }

    /** Missing required fields, zero/negative amount, unknown type, malformed body → 400. */
    @ParameterizedTest
    @ValueSource(strings = {
            // negative amount
            "{\"eventId\":\"v\",\"type\":\"CREDIT\",\"amount\":-5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // zero amount
            "{\"eventId\":\"v\",\"type\":\"CREDIT\",\"amount\":0,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // unknown type
            "{\"eventId\":\"v\",\"type\":\"REFUND\",\"amount\":5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing eventId
            "{\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing currency
            "{\"eventId\":\"v\",\"type\":\"CREDIT\",\"amount\":5,\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing eventTimestamp
            "{\"eventId\":\"v\",\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\"}",
            // malformed JSON
            "{ not valid json"
    })
    void invalidTransaction_isRejectedWith400(String body) throws Exception {
        mvc.perform(post("/accounts/{id}/transactions", "acct-v")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccount_balance_returns404() throws Exception {
        mvc.perform(get("/accounts/{id}/balance", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}

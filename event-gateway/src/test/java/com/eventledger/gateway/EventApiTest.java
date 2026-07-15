package com.eventledger.gateway;

import com.eventledger.gateway.repository.EventRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core component tests for the Event Gateway. The Account Service is stubbed with
 * WireMock, so these exercise idempotency, validation, and ordered listing without
 * a real downstream service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventApiTest {

    @RegisterExtension
    static WireMockExtension account = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void wireAccountService(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", account::baseUrl);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        account.resetAll();
        stubAccountOk();
    }

    private void stubAccountOk() {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/[^/]+/transactions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"accountId":"acct-1","eventId":"e","applied":true,
                                 "duplicate":false,"balance":100.0000,"currency":"USD"}
                                """)));
    }

    private String event(String id, String acct, String type, String amount, String ts) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,
                 "currency":"USD","eventTimestamp":"%s"}
                """.formatted(id, acct, type, amount, ts);
    }

    @Test
    void submit_happyPath_returns201Applied() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("e1", "a1", "CREDIT", "150.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.eventId").value("e1"));
    }

    @Test
    void duplicateSubmission_returns200_original_notReapplied() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("dup", "a1", "CREDIT", "150.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isCreated());

        // Resubmit same eventId with a different amount → returns the ORIGINAL, 200.
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("dup", "a1", "CREDIT", "999.00", "2026-05-15T14:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(is(150.00)));

        // Downstream apply happened exactly once (the duplicate is not forwarded).
        account.verify(1, postRequestedForTransactions());
    }

    @Test
    void invalidAmount_returns400_andDoesNotCallDownstream() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("bad", "a1", "CREDIT", "-5", "2026-05-15T14:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        account.verify(0, postRequestedForTransactions());
    }

    /** Missing required fields, zero amount, unknown type, malformed body → 400, never forwarded. */
    @ParameterizedTest
    @ValueSource(strings = {
            // zero amount
            "{\"eventId\":\"v\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":0,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // unknown type
            "{\"eventId\":\"v\",\"accountId\":\"a1\",\"type\":\"REFUND\",\"amount\":5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing eventId
            "{\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing accountId
            "{\"eventId\":\"v\",\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:00:00Z\"}",
            // missing eventTimestamp
            "{\"eventId\":\"v\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":5,\"currency\":\"USD\"}",
            // malformed JSON
            "{ not valid json"
    })
    void invalidSubmission_isRejectedWith400_andNotForwarded(String body) throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        account.verify(0, postRequestedForTransactions());
    }

    @Test
    void listing_isOrderedByEventTimestamp_notArrivalOrder() throws Exception {
        // Submit later timestamp first, then earlier.
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(event("late", "ord", "CREDIT", "30.00", "2026-06-01T16:00:00Z")));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content(event("early", "ord", "CREDIT", "70.00", "2026-06-01T10:00:00Z")));

        mvc.perform(get("/events").param("account", "ord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("early"))   // earlier timestamp first
                .andExpect(jsonPath("$[1].eventId").value("late"));
    }

    @Test
    void getUnknownEvent_returns404() throws Exception {
        mvc.perform(get("/events/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    private static com.github.tomakehurst.wiremock.matching.RequestPatternBuilder postRequestedForTransactions() {
        return com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathMatching("/accounts/[^/]+/transactions"));
    }
}

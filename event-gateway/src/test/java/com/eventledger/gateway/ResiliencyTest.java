package com.eventledger.gateway;

import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.repository.EventRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resiliency behavior on the Gateway → Account Service call: failures produce a clean
 * 503 (not 500, no hang), the event is retained as FAILED, and repeated failures open
 * the circuit breaker so further calls fast-fail without hitting the dependency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResiliencyTest {

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
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        account.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private String event(String id) {
        return """
                {"eventId":"%s","accountId":"acct","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T14:00:00Z"}
                """.formatted(id);
    }

    @Test
    void accountServiceError_returns503_andStoresEventAsFailed() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/[^/]+/transactions"))
                .willReturn(aResponse().withStatus(500)));

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("f1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));

        // Graceful degradation: the event is still persisted locally, marked FAILED.
        assertThat(eventRepository.findById("f1")).isPresent()
                .get().extracting("status").isEqualTo(EventStatus.FAILED);

        // ...and the local read still works even though the apply failed.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/events/{id}", "f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void failedCall_retriesAreBounded_notInfinite() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/[^/]+/transactions"))
                .willReturn(aResponse().withStatus(500)));

        // A single submission (breaker stays closed — below the 3-call minimum).
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("bounded")))
                .andExpect(status().isServiceUnavailable());

        // Exactly max-attempts (2 in the test profile) downstream calls — retries are bounded.
        account.verify(2, postRequestedFor(urlPathMatching("/accounts/[^/]+/transactions")));
    }

    @Test
    void slowAccountService_timesOut_returns503() throws Exception {
        // Delay (1500ms) far exceeds the test timeout (400ms) → each attempt times out.
        account.stubFor(WireMock.post(urlPathMatching("/accounts/[^/]+/transactions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("slow")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void repeatedFailures_openCircuitBreaker_thenFastFailWithoutCallingDownstream() throws Exception {
        account.stubFor(WireMock.post(urlPathMatching("/accounts/[^/]+/transactions"))
                .willReturn(aResponse().withStatus(500)));

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("accountService");

        // Fire enough submissions to cross the failure threshold and open the breaker.
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("cb-" + i)))
                    .andExpect(status().isServiceUnavailable());
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN, a further submission fast-fails and does NOT reach the Account Service.
        int callsBefore = account.getServeEvents().getServeEvents().size();
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("cb-open")))
                .andExpect(status().isServiceUnavailable());
        int callsAfter = account.getServeEvents().getServeEvents().size();

        assertThat(callsAfter).isEqualTo(callsBefore);   // no downstream call while open
    }
}

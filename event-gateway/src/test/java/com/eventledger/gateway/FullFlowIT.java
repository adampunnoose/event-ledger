package com.eventledger.gateway;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-flow E2E with BOTH services running as real containers on a shared network
 * (the same topology as {@code docker compose}). This is the only setup that faithfully
 * reproduces trace propagation, since the Gateway must be a real process for its
 * instrumented WebClient to inject the {@code traceparent} header.
 *
 * <p>Verifies: (1) a submitted event is applied on the real Account Service (balance
 * updates across the wire), and (2) the Gateway's traceId appears in the Account
 * Service's logs — proving the trace propagated Gateway → Account.
 *
 * <p>Runs under {@code mvn verify} (failsafe). Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class FullFlowIT {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static GenericContainer<?> accountService = new GenericContainer<>(
            new ImageFromDockerfile().withFileFromPath(".", Path.of("../account-service")))
            .withNetwork(NETWORK)
            .withNetworkAliases("account-service")
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/health").forPort(8081)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    @Container
    static GenericContainer<?> gateway = new GenericContainer<>(
            new ImageFromDockerfile().withFileFromPath(".", Path.of(".")))
            .withNetwork(NETWORK)
            .withEnv("ACCOUNT_SERVICE_BASE_URL", "http://account-service:8081")
            .withExposedPorts(8080)
            .dependsOn(accountService)
            .waitingFor(Wait.forHttp("/health").forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void submitEvent_appliesOnRealAccountService_andPropagatesTrace() throws Exception {
        String body = """
                {"eventId":"e2e-1","accountId":"acct-e2e","type":"CREDIT",
                 "amount":250.00,"currency":"USD","eventTimestamp":"2026-07-01T10:00:00Z"}
                """;

        // 1. Submit through the real Gateway container.
        HttpResponse<String> submit = http.send(
                HttpRequest.newBuilder(URI.create(gatewayUrl("/events")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(submit.statusCode()).isEqualTo(201);
        assertThat(submit.body()).contains("\"status\":\"APPLIED\"");

        // 2. Full flow: the balance actually updated on the Account Service container.
        HttpResponse<String> balance = http.send(
                HttpRequest.newBuilder(URI.create(accountUrl("/accounts/acct-e2e/balance"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(balance.body()).contains("\"accountId\":\"acct-e2e\"").contains("250.0000");

        // 3. Trace propagation: the Gateway's traceId shows up in the Account container's logs.
        String gatewayTraceId = extractTraceId(gateway.getLogs(), "Applied event e2e-1");
        assertThat(gatewayTraceId).as("Gateway should log a traceId for the applied event").isNotBlank();

        assertThat(accountService.getLogs())
                .as("Account Service logs should carry the same traceId (trace propagated over HTTP)")
                .contains(gatewayTraceId);
    }

    private String gatewayUrl(String path) {
        return "http://" + gateway.getHost() + ":" + gateway.getMappedPort(8080) + path;
    }

    private String accountUrl(String path) {
        return "http://" + accountService.getHost() + ":" + accountService.getMappedPort(8081) + path;
    }

    /** Pull the traceId out of the ECS JSON log line that recorded the applied event. */
    private static String extractTraceId(String logs, String marker) {
        Pattern p = Pattern.compile("\"traceId\":\"([a-f0-9]+)\"");
        for (String line : logs.split("\n")) {
            if (line.contains(marker)) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }
}

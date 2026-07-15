package com.eventledger.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Event Gateway — public-facing microservice. Validates events, enforces
 * idempotency, stores the event ledger, and orchestrates the downstream
 * transaction apply on the Account Service via WebClient.
 */
@SpringBootApplication
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}

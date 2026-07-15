package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountClient;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.exception.DownstreamRejectedException;
import com.eventledger.gateway.exception.EventRejectedException;
import com.eventledger.gateway.exception.NotFoundException;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class EventService {

    /** Custom metric: submissions by outcome. Prometheus: gateway_events_submitted_total{result=...}. */
    private static final String METRIC = "gateway.events.submitted";

    private final EventRepository eventRepository;
    private final AccountClient accountClient;
    private final EventMapper eventMapper;
    private final MeterRegistry meterRegistry;

    public EventService(EventRepository eventRepository, AccountClient accountClient,
                        EventMapper eventMapper, MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountClient = accountClient;
        this.eventMapper = eventMapper;
        this.meterRegistry = meterRegistry;
    }

    public SubmitResult submitEvent(SubmitEventRequest request) {
        Optional<Event> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            Event event = existing.get();
            if (event.getStatus() == EventStatus.APPLIED) {
                // True idempotent replay of an already-applied event — no downstream call.
                log.info("Duplicate submission eventId={} — already APPLIED", request.getEventId());
                count("duplicate");
                return new SubmitResult(event, false);
            }
            // RECEIVED/FAILED: a prior apply may have actually succeeded (e.g. timed-out
            // response). Re-attempt to reconcile — safe because the Account Service is
            // idempotent on eventId, so this cannot double-apply.
            log.info("Resubmission of {} event {} — re-attempting apply to reconcile",
                    event.getStatus(), request.getEventId());
            return applyDownstream(event, request, false);
        }

        Event event = eventMapper.toEntity(request, EventStatus.RECEIVED, Instant.now());
        try {
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException race) {
            // Concurrent duplicate: another thread inserted the same eventId first.
            Event winner = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> race);
            count("duplicate");
            return new SubmitResult(winner, false);
        }
        return applyDownstream(event, request, true);
    }

    /**
     * Apply the event on the Account Service (wrapped with Resilience4j in {@link AccountClient}).
     * On success the event becomes APPLIED; on failure it is retained as FAILED (graceful
     * degradation) and a 503 is raised. {@code newlyCreated} drives 201 vs 200 at the controller.
     */
    private SubmitResult applyDownstream(Event event, SubmitEventRequest request, boolean newlyCreated) {
        try {
            accountClient.applyTransaction(request.getAccountId(), eventMapper.toApplyRequest(request));
            event.setStatus(EventStatus.APPLIED);
            eventRepository.save(event);
            log.info("Applied event {} for account {}", event.getEventId(), event.getAccountId());
            count(newlyCreated ? "created" : "duplicate");
            return new SubmitResult(event, newlyCreated);
        } catch (DownstreamRejectedException rejected) {
            // Permanent client error (e.g. currency mismatch) — not a degradation.
            event.setStatus(EventStatus.REJECTED);
            eventRepository.save(event);
            log.warn("Account Service rejected event {} (status {}): {}",
                    event.getEventId(), rejected.getStatus(), rejected.getBody());
            count("rejected");
            throw new EventRejectedException(event, rejected.getStatus(), rejected.getBody());
        } catch (Exception ex) {
            event.setStatus(EventStatus.FAILED);
            eventRepository.save(event);
            log.warn("Account Service apply failed for event {} — stored as FAILED: {}",
                    event.getEventId(), ex.toString());
            count("degraded");
            throw new AccountServiceUnavailableException(event, ex);
        }
    }

    private void count(String result) {
        meterRegistry.counter(METRIC, "result", result).increment();
    }

    public Event getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
    }

    public List<Event> listByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }

    /**
     * Internal result of {@link #submitEvent}. {@code newlyCreated} distinguishes a fresh
     * event (→ 201) from a recognized duplicate (→ 200). Not part of the public API.
     */
    public record SubmitResult(Event event, boolean newlyCreated) {
    }
}

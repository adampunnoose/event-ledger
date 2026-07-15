package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountClient;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.exception.NotFoundException;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.eventledger.gateway.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountClient accountClient;
    private final EventMapper eventMapper;

    public SubmitResult submitEvent(SubmitEventRequest request) {
        // Idempotency: same eventId already seen → return the original, no re-apply.
        Optional<Event> existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate submission eventId={} — returning stored event (status={})",
                    request.getEventId(), existing.get().getStatus());
            return new SubmitResult(existing.get(), false);
        }

        Event event = eventMapper.toEntity(request, EventStatus.RECEIVED, Instant.now());
        try {
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException race) {
            // Concurrent duplicate: another thread inserted the same eventId first.
            Event winner = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> race);
            return new SubmitResult(winner, false);
        }

        // Apply downstream. Phase 6 wraps this call with Resilience4j.
        try {
            accountClient.applyTransaction(request.getAccountId(), eventMapper.toApplyRequest(request));
            event.setStatus(EventStatus.APPLIED);
            eventRepository.save(event);
            log.info("Applied event {} for account {}", event.getEventId(), event.getAccountId());
            return new SubmitResult(event, true);
        } catch (Exception ex) {
            event.setStatus(EventStatus.FAILED);
            eventRepository.save(event);
            log.warn("Account Service apply failed for event {} — stored as FAILED: {}",
                    event.getEventId(), ex.toString());
            throw new AccountServiceUnavailableException(event.getEventId(), ex);
        }
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

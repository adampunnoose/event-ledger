package com.eventledger.gateway.api;

import com.eventledger.gateway.model.EventResponse;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.eventledger.gateway.service.EventMapper;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.EventService.SubmitResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final EventMapper eventMapper;

    /** Submit an event. 201 when newly created + applied, 200 for a duplicate. */
    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody SubmitEventRequest request) {
        SubmitResult result = eventService.submitEvent(request);
        HttpStatus status = result.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(eventMapper.toResponse(result.event()));
    }

    /** Local-only read — works even when the Account Service is down. */
    @GetMapping("/{id}")
    public EventResponse getById(@PathVariable String id) {
        return eventMapper.toResponse(eventService.getEvent(id));
    }

    /** Local-only read, ordered by original event timestamp. */
    @GetMapping
    public List<EventResponse> listByAccount(@RequestParam("account") String account) {
        return eventService.listByAccount(account).stream()
                .map(eventMapper::toResponse)
                .toList();
    }
}

package com.eventledger.gateway.repository;

import com.eventledger.gateway.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, String> {

    /** Events for an account, in chronological order by original event timestamp. */
    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
}

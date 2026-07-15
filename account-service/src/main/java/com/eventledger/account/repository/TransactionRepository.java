package com.eventledger.account.repository;

import com.eventledger.account.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** Idempotency lookup by the Gateway-supplied event id. */
    Optional<Transaction> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    /** Recent transactions for the account-details endpoint (newest first). */
    List<Transaction> findTop50ByAccountIdOrderByEventTimestampDesc(String accountId);
}

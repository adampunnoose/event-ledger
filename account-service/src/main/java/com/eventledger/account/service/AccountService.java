package com.eventledger.account.service;

import com.eventledger.account.entity.Account;
import com.eventledger.account.entity.Transaction;
import com.eventledger.account.entity.TxType;
import com.eventledger.account.exception.NotFoundException;
import com.eventledger.account.model.AccountDetailsResponse;
import com.eventledger.account.model.ApplyTransactionRequest;
import com.eventledger.account.model.ApplyTransactionResponse;
import com.eventledger.account.model.BalanceResponse;
import com.eventledger.account.model.TransactionView;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AccountService {

    private static final int SCALE = 4;

    /** Custom metric. Prometheus: account_transactions_applied_total{outcome=...}. */
    private static final String METRIC = "account.transactions.applied";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Apply a transaction to an account, idempotently on {@code eventId}.
     *
     * <p>If the eventId was already applied, this is a no-op that returns the current
     * balance ({@code duplicate=true}) — that is what makes Gateway retries safe.
     * Otherwise the account is upserted (auto-created on first use) and the balance is
     * updated by a signed amount (CREDIT +, DEBIT −) in a single transaction.
     */
    @Transactional
    public ApplyTransactionResponse applyTransaction(String accountId, ApplyTransactionRequest request) {
        Optional<Transaction> existing = transactionRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            Account account = accountRepository.findById(existing.get().getAccountId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Account missing for existing transaction " + request.getEventId()));
            log.info("Duplicate transaction eventId={} — no-op, balance unchanged", request.getEventId());
            meterRegistry.counter(METRIC, "outcome", "duplicate").increment();
            return toResponse(account, request.getEventId(), true, true);
        }

        Instant now = Instant.now();

        // Upsert the account FIRST so the FK on transactions.account_id is satisfied
        // (accounts are auto-created on first transaction). saveAndFlush pins insert order.
        Account account = accountRepository.findById(accountId).orElseGet(() -> Account.builder()
                .accountId(accountId)
                .balance(BigDecimal.ZERO.setScale(SCALE))
                .currency(request.getCurrency())
                .createdAt(now)
                .updatedAt(now)
                .build());

        TxType type = TxType.valueOf(request.getType());
        BigDecimal signed = type == TxType.CREDIT ? request.getAmount() : request.getAmount().negate();
        account.setBalance(account.getBalance().add(signed));
        account.setUpdatedAt(now);
        accountRepository.saveAndFlush(account);

        Transaction transaction = Transaction.builder()
                .eventId(request.getEventId())
                .accountId(accountId)
                .type(type)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .appliedAt(now)
                .build();
        transactionRepository.save(transaction);

        log.info("Applied {} {} {} to account={} — new balance={}",
                type, request.getAmount(), request.getCurrency(), accountId, account.getBalance());
        meterRegistry.counter(METRIC, "outcome", "applied").increment();
        return toResponse(account, request.getEventId(), true, false);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        Account account = requireAccount(accountId);
        return new BalanceResponse(account.getAccountId(), account.getBalance(), account.getCurrency());
    }

    @Transactional(readOnly = true)
    public AccountDetailsResponse getDetails(String accountId) {
        Account account = requireAccount(accountId);
        List<TransactionView> recent = transactionRepository
                .findTop50ByAccountIdOrderByEventTimestampDesc(accountId)
                .stream()
                .map(t -> new TransactionView(
                        t.getEventId(), t.getType().name(), t.getAmount(),
                        t.getCurrency(), t.getEventTimestamp()))
                .toList();
        return new AccountDetailsResponse(
                account.getAccountId(), account.getBalance(), account.getCurrency(),
                account.getCreatedAt(), account.getUpdatedAt(), recent);
    }

    private Account requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
    }

    private ApplyTransactionResponse toResponse(
            Account account, String eventId, boolean applied, boolean duplicate) {
        return new ApplyTransactionResponse(
                account.getAccountId(), eventId, applied, duplicate,
                account.getBalance(), account.getCurrency());
    }
}

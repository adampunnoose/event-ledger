package com.eventledger.account.service;

import com.eventledger.account.entity.Account;
import com.eventledger.account.entity.Transaction;
import com.eventledger.account.entity.TxType;
import com.eventledger.account.exception.CurrencyMismatchException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Self-reference so the @Transactional apply is invoked through the proxy (not self-invocation). */
    @Autowired
    @Lazy
    private AccountService self;

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
     * <p>Idempotency has two layers: a fast-path lookup for the common already-applied
     * case, and a race-safe fallback — if two concurrent retries both pass the lookup,
     * the unique {@code event_id} constraint lets exactly one win; the loser's
     * {@link DataIntegrityViolationException} is caught (after its transaction rolls back)
     * and reconciled to {@code duplicate=true} rather than surfacing as a 500.
     */
    public ApplyTransactionResponse applyTransaction(String accountId, ApplyTransactionRequest request) {
        Optional<Transaction> existing = transactionRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            return duplicateResponse(existing.get());
        }
        try {
            return self.applyNewTransaction(accountId, request);
        } catch (DataIntegrityViolationException race) {
            // Lost the unique-constraint race — the apply committed on another thread.
            Transaction winner = transactionRepository.findByEventId(request.getEventId())
                    .orElseThrow(() -> race);
            log.info("Concurrent duplicate eventId={} — reconciled to duplicate", request.getEventId());
            return duplicateResponse(winner);
        }
    }

    /**
     * Applies a genuinely new transaction in one unit of work. If the {@code event_id}
     * insert violates the unique constraint, the whole transaction (including the balance
     * update) rolls back — so a losing concurrent retry never double-applies.
     */
    @Transactional
    public ApplyTransactionResponse applyNewTransaction(String accountId, ApplyTransactionRequest request) {
        Instant now = Instant.now();

        // Upsert the account FIRST so the FK on transactions.account_id is satisfied.
        Account account = accountRepository.findById(accountId)
                .map(existingAccount -> {
                    requireSameCurrency(existingAccount, request);
                    return existingAccount;
                })
                .orElseGet(() -> Account.builder()
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
        // saveAndFlush forces the INSERT now: a duplicate eventId throws here and rolls back
        // the balance change above, rather than failing silently at commit.
        transactionRepository.saveAndFlush(transaction);

        log.info("Applied {} {} {} to account={} — new balance={}",
                type, request.getAmount(), request.getCurrency(), accountId, account.getBalance());
        meterRegistry.counter(METRIC, "outcome", "applied").increment();
        return toResponse(account, request.getEventId(), true, false);
    }

    /** Reject a transaction whose currency differs from the account's (single-currency-per-account). */
    private void requireSameCurrency(Account account, ApplyTransactionRequest request) {
        if (account.getCurrency() != null && !account.getCurrency().equals(request.getCurrency())) {
            throw new CurrencyMismatchException(
                    "Account " + account.getAccountId() + " is " + account.getCurrency()
                            + "; cannot apply a " + request.getCurrency() + " transaction");
        }
    }

    private ApplyTransactionResponse duplicateResponse(Transaction transaction) {
        Account account = accountRepository.findById(transaction.getAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Account missing for existing transaction " + transaction.getEventId()));
        log.info("Duplicate transaction eventId={} — no-op, balance unchanged", transaction.getEventId());
        meterRegistry.counter(METRIC, "outcome", "duplicate").increment();
        return toResponse(account, transaction.getEventId(), true, true);
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

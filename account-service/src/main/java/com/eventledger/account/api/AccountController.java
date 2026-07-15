package com.eventledger.account.api;

import com.eventledger.account.model.AccountDetailsResponse;
import com.eventledger.account.model.ApplyTransactionRequest;
import com.eventledger.account.model.ApplyTransactionResponse;
import com.eventledger.account.model.BalanceResponse;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts/{accountId}")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /** Apply a transaction. 201 when newly applied, 200 when a recognized duplicate. */
    @PostMapping("/transactions")
    public ResponseEntity<ApplyTransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody ApplyTransactionRequest request) {
        ApplyTransactionResponse response = accountService.applyTransaction(accountId, request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return accountService.getBalance(accountId);
    }

    @GetMapping
    public AccountDetailsResponse getDetails(@PathVariable String accountId) {
        return accountService.getDetails(accountId);
    }
}

package com.bankx.service;

import com.bankx.domain.Account;
import com.bankx.domain.Transaction;
import com.bankx.dto.TransactionRequest;
import com.bankx.repository.AccountRepository;
import com.bankx.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private RiskService riskService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(accountRepository, transactionRepository, riskService);
    }

    @Test
    void testProcessTransaction_Success_Debit() {
        // Arrange
        String accountNumber = "001-0001";
        BigDecimal initialBalance = BigDecimal.valueOf(1000);
        BigDecimal debitAmount = BigDecimal.valueOf(100);

        Account account = Account.builder()
            .id("acc-1")
            .accountNumber(accountNumber)
            .holderName("John Doe")
            .balance(initialBalance)
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .build();

        TransactionRequest request = TransactionRequest.builder()
            .accountNumber(accountNumber)
            .type("DEBIT")
            .amount(debitAmount)
            .build();

        RiskService.RiskDecision riskOk = RiskService.RiskDecision.builder()
            .decision("OK")
            .fallback(false)
            .build();

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(account));
        
        when(riskService.evaluateRisk(any(), any(), any(), any()))
            .thenReturn(Mono.just(riskOk));
        
        when(accountRepository.save(any()))
            .thenReturn(Mono.just(account.toBuilder()
                .balance(initialBalance.subtract(debitAmount))
                .build()));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectNextMatches(response -> 
                response.getAccountNumber().equals(accountNumber) &&
                response.getStatus().equals("OK") &&
                response.getType().equals("DEBIT")
            )
            .verifyComplete();
    }

    @Test
    void testProcessTransaction_InsufficientFunds() {
        // Arrange
        String accountNumber = "001-0001";
        BigDecimal lowBalance = BigDecimal.valueOf(50);
        BigDecimal debitAmount = BigDecimal.valueOf(100);

        Account account = Account.builder()
            .id("acc-1")
            .accountNumber(accountNumber)
            .holderName("John Doe")
            .balance(lowBalance)
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .build();

        TransactionRequest request = TransactionRequest.builder()
            .accountNumber(accountNumber)
            .type("DEBIT")
            .amount(debitAmount)
            .build();

        RiskService.RiskDecision riskOk = RiskService.RiskDecision.builder()
            .decision("OK")
            .fallback(false)
            .build();

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(account));
        
        when(riskService.evaluateRisk(any(), any(), any(), any()))
            .thenReturn(Mono.just(riskOk));

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectErrorMatches(error -> 
                error.getMessage().contains("insufficient_funds")
            )
            .verify();
    }

    @Test
    void testProcessTransaction_AccountNotFound() {
        // Arrange
        String accountNumber = "999-9999";
        BigDecimal amount = BigDecimal.valueOf(100);

        TransactionRequest request = TransactionRequest.builder()
            .accountNumber(accountNumber)
            .type("DEBIT")
            .amount(amount)
            .build();

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectErrorMatches(error -> 
                error.getMessage().contains("account_not_found")
            )
            .verify();
    }

    @Test
    void testProcessTransaction_Success_Credit() {
        // Arrange
        String accountNumber = "001-0001";
        Account account = activeAccount(accountNumber, BigDecimal.valueOf(1000));
        TransactionRequest request = request(accountNumber, "CREDIT", BigDecimal.valueOf(250));

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(account));
        when(riskService.evaluateRisk(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskService.RiskDecision.builder().decision("OK").fallback(true).build()));
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectNextMatches(response ->
                response.getType().equals("CREDIT") &&
                response.getNewBalance().compareTo(BigDecimal.valueOf(1250)) == 0 &&
                response.isUsedFallback() &&
                response.getCorrelationId().equals("test-corr-id")
            )
            .verifyComplete();
    }

    @Test
    void testProcessTransaction_RiskRejected() {
        // Arrange
        String accountNumber = "001-0001";
        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(activeAccount(accountNumber, BigDecimal.valueOf(1000))));
        when(riskService.evaluateRisk(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskService.RiskDecision.builder()
                .decision("REJECTED").reason("Amount exceeds legacy limit").build()));

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request(accountNumber, "DEBIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectErrorMatches(error -> error.getMessage().equals("risk_rejected"))
            .verify();
    }

    @Test
    void testProcessTransaction_RiskServiceErrorIsTreatedAsRejection() {
        // Arrange
        String accountNumber = "001-0001";
        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(activeAccount(accountNumber, BigDecimal.valueOf(1000))));
        when(riskService.evaluateRisk(any(), any(), any(), any()))
            .thenReturn(Mono.error(new RuntimeException("Legacy service unavailable")));

        // Act & Assert
        StepVerifier.create(transactionService.processTransaction(request(accountNumber, "DEBIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectErrorMatches(error -> error.getMessage().equals("risk_rejected"))
            .verify();
    }

    @Test
    void testGetTransactionsByAccount() {
        Transaction transaction = Transaction.builder().id("tx-1").accountNumber("001-0001").build();
        when(transactionRepository.findByAccountNumber("001-0001"))
            .thenReturn(Flux.just(transaction));

        StepVerifier.create(transactionService.getTransactionsByAccount("001-0001"))
            .expectNext(transaction)
            .verifyComplete();
    }

    @Test
    void testGetTransactionsByAccount_Error() {
        when(transactionRepository.findByAccountNumber("001-0001"))
            .thenReturn(Flux.error(new RuntimeException("mongo down")));

        StepVerifier.create(transactionService.getTransactionsByAccount("001-0001"))
            .expectErrorMessage("mongo down")
            .verify();
    }

    private Account activeAccount(String accountNumber, BigDecimal balance) {
        return Account.builder()
            .id("acc-1")
            .accountNumber(accountNumber)
            .holderName("John Doe")
            .balance(balance)
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .build();
    }

    private TransactionRequest request(String accountNumber, String type, BigDecimal amount) {
        return TransactionRequest.builder()
            .accountNumber(accountNumber)
            .type(type)
            .amount(amount)
            .build();
    }
}

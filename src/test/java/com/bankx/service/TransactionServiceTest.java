package com.bankx.service;

import com.bankx.domain.Account;
import com.bankx.domain.Transaction;
import com.bankx.dto.RiskDecision;
import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.exception.AccountNotFoundException;
import com.bankx.exception.InsufficientFundsException;
import com.bankx.exception.RiskRejectedException;
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
import java.time.Duration;
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

    private TransactionEventPublisher eventPublisher;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        eventPublisher = new TransactionEventPublisher();
        transactionService = new TransactionService(accountRepository, transactionRepository, riskService,
            eventPublisher);
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

        RiskDecision riskOk = RiskDecision.builder()
            .decision("OK")
            .fallback(false)
            .build();

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(account));
        
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(riskOk));
        
        when(accountRepository.save(any()))
            .thenReturn(Mono.just(account.toBuilder()
                .balance(initialBalance.subtract(debitAmount))
                .build()));
        
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(transactionService.procesarTransaccion(request)
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

        RiskDecision riskOk = RiskDecision.builder()
            .decision("OK")
            .fallback(false)
            .build();

        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(account));
        
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(riskOk));

        // Act & Assert
        StepVerifier.create(transactionService.procesarTransaccion(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectError(InsufficientFundsException.class)
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
        StepVerifier.create(transactionService.procesarTransaccion(request)
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectError(AccountNotFoundException.class)
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
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskDecision.builder().decision("OK").fallback(true).build()));
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(transactionService.procesarTransaccion(request)
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
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskDecision.builder()
                .decision("REJECTED").reason("Amount exceeds legacy limit").build()));

        // Act & Assert
        StepVerifier.create(transactionService.procesarTransaccion(request(accountNumber, "DEBIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectError(RiskRejectedException.class)
            .verify();
    }

    @Test
    void testProcessTransaction_RiskServiceErrorIsTreatedAsRejection() {
        // Arrange
        String accountNumber = "001-0001";
        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(activeAccount(accountNumber, BigDecimal.valueOf(1000))));
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.error(new RuntimeException("Legacy service unavailable")));

        // Act & Assert
        StepVerifier.create(transactionService.procesarTransaccion(request(accountNumber, "DEBIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "test-corr-id")))
            .expectError(RiskRejectedException.class)
            .verify();
    }

    @Test
    void testSuccessfulTransactionIsPublishedToLiveStream() {
        String accountNumber = "001-0001";
        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(activeAccount(accountNumber, BigDecimal.valueOf(1000))));
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskDecision.builder().decision("OK").build()));
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(eventPublisher.eventos())
            .then(() -> transactionService.procesarTransaccion(request(accountNumber, "CREDIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "live-corr"))
                .subscribe())
            .expectNextMatches(event -> "CREDIT".equals(event.getType()) && "live-corr".equals(event.getCorrelationId()))
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void testRejectedTransactionIsNotPublished() {
        String accountNumber = "001-0001";
        when(accountRepository.findByAccountNumber(accountNumber))
            .thenReturn(Mono.just(activeAccount(accountNumber, BigDecimal.valueOf(1000))));
        when(riskService.evaluarRiesgo(any(), any(), any(), any()))
            .thenReturn(Mono.just(RiskDecision.builder().decision("REJECTED").build()));

        StepVerifier.create(eventPublisher.eventos())
            .then(() -> transactionService.procesarTransaccion(request(accountNumber, "DEBIT", BigDecimal.TEN))
                .contextWrite(Context.of("correlationId", "rejected-corr"))
                .onErrorResume(error -> Mono.empty())
                .subscribe())
            .expectNoEvent(Duration.ofMillis(200))
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamWithAccountEmitsHistoryThenOnlyLiveEventsOfThatAccount() {
        Transaction historic = Transaction.builder().id("tx-old").accountNumber("001-0001")
            .type("DEBIT").amount(BigDecimal.ONE).status("OK").correlationId("old-corr").build();
        when(transactionRepository.findByAccountNumber("001-0001"))
            .thenReturn(Flux.just(historic));

        StepVerifier.create(transactionService.transmitirTransacciones("001-0001"))
            .expectNextMatches(event -> "tx-old".equals(event.getTransactionId())
                && "old-corr".equals(event.getCorrelationId()))
            .then(() -> {
                eventPublisher.publicar(TransactionResponse.builder().transactionId("tx-other").accountNumber("002-0002").build());
                eventPublisher.publicar(TransactionResponse.builder().transactionId("tx-new").accountNumber("001-0001").build());
            })
            .expectNextMatches(event -> "tx-new".equals(event.getTransactionId()))
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamWithoutAccountEmitsAllLiveEvents() {
        StepVerifier.create(transactionService.transmitirTransacciones(null))
            .then(() -> {
                eventPublisher.publicar(TransactionResponse.builder().transactionId("tx-a").accountNumber("001-0001").build());
                eventPublisher.publicar(TransactionResponse.builder().transactionId("tx-b").accountNumber("002-0002").build());
            })
            .expectNextMatches(event -> "tx-a".equals(event.getTransactionId()))
            .expectNextMatches(event -> "tx-b".equals(event.getTransactionId()))
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamPropagatesHistoryError() {
        when(transactionRepository.findByAccountNumber("001-0001"))
            .thenReturn(Flux.error(new RuntimeException("mongo down")));

        StepVerifier.create(transactionService.transmitirTransacciones("001-0001"))
            .expectErrorMessage("mongo down")
            .verify(Duration.ofSeconds(5));
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

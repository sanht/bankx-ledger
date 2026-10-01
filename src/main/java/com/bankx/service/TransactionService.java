package com.bankx.service;

import com.bankx.domain.Account;
import com.bankx.domain.Transaction;
import com.bankx.dto.ErrorResponse;
import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.exception.BusinessException;
import com.bankx.repository.AccountRepository;
import com.bankx.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RiskService riskService;

    public TransactionService(AccountRepository accountRepository,
                            TransactionRepository transactionRepository,
                            RiskService riskService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.riskService = riskService;
    }

    /**
     * Procesa una transacción: valida, evalúa riesgo, actualiza saldo.
     * Retorna Mono<TransactionResponse> o error.
     */
    public Mono<TransactionResponse> processTransaction(TransactionRequest request) {
        
        return Mono.deferContextual(ctx -> {
            String correlationId = ctx.get("correlationId");
            String transactionId = UUID.randomUUID().toString();
            
            log.info("[{}] Processing {} transaction for account {} amount {}", 
                correlationId, request.getType(), request.getAccountNumber(), request.getAmount());
            
            return validateAccount(request.getAccountNumber(), correlationId)
                .flatMap(account -> evaluateRisk(account, request, correlationId))
                .flatMap(riskDecision -> processWithRisk(request, riskDecision, correlationId, transactionId))
                .doOnSuccess(response -> {
                    log.info("[{}] Transaction {} completed successfully", correlationId, transactionId);
                })
                .doOnError(error -> {
                    log.error("[{}] Transaction processing failed: {}", correlationId, error.getMessage());
                });
        });
    }

    /**
     * Valida que la cuenta exista.
     */
    private Mono<Account> validateAccount(String accountNumber, String correlationId) {
        return accountRepository.findByAccountNumber(accountNumber)
            .switchIfEmpty(Mono.error(
                new RuntimeException("account_not_found")
            ))
            .doOnError(error -> {
                log.warn("[{}] Account validation failed: {}", correlationId, error.getMessage());
            });
    }

    /**
     * Evalúa riesgo a través del servicio remoto.
     * Propaga correlationId en el contexto.
     */
    private Mono<RiskService.RiskDecision> evaluateRisk(Account account, 
                                                         TransactionRequest request,
                                                         String correlationId) {
        return riskService.evaluateRisk(
            account.getAccountNumber(),
            request.getAmount(),
            correlationId,
            request.getSimulate()
        )
        .onErrorResume(error -> {
            log.warn("[{}] Risk evaluation error, will be handled in processing", correlationId);
            return Mono.just(RiskService.RiskDecision.builder()
                .decision("ERROR")
                .reason(error.getMessage())
                .build());
        });
    }

    /**
     * Procesa la transacción si el riesgo es aceptable.
     */
    private Mono<TransactionResponse> processWithRisk(TransactionRequest request,
                                                      RiskService.RiskDecision riskDecision,
                                                      String correlationId,
                                                      String transactionId) {
        
        // Verificar decisión de riesgo
        if (!"OK".equals(riskDecision.getDecision())) {
            log.warn("[{}] Risk rejected: {}", correlationId, riskDecision.getReason());
            return Mono.error(new RuntimeException("risk_rejected"));
        }

        // Obtener cuenta y validar fondos
        return accountRepository.findByAccountNumber(request.getAccountNumber())
            .flatMap(account -> {
                
                // Validar fondos para DEBIT
                if ("DEBIT".equals(request.getType()) && 
                    account.getBalance().compareTo(request.getAmount()) < 0) {
                    
                    log.warn("[{}] Insufficient funds. Balance: {}, Requested: {}", 
                        correlationId, account.getBalance(), request.getAmount());
                    
                    return Mono.error(new RuntimeException("insufficient_funds"));
                }

                // Actualizar saldo
                if ("DEBIT".equals(request.getType())) {
                    account.debit(request.getAmount());
                } else {
                    account.credit(request.getAmount());
                }

                // Guardar cuenta actualizada
                return accountRepository.save(account)
                    .flatMap(updatedAccount -> {
                        
                        // Crear y guardar transacción
                        Transaction transaction = Transaction.builder()
                            .id(transactionId)
                            .accountNumber(request.getAccountNumber())
                            .type(request.getType())
                            .amount(request.getAmount())
                            .status("OK")
                            .correlationId(correlationId)
                            .createdAt(LocalDateTime.now())
                            .riskDecision(riskDecision.getDecision())
                            .usedFallback(riskDecision.isFallback())
                            .build();
                        
                        return transactionRepository.save(transaction)
                            .map(saved -> toResponse(saved, updatedAccount));
                    });
            });
    }

    /**
     * Convierte Transaction + Account actualizada a TransactionResponse.
     */
    private TransactionResponse toResponse(Transaction transaction, Account account) {
        return TransactionResponse.builder()
            .transactionId(transaction.getId())
            .accountNumber(transaction.getAccountNumber())
            .type(transaction.getType())
            .amount(transaction.getAmount())
            .status(transaction.getStatus())
            .newBalance(account.getBalance())
            .createdAt(transaction.getCreatedAt())
            .correlationId(transaction.getCorrelationId())
            .usedFallback(transaction.isUsedFallback())
            .build();
    }

    /**
     * Retorna transacciones para una cuenta.
     */
    public reactor.core.publisher.Flux<Transaction> getTransactionsByAccount(String accountNumber) {
        return transactionRepository.findByAccountNumber(accountNumber)
            .doOnError(error -> {
                log.error("Error fetching transactions for account {}: {}", accountNumber, error.getMessage());
            });
    }
}

package com.bankx.controller;

import com.bankx.dto.ErrorResponse;
import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.service.TransactionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * POST /api/transactions
     * Crea una transacción.
     */
    @PostMapping(value = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> createTransaction(
        @Valid @RequestBody TransactionRequest request,
        ServerWebExchange exchange) {
        
        // Extraer o generar X-Correlation-Id
        String correlationId = exchange.getRequest()
            .getHeaders()
            .getFirst("X-Correlation-Id");
        
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        final String corrId = correlationId;
        MDC.put("correlationId", corrId);
        
        return transactionService.processTransaction(request)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .onErrorResume(error -> handleError(error, corrId))
            .contextWrite(Context.of("correlationId", corrId))
            .doFinally(signal -> MDC.remove("correlationId"));
    }

    /**
     * GET /api/stream/transactions
     * Server-Sent Events: stream de transacciones.
     */
    @GetMapping(value = "/stream/transactions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TransactionResponse> streamTransactions(
        @RequestParam(required = false) String accountNumber,
        ServerWebExchange exchange) {
        
        String correlationId = exchange.getRequest()
            .getHeaders()
            .getFirst("X-Correlation-Id");
        
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        final String corrId = correlationId;
        log.info("[{}] Streaming transactions for account: {}", corrId, accountNumber);
        
        if (accountNumber != null && !accountNumber.isEmpty()) {
            return transactionService.getTransactionsByAccount(accountNumber)
                .map(transaction -> TransactionResponse.builder()
                    .transactionId(transaction.getId())
                    .accountNumber(transaction.getAccountNumber())
                    .type(transaction.getType())
                    .amount(transaction.getAmount())
                    .status(transaction.getStatus())
                    .correlationId(corrId)
                    .build())
                .doOnError(error -> {
                    log.error("[{}] Stream error: {}", corrId, error.getMessage());
                });
        }
        
        return Flux.empty();
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("{\"status\":\"UP\"}"));
    }

    /**
     * Maneja errores de negocio.
     */
    private Mono<ResponseEntity<?>> handleError(Throwable error, String correlationId) {
        String errorCode = "internal_error";
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = error.getMessage();

        log.error("[{}] Transaction error: {}", correlationId, message);

        if ("account_not_found".equals(message)) {
            errorCode = "account_not_found";
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            message = "Account not found";
        } else if ("insufficient_funds".equals(message)) {
            errorCode = "insufficient_funds";
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            message = "Insufficient funds";
        } else if ("risk_rejected".equals(message)) {
            errorCode = "risk_rejected";
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            message = "Risk service rejected the transaction";
        } else if (message != null && message.contains("validation")) {
            errorCode = "validation_error";
            status = HttpStatus.BAD_REQUEST;
        }

        ErrorResponse response = ErrorResponse.of(errorCode, message, correlationId);
        return Mono.just(ResponseEntity.status(status).body(response));
    }
}

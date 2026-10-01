package com.bankx.controller;

import com.bankx.dto.ErrorResponse;
import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.exception.BusinessException;
import com.bankx.observability.CorrelationId;
import com.bankx.service.TransactionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/api")
public class TransactionController {

    private static final String SSE_EVENT = "transaction";
    private static final String SSE_HEARTBEAT = "heartbeat";

    private final TransactionService transactionService;
    private final Duration heartbeatInterval;

    public TransactionController(TransactionService transactionService,
                                 @Value("${bankx.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.transactionService = transactionService;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * POST /api/transactions
     * Crea una transacción.
     */
    @PostMapping(value = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> crearTransaccion(
        @Valid @RequestBody TransactionRequest request,
        ServerWebExchange exchange) {

        // CorrelationIdFilter ya dejó el correlationId en el exchange y en el contexto de Reactor
        String correlationId = CorrelationId.from(exchange);

        return transactionService.procesarTransaccion(request)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .onErrorResume(error -> manejarError(error, correlationId));
    }

    /**
     * GET /api/stream/transactions
     * Server-Sent Events: historial de la cuenta (opcional) y transacciones en vivo.
     *
     * Intercala un comentario ":heartbeat" cada heartbeatInterval para que ingress/APIM no corten
     * la conexión por inactividad y para detectar clientes caídos al fallar la escritura.
     * El heartbeat se detiene cuando el stream de transacciones termina o falla.
     */
    @GetMapping(value = "/stream/transactions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TransactionResponse>> transmitirTransacciones(
        @RequestParam(required = false) String accountNumber) {

        log.info("Streaming transactions for account: {}", accountNumber);

        Flux<ServerSentEvent<TransactionResponse>> eventos = transactionService.transmitirTransacciones(accountNumber)
            .map(transaction -> ServerSentEvent.builder(transaction)
                .id(transaction.getTransactionId())
                .event(SSE_EVENT)
                .build());

        return eventos
            .publish(compartido -> Flux.merge(compartido, heartbeat().takeUntilOther(compartido.then())))
            .doOnCancel(() -> log.info("Stream client disconnected"))
            .doOnError(error -> log.error("Stream error: {}", error.getMessage()));
    }

    private Flux<ServerSentEvent<TransactionResponse>> heartbeat() {
        return Flux.interval(heartbeatInterval)
            .map(tick -> ServerSentEvent.<TransactionResponse>builder().comment(SSE_HEARTBEAT).build());
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<String>> salud() {
        return Mono.just(ResponseEntity.ok("{\"status\":\"UP\"}"));
    }

    /**
     * Maneja errores de negocio.
     */
    private Mono<ResponseEntity<?>> manejarError(Throwable error, String correlationId) {
        String errorCode = "internal_error";
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = error.getMessage();

        log.error("[{}] Transaction error: {}", correlationId, message);

        if (error instanceof BusinessException businessError) {
            errorCode = businessError.getErrorCode();
            status = businessError.getStatus();
        }

        ErrorResponse response = ErrorResponse.of(errorCode, message, correlationId);
        return Mono.just(ResponseEntity.status(status).body(response));
    }
}

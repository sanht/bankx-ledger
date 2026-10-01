package com.bankx.controller;

import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.exception.AccountNotFoundException;
import com.bankx.exception.InsufficientFundsException;
import com.bankx.exception.RiskRejectedException;
import com.bankx.exception.ValidationException;
import com.bankx.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = TransactionController.class,
    properties = "bankx.sse.heartbeat-interval=100ms")
class TransactionControllerTest {

    private static final ParameterizedTypeReference<ServerSentEvent<TransactionResponse>> SSE_TYPE =
        new ParameterizedTypeReference<>() { };

    private static final Map<String, Object> VALID_REQUEST = Map.of(
        "accountNumber", "001-0001",
        "type", "DEBIT",
        "amount", 100);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private TransactionService transactionService;

    private WebTestClient.ResponseSpec postTransaction(Object body, String correlationId) {
        WebTestClient.RequestBodySpec request = webTestClient.post()
            .uri("/api/transactions")
            .contentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return request.bodyValue(body).exchange();
    }

    @Test
    void createTransactionReturnsOkWithResponse() {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.just(TransactionResponse.builder()
                .transactionId("tx-1")
                .accountNumber("001-0001")
                .type("DEBIT")
                .amount(BigDecimal.valueOf(100))
                .status("OK")
                .newBalance(BigDecimal.valueOf(900))
                .correlationId("corr-1")
                .build()));

        postTransaction(VALID_REQUEST, "corr-1")
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.transactionId").isEqualTo("tx-1")
            .jsonPath("$.status").isEqualTo("OK")
            .jsonPath("$.newBalance").isEqualTo(900);
    }

    @Test
    void createTransactionPropagatesCorrelationIdToReactorContext() {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.deferContextual(ctx -> Mono.just(TransactionResponse.builder()
                .correlationId(ctx.get("correlationId"))
                .build())));

        postTransaction(VALID_REQUEST, "corr-from-header")
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.correlationId").isEqualTo("corr-from-header");
    }

    @Test
    void createTransactionGeneratesCorrelationIdWhenHeaderIsMissing() {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new AccountNotFoundException()));

        postTransaction(VALID_REQUEST, null)
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void accountNotFoundMapsTo422() {
        assertBusinessError(new AccountNotFoundException(), 422, "account_not_found", "Account not found");
    }

    @Test
    void insufficientFundsMapsTo422() {
        assertBusinessError(new InsufficientFundsException(), 422, "insufficient_funds", "Insufficient funds");
    }

    @Test
    void riskRejectedMapsTo422() {
        assertBusinessError(new RiskRejectedException(), 422, "risk_rejected", "Risk service rejected the transaction");
    }

    @Test
    void validationErrorMapsTo400() {
        assertBusinessError(new ValidationException("validation failed: amount"), 400, "validation_error", "validation failed: amount");
    }

    @Test
    void messageMentioningBusinessCodeIsNotTreatedAsBusinessError() {
        assertBusinessError(new RuntimeException("insufficient_funds"), 500, "internal_error", "insufficient_funds");
    }

    @Test
    void unexpectedErrorMapsTo500() {
        assertBusinessError(new RuntimeException("boom"), 500, "internal_error", "boom");
    }

    @Test
    void errorWithoutMessageMapsTo500() {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new RuntimeException()));

        postTransaction(VALID_REQUEST, "corr-null")
            .expectStatus().isEqualTo(500)
            .expectBody()
            .jsonPath("$.error").isEqualTo("internal_error");
    }

    @Test
    void invalidRequestIsRejectedBeforeReachingTheService() {
        postTransaction(Map.of("accountNumber", "", "type", "DEBIT", "amount", -5), "corr-invalid")
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").isEqualTo("validation_error")
            .jsonPath("$.correlationId").isEqualTo("corr-invalid");

        verify(transactionService, never()).procesarTransaccion(any());
    }

    @Test
    void unknownTransactionTypeIsRejectedAsValidationError() {
        postTransaction(Map.of("accountNumber", "001-0001", "type", "TRANSFER", "amount", 10), "corr-type")
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").isEqualTo("validation_error")
            .jsonPath("$.message").value(message -> org.assertj.core.api.Assertions.assertThat((String) message).contains("type"));

        verify(transactionService, never()).procesarTransaccion(any());
    }

    @Test
    void malformedJsonIsRejectedAsValidationError() {
        webTestClient.post()
            .uri("/api/transactions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{ not json")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").isEqualTo("validation_error")
            .jsonPath("$.correlationId").isNotEmpty();

        verify(transactionService, never()).procesarTransaccion(any());
    }

    @Test
    void streamTransactionsEmitsNamedSseEvents() {
        when(transactionService.transmitirTransacciones("001-0001"))
            .thenReturn(Flux.just(
                TransactionResponse.builder().transactionId("tx-1").accountNumber("001-0001")
                    .type("DEBIT").amount(BigDecimal.TEN).status("OK").build(),
                TransactionResponse.builder().transactionId("tx-2").accountNumber("001-0001")
                    .type("CREDIT").amount(BigDecimal.ONE).status("OK").build()));

        Flux<ServerSentEvent<TransactionResponse>> body = webTestClient.get()
            .uri("/api/stream/transactions?accountNumber=001-0001")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(SSE_TYPE)
            .getResponseBody();

        StepVerifier.create(body)
            .expectNextMatches(event -> "transaction".equals(event.event())
                && "tx-1".equals(event.id())
                && "DEBIT".equals(event.data().getType()))
            .expectNextMatches(event -> "tx-2".equals(event.data().getTransactionId()))
            .verifyComplete();
    }

    @Test
    void streamSendsHeartbeatCommentsWhileThereAreNoTransactions() {
        when(transactionService.transmitirTransacciones(null)).thenReturn(Flux.never());

        Flux<ServerSentEvent<TransactionResponse>> body = webTestClient.get()
            .uri("/api/stream/transactions")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(SSE_TYPE)
            .getResponseBody();

        StepVerifier.create(body)
            .expectNextMatches(event -> "heartbeat".equals(event.comment()) && event.data() == null)
            .expectNextMatches(event -> "heartbeat".equals(event.comment()))
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void streamClosesWhenTransactionStreamFailsAfterHeartbeats() {
        when(transactionService.transmitirTransacciones("001-0001"))
            .thenReturn(Flux.<TransactionResponse>error(new RuntimeException("mongo down"))
                .delaySubscription(Duration.ofMillis(350)));

        Flux<ServerSentEvent<TransactionResponse>> body = webTestClient.get()
            .uri("/api/stream/transactions?accountNumber=001-0001")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(SSE_TYPE)
            .getResponseBody()
            .onErrorResume(error -> Flux.empty());

        // Un error con la respuesta ya comprometida por heartbeats debe cerrar la conexión
        StepVerifier.create(body)
            .expectNextMatches(event -> "heartbeat".equals(event.comment()))
            .thenConsumeWhile(event -> "heartbeat".equals(event.comment()))
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void streamTransactionsWithoutAccountDelegatesWithNull() {
        when(transactionService.transmitirTransacciones(null)).thenReturn(Flux.empty());

        Flux<ServerSentEvent<TransactionResponse>> body = webTestClient.get()
            .uri("/api/stream/transactions")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(SSE_TYPE)
            .getResponseBody();

        StepVerifier.create(body).verifyComplete();
        verify(transactionService).transmitirTransacciones(null);
    }

    @Test
    void correlationIdHeaderIsEchoedInResponse() {
        webTestClient.get()
            .uri("/api/health")
            .header("X-Correlation-Id", "corr-echo")
            .exchange()
            .expectHeader().valueEquals("X-Correlation-Id", "corr-echo");
    }

    @Test
    void correlationIdIsGeneratedAndReturnedWhenMissing() {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new AccountNotFoundException()));

        WebTestClient.BodyContentSpec body = postTransaction(VALID_REQUEST, null)
            .expectHeader().exists("X-Correlation-Id")
            .expectBody();
        String header = body.returnResult().getResponseHeaders().getFirst("X-Correlation-Id");
        body.jsonPath("$.correlationId").isEqualTo(header);
    }

    @Test
    void healthReturnsUp() {
        webTestClient.get()
            .uri("/api/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("{\"status\":\"UP\"}");
    }

    private void assertBusinessError(Throwable serviceError, int expectedStatus,
                                     String expectedCode, String expectedMessage) {
        when(transactionService.procesarTransaccion(any(TransactionRequest.class)))
            .thenReturn(Mono.error(serviceError));

        postTransaction(VALID_REQUEST, "corr-err")
            .expectStatus().isEqualTo(expectedStatus)
            .expectBody()
            .jsonPath("$.error").isEqualTo(expectedCode)
            .jsonPath("$.message").isEqualTo(expectedMessage)
            .jsonPath("$.correlationId").isEqualTo("corr-err");
    }
}

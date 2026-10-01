package com.bankx.controller;

import com.bankx.domain.Transaction;
import com.bankx.dto.TransactionRequest;
import com.bankx.dto.TransactionResponse;
import com.bankx.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(TransactionController.class)
class TransactionControllerTest {

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
        when(transactionService.processTransaction(any(TransactionRequest.class)))
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
        when(transactionService.processTransaction(any(TransactionRequest.class)))
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
        when(transactionService.processTransaction(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new RuntimeException("account_not_found")));

        postTransaction(VALID_REQUEST, null)
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void accountNotFoundMapsTo422() {
        assertBusinessError("account_not_found", 422, "account_not_found", "Account not found");
    }

    @Test
    void insufficientFundsMapsTo422() {
        assertBusinessError("insufficient_funds", 422, "insufficient_funds", "Insufficient funds");
    }

    @Test
    void riskRejectedMapsTo422() {
        assertBusinessError("risk_rejected", 422, "risk_rejected", "Risk service rejected the transaction");
    }

    @Test
    void validationErrorMapsTo400() {
        assertBusinessError("validation failed: amount", 400, "validation_error", "validation failed: amount");
    }

    @Test
    void unexpectedErrorMapsTo500() {
        assertBusinessError("boom", 500, "internal_error", "boom");
    }

    @Test
    void errorWithoutMessageMapsTo500() {
        when(transactionService.processTransaction(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new RuntimeException()));

        postTransaction(VALID_REQUEST, "corr-null")
            .expectStatus().isEqualTo(500)
            .expectBody()
            .jsonPath("$.error").isEqualTo("internal_error");
    }

    @Test
    void invalidRequestIsRejectedBeforeReachingTheService() {
        postTransaction(Map.of("accountNumber", "", "type", "DEBIT", "amount", -5), "corr-invalid")
            .expectStatus().isBadRequest();

        verify(transactionService, never()).processTransaction(any());
    }

    @Test
    void streamTransactionsReturnsAccountTransactions() {
        when(transactionService.getTransactionsByAccount("001-0001"))
            .thenReturn(Flux.just(
                Transaction.builder().id("tx-1").accountNumber("001-0001").type("DEBIT")
                    .amount(BigDecimal.TEN).status("OK").build(),
                Transaction.builder().id("tx-2").accountNumber("001-0001").type("CREDIT")
                    .amount(BigDecimal.ONE).status("OK").build()));

        Flux<TransactionResponse> body = webTestClient.get()
            .uri("/api/stream/transactions?accountNumber=001-0001")
            .header("X-Correlation-Id", "corr-stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(TransactionResponse.class)
            .getResponseBody();

        StepVerifier.create(body)
            .expectNextMatches(tx -> "tx-1".equals(tx.getTransactionId()) && "corr-stream".equals(tx.getCorrelationId()))
            .expectNextMatches(tx -> "tx-2".equals(tx.getTransactionId()))
            .verifyComplete();
    }

    @Test
    void streamTransactionsWithoutAccountIsEmpty() {
        Flux<TransactionResponse> body = webTestClient.get()
            .uri("/api/stream/transactions")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(TransactionResponse.class)
            .getResponseBody();

        StepVerifier.create(body).verifyComplete();
        verify(transactionService, never()).getTransactionsByAccount(any());
    }

    @Test
    void healthReturnsUp() {
        webTestClient.get()
            .uri("/api/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("{\"status\":\"UP\"}");
    }

    private void assertBusinessError(String serviceMessage, int expectedStatus,
                                     String expectedCode, String expectedMessage) {
        when(transactionService.processTransaction(any(TransactionRequest.class)))
            .thenReturn(Mono.error(new RuntimeException(serviceMessage)));

        postTransaction(VALID_REQUEST, "corr-err")
            .expectStatus().isEqualTo(expectedStatus)
            .expectBody()
            .jsonPath("$.error").isEqualTo(expectedCode)
            .jsonPath("$.message").isEqualTo(expectedMessage)
            .jsonPath("$.correlationId").isEqualTo("corr-err");
    }
}

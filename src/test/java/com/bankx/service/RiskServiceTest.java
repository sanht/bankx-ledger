package com.bankx.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskServiceTest {

    private static final String BASE_URL = "http://risk.test";

    private final LegacyService legacyService = new LegacyService();

    /**
     * Crea el servicio con un WebClient cuyo "servidor remoto" responde con el estado y cuerpo indicados.
     * No aplica los aspectos de Resilience4j (no hay contexto de Spring): se prueba la lógica propia.
     */
    private RiskService riskServiceRespondingWith(HttpStatus status, String body,
                                                  AtomicReference<ClientRequest> captured) {
        WebClient.Builder builder = WebClient.builder()
            .exchangeFunction(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
            });
        return new RiskService(builder, legacyService, BASE_URL);
    }

    @Test
    void returnsDecisionFromRemoteServiceAndPropagatesCorrelationId() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        RiskService riskService = riskServiceRespondingWith(HttpStatus.OK,
            "{\"decision\":\"OK\",\"reason\":\"low risk\",\"fallback\":false}", captured);

        StepVerifier.create(riskService.evaluateRisk("001-0001", BigDecimal.TEN, "corr-1", null))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && !decision.isFallback())
            .verifyComplete();

        assertEquals(BASE_URL + "/api/risk/evaluate", captured.get().url().toString());
        assertEquals("corr-1", captured.get().headers().getFirst("X-Correlation-Id"));
    }

    @Test
    void propagatesRemoteServiceError() {
        RiskService riskService = riskServiceRespondingWith(HttpStatus.SERVICE_UNAVAILABLE, "{}",
            new AtomicReference<>());

        StepVerifier.create(riskService.evaluateRisk("001-0001", BigDecimal.TEN, "corr-2", null))
            .expectError()
            .verify();
    }

    @Test
    void simulatedFailureReturnsError() {
        RiskService riskService = riskServiceRespondingWith(HttpStatus.OK, "{}", new AtomicReference<>());

        StepVerifier.create(riskService.evaluateRisk("001-0001", BigDecimal.TEN, "corr-3", "fail"))
            .expectErrorMessage("Simulated risk service failure")
            .verify();
    }

    @Test
    void simulatedTimeoutDelaysTheResponse() {
        RiskService riskService = riskServiceRespondingWith(HttpStatus.OK, "{}", new AtomicReference<>());

        StepVerifier.withVirtualTime(() ->
                riskService.evaluateRisk("001-0001", BigDecimal.TEN, "corr-4", "timeout"))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(9))
            .thenAwait(Duration.ofSeconds(1))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()))
            .verifyComplete();
    }

    @Test
    void fallbackUsesLegacyServiceAndMarksDecision() {
        RiskService riskService = riskServiceRespondingWith(HttpStatus.OK, "{}", new AtomicReference<>());

        StepVerifier.create(riskService.fallbackRiskEvaluation("001-0001", BigDecimal.TEN, "corr-5", null,
                new RuntimeException("circuit open")))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && decision.isFallback())
            .verifyComplete();
    }

    @Test
    void fallbackKeepsLegacyRejection() {
        RiskService riskService = riskServiceRespondingWith(HttpStatus.OK, "{}", new AtomicReference<>());

        StepVerifier.create(riskService.fallbackRiskEvaluation("001-0001", BigDecimal.valueOf(20000), "corr-6",
                null, new RuntimeException("circuit open")))
            .expectNextMatches(decision -> "REJECTED".equals(decision.getDecision()) && decision.isFallback())
            .verifyComplete();
    }
}

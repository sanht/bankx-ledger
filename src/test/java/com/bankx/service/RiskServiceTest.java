package com.bankx.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba RiskService con objetos reales de Resilience4j (sin Spring) y un "servidor remoto" falso.
 * Los tiempos son cortos para que los tests sean rápidos; las reglas son las mismas de application.yml.
 */
class RiskServiceTest {

    private static final String BASE_URL = "http://risk.test";
    private static final String OK_BODY = "{\"decision\":\"OK\",\"reason\":\"low risk\"}";

    private final LegacyService legacyService = new LegacyService();
    private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();

    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
        .slidingWindowSize(10)
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(60))
        .ignoreException(new RiskClientErrorPredicate())
        .build());

    private final RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(10))
        .retryOnException(new TransientRiskErrorPredicate())
        .build());

    private final TimeLimiterRegistry timeLimiters = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
        .timeoutDuration(Duration.ofMillis(300))
        .build());

    /**
     * Servidor remoto falso: responde en orden con cada respuesta de la lista (la última se repite).
     */
    private RiskService riskServiceRespondingWith(Mono<ClientResponse>... responses) {
        ExchangeFunction server = request -> {
            requests.add(request);
            int index = Math.min(requests.size(), responses.length) - 1;
            return responses[index];
        };
        return new RiskService(WebClient.builder().exchangeFunction(server), legacyService, BASE_URL,
            circuitBreakers, retries, timeLimiters);
    }

    private static Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    /**
     * Serializa el body de un ClientRequest como lo haría WebClient al enviarlo.
     */
    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest http = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(http, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return http.getBodyAsString().block();
    }

    private static Mono<ClientResponse> connectionRefused() {
        return Mono.error(new WebClientRequestException(new ConnectException("Connection refused"),
            HttpMethod.POST, URI.create(BASE_URL), HttpHeaders.EMPTY));
    }

    @Test
    void returnsRemoteDecisionAndPropagatesCorrelationId() {
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.OK, OK_BODY));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-1", null))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && !decision.isFallback())
            .verifyComplete();

        assertEquals(1, requests.size());
        assertEquals(BASE_URL + "/api/risk/evaluate", requests.get(0).url().toString());
        assertEquals("corr-1", requests.get(0).headers().getFirst("X-Correlation-Id"));
    }

    @Test
    void keepsRemoteRejectionWithoutFallback() {
        RiskService riskService = riskServiceRespondingWith(
            response(HttpStatus.OK, "{\"decision\":\"REJECTED\",\"reason\":\"too high\"}"));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-2", null))
            .expectNextMatches(decision -> "REJECTED".equals(decision.getDecision()) && !decision.isFallback())
            .verifyComplete();
    }

    @Test
    void retriesTransientErrorAndSucceedsWithoutFallback() {
        RiskService riskService = riskServiceRespondingWith(
            response(HttpStatus.SERVICE_UNAVAILABLE, "{}"),
            response(HttpStatus.OK, OK_BODY));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-3", null))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && !decision.isFallback())
            .verifyComplete();

        assertEquals(2, requests.size());
    }

    @Test
    void retriesConnectionErrorsThenFallsBackToLegacy() {
        RiskService riskService = riskServiceRespondingWith(connectionRefused());

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-4", null))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && decision.isFallback())
            .verifyComplete();

        assertEquals(3, requests.size());
    }

    @Test
    void doesNotRetryClientErrors() {
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.BAD_REQUEST, "{}"));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-5", null))
            .expectNextMatches(decision -> decision.isFallback())
            .verifyComplete();

        assertEquals(1, requests.size());
    }

    @Test
    void clientErrorsDoNotCountAsCircuitBreakerFailures() {
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.BAD_REQUEST, "{}"));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-10", null))
            .expectNextMatches(decision -> decision.isFallback())
            .verifyComplete();

        CircuitBreaker.Metrics metrics = circuitBreakers.circuitBreaker(RiskService.RESILIENCE_INSTANCE).getMetrics();
        assertEquals(0, metrics.getNumberOfFailedCalls());
        assertEquals(0, metrics.getNumberOfBufferedCalls());
    }

    @Test
    void serverErrorsCountAsCircuitBreakerFailures() {
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.SERVICE_UNAVAILABLE, "{}"));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-11", null))
            .expectNextMatches(decision -> decision.isFallback())
            .verifyComplete();

        // Cada reintento cuenta en la ventana del circuito
        assertEquals(3, circuitBreakers.circuitBreaker(RiskService.RESILIENCE_INSTANCE)
            .getMetrics().getNumberOfFailedCalls());
    }

    @Test
    void timeoutFallsBackWithoutRetrying() {
        RiskService riskService = riskServiceRespondingWith(
            response(HttpStatus.OK, OK_BODY).delayElement(Duration.ofSeconds(5)));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-6", "timeout"))
            .expectNextMatches(decision -> decision.isFallback())
            .verifyComplete();

        assertEquals(1, requests.size());
    }

    @Test
    void openCircuitSkipsRemoteCallAndFallsBack() {
        circuitBreakers.circuitBreaker(RiskService.RESILIENCE_INSTANCE).transitionToOpenState();
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.OK, OK_BODY));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-7", null))
            .expectNextMatches(decision -> "OK".equals(decision.getDecision()) && decision.isFallback())
            .verifyComplete();

        assertTrue(requests.isEmpty());
    }

    @Test
    void forwardsSimulateFlagToRemoteService() {
        RiskService riskService = riskServiceRespondingWith(response(HttpStatus.OK, OK_BODY));

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.TEN, "corr-8", "fail"))
            .expectNextCount(1)
            .verifyComplete();

        String body = bodyOf(requests.get(0));
        assertTrue(body.contains("\"simulate\":\"fail\""), body);
    }

    @Test
    void fallbackKeepsLegacyRejection() {
        RiskService riskService = riskServiceRespondingWith(connectionRefused());

        StepVerifier.create(riskService.evaluarRiesgo("001-0001", BigDecimal.valueOf(20000), "corr-9", null))
            .expectNextMatches(decision -> "REJECTED".equals(decision.getDecision()) && decision.isFallback())
            .verifyComplete();
    }
}

package com.bankx.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class RiskService {

    private final WebClient webClient;
    private final LegacyService legacyService;

    public RiskService(WebClient.Builder webClientBuilder,
                       LegacyService legacyService,
                       @Value("${risk.service.url:http://localhost:9090}") String riskServiceUrl) {
        this.webClient = webClientBuilder
            .baseUrl(riskServiceUrl) // Mock de servicio remoto
            .build();
        this.legacyService = legacyService;
    }

    /**
     * Evalúa el riesgo de una transacción.
     * Protegido con Circuit Breaker, Retry, TimeLimiter.
     * Fallback al módulo legacy si falla.
     */
    @CircuitBreaker(name = "risk-service", fallbackMethod = "fallbackRiskEvaluation")
    @Retry(name = "risk-service")
    @TimeLimiter(name = "risk-service")
    public Mono<RiskDecision> evaluateRisk(String accountNumber, java.math.BigDecimal amount, 
                                            String correlationId, String simulate) {
        
        // Simulación para pruebas
        if ("fail".equals(simulate)) {
            return Mono.error(new RuntimeException("Simulated risk service failure"));
        }
        if ("timeout".equals(simulate)) {
            return Mono.delay(Duration.ofSeconds(10))
                .then(Mono.just(RiskDecision.builder().decision("OK").build()));
        }

        return webClient.post()
            .uri("/api/risk/evaluate")
            .bodyValue(new RiskRequest(accountNumber, amount))
            .header("X-Correlation-Id", correlationId)
            .retrieve()
            .bodyToMono(RiskDecision.class)
            .doOnNext(decision -> {
                log.debug("[{}] Risk evaluation: {}", correlationId, decision.decision);
            })
            .doOnError(error -> {
                log.warn("[{}] Risk evaluation failed: {}", correlationId, error.getMessage());
            })
            .timeout(Duration.ofSeconds(5))
            .onErrorMap(TimeoutException.class, e -> 
                new RuntimeException("Risk service timeout", e)
            );
    }

    /**
     * Fallback: consultar al módulo legacy en un thread pool aislado.
     */
    public Mono<RiskDecision> fallbackRiskEvaluation(String accountNumber, java.math.BigDecimal amount, 
                                                      String correlationId, String simulate, Exception ex) {
        log.warn("[{}] Risk service circuit open/failed, falling back to legacy. Cause: {}", 
            correlationId, ex.getMessage());
        
        return Mono.fromCallable(() -> 
            legacyService.evaluateRiskLegacy(accountNumber, amount)
        )
        .subscribeOn(Schedulers.boundedElastic())
        .map(decision -> {
            decision.setFallback(true);
            return decision;
        })
        .doOnError(error -> {
            log.error("[{}] Legacy fallback also failed: {}", correlationId, error.getMessage());
        });
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class RiskRequest {
        private String accountNumber;
        private java.math.BigDecimal amount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RiskDecision {
        private String decision; // OK, REJECTED, TIMEOUT
        private String reason;
        private boolean fallback;
    }
}

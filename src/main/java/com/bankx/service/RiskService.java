package com.bankx.service;

import com.bankx.dto.RiskDecision;
import com.bankx.dto.RiskRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

/**
 * Cliente del servicio de riesgo remoto, protegido con Resilience4j y con el legado como plan B.
 *
 * Los operadores se componen a mano (no con anotaciones) para que el orden sea explícito:
 * con anotaciones el fallback quedaba dentro del Retry y este nunca veía los errores.
 */
@Slf4j
@Service
public class RiskService {

    /** Nombre de la instancia en application.yml (circuitbreaker, retry y timelimiter). */
    public static final String RESILIENCE_INSTANCE = "risk-service";

    private final WebClient webClient;
    private final LegacyService legacyService;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;

    public RiskService(WebClient.Builder webClientBuilder,
                       LegacyService legacyService,
                       @Value("${risk.service.url:http://localhost:9090}") String riskServiceUrl,
                       CircuitBreakerRegistry circuitBreakerRegistry,
                       RetryRegistry retryRegistry,
                       TimeLimiterRegistry timeLimiterRegistry) {
        this.webClient = webClientBuilder
            .baseUrl(riskServiceUrl)
            .build();
        this.legacyService = legacyService;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(RESILIENCE_INSTANCE);
    }

    /**
     * Evalúa el riesgo de una transacción. Nunca falla por el servicio remoto: si no responde,
     * responde el legado con fallback=true.
     *
     * Cada operador envuelve a los anteriores, así que el orden de lectura es de adentro hacia afuera:
     * 1. TimeLimiter: corta cada intento a los 5 s.
     * 2. CircuitBreaker: registra el resultado de cada intento; con el circuito OPEN ni siquiera llama.
     * 3. Retry: reintenta solo errores pasajeros (TransientRiskErrorPredicate). Como envuelve al
     *    CircuitBreaker, cada reintento cuenta en la ventana y, si el circuito se abre a mitad de
     *    los reintentos, CallNotPermittedException no se reintenta.
     * 4. Fallback: último recurso, por eso va afuera de todo.
     */
    public Mono<RiskDecision> evaluarRiesgo(String accountNumber, BigDecimal amount,
                                            String correlationId, String simulate) {
        return llamarServicioRemoto(accountNumber, amount, correlationId, simulate)
            .transformDeferred(TimeLimiterOperator.of(timeLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
            .onErrorResume(error -> respaldoEvaluacionRiesgo(accountNumber, amount, correlationId, error));
    }

    private Mono<RiskDecision> llamarServicioRemoto(String accountNumber, BigDecimal amount,
                                                    String correlationId, String simulate) {
        return webClient.post()
            .uri("/api/risk/evaluate")
            .header("X-Correlation-Id", correlationId)
            .bodyValue(new RiskRequest(accountNumber, amount, simulate))
            .retrieve()
            .bodyToMono(RiskDecision.class)
            .doOnNext(decision ->
                log.debug("[{}] Risk evaluation: {}", correlationId, decision.getDecision()))
            // Se registra cada intento, así los reintentos quedan visibles en los logs
            .doOnError(error ->
                log.warn("[{}] Risk evaluation attempt failed: {}", correlationId, error.toString()));
    }

    /**
     * Plan B: consulta el módulo legado, que es bloqueante, en boundedElastic para no ocupar el event-loop.
     */
    private Mono<RiskDecision> respaldoEvaluacionRiesgo(String accountNumber, BigDecimal amount,
                                                        String correlationId, Throwable cause) {
        log.warn("[{}] Risk service unavailable (circuit {}), falling back to legacy. Cause: {}",
            correlationId, circuitBreaker.getState(), cause.toString());

        return Mono.fromCallable(() -> legacyService.evaluarRiesgoLegado(accountNumber, amount))
            .subscribeOn(Schedulers.boundedElastic())
            .map(decision -> {
                decision.setFallback(true);
                return decision;
            })
            .doOnError(error ->
                log.error("[{}] Legacy fallback also failed: {}", correlationId, error.getMessage()));
    }
}

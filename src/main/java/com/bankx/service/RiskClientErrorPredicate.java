package com.bankx.service;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.function.Predicate;

/**
 * Errores 4xx del servicio de riesgo: el request era inválido, el servicio está sano.
 *
 * El circuit breaker los ignora (no cuentan como fallo ni como éxito) para que requests mal
 * formados no abran el circuito y corten el tráfico bueno. Igual terminan en el fallback.
 *
 * Se usa en application.yml (resilience4j.circuitbreaker...ignore-exception-predicate).
 */
public class RiskClientErrorPredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable error) {
        return error instanceof WebClientResponseException response
            && response.getStatusCode().is4xxClientError();
    }
}

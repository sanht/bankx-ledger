package com.bankx.service;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Decide qué fallos del servicio de riesgo vale la pena reintentar: solo los pasajeros.
 *
 * Se reintenta:
 * - Error de conexión (rechazada, reset, DNS): WebClientRequestException.
 * - 502, 503 y 504: el servicio o su proxy no pudo atender en ese momento.
 *
 * No se reintenta:
 * - Timeout: el servicio ya está lento; reintentar suma carga y multiplica la espera del cliente.
 * - 4xx: el request es inválido y la respuesta va a ser la misma.
 * - CallNotPermittedException: el circuito está abierto, el objetivo es justamente no llamar.
 *
 * Se usa en application.yml (resilience4j.retry...retry-exception-predicate).
 */
public class TransientRiskErrorPredicate implements Predicate<Throwable> {

    private static final Set<Integer> TRANSIENT_STATUS = Set.of(502, 503, 504);

    @Override
    public boolean test(Throwable error) {
        if (error instanceof WebClientRequestException) {
            return true;
        }
        return error instanceof WebClientResponseException response
            && TRANSIENT_STATUS.contains(response.getStatusCode().value());
    }
}

package com.bankx.observability;

import org.springframework.web.server.ServerWebExchange;

/**
 * Nombres compartidos del identificador de correlación.
 * La misma clave se usa en el contexto de Reactor, en el MDC y en los atributos del exchange.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String KEY = "correlationId";

    private CorrelationId() {
    }

    /**
     * Devuelve el correlationId que {@link CorrelationIdFilter} asignó a la petición.
     */
    public static String from(ServerWebExchange exchange) {
        return exchange.getAttribute(KEY);
    }
}

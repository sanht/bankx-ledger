package com.bankx.observability;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * Toma X-Correlation-Id de la petición (o genera uno), lo devuelve en la respuesta
 * y lo deja en el contexto de Reactor para toda la cadena: controller, servicios y logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        String correlationId = StringUtils.hasText(header) ? header : UUID.randomUUID().toString();

        exchange.getAttributes().put(CorrelationId.KEY, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);

        return chain.filter(exchange)
            .contextWrite(Context.of(CorrelationId.KEY, correlationId));
    }
}

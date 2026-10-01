package com.bankx.observability;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * Registra el MDC como destino del correlationId del contexto de Reactor.
 * Con spring.reactor.context-propagation=auto, Reactor lo restaura en cada hilo
 * (event-loop, boundedElastic, legacy) antes de ejecutar los operadores.
 */
@Configuration(proxyBeanMethods = false)
public class MdcContextPropagation {

    static {
        registrar();
    }

    public static void registrar() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            CorrelationId.KEY,
            () -> MDC.get(CorrelationId.KEY),
            value -> MDC.put(CorrelationId.KEY, value),
            () -> MDC.remove(CorrelationId.KEY));
    }
}

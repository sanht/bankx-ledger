package com.bankx.service;

import com.bankx.dto.TransactionResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Bus en memoria de transacciones confirmadas para los clientes SSE.
 *
 * multicast().directBestEffort(): cada suscriptor recibe solo lo emitido desde que se conectó
 * y, si un cliente lento no tiene demanda, se le descarta el evento a él sin frenar
 * a los demás ni al flujo de escritura.
 */
@Component
public class TransactionEventPublisher {

    private static final Duration EMIT_RETRY = Duration.ofMillis(100);

    private final Sinks.Many<TransactionResponse> sink = Sinks.many().multicast().directBestEffort();

    /**
     * Publica una transacción. Reintenta brevemente si dos hilos emiten a la vez;
     * sin suscriptores, el evento simplemente se descarta.
     */
    public void publicar(TransactionResponse transaction) {
        sink.emitNext(transaction, Sinks.EmitFailureHandler.busyLooping(EMIT_RETRY));
    }

    public Flux<TransactionResponse> eventos() {
        return sink.asFlux();
    }
}

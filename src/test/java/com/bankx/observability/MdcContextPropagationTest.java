package com.bankx.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class MdcContextPropagationTest {

    @BeforeEach
    void setUp() {
        MdcContextPropagation.registrar();
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterEach
    void tearDown() {
        Hooks.disableAutomaticContextPropagation();
        MDC.clear();
    }

    @Test
    void correlationIdReachesMdcAfterSwitchingToAnotherThread() {
        Mono<String> mdcOnWorker = Mono.just("ignored")
            .publishOn(Schedulers.boundedElastic())
            .map(value -> Thread.currentThread().getName() + "|" + MDC.get(CorrelationId.KEY))
            .contextWrite(Context.of(CorrelationId.KEY, "corr-mdc"));

        StepVerifier.create(mdcOnWorker)
            .expectNextMatches(result -> result.startsWith("boundedElastic") && result.endsWith("|corr-mdc"))
            .verifyComplete();
    }
}

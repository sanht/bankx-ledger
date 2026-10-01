package com.bankx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.Executor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication
public class BankxLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankxLedgerApplication.class, args);
    }

    /**
     * Executor para operaciones bloqueantes (legacy JPA/H2).
     * Aísla el bloqueo del event-loop de WebFlux.
     */
    @Bean(name = "legacyExecutor")
    public Executor legacyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("legacy-jpa-");
        executor.initialize();
        return executor;
    }
}

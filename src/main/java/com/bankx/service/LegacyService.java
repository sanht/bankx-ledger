package com.bankx.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;

/**
 * Servicio que simula acceso a módulo legacy en JPA/H2.
 * Las operaciones bloqueantes deben ejecutarse en boundedElastic().
 */
@Slf4j
@Service
public class LegacyService {

    /**
     * Evalúa riesgo en el módulo legacy (simulado con Thread.sleep).
     * En producción: JPA repository bloqueante.
     */
    public RiskService.RiskDecision evaluateRiskLegacy(String accountNumber, java.math.BigDecimal amount) {
        try {
            // Simula acceso a base de datos legacy (bloqueante)
            Thread.sleep(100);
            
            log.info("Legacy risk evaluation for account {}, amount {}", accountNumber, amount);
            
            // Lógica legacy simple
            if (amount.compareTo(java.math.BigDecimal.valueOf(10000)) > 0) {
                return RiskService.RiskDecision.builder()
                    .decision("REJECTED")
                    .reason("Amount exceeds legacy limit")
                    .build();
            }
            
            return RiskService.RiskDecision.builder()
                .decision("OK")
                .reason("Legacy approval")
                .build();
                
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Legacy service interrupted", e);
            throw new RuntimeException("Legacy service unavailable", e);
        }
    }
}

package com.bankx.service;

import com.bankx.dto.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyServiceTest {

    private final LegacyService legacyService = new LegacyService();

    @Test
    void approvesAmountWithinLegacyLimit() {
        RiskDecision decision = legacyService.evaluarRiesgoLegado("001-0001", BigDecimal.valueOf(500));

        assertEquals("OK", decision.getDecision());
        assertEquals("Legacy approval", decision.getReason());
    }

    @Test
    void rejectsAmountAboveLegacyLimit() {
        RiskDecision decision = legacyService.evaluarRiesgoLegado("001-0001", BigDecimal.valueOf(10001));

        assertEquals("REJECTED", decision.getDecision());
        assertEquals("Amount exceeds legacy limit", decision.getReason());
    }
}

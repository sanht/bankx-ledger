package com.bankx.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskRequest {

    private String accountNumber;

    private BigDecimal amount;

    // Se reenvía al servicio de riesgo (o su mock) para provocar fallos: fail, timeout, flaky, invalid
    private String simulate;
}

package com.bankx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    @NotBlank(message = "accountNumber cannot be blank")
    private String accountNumber;

    @NotNull(message = "type cannot be null")
    @Pattern(regexp = "DEBIT|CREDIT", message = "type must be DEBIT or CREDIT")
    private String type;

    @NotNull(message = "amount cannot be null")
    @Positive(message = "amount must be positive")
    private BigDecimal amount;

    // Para pruebas: simular fallos del servicio de riesgo
    private String simulate; // "fail", "timeout"
}

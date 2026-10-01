package com.bankx.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private String transactionId;

    private String accountNumber;

    private String type;

    private BigDecimal amount;

    private String status;

    private BigDecimal newBalance;

    private LocalDateTime createdAt;

    private String correlationId;

    private boolean usedFallback;
}

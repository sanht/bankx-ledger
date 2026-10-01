package com.bankx.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Document(collection = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    private String id;

    @Indexed(name = "accountNumber_1")
    private String accountNumber;

    private String type; // DEBIT, CREDIT

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private String status; // PENDING, OK, REJECTED, FALLBACK

    private String reason; // Razón del rechazo si aplica

    private String correlationId;

    private LocalDateTime createdAt;

    private String riskDecision; // OK, REJECTED, TIMEOUT

    private boolean usedFallback;
}

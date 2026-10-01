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

@Document(collection = "accounts")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    private String id;

    @Indexed(name = "accountNumber_1", unique = true)
    private String accountNumber;

    private String holderName;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal balance;

    private String status; // ACTIVE, CLOSED, SUSPENDED

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }
}

package com.bankx.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecision {

    private String decision; // OK, REJECTED, TIMEOUT

    private String reason;

    private boolean fallback;
}

package com.bankx.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    @JsonProperty("error")
    private String error;

    @JsonProperty("message")
    private String message;

    @JsonProperty("correlationId")
    private String correlationId;

    @JsonProperty("timestamp")
    private long timestamp;

    public static ErrorResponse of(String errorCode, String message, String correlationId) {
        return ErrorResponse.builder()
            .error(errorCode)
            .message(message)
            .correlationId(correlationId)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}

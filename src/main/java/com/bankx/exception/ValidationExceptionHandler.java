package com.bankx.exception;

import com.bankx.dto.ErrorResponse;
import com.bankx.observability.CorrelationId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.stream.Collectors;

/**
 * Traduce los errores de entrada que ocurren antes de llegar al controller
 * (Bean Validation y JSON mal formado) al mismo formato de error que el resto de la API.
 */
@Slf4j
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> manejarBeanValidation(WebExchangeBindException error,
                                                               ServerWebExchange exchange) {
        String message = error.getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        return responder(new ValidationException(message), exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> manejarEntradaInvalida(ServerWebInputException error,
                                                                ServerWebExchange exchange) {
        return responder(new ValidationException("Malformed request body"), exchange);
    }

    private ResponseEntity<ErrorResponse> responder(ValidationException error, ServerWebExchange exchange) {
        String correlationId = CorrelationId.from(exchange);
        log.warn("[{}] Validation error: {}", correlationId, error.getMessage());
        return ResponseEntity.status(error.getStatus())
            .body(ErrorResponse.of(error.getErrorCode(), error.getMessage(), correlationId));
    }
}

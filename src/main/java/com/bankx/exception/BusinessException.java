package com.bankx.exception;

import org.springframework.http.HttpStatus;

/**
 * Base de los errores de negocio. Cada subclase define su código de error
 * y el status HTTP con el que se expone en la API.
 */
public abstract class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    protected BusinessException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

package com.bankx.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends BusinessException {

    public ValidationException(String message) {
        super("validation_error", HttpStatus.BAD_REQUEST, message);
    }
}

package com.bankx.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException() {
        super("insufficient_funds", HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
    }
}

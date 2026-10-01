package com.bankx.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException() {
        super("account_not_found", HttpStatus.UNPROCESSABLE_ENTITY, "Account not found");
    }
}

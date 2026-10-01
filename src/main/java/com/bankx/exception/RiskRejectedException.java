package com.bankx.exception;

import org.springframework.http.HttpStatus;

public class RiskRejectedException extends BusinessException {

    public RiskRejectedException() {
        super("risk_rejected", HttpStatus.UNPROCESSABLE_ENTITY, "Risk service rejected the transaction");
    }
}

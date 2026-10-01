package com.bankx.exception;

public abstract class BusinessException extends RuntimeException {
    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

class AccountNotFoundException extends BusinessException {
    public AccountNotFoundException(String accountNumber) {
        super("account_not_found", "Account not found: " + accountNumber);
    }
}

class InsufficientFundsException extends BusinessException {
    public InsufficientFundsException(String accountNumber, java.math.BigDecimal balance, java.math.BigDecimal amount) {
        super("insufficient_funds", 
            String.format("Insufficient funds for account %s. Balance: %s, Requested: %s", 
                accountNumber, balance, amount));
    }
}

class RiskRejectedException extends BusinessException {
    public RiskRejectedException(String reason) {
        super("risk_rejected", "Risk service rejected the transaction: " + reason);
    }
}

class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super("validation_error", message);
    }
}

// Exports
class Exceptions {
    public static AccountNotFoundException accountNotFound(String accountNumber) {
        return new AccountNotFoundException(accountNumber);
    }

    public static InsufficientFundsException insufficientFunds(String accountNumber, 
                                                                java.math.BigDecimal balance, 
                                                                java.math.BigDecimal amount) {
        return new InsufficientFundsException(accountNumber, balance, amount);
    }

    public static RiskRejectedException riskRejected(String reason) {
        return new RiskRejectedException(reason);
    }

    public static ValidationException validation(String message) {
        return new ValidationException(message);
    }
}

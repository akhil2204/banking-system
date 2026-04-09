package com.banking.transactionservice.exception;

public class SelfTransferException extends RuntimeException {

    public SelfTransferException() {
        super("Source and target accounts must be different");
    }
}

package com.banking.transactionservice.entity;

public enum TransactionType {
    DEBIT,    // money leaves an account
    CREDIT,   // money enters an account
    TRANSFER  // money moves between two accounts
}

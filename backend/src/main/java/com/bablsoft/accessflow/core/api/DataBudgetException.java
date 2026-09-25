package com.bablsoft.accessflow.core.api;

public sealed class DataBudgetException extends RuntimeException
        permits DataBudgetNotFoundException, IllegalDataBudgetException {

    protected DataBudgetException(String message) {
        super(message);
    }
}

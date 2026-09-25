package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class DataBudgetNotFoundException extends DataBudgetException {

    public DataBudgetNotFoundException(UUID id) {
        super("Data budget not found: " + id);
    }
}

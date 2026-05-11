package com.partqam.accessflow.core.api;

import java.util.List;

public record TotpConfirmationResult(List<String> backupCodes) {}

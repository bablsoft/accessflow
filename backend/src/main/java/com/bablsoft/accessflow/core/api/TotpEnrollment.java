package com.bablsoft.accessflow.core.api;

public record TotpEnrollment(String secret, String otpauthUrl, String qrDataUri) {}

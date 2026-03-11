package com.stablebridge.oncall.domain.model.logs;

public record NewPattern(String pattern, double confidence, String possibleCause) {}

package com.stablebridge.oncall.domain.model.trace;

import java.util.List;

public record BottleneckInfo(String service, String reason, List<String> evidence) {}
